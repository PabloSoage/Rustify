package com.varuna.rustify.dj

import org.json.JSONObject

/**
 * What a language-model DJ is told and how its answer is read — shared by the remote
 * [ApiDjProvider] and the on-device [LocalDjProvider], so both answer in one shape and one parser
 * serves both.
 */
internal object DjPrompt {
    internal fun systemPrompt(context: DjContext): String = buildString {
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

    internal fun userPrompt(context: DjContext, request: String): String = buildString {
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
    internal fun parsePlan(content: String): DjPlan? {
        val json = extractJsonObject(content) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val intro = obj.optString("intro", "").trim()
        val seeds = mutableListOf<DjSeed>()
        obj.optJSONArray("seeds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotBlank()) seeds += DjSeed(DjSeed.Type.QUERY, v)
            }
        }
        obj.optJSONArray("queries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotBlank()) seeds += DjSeed(DjSeed.Type.QUERY, v)
            }
        }
        if (intro.isBlank() && seeds.isEmpty()) return null
        return DjPlan(
            intro = intro.ifBlank { "Here's your DJ set." },
            seeds = seeds
        )
    }

    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
