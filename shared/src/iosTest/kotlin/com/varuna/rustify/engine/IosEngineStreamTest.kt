// shared/src/iosTest/kotlin/com/varuna/rustify/engine/IosEngineStreamTest.kt
package com.varuna.rustify.engine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine playing over the network on iOS: the real core, `HttpSource` and the real output, against
 * a server on loopback, because YouTube turns the CI's runners away. A file played from disk proves
 * none of this path.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class IosEngineStreamTest {

    @Test
    fun `audio that arrives over http plays and seeks`() = runBlocking<Unit> {
        val server = LoopbackAudio(wavTone(seconds = 30))
        try {
            assertTrue(IosPlayerBackend.startEngine().contains("\"success\":true"), "the engine starts")
            val token = 7001L

            IosPlayerBackend.send(EngineCommand.Load(token, server.url("tone.wav")))
            val ready = await(token) { it is EngineEvent.Ready && it.token == token } as EngineEvent.Ready
            assertNotNull(ready.durationMs, "the length is known from the header")
            assertTrue(ready.durationMs!! in 29_000L..31_000L, "30 s long, read ${ready.durationMs}")

            await(token) { it is EngineEvent.Position && it.token == token && it.positionMs >= 1_000 }

            // Further than the first chunk asked for, so it is only there if the ranges were followed.
            IosPlayerBackend.send(EngineCommand.Seek(20_000))
            await(token) { it is EngineEvent.Position && it.token == token && it.positionMs >= 20_000 }
            assertTrue(server.furthestRange.value > 0, "asked for more than the first range")
        } catch (e: NoAudioDevice) {
            println("Not checked here, the simulator has no audio device: ${e.message}")
        } finally {
            IosPlayerBackend.send(EngineCommand.Stop)
            server.close()
        }
    }

    @Test
    fun `a stream the server does not have is an error and not silence`() = runBlocking<Unit> {
        val server = LoopbackAudio(wavTone(seconds = 1))
        try {
            assertTrue(IosPlayerBackend.startEngine().contains("\"success\":true"), "the engine starts")
            val token = 7002L

            IosPlayerBackend.send(EngineCommand.Load(token, server.url("missing.wav")))
            val failure = withTimeout(TIMEOUT_MS) {
                IosPlayerBackend.events().first { it is EngineEvent.Error && it.token == token }
            } as EngineEvent.Error

            assertEquals("http", failure.code, failure.message)
        } finally {
            IosPlayerBackend.send(EngineCommand.Stop)
            server.close()
        }
    }

    /** The first event [matching], failing on an error of [token] rather than waiting out the time. */
    private suspend fun await(token: Long, matching: (EngineEvent) -> Boolean): EngineEvent =
        withTimeout(TIMEOUT_MS) {
            IosPlayerBackend.events().first {
                if (it is EngineEvent.Error && it.token == token) {
                    if (it.code == "output" && Platform.cpuArchitecture == CpuArchitecture.X64) throw NoAudioDevice(it.message)
                    fail("the engine failed: $it")
                }
                matching(it)
            }
        }

    /**
     * The x86_64 simulator of the Mac VM has no audio device, so the engine cannot open its output there.
     * Only there: on arm64, which is what the CI runs, this is a failure like any other.
     */
    private class NoAudioDevice(message: String) : Exception(message)

    /** A mono 16-bit WAV of a 440 Hz tone, as a square wave so it needs no maths library. */
    private fun wavTone(seconds: Int): ByteArray {
        val rate = 44_100
        val samples = rate * seconds
        val dataSize = samples * 2
        val out = ByteArray(44 + dataSize)
        fun text(at: Int, s: String) = s.encodeToByteArray().copyInto(out, at)
        fun int(at: Int, bytes: Int, value: Int) = repeat(bytes) { out[at + it] = (value shr (8 * it)).toByte() }
        text(0, "RIFF")
        int(4, 4, 36 + dataSize)
        text(8, "WAVEfmt ")
        int(16, 4, 16)
        int(20, 2, 1)
        int(22, 2, 1)
        int(24, 4, rate)
        int(28, 4, rate * 2)
        int(32, 2, 2)
        int(34, 2, 16)
        text(36, "data")
        int(40, 4, dataSize)
        for (i in 0 until samples) int(44 + i * 2, 2, if ((i * 880L / rate) % 2 == 0L) 4_000 else -4_000)
        return out
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * Serves [body] as `/tone.wav` on 127.0.0.1, honouring `Range`, and 404 for anything else. Enough HTTP
 * for what `HttpSource` asks: one GET per connection.
 */
@OptIn(ExperimentalForeignApi::class)
private class LoopbackAudio(private val body: ByteArray) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val listener = socket(AF_INET, SOCK_STREAM, 0)
    private val port: Int

    /** The furthest byte a request has started from. */
    val furthestRange = MutableStateFlow(0L)

    init {
        check(listener >= 0) { "no socket" }
        port = memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1, in network order.
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "no bind" }
            check(listen(listener, 8) == 0) { "no listen" }
            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "no name" }
            val raw = address.sin_port.toInt()
            ((raw and 0xFF) shl 8) or (raw shr 8)
        }
        scope.launch {
            while (true) {
                val client = accept(listener, null, null)
                if (client < 0) break
                scope.launch { answer(client) }
            }
        }
    }

    fun url(name: String) = "http://127.0.0.1:$port/$name"

    private fun answer(client: Int) {
        // A client that hangs up mid-body must end the send, not the process.
        memScoped {
            val on = alloc<kotlinx.cinterop.IntVar>()
            on.value = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<kotlinx.cinterop.IntVar>().convert())
        }
        val request = readRequest(client)
        val path = request.lineSequence().first().split(' ').getOrElse(1) { "" }
        if (path != "/tone.wav") {
            write(client, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
        } else {
            val range = Regex("""(?i)range: *bytes=(\d+)-(\d*)""").find(request)
            val from = range?.groupValues?.get(1)?.toLong() ?: 0L
            val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
            furthestRange.update { maxOf(it, from) }
            val status = if (range != null) "206 Partial Content" else "200 OK"
            val head = "HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${to - from + 1}\r\n" +
                "Content-Range: bytes $from-$to/${body.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
            write(client, head.encodeToByteArray())
            write(client, body.copyOfRange(from.toInt(), to.toInt() + 1))
        }
        shutdown(client, SHUT_RDWR)
        close(client)
    }

    private fun readRequest(client: Int): String {
        val buffer = ByteArray(8192)
        var have = 0
        while (have < buffer.size) {
            val read = buffer.usePinned { recv(client, it.addressOf(have), (buffer.size - have).convert(), 0) }
            if (read <= 0) break
            have += read.toInt()
            if (buffer.decodeToString(0, have).contains("\r\n\r\n")) break
        }
        return buffer.decodeToString(0, have)
    }

    private fun write(client: Int, bytes: ByteArray) {
        var sent = 0
        bytes.usePinned { pinned ->
            while (sent < bytes.size) {
                val wrote = send(client, pinned.addressOf(sent), (bytes.size - sent).convert(), 0)
                if (wrote <= 0) return
                sent += wrote.toInt()
            }
        }
    }

    fun close() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
        scope.cancel()
    }
}
