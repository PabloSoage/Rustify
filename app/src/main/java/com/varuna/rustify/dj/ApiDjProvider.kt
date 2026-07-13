package com.varuna.rustify.dj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * E90 — Provider de IA externa. Llama a un endpoint **OpenAI-compatible**
 * (`/v1/chat/completions` o `/openai`) usando `java.net.HttpURLConnection` (sin deps nuevas).
 *
 * Por defecto apunta a Pollinations AI (keyless, gratuito, best-effort). [apiKey] es OPCIONAL:
 * si está en blanco no se envía cabecera Authorization (endpoint público). NO se embebe ninguna
 * key privada de terceros.
 *
 * Diseño robusto (ver docs/90-ai-dj-assistant.md §3.4):
 *  - system prompt (rol de DJ) + contexto del usuario + petición NL.
 *  - Se pide al modelo responder JSON `{intro, seeds:[...], queries:[...]}` — NO URIs finales.
 *  - Se parsea esa respuesta a [DjPlan] con [DjSeed]s; el [DjEngine] las resuelve a tracks reales.
 *  - Ante cualquier fallo (red, JSON inválido, rate limit) degrada al [fallback] heurístico.
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
            // Degradación elegante: si la API falla, usa el heurístico offline.
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
     * Acepta base URLs con o sin ruta. Si ya trae `/openai` o `/chat/completions` se respeta;
     * si termina en `/v1` se le añade `/chat/completions`; en otro caso se asume OpenAI estándar.
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

    /** Extrae `choices[0].message.content` de la respuesta OpenAI-compatible. */
    private fun extractAssistantContent(raw: String): String {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        return first.optJSONObject("message")?.optString("content")
            ?: first.optString("text", "")
    }

    /**
     * Parsea el JSON pedido al modelo `{intro, seeds, queries}`. Tolerante: el LLM a veces envuelve
     * el JSON en texto/markdown, así que se extrae el primer objeto `{...}`. Devuelve null si no
     * hay nada aprovechable (→ el llamador cae al fallback).
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
