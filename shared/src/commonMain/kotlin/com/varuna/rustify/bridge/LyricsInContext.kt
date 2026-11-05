// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/LyricsInContext.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.platform.languageNameInEnglish

/**
 * Lyrics translated with the whole song in view (4.9), by the on-device model the DJ already uses.
 *
 * ML Kit's translator works one sentence at a time, and a song is not a list of sentences. Japanese
 * in particular spreads one thought over several lines — the subject in one, the verb two lines
 * later — so translated line by line it came out as unrelated fragments (reported on DYNA MIND).
 * Gemini Nano reads the full lyrics and translates a block of lines at a time against them.
 *
 * Blocks rather than the whole song because Nano's answer is capped at a few hundred tokens; every
 * block carries the full lyrics as context, so nothing is lost by the split. A line the model does
 * not give back is left to ML Kit, so a weak answer degrades to the old result, never to a gap.
 *
 * Only the text handling lives here, so it is tested on the JVM.
 */
object LyricsInContext {
    const val BLOCK_LINES = 10

    /** Past this the context is cut; Nano's input is a few thousand tokens. */
    const val MAX_CONTEXT_CHARS = 3_000

    fun languageName(code: String): String =
        languageNameInEnglish(code).ifBlank { code }

    /**
     * The prompt for one block. [block] holds (number, line) pairs; the numbers are what the answer
     * is matched back on, so they are the lines' own indices, not positions in the block.
     */
    fun prompt(source: String, target: String, fullLyrics: List<String>, block: List<Pair<Int, String>>): String {
        val context = fullLyrics.joinToString("\n").let {
            if (it.length > MAX_CONTEXT_CHARS) it.take(MAX_CONTEXT_CHARS) + "\n…" else it
        }
        return buildString {
            append("You translate song lyrics from ").append(languageName(source))
            append(" into ").append(languageName(target)).append(".\n\n")
            append("The complete lyrics, for context:\n<<<\n").append(context).append("\n>>>\n\n")
            append("Translate only the numbered lines below. Read each one in the context of the whole song: ")
            append("keep its meaning, its subject and its tone, and write natural ")
            append(languageName(target)).append(", not a word-for-word rendering. ")
            append("Answer with exactly one line per number, as `N: translation`, and nothing else.\n\n")
            block.forEach { (n, line) -> append(n).append(": ").append(line).append('\n') }
        }
    }

    private val numbered = Regex("""^\s*\**\s*(\d+)\s*\**\s*[:.)\-–]\s*(.*?)\s*$""")

    /**
     * The model's answer back as number → translation, keeping only the numbers that were asked
     * for. Blank translations, quotes the model wraps lines in, and stray chatter are dropped.
     */
    fun parse(answer: String, expected: Set<Int>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        answer.lineSequence().forEach { raw ->
            val m = numbered.matchEntire(raw) ?: return@forEach
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (n !in expected || n in out) return@forEach
            val text = m.groupValues[2].trim().trim('"', '“', '”', '「', '」').trim()
            if (text.isNotEmpty()) out[n] = text
        }
        return out
    }
}
