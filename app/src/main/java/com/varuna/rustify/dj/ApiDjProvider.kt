package com.varuna.rustify.dj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * External AI provider. Calls an OpenAI-compatible endpoint (`/v1/chat/completions` or `/openai`)
 * using `java.net.HttpURLConnection` (no new dependencies).
 *
 * Defaults to Pollinations AI (keyless, free, best-effort). [apiKey] is optional: when blank, no
 * Authorization header is sent (public endpoint). No third-party private key is embedded.
 *
 * Design:
 *  - system prompt (DJ role) + user context + NL request.
 *  - The model is asked to respond with JSON `{intro, seeds:[...], queries:[...]}` — not final URIs.
 *  - That response is parsed into a [DjPlan] with [DjSeed]s; the [DjEngine] resolves them to real tracks.
 *  - On any failure (network, invalid JSON, rate limit) it degrades to the [fallback] heuristic.
 */
class ApiDjProvider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val fallback: DjProvider = HeuristicDjProvider()
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequestBody(context, request)
            val raw = postChatCompletions(body)
            val content = extractAssistantContent(raw)
            parsePlan(content) ?: fallback.plan(context, request)
        }.getOrElse {
            // Graceful degradation: if the API fails, use the offline heuristic.
            fallback.plan(context, request)
        }
    }

    private fun systemPrompt(context: DjContext): String = buildString {
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

    private fun userPrompt(context: DjContext, request: String): String = buildString {
        append("Top artists: ").append(context.topArtists.joinToString(", ").ifBlank { "(unknown)" }).append("\n")
        append("Top tracks: ").append(context.topTracks.joinToString(", ").ifBlank { "(unknown)" }).append("\n")
        context.nowPlaying?.let { append("Now playing: ").append(it).append("\n") }
        if (context.queuePreview.isNotEmpty()) {
            append("Already queued (avoid repeating): ").append(context.queuePreview.joinToString(", ")).append("\n")
        }
        append("Request: ").append(request.ifBlank { "Start an automix that fits my taste." })
    }

    private fun buildRequestBody(context: DjContext, request: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
        messages.put(JSONObject().put("role", "user").put("content", userPrompt(context, request)))
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.8)
        }.toString()
    }

    private fun postChatCompletions(body: String): String {
        val url = resolveEndpoint(baseUrl)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("DJ API HTTP $code: ${text.take(200)}")
        return text
    }

    /**
     * Accepts base URLs with or without a path. If it already contains `/openai` or
     * `/chat/completions` it is kept as-is; if it ends in `/v1`, `/chat/completions` is appended;
     * otherwise the standard OpenAI layout is assumed.
     */
    private fun resolveEndpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/openai") -> b
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    /** Extracts `choices[0].message.content` from the OpenAI-compatible response. */
    private fun extractAssistantContent(raw: String): String {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        return first.optJSONObject("message")?.optString("content")
            ?: first.optString("text", "")
    }

    /**
     * Parses the JSON requested from the model `{intro, seeds, queries}`. Tolerant: the LLM sometimes
     * wraps the JSON in text/markdown, so the first `{...}` object is extracted. Returns null when
     * there is nothing usable (→ the caller falls back).
     */
    private fun parsePlan(content: String): DjPlan? {
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

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
