package com.varuna.rustify.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.varuna.rustify.platform.httpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The ranged fetch against a real HTTP server on loopback, through the same client the app uses. */
class EngineDownloadsTest {
    private val body = Random(7).nextBytes((3.5 * (1 shl 20)).toInt())
    private val dir = createTempDirectory("downloads").toFile()
    private var server: HttpServer? = null

    @AfterTest
    fun clean() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/a") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/a"
    }

    private fun answerRange(exchange: HttpExchange, total: Boolean = true) {
        val (from, to) = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map { it.toInt() }
        if (from >= body.size) {
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val last = minOf(to, body.size - 1)
        exchange.responseHeaders.add("Content-Range", "bytes $from-$last/${if (total) body.size.toString() else "*"}")
        exchange.sendResponseHeaders(206, (last - from + 1).toLong())
        exchange.responseBody.write(body, from, last - from + 1)
    }

    private fun fetch(url: String, out: File, knownTotal: Long? = null, onProgress: (Int) -> Unit = {}) = runBlocking {
        RangeFetcher(httpClient { install(HttpTimeout) }).fetchTo(url, out, knownTotal, onProgress)
    }

    @Test
    fun `a file comes down in ranges, whole and in order`() {
        val asked = AtomicInteger()
        val url = serve { asked.incrementAndGet(); answerRange(it) }
        val out = File(dir, "a")
        val progress = mutableListOf<Int>()
        fetch(url, out) { progress += it }
        assertContentEquals(body, out.readBytes())
        assertEquals(4, asked.get())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.all { it in 0..99 })
    }

    @Test
    fun `a known size stops at the size`() {
        val url = serve { answerRange(it) }
        val out = File(dir, "a")
        fetch(url, out, knownTotal = body.size.toLong())
        assertContentEquals(body, out.readBytes())
    }

    @Test
    fun `a server that does not say the total still ends`() {
        val url = serve { answerRange(it, total = false) }
        val out = File(dir, "a")
        fetch(url, out)
        assertContentEquals(body, out.readBytes())
    }

    @Test
    fun `a server that ignores ranges gives the whole file at once`() {
        val asked = AtomicInteger()
        val url = serve {
            asked.incrementAndGet()
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.write(body)
        }
        val out = File(dir, "a")
        fetch(url, out)
        assertContentEquals(body, out.readBytes())
        assertEquals(1, asked.get())
    }

    @Test
    fun `a range that fails once is asked again and not written twice`() {
        val asked = AtomicInteger()
        val url = serve {
            if (asked.incrementAndGet() == 2) it.sendResponseHeaders(503, -1) else answerRange(it)
        }
        val out = File(dir, "a")
        fetch(url, out)
        assertContentEquals(body, out.readBytes())
    }

    @Test
    fun `a server that keeps failing is a failure`() {
        val url = serve { it.sendResponseHeaders(403, -1) }
        assertFailsWith<Exception> { fetch(url, File(dir, "a")) }
    }

    @Test
    fun `less than the known size is a failure`() {
        val url = serve { answerRange(it) }
        assertFailsWith<Exception> { fetch(url, File(dir, "a"), knownTotal = body.size + 5L) }
    }

    @Test
    fun `an earlier partial file is replaced`() {
        val url = serve { answerRange(it) }
        val out = File(dir, "a").apply { writeBytes(ByteArray(99)) }
        fetch(url, out)
        assertContentEquals(body, out.readBytes())
    }

    @Test
    fun `file names lose what a system refuses`() {
        assertEquals("AC_DC - Back_In_Black_", safeFileName("AC/DC - Back\\In*Black?"))
        assertEquals("a_b", safeFileName("a\u0007b"))
        assertEquals("Mr", safeFileName("  Mr.. "))
        assertEquals("download", safeFileName(" ... "))
        assertEquals(180, safeFileName("x".repeat(400)).length)
    }

    @Test
    fun `the bytes decide the extension before the type does`() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals("m4a", audioExtensionOf(bytes(0, 0, 0, 0x20) + "ftypM4A ".encodeToByteArray(), "audio/webm"))
        assertEquals("webm", audioExtensionOf(bytes(0x1A, 0x45, 0xDF, 0xA3), "audio/mp4"))
        assertEquals("flac", audioExtensionOf("fLaC".encodeToByteArray(), "audio/mp4"))
        assertEquals("ogg", audioExtensionOf("OggS".encodeToByteArray(), "audio/mp4"))
        assertEquals("mp3", audioExtensionOf("ID3".encodeToByteArray(), "audio/mp4"))
        assertEquals("mp3", audioExtensionOf(bytes(0xFF, 0xFB), "audio/mp4"))
        assertEquals("webm", audioExtensionOf(ByteArray(0), "audio/webm; codecs=opus"))
        assertEquals("m4a", audioExtensionOf(ByteArray(0), "application/octet-stream"))
    }
}
