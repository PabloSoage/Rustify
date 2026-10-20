package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chain's decisions, with fake providers and real (short) clocks.
 *
 * What these pin: a slow provider no longer holds up a fast one behind it (4.2), a failure hands
 * over immediately, and a YouTube hint still never reaches a provider that cannot honour it.
 */
class AudioSourceChainTest {

    private class Fake(
        val id: String,
        private val delayMs: Long,
        private val answer: String?,
        private val honoursHint: Boolean = true,
        private val fails: Boolean = false
    ) : AudioSourceProvider {
        var asked = 0
        override val capabilities = AudioSourceCapabilities(
            id = id, displayNameRes = 0, canStream = true, canDownload = false,
            resolveTimeoutMs = 5_000, honoursYoutubeHint = honoursHint
        )
        override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> {
            asked++
            delay(delayMs)
            if (fails) return Result.failure(IllegalStateException("$id broke"))
            return Result.success(StreamInfo(uri = answer ?: error("no answer")))
        }
        override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit) =
            Result.failure<File>(UnsupportedOperationException())
    }

    private val track = FullTrack(id = "t1", name = "Song")

    @Test
    fun `a slow first provider does not block a fast one behind it`() = runBlocking {
        val slow = Fake("slow", delayMs = 3_000, answer = "slow-url")
        val fast = Fake("fast", delayMs = 50, answer = "fast-url")
        val chain = AudioSourceChain(listOf(slow, fast), hedgeAfterMs = 200)
        val started = System.currentTimeMillis()
        val (id, info) = chain.resolveStreamUrl(track).getOrThrow()
        assertEquals("fast", id)
        assertEquals("fast-url", info.uri)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_500)
    }

    @Test
    fun `a healthy first provider answers alone`() = runBlocking {
        val first = Fake("first", delayMs = 50, answer = "first-url")
        val second = Fake("second", delayMs = 50, answer = "second-url")
        val (id, _) = AudioSourceChain(listOf(first, second), hedgeAfterMs = 500).resolveStreamUrl(track).getOrThrow()
        assertEquals("first", id)
        assertEquals("the second was never needed", 0, second.asked)
    }

    @Test
    fun `a failure hands over without waiting for the hedge`() = runBlocking {
        val broken = Fake("broken", delayMs = 10, answer = null, fails = true)
        val next = Fake("next", delayMs = 10, answer = "next-url")
        val started = System.currentTimeMillis()
        val (id, _) = AudioSourceChain(listOf(broken, next), hedgeAfterMs = 5_000).resolveStreamUrl(track).getOrThrow()
        assertEquals("next", id)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `everyone failing names everyone`() = runBlocking {
        val a = Fake("a", delayMs = 10, answer = null, fails = true)
        val b = Fake("b", delayMs = 10, answer = null, fails = true)
        val error = AudioSourceChain(listOf(a, b), hedgeAfterMs = 100).resolveStreamUrl(track).exceptionOrNull()
        assertTrue(error is AudioSourceChainException)
        val message = error!!.message.orEmpty()
        assertTrue(message, message.contains("a: a broke") && message.contains("b: b broke"))
    }

    @Test
    fun `a hint never reaches a provider that cannot honour it`() = runBlocking {
        val deezer = Fake("deezer", delayMs = 10, answer = "wrong-recording", honoursHint = false)
        val youtube = Fake("youtube", delayMs = 300, answer = "the-video")
        val (id, _) = AudioSourceChain(listOf(deezer, youtube), hedgeAfterMs = 50)
            .resolveStreamUrl(track, hint = "videoId").getOrThrow()
        assertEquals("youtube", id)
        assertEquals(0, deezer.asked)
    }

    @Test
    fun `a losing provider that ignores cancellation does not hold the winner`() = runBlocking {
        // yt-dlp's shape: a blocking call that only notices cancellation when it returns.
        val blocking = object : AudioSourceProvider {
            override val capabilities = AudioSourceCapabilities(
                id = "blocking", displayNameRes = 0, canStream = true, canDownload = false,
                resolveTimeoutMs = 10_000
            )
            override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> {
                Thread.sleep(3_000)
                return Result.success(StreamInfo(uri = "late"))
            }
            override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit) =
                Result.failure<File>(UnsupportedOperationException())
        }
        val fast = Fake("fast", delayMs = 100, answer = "fast-url")
        val started = System.currentTimeMillis()
        val (id, _) = AudioSourceChain(listOf(blocking, fast), hedgeAfterMs = 100).resolveStreamUrl(track).getOrThrow()
        assertEquals("fast", id)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_500)
    }
}
