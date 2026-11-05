// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/ModelLyricsTranslator.kt
package com.varuna.rustify.ui

import com.varuna.rustify.bridge.LyricsByModel
import com.varuna.rustify.dj.OpenAiChat
import com.varuna.rustify.platform.Log
import com.varuna.rustify.util.LockedMap
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.lyrics_translate_no_model
import com.varuna.rustify.ui.resources.lyrics_translate_not_json
import com.varuna.rustify.ui.resources.lyrics_translate_wrong_count
import kotlinx.coroutines.CancellationException

/**
 * Lyrics translation by the model the DJ asks, where there is neither ML Kit nor Gemini Nano: the
 * desktop, iOS and the web. Each block of lines goes with the whole song
 * as context ([LyricsByModel], the phone's translation in context), so this is that one, not ML
 * Kit's line by line.
 *
 * The answer must have one line per line asked, in order, or nothing is shown under the wrong line:
 * a block that comes back with another count is asked once more, and then the translation fails and
 * says why. Repeated lines (every chorus) are asked once. Kept in memory per track and language for
 * the session, as on the phone; a failure is not kept, so tapping again asks again.
 */
class ModelLyricsTranslator(private val client: suspend () -> OpenAiChat?) {
    private val cache = LockedMap<String, LyricsTranslation>()

    suspend fun translate(
        key: String,
        lines: List<String>,
        target: String,
        onProgress: (LyricsTranslation) -> Unit,
    ): LyricsTranslation {
        cache["$key|$target"]?.let { return it }
        if (lines.none { it.isNotBlank() }) return LyricsTranslation.NotNeeded("und")
        val outcome = try {
            run(lines, target, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "translation failed: ${e.message}")
            LyricsTranslation.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        if (outcome !is LyricsTranslation.Failed) cache["$key|$target"] = outcome
        return outcome
    }

    private suspend fun run(lines: List<String>, target: String, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation {
        val chat = client() ?: return LyricsTranslation.Failed(appString(Res.string.lyrics_translate_no_model))
        val song = lines.filter { it.isNotBlank() }
        // Each distinct line once, at its first appearance.
        val distinct = song.distinct()
        val done = HashMap<String, String>()
        var source: String? = null
        val out = MutableList(lines.size) { "" }
        fun publish() {
            for (i in lines.indices) done[lines[i]]?.let { t ->
                out[i] = t.takeUnless { it.equals(lines[i].trim(), ignoreCase = true) }.orEmpty()
            }
        }
        for (block in distinct.chunked(LyricsByModel.BLOCK_LINES)) {
            val answer = ask(chat, target, song, block)
            if (answer !is LyricsByModel.Answer.Lines) return LyricsTranslation.Failed(reason(answer))
            if (source == null) source = answer.language
            block.forEachIndexed { i, line -> done[line] = answer.lines[i] }
            publish()
            onProgress(LyricsTranslation.Translated(source ?: UNKNOWN, out.toList()))
        }
        // Already in the target language: every line came back empty or as it was.
        if (out.all { it.isBlank() }) return LyricsTranslation.NotNeeded(target)
        return LyricsTranslation.Translated(source ?: UNKNOWN, out)
    }

    /** One block, asked again once when the answer is not the list asked for. */
    private suspend fun ask(chat: OpenAiChat, target: String, song: List<String>, block: List<String>): LyricsByModel.Answer {
        val messages = listOf(
            OpenAiChat.Message("system", LyricsByModel.SYSTEM),
            OpenAiChat.Message("user", LyricsByModel.prompt(target, song, block)),
        )
        var answer: LyricsByModel.Answer = LyricsByModel.Answer.NotJson
        repeat(2) { attempt ->
            val text = chat.stream(messages, temperature = 0.2, maxTokens = 1_024)
            answer = LyricsByModel.parse(text, block.size)
            if (answer is LyricsByModel.Answer.Lines) return answer
            Log.w(TAG, "block of ${block.size} lines, attempt ${attempt + 1}: $answer")
        }
        return answer
    }

    private suspend fun reason(answer: LyricsByModel.Answer): String = when (answer) {
        is LyricsByModel.Answer.WrongCount ->
            appString(Res.string.lyrics_translate_wrong_count, answer.got.toString(), answer.expected.toString())
        else -> appString(Res.string.lyrics_translate_not_json)
    }

    private companion object {
        const val TAG = "LyricsTranslator"
        const val UNKNOWN = "und"
    }
}
