// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/LyricsByModel.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.dj.DjPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lyrics translated by a general language model, where there is no ML Kit: the desktop, with the
 * model the DJ asks (a model server on this computer, or the DJ's external AI). The text handling
 * lives here so it is tested on every target; the asking is the desktop's.
 *
 * It is [LyricsInContext]'s idea with what a model of any size can be held to: every block of lines
 * goes with the whole song as context, so a thought spread over three lines is translated as one,
 * and the answer is a JSON array with exactly one string per line asked, in order. The count is
 * checked: a translation is shown under the line it translates and follows the synced highlight,
 * so an answer one line short would put every line after it under the wrong one. There is no
 * language identifier on the desktop either, so the answer also names the lyrics' language.
 */
object LyricsByModel {
    /** Lines per request: small enough for a 1.5B model to keep count, and its answer short. */
    const val BLOCK_LINES = 12

    /**
     * Past this the context is cut: Rustify's own llama-server runs with a 4096-token context, and
     * Japanese is about a token a character.
     */
    const val MAX_CONTEXT_CHARS = 1_500

    /** The instructions for every block. */
    const val SYSTEM = "You translate song lyrics. You answer with JSON only, never with anything around it."

    /** The request for [block] (lines of the song, in order) into [target], a language code. */
    fun prompt(target: String, fullLyrics: List<String>, block: List<String>): String {
        val name = LyricsInContext.languageName(target)
        val context = fullLyrics.joinToString("\n").let {
            if (it.length > MAX_CONTEXT_CHARS) it.take(MAX_CONTEXT_CHARS) + "\n…" else it
        }
        return buildString {
            append("Translate song lyrics into ").append(name).append(".\n\n")
            append("The complete lyrics, for context:\n<<<\n").append(context).append("\n>>>\n\n")
            append("Translate each of these ").append(block.size).append(" lines, given as a JSON array:\n")
            append(JsonArray(block.map { JsonPrimitive(it) }).toString()).append("\n\n")
            append("Read each line in the context of the whole song: keep its meaning, its subject and its tone, ")
            append("and write natural ").append(name).append(", not a word-for-word rendering. ")
            append("A line that is already in ").append(name).append(" is answered with an empty string.\n")
            append("Answer with this JSON object and nothing else: ")
            append("{\"language\": \"the ISO 639-1 code of the language the lyrics are in\", ")
            append("\"lines\": [exactly ").append(block.size).append(" strings, one per line above, in the same order]}")
        }
    }

    /** What an answer came to. */
    sealed interface Answer {
        /** [lines] has exactly the number of lines asked for; [language] is null when not named. */
        data class Lines(val language: String?, val lines: List<String>) : Answer

        /** Not the JSON asked for. */
        data object NotJson : Answer

        /** Lines, but not as many as were asked for: [got] for [expected]. */
        data class WrongCount(val got: Int, val expected: Int) : Answer
    }

    /**
     * Reads an answer for a block of [expected] lines: the object asked for, or a bare array (what a
     * small model sometimes writes instead), with or without a code fence or a sentence around it.
     */
    fun parse(answer: String, expected: Int): Answer {
        val root = jsonIn(answer) ?: return Answer.NotJson
        val (language, array) = when (root) {
            is JsonArray -> null to root
            is JsonObject -> {
                val lines = (root["lines"] ?: root["translations"] ?: root["translation"]) as? JsonArray
                    ?: return Answer.NotJson
                languageCode((root["language"] as? JsonPrimitive)?.takeIf { it.isString }?.content) to lines
            }
            else -> return Answer.NotJson
        }
        val lines = array.map { e ->
            when (e) {
                JsonNull -> ""
                is JsonPrimitive -> if (e.isString) e.content.trim() else return Answer.NotJson
                else -> return Answer.NotJson
            }
        }
        if (lines.size != expected) return Answer.WrongCount(lines.size, expected)
        return Answer.Lines(language, lines)
    }

    /**
     * A language as the model named it, as a code: `ja`, `JA`, `ja-JP` and `jp` are `ja`; anything
     * that is not a two- or three-letter code ("Japanese") is null.
     */
    fun languageCode(named: String?): String? {
        val code = named?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (code.length !in 2..3 || !code.all { it in 'a'..'z' }) return null
        return when (code) {
            "jp" -> "ja"
            "kr" -> "ko"
            "cn" -> "zh"
            else -> code
        }
    }

    private fun jsonIn(answer: String): JsonElement? {
        val text = answer.trim()
        json(text)?.let { return it }
        // Something around it: the object (DjPrompt's rule), else the array, from first to last bracket.
        DjPrompt.extractJsonObject(text)?.let { o -> json(o)?.let { return it } }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start in 0 until end) json(text.substring(start, end + 1)) else null
    }

    private fun json(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
}
