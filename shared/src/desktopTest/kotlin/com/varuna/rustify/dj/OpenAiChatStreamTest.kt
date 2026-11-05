package com.varuna.rustify.dj

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException

/** The request and the reading of the answer, against a server on loopback. */
class OpenAiChatStreamTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var sentBody = ""
    private var sentAuth: String? = null

    private fun answering(status: Int, type: String, body: String): OpenAiChat {
        server.createContext("/v1/chat/completions") { exchange ->
            sentBody = exchange.requestBody.readBytes().decodeToString()
            sentAuth = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Content-Type", type)
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return OpenAiChat("http://127.0.0.1:${server.address.port}/v1/chat/completions", "m", "secret")
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val hello = listOf(OpenAiChat.Message("user", "hola"))

    @Test
    fun aStreamIsReadPieceByPieceUpToDone() = runBlocking {
        val chat = answering(
            200, "text/event-stream",
            """
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"Bue"}}]}

            data: {"choices":[{"delta":{"content":"nas ñ"}}]}

            data: [DONE]

            data: {"choices":[{"delta":{"content":"after the end"}}]}
            """.trimIndent(),
        )
        val pieces = ArrayList<String>()

        assertEquals("Buenas ñ", chat.stream(hello) { pieces += it })
        assertEquals(listOf("Bue", "nas ñ"), pieces)
        assertEquals("Bearer secret", sentAuth)
        assertTrue(sentBody.contains("\"stream\":true") && sentBody.contains("\"hola\""), sentBody)
    }

    @Test
    fun aServerThatIgnoresStreamIsReadWhole() = runBlocking {
        val chat = answering(200, "application/json", """{"choices":[{"message":{"content":"entero"}}]}""")
        val pieces = ArrayList<String>()

        assertEquals("entero", chat.stream(hello) { pieces += it })
        assertEquals(listOf("entero"), pieces)
    }

    @Test
    fun aRefusalSaysWhatTheServerSaid() = runBlocking<Unit> {
        val chat = answering(401, "application/json", """{"error":{"message":"bad key"}}""")

        val failure = assertFailsWith<IOException> { chat.stream(hello) }
        assertEquals("HTTP 401: bad key", failure.message)
    }

    @Test
    fun anErrorInTheMiddleOfTheStreamIsAFailure() = runBlocking<Unit> {
        val chat = answering(200, "text/event-stream", "data: {\"error\":{\"message\":\"overloaded\"}}\n")

        assertEquals("overloaded", assertFailsWith<IOException> { chat.stream(hello) }.message)
    }
}
