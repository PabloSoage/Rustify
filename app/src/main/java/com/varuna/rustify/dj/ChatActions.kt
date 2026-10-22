package com.varuna.rustify.dj

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the Gemini Nano chat can do in the app (4.8.1), besides talking: play, queue, search, start a
 * DJ mix and the transport buttons.
 *
 * The Prompt API has no function calling, so the model is asked for the same thing the DJ asks it
 * for: plain text plus one machine-readable line, here `ACTION: {"do": …}` at the end of the answer.
 * The app strips that line from what it shows and does the action itself — the model never touches
 * the player, it only says what the user asked for. One action per answer, and none for a question.
 */
sealed interface ChatAction {
    data class Play(val query: String) : ChatAction
    data class Queue(val query: String) : ChatAction
    data class Search(val query: String) : ChatAction
    data class Dj(val request: String) : ChatAction
    data object Pause : ChatAction
    data object Resume : ChatAction
    data object Next : ChatAction
    data object Previous : ChatAction
}

object ChatActions {
    private const val MARKER = "ACTION:"

    /** The instructions that go before the conversation: who the assistant is and what it can do. */
    fun systemPrompt(nowPlaying: String?, upNext: List<String>): String = buildString {
        append("You are the music assistant inside Rustify, a music player app, running on this phone. ")
        append("Talk about music normally. You can also control the app: when the user asks you to play, ")
        append("queue or find music, start a mix, or pause, resume or skip, answer with one short sentence ")
        append("and end with one last line exactly like this:\n")
        append("ACTION: {\"do\": \"play\", \"query\": \"Artist - Song\"}\n")
        append("\"do\" is one of: play (a song or an artist, then similar music), queue (add one song to ")
        append("the queue), search (show matching songs), dj (start a mix; put what it should be in ")
        append("\"request\"), pause, resume, next, previous. Use real artist and song names. ")
        append("Write ACTION only when the user asks for one of those things, never for a question.\n")
        append("Examples:\n")
        append("User: play something by Queen\nAssistant: Here's Queen.\nACTION: {\"do\": \"play\", \"query\": \"Queen\"}\n")
        append("User: put Hotel California next\nAssistant: Added.\nACTION: {\"do\": \"queue\", \"query\": \"Eagles - Hotel California\"}\n")
        append("User: make me a chill mix for studying\nAssistant: A calm mix coming up.\nACTION: {\"do\": \"dj\", \"request\": \"chill music for studying\"}\n")
        append("User: skip this one\nAssistant: Skipping.\nACTION: {\"do\": \"next\"}\n")
        nowPlaying?.let { append("Now playing: ").append(it).append(".\n") }
        if (upNext.isNotEmpty()) append("Up next: ").append(upNext.take(5).joinToString("; ")).append(".\n")
    }

    /**
     * What to show of an answer that may still be streaming: everything before the action line, so
     * the raw JSON never flashes on screen while it is being written.
     */
    fun visibleText(answer: String): String {
        val at = answer.indexOf(MARKER, ignoreCase = true)
        var shown = if (at >= 0) answer.substring(0, at) else answer
        // The marker itself arrives a few letters at a time: a last line that could still become it
        // ("AC", "ACTI") is held back too.
        val lastLine = shown.substringAfterLast('\n').trim().trimStart('`')
        if (at < 0 && '\n' in shown && lastLine.isNotEmpty() && MARKER.startsWith(lastLine, ignoreCase = true)) {
            shown = shown.substringBeforeLast('\n', "")
        }
        return shown.trimEnd().removeSuffix("`").trimEnd()
    }

    /** The action a finished answer asks for, or null when there is none or it cannot be read. */
    fun parse(answer: String): ChatAction? {
        val at = answer.lastIndexOf(MARKER, ignoreCase = true)
        if (at < 0) return null
        val json = DjPrompt.extractJsonObject(answer.substring(at + MARKER.length)) ?: return null
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        fun field(name: String) = ((obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()
        val query = field("query")
        val request = field("request").ifEmpty { query }
        return when (field("do").lowercase()) {
            "play" -> query.takeIf { it.isNotEmpty() }?.let { ChatAction.Play(it) }
            "queue" -> query.takeIf { it.isNotEmpty() }?.let { ChatAction.Queue(it) }
            "search" -> query.takeIf { it.isNotEmpty() }?.let { ChatAction.Search(it) }
            "dj" -> request.takeIf { it.isNotEmpty() }?.let { ChatAction.Dj(it) }
            "pause" -> ChatAction.Pause
            "resume", "play_pause" -> ChatAction.Resume
            "next", "skip" -> ChatAction.Next
            "previous", "back" -> ChatAction.Previous
            else -> null
        }
    }
}
