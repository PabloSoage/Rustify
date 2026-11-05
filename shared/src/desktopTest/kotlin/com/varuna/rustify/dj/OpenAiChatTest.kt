package com.varuna.rustify.dj

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the desktop's chat reads from an OpenAI-compatible stream. The lines are the shapes llama.cpp's
 * llama-server, Ollama and LM Studio send; a role-only first chunk, a finish chunk and comments say
 * nothing, and an error inside the stream is an error.
 */
class OpenAiChatTest {

    @Test
    fun `a delta's content is the text`() {
        val line = """data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
        assertEquals(OpenAiChat.Event.Text("Hel"), OpenAiChat.parseEvent(line))
    }

    @Test
    fun `the role chunk and the finish chunk say nothing`() {
        assertEquals(OpenAiChat.Event.Empty, OpenAiChat.parseEvent("""data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}"""))
        assertEquals(OpenAiChat.Event.Empty, OpenAiChat.parseEvent("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals(OpenAiChat.Event.Empty, OpenAiChat.parseEvent("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(OpenAiChat.Event.Empty, OpenAiChat.parseEvent("""data: {"choices":[],"usage":{"completion_tokens":12}}"""))
    }

    @Test
    fun `DONE ends the stream and other fields are not data`() {
        assertEquals(OpenAiChat.Event.Done, OpenAiChat.parseEvent("data: [DONE]"))
        assertEquals(OpenAiChat.Event.Done, OpenAiChat.parseEvent("data:[DONE]"))
        assertEquals(OpenAiChat.Event.NotData, OpenAiChat.parseEvent(""))
        assertEquals(OpenAiChat.Event.NotData, OpenAiChat.parseEvent(": ping"))
        assertEquals(OpenAiChat.Event.NotData, OpenAiChat.parseEvent("event: message"))
    }

    @Test
    fun `an error in the stream is said`() {
        assertEquals(
            OpenAiChat.Event.Failed("the request exceeds the available context size"),
            OpenAiChat.parseEvent("""data: {"error":{"code":400,"message":"the request exceeds the available context size","type":"invalid_request_error"}}"""),
        )
        assertEquals(OpenAiChat.Event.Failed("model not found"), OpenAiChat.parseEvent("""data: {"error":"model not found"}"""))
    }

    @Test
    fun `a completion that was not streamed is read whole`() {
        val body = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"Here is Queen."},"finish_reason":"stop"}]}"""
        assertEquals("Here is Queen.", OpenAiChat.completionContent(body))
        assertNull(OpenAiChat.completionContent("<html>It works!</html>"))
        assertEquals("""model "x" not found""", OpenAiChat.errorMessage("""{"error":{"message":"model \"x\" not found"}}"""))
    }

    @Test
    fun `the request asks for a stream with the conversation in order`() {
        val body = OpenAiChat.requestBody(
            "qwen2.5:3b",
            listOf(OpenAiChat.Message("system", "be brief"), OpenAiChat.Message("user", "hi")),
            temperature = 0.7,
            maxTokens = 64,
        )
        val obj = Json.parseToJsonElement(body) as JsonObject
        assertEquals(JsonPrimitive(true), obj["stream"])
        assertEquals(JsonPrimitive("qwen2.5:3b"), obj["model"])
        assertEquals(JsonPrimitive(64), obj["max_tokens"])
        val roles = (obj["messages"] as JsonArray).map { ((it as JsonObject)["role"] as JsonPrimitive).content }
        assertEquals(listOf("system", "user"), roles)
    }
}
