// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/bridge/DesktopLocalFiles.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.net.URI

/**
 * A desktop local track is a file on this computer, and its id is `local:` plus the file's `file://`
 * URI (`file:///home/me/Music/a%20b.mp3`, `file:///C:/Music/a.mp3`): percent-encoded, three slashes,
 * the form the engine opens, Coil loads and [M3u.pathOf] reads a path out of. Built and read here only.
 */
object DesktopLocalFiles {
    /** The `file://` URI of [file]. */
    fun uriOf(file: File): String = file.absoluteFile.toPath().toUri().toString()

    /** The track id of [file]. */
    fun trackIdOf(file: File): String = TrackRef.Local(uriOf(file)).raw

    /** The file a `file://` URI names, or null when it names none (a phone's `content://` track). */
    fun fileOfUri(uri: String): File? =
        if (uri.startsWith("file:")) runCatching { File(URI(uri)) }.getOrNull() else null

    /** The file a `local:` track is, or null when it is no file on this computer. */
    fun fileOf(trackId: String?): File? = TrackRef.localUriOf(trackId)?.let { fileOfUri(it) }
}

/**
 * The desktop's lyrics from the user's own file, as Android's `LocalLyrics`: a `.lrc` with the same
 * name in the same folder first, then the lyrics tag inside the file (read by the core). Blocking;
 * [LyricsChain] calls it off the main thread.
 */
object DesktopLocalLyrics {
    fun read(trackId: String): LyricsResult? {
        val file = DesktopLocalFiles.fileOf(trackId) ?: return null
        val text = sidecar(file) ?: embedded(file) ?: return null
        val synced = parseLrcText(text)
        return if (synced.isNotEmpty()) LyricsResult(synced, null)
        else text.trim().takeIf { it.isNotEmpty() }?.let { LyricsResult(emptyList(), it) }
    }

    private fun sidecar(file: File): String? {
        val dir = file.parentFile ?: return null
        val base = file.nameWithoutExtension
        val lrc = listOf("$base.lrc", "$base.LRC").map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        return runCatching { lrc.readText(Charsets.UTF_8) }.getOrNull()?.removePrefix("\uFEFF")?.takeIf { it.isNotBlank() }
    }

    private fun embedded(file: File): String? = runCatching {
        val json = NativeEngine.readEmbeddedLyrics(file.absolutePath)
        RustifyJson.decodeFromString(String.serializer().nullable, json)
    }.getOrNull()
}
