// shared/src/desktopMain/kotlin/com/varuna/rustify/dj/LocalLlm.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * A language-model server answering on this computer's loopback, OpenAI-compatible at [baseUrl]
 * (`…/v1`, which [ApiDjProvider] completes with `/chat/completions`).
 */
data class LocalLlmServer(val kind: Kind, val baseUrl: String, val models: List<String>) {
    enum class Kind(val label: String, val port: Int, val code: String) {
        OLLAMA("Ollama", 11434, "ollama"),
        LM_STUDIO("LM Studio", 1234, "lmstudio"),
        LLAMA_CPP("llama.cpp (llama-server)", 8080, "llamacpp"),

        /** The llama-server Rustify downloads and runs itself ([LocalModelManager]), on a free port. */
        MANAGED("Rustify (llama.cpp)", 0, "managed"),
    }

    /** `127.0.0.1:11434`, for saying where it was found. */
    val address: String get() = baseUrl.removePrefix("http://").substringBefore('/')
}

/** Where the desktop's "local" DJ mode looks, and what it picked; kept in `rustify_settings`. */
object LocalLlmSettings {
    /** "auto" (the first server found, else Rustify's own), or a [LocalLlmServer.Kind.code]. */
    const val KEY_SOURCE = "dj_local_source"

    /** The model chosen on the server [KEY_SOURCE] names; blank: its first. */
    const val KEY_MODEL = "dj_local_model"

    const val AUTO = "auto"

    /** The [LocalModelCatalog] model Rustify's own server runs. */
    const val KEY_MANAGED_MODEL = "dj_local_managed_model"

    fun source(prefs: KeyValueStore): String = prefs.getString(KEY_SOURCE)?.ifBlank { null } ?: AUTO
    fun model(prefs: KeyValueStore): String = prefs.getString(KEY_MODEL) ?: ""
    fun managedModel(prefs: KeyValueStore): LocalModel = LocalModelCatalog.byId(prefs.getString(KEY_MANAGED_MODEL) ?: "")

    /** What a request goes to: the server's OpenAI base URL and the model to name. */
    data class Target(val kind: LocalLlmServer.Kind, val baseUrl: String, val model: String)

    /**
     * The target among [servers] for [source] and the [chosenModel], or null when none of them will
     * do (then Rustify's own server, if there is one, is the caller's to try). A server with no model
     * loaded is no target.
     */
    fun pick(source: String, chosenModel: String, servers: List<LocalLlmServer>): Target? {
        val candidates = when (source) {
            AUTO -> servers
            else -> servers.filter { it.kind.code == source }
        }
        val server = candidates.firstOrNull { it.models.isNotEmpty() } ?: return null
        val model = chosenModel.takeIf { it in server.models } ?: server.models.first()
        return Target(server.kind, server.baseUrl, model)
    }

    /**
     * What the "local" mode asks now, by what [prefs] chose: a server found on loopback ([pick]), else
     * Rustify's own ([managed]) when the choice allows it; null when nothing will answer. The DJ and
     * the desktop's chat and lyrics translation ask the same one.
     */
    suspend fun target(prefs: KeyValueStore, managed: suspend () -> Target?): Target? {
        val source = source(prefs)
        val servers = if (source == LocalLlmServer.Kind.MANAGED.code) emptyList() else LocalLlmDetector.detect()
        return pick(source, model(prefs), servers)
            ?: if (source == AUTO || source == LocalLlmServer.Kind.MANAGED.code) managed() else null
    }
}

/**
 * Finds the servers people already run for local models — Ollama, LM Studio and llama.cpp's
 * `llama-server` — at their usual ports. Only ever `127.0.0.1`: nothing here talks to another
 * machine, and nothing is sent but a list request.
 */
object LocalLlmDetector {
    const val HOST = "127.0.0.1"

    /** The servers looked for, in the "auto" order; Rustify's own is not looked for, it is known. */
    val PROBED: List<LocalLlmServer.Kind> = listOf(LocalLlmServer.Kind.OLLAMA, LocalLlmServer.Kind.LM_STUDIO, LocalLlmServer.Kind.LLAMA_CPP)

    /** Every server answering, in the order above (the "auto" preference). */
    suspend fun detect(): List<LocalLlmServer> = withContext(Dispatchers.IO) {
        coroutineScope {
            PROBED.map { kind -> async { runCatching { probe(kind) }.getOrNull() } }.awaitAll().filterNotNull()
        }
    }

    private fun probe(kind: LocalLlmServer.Kind): LocalLlmServer? {
        val base = "http://$HOST:${kind.port}"
        return when (kind) {
            // Ollama's own list has every pulled model; /v1/models only the OpenAI view of them.
            LocalLlmServer.Kind.OLLAMA -> get("$base/api/tags")?.let { body ->
                parseOllamaTags(body)?.let { LocalLlmServer(kind, "$base/v1", it) }
            }
            else -> get("$base/v1/models")?.let { body ->
                parseOpenAiModels(body)?.let { LocalLlmServer(kind, "$base/v1", it) }
            }
        }
    }

    /** The body of a short GET on loopback, or null when nothing (or not 2xx) answers in time. */
    fun get(url: String, connectMs: Int = 700, readMs: Int = 2_500): String? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `{"models":[{"name":"llama3.2:latest",…}]}`; null when it is not that shape (something else on
     * the port). Embedding models are left out: they cannot chat.
     */
    fun parseOllamaTags(body: String): List<String>? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val models = root["models"] as? JsonArray ?: return null
        return models.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            (o["name"] ?: o["model"]).string()
        }.filter { chatCapable(it) }
    }

    /**
     * OpenAI's `{"data":[{"id":"…"}]}` (LM Studio, llama-server), or llama-server's own
     * `{"models":[{"name"|"model":"…"}]}`; null when it is neither.
     */
    fun parseOpenAiModels(body: String): List<String>? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val data = root["data"] as? JsonArray
        if (data != null) {
            return data.mapNotNull { (it as? JsonObject)?.get("id").string() }.filter { chatCapable(it) }
        }
        val models = root["models"] as? JsonArray ?: return null
        return models.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            (o["name"] ?: o["model"]).string()
        }.filter { chatCapable(it) }
    }

    private fun chatCapable(model: String): Boolean = !model.lowercase().contains("embed")

    private fun kotlinx.serialization.json.JsonElement?.string(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
