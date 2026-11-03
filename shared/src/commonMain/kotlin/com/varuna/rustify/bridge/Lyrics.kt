// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/Lyrics.kt
package com.varuna.rustify.bridge

/** A single synchronized lyric line. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** Lyrics result: synced lines (empty if none) + a plain-text fallback, and who answered. */
data class LyricsResult(
    val synced: List<LyricLine>,
    val plain: String?,
    /** The provider's id ([LyricsProviders]), or [LyricsChain.LOCAL] for the file's own. */
    val providerId: String? = null
)

/**
 * Shared LRC parser: `[mm:ss.xx] text`.
 *
 * Also what files made by hand or by taggers carry (`17` §4, after Gramophone): several stamps on
 * one line for a repeated chorus (`[00:12.00][01:30.00]text`), stamps without a fraction
 * (`[01:02]`), a one-digit minute, and the enhanced-LRC per-word stamps (`<00:12.50> word`), which
 * are dropped — the screen highlights lines — instead of being shown as text. Tag lines such as
 * `[ar:…]` have no time and are skipped.
 */
fun parseLrcText(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val stamp = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    val wordStamp = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")
    for (raw in lrc.lines()) {
        var rest = raw.trim()
        val times = mutableListOf<Long>()
        while (true) {
            val m = stamp.find(rest) ?: break
            val (mm, ss, frac) = m.destructured
            val ms = frac.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLongOrNull() ?: 0L
            times += mm.toLong() * 60_000 + ss.toLong() * 1_000 + ms
            rest = rest.substring(m.value.length)
        }
        if (times.isEmpty()) continue
        val text = rest.replace(wordStamp, "").replace(Regex("\\s+"), " ").trim()
        times.forEach { lines.add(LyricLine(it, text)) }
    }
    return lines.sortedBy { it.timeMs }
}

/** The line being sung at [positionMs]: the last one that has started, or -1 before the first. */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timeMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}
