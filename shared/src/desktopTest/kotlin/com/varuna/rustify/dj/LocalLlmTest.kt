package com.varuna.rustify.dj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the desktop's local DJ reads from the servers it finds, and which one it asks. The bodies are
 * the shapes each server documents; a port answered by something else must not look like a server.
 */
class LocalLlmTest {

    @Test
    fun `Ollama's tags are its models, without the embedding ones`() {
        val body = """{"models":[{"name":"llama3.2:latest","model":"llama3.2:latest","size":2019393189},
            {"name":"nomic-embed-text:latest","model":"nomic-embed-text:latest"},{"model":"qwen2.5:3b"}]}"""
        assertEquals(listOf("llama3.2:latest", "qwen2.5:3b"), LocalLlmDetector.parseOllamaTags(body))
    }

    @Test
    fun `an OpenAI model list is read by id`() {
        val body = """{"object":"list","data":[{"id":"qwen2.5-7b-instruct","object":"model"},
            {"id":"text-embedding-nomic-embed-text-v1.5","object":"model"}]}"""
        assertEquals(listOf("qwen2.5-7b-instruct"), LocalLlmDetector.parseOpenAiModels(body))
    }

    @Test
    fun `llama-server's own list is read too`() {
        val body = """{"models":[{"name":"model.gguf","model":"model.gguf"}]}"""
        assertEquals(listOf("model.gguf"), LocalLlmDetector.parseOpenAiModels(body))
    }

    @Test
    fun `something else on the port is not a server`() {
        assertNull(LocalLlmDetector.parseOpenAiModels("<html>It works!</html>"))
        assertNull(LocalLlmDetector.parseOpenAiModels("""{"status":"ok"}"""))
        assertNull(LocalLlmDetector.parseOllamaTags("[]"))
    }

    private val ollama = LocalLlmServer(LocalLlmServer.Kind.OLLAMA, "http://127.0.0.1:11434/v1", listOf("llama3.2:latest", "qwen2.5:3b"))
    private val emptyLmStudio = LocalLlmServer(LocalLlmServer.Kind.LM_STUDIO, "http://127.0.0.1:1234/v1", emptyList())
    private val llamaCpp = LocalLlmServer(LocalLlmServer.Kind.LLAMA_CPP, "http://127.0.0.1:8080/v1", listOf("model.gguf"))

    @Test
    fun `auto takes the first server with a model`() {
        val target = LocalLlmSettings.pick(LocalLlmSettings.AUTO, "", listOf(emptyLmStudio, llamaCpp))
        assertEquals(LocalLlmSettings.Target(LocalLlmServer.Kind.LLAMA_CPP, "http://127.0.0.1:8080/v1", "model.gguf"), target)
    }

    @Test
    fun `the chosen model is kept while the server still has it`() {
        assertEquals("qwen2.5:3b", LocalLlmSettings.pick("ollama", "qwen2.5:3b", listOf(ollama))?.model)
        assertEquals("llama3.2:latest", LocalLlmSettings.pick("ollama", "gone:latest", listOf(ollama))?.model)
    }

    @Test
    fun `a chosen server that is not running is no target`() {
        assertNull(LocalLlmSettings.pick("llamacpp", "", listOf(ollama)))
        assertNull(LocalLlmSettings.pick("lmstudio", "", listOf(emptyLmStudio, ollama)))
        assertNull(LocalLlmSettings.pick(LocalLlmSettings.AUTO, "", emptyList()))
    }

    @Test
    fun `Rustify's own server is never picked from what was found`() {
        // It is not looked for on a port; the caller starts it when it is the choice.
        assertNull(LocalLlmSettings.pick(LocalLlmServer.Kind.MANAGED.code, "", listOf(ollama)))
        assertEquals(false, LocalLlmServer.Kind.MANAGED in LocalLlmDetector.PROBED)
    }

    @Test
    fun `an unknown managed model falls back to the first in the catalog`() {
        val prefs = com.varuna.rustify.platform.MemoryStore()
        assertEquals(LocalModelCatalog.MODELS.first(), LocalLlmSettings.managedModel(prefs))
        prefs.putString(LocalLlmSettings.KEY_MANAGED_MODEL, LocalModelCatalog.MODELS.last().id)
        assertEquals(LocalModelCatalog.MODELS.last(), LocalLlmSettings.managedModel(prefs))
        prefs.putString(LocalLlmSettings.KEY_MANAGED_MODEL, "gone")
        assertEquals(LocalModelCatalog.MODELS.first(), LocalLlmSettings.managedModel(prefs))
    }

    @Test
    fun `every model in the catalog is a GGUF file under its own name`() {
        for (m in LocalModelCatalog.MODELS) {
            assertEquals(true, m.url.startsWith("https://huggingface.co/") && m.url.endsWith("/" + m.fileName), m.id)
            assertEquals(true, m.fileName.endsWith(".gguf"), m.id)
        }
        assertEquals(LocalModelCatalog.MODELS.size, LocalModelCatalog.MODELS.map { it.id }.toSet().size)
    }
}
