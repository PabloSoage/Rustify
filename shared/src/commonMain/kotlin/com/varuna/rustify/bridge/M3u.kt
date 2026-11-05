package com.varuna.rustify.bridge

import com.varuna.rustify.platform.normalizeNfc

/**
 * M3U/M3U8 playlists (4.10, Musicolet 3.6): read one written by any player or PC, write one the
 * same players read back.
 *
 * Local tracks are SAF documents, `local:content://…/document/primary%3AMusic%2FA%2Fb.mp3`; the
 * document id carries the path on its volume, which is what a playlist line names. A line from
 * another device rarely matches the whole path (`C:\Music\…`, `/sdcard/…`, `../b.mp3`), so a line
 * is resolved to the library track sharing the longest tail of path components — at least the
 * file name — and, failing that, to the `#EXTINF` "Artist - Title". Pure, so tested on the JVM.
 */
object M3u {
    data class Entry(val path: String, val title: String? = null, val seconds: Int? = null)

    /** A library track as the resolver sees it. */
    data class Known(val id: String, val path: String?, val artist: String, val title: String)

    data class Resolved(val ids: List<String>, val missing: List<Entry>)

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var pendingTitle: String? = null
        var pendingSeconds: Int? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substring(8)
                    val comma = body.indexOf(',')
                    // Attributes (`tvg-id="…"`) may follow the duration; the duration is what leads.
                    pendingSeconds = body.substring(0, if (comma >= 0) comma else body.length)
                        .trim().substringBefore(' ').toIntOrNull()?.takeIf { it >= 0 }
                    pendingTitle = if (comma >= 0) body.substring(comma + 1).trim().ifEmpty { null } else null
                }
                line.startsWith("#") -> Unit
                else -> {
                    out += Entry(line, pendingTitle, pendingSeconds)
                    pendingTitle = null
                    pendingSeconds = null
                }
            }
        }
        return out
    }

    fun resolve(entries: List<Entry>, library: List<Known>): Resolved {
        val byTail = HashMap<String, MutableList<Known>>()
        for (k in library) {
            val name = k.path?.let { components(it).lastOrNull() } ?: continue
            byTail.getOrPut(name) { mutableListOf() } += k
        }
        val byTitle = library.groupBy { fold("${it.artist} - ${it.title}") }
        val ids = mutableListOf<String>()
        val missing = mutableListOf<Entry>()
        for (e in entries) {
            val wanted = components(e.path)
            val candidates = wanted.lastOrNull()?.let { byTail[it] }.orEmpty()
            val best = candidates.maxByOrNull { sharedTail(wanted, components(it.path!!)) }
                ?: e.title?.let { byTitle[fold(it)]?.firstOrNull() }
            if (best != null) ids += best.id else missing += e
        }
        return Resolved(ids, missing)
    }

    /** An extended M3U8 (UTF-8) with each local track as an absolute path on its volume. */
    fun write(name: String, tracks: List<Known>, durationsSec: List<Int>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
        tracks.forEachIndexed { i, t ->
            val path = t.path ?: return@forEachIndexed
            append("#EXTINF:").append(durationsSec.getOrElse(i) { -1 }).append(',')
                .append("${t.artist} - ${t.title}".replace('\n', ' ')).append('\n')
            append(path).append('\n')
        }
    }

    /**
     * The path of a `local:` track on the device — `/storage/emulated/0/Music/a.mp3` for the
     * primary volume, `/storage/XXXX-XXXX/…` for a card — or null when the provider's ids are
     * not paths (a cloud provider's opaque ids).
     *
     * On the desktop a local track is a `file://` URI, and its path is the URI's, decoded:
     * `/home/me/Music/a b.mp3`, or `C:/Users/me/Music/a.mp3` on Windows (forward slashes, which is
     * what the folder view splits on).
     */
    fun pathOf(trackId: String): String? {
        val uri = trackId.removePrefix("local:")
        if (uri.startsWith("file:")) return filePath(uri)
        val doc = uri.substringAfterLast("/document/", "").ifEmpty { return null }
        val id = percentDecode(doc)
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val volume = id.substring(0, colon)
        val rel = id.substring(colon + 1).trimStart('/')
        if (rel.isEmpty()) return null
        return if (volume == "primary") "/storage/emulated/0/$rel" else "/storage/$volume/$rel"
    }

    /** `file:///a/b%20c.mp3` → `/a/b c.mp3`; `file:///C:/a.mp3` → `C:/a.mp3`; `file://localhost/a` → `/a`. */
    private fun filePath(uri: String): String? {
        var rest = uri.removePrefix("file:")
        if (rest.startsWith("//")) {
            rest = rest.removePrefix("//")
            // An authority: only the local host names this machine.
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            val host = rest.substring(0, slash)
            if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
            rest = rest.substring(slash)
        }
        val path = percentDecode(rest.substringBefore('?').substringBefore('#'))
        // `/C:/…`: a Windows drive, which takes no slash in front.
        val drive = path.length >= 3 && path[0] == '/' && path[1].isLetter() && path[2] == ':'
        return (if (drive) path.substring(1) else path).takeIf { it.length > 1 }
    }

    private fun components(path: String): List<String> =
        path.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith(":") }
            .map { fold(it) }

    private fun sharedTail(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
        return n
    }

    private fun fold(s: String): String = normalizeNfc(s.trim()).lowercase()

    private fun percentDecode(s: String): String {
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
            }
            c.toString().encodeToByteArray().forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}
