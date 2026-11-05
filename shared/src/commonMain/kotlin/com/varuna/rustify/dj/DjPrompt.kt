// shared/src/commonMain/kotlin/com/varuna/rustify/dj/DjPrompt.kt
package com.varuna.rustify.dj

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a language-model DJ is told and how its answer is read — shared by the remote
 * [ApiDjProvider] and the on-device [LocalDjProvider], so both answer in one shape and one parser
 * serves both.
 */
object DjPrompt {
    fun systemPrompt(context: DjContext): String = buildString {
        append("You are the user's personal music DJ inside a music app (like Spotify's AI DJ). ")
        append("Given the user's listening context and a request, pick GOOD music to play next. ")
        append("Respond ONLY with a compact JSON object, no markdown, of the exact shape: ")
        append("{\"intro\": string, \"seeds\": [string], \"queries\": [string]}. ")
        append("\"intro\" is one short spoken line to introduce the set, written in language code '")
        append(context.language).append("'. ")
        append("\"seeds\" are artist names or \"Artist - Song\" strings to build a radio/automix from. ")
        append("\"queries\" are free-text search queries (moods/genres). ")
        append("Return real artist/song names, NOT spotify URIs or ids. Keep 5-12 seeds/queries total.")
    }

    fun userPrompt(context: DjContext, request: String): String = buildString {
        append("Top artists: ").append(context.topArtists.joinToString(", ").ifBlank { "(unknown)" }).append("\n")
        append("Top tracks: ").append(context.topTracks.joinToString(", ").ifBlank { "(unknown)" }).append("\n")
        context.nowPlaying?.let { append("Now playing: ").append(it).append("\n") }
        if (context.queuePreview.isNotEmpty()) {
            append("Already queued (avoid repeating): ").append(context.queuePreview.joinToString(", ")).append("\n")
        }
        append("Request: ").append(request.ifBlank { "Start an automix that fits my taste." })
    }

    /**
     * Parses the JSON requested from the model `{intro, seeds, queries}`. Tolerant: the LLM sometimes
     * wraps the JSON in text/markdown, so the first `{...}` object is extracted. Returns null when
     * there is nothing usable (→ the caller falls back).
     */
    fun parsePlan(content: String): DjPlan? {
        val json = extractJsonObject(content) ?: return null
        val obj = runCatching { Lenient.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val intro = obj["intro"].optString().trim()
        val seeds = mutableListOf<DjSeed>()
        (obj["seeds"] as? JsonArray)?.forEach { item ->
            val v = item.optString().trim()
            if (v.isNotBlank()) seeds += DjSeed(DjSeed.Type.QUERY, v)
        }
        (obj["queries"] as? JsonArray)?.forEach { item ->
            val v = item.optString().trim()
            if (v.isNotBlank()) seeds += DjSeed(DjSeed.Type.QUERY, v)
        }
        if (intro.isBlank() && seeds.isEmpty()) return null
        return DjPlan(
            intro = intro.ifBlank { "Here's your DJ set." },
            seeds = seeds
        )
    }

    /**
     * The model's JSON, read the way `org.json` read it before 5.x moved this to shared code: lenient
     * (unquoted keys and strings get through), and a field's text whatever its JSON type.
     */
    private val Lenient = Json { isLenient = true }

    /** `org.json`'s `optString`: absent or `null` is "", a number or a boolean is its text. */
    private fun JsonElement?.optString(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
