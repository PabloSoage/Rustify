package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What the chain does with a `hint`.
 *
 * A hint is a YouTube video id — the alternative the user picked in the dialog, or is previewing.
 * Deezer and add-ons resolve from the track's own metadata: handed a video id they ignore it and
 * answer **successfully** with the recording the user was trying to replace. A successful wrong
 * answer ends the chain, so with either of them first nothing else is ever asked and every
 * candidate plays the same thing.
 *
 * Found while chasing the 3.7.2 preview bug and **not** its cause — that one was the track-keyed
 * stream cache overriding a correctly resolved URL, and it bit a yt-dlp-only setup where this
 * cannot. Kept and pinned because it is a real second way to reach the identical symptom, and
 * because it only shows up for someone who has Deezer or an add-on enabled, which is the kind of
 * bug that gets reported as "works for me".
 */
class AudioSourceChainTest {

    private val track = FullTrack(
        id = "4cOdK2wGLETKBW3PvgPWqT",
        name = "Jóga",
        externalUri = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
        durationMs = 303_000
    )

    /**
     * A backend in two flavours.
     *
     * @param honoursHint whether it declares it can play a specific video — yt-dlp and Invidious do,
     *   Deezer and add-ons do not.
     * @param ignoresHintAnyway what Deezer actually does to the hint it is given: nothing. Kept
     *   separate from [honoursHint] on purpose, so a test can build the exact provider that caused
     *   the bug — one that does not honour a hint but answers successfully regardless.
     */
    private class FakeSource(
        id: String,
        private val honoursHint: Boolean,
        private val ignoresHintAnyway: Boolean = !honoursHint,
        private val available: Boolean = true
    ) : AudioSourceProvider {
        var asked: Boolean = false
            private set

        override val capabilities = AudioSourceCapabilities(
            id = id,
            displayNameRes = 0,
            canStream = true,
            canDownload = true,
            honoursYoutubeHint = honoursHint
        )

        override suspend fun isAvailableFor(track: FullTrack): Boolean = available

        override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> {
            asked = true
            val video = if (ignoresHintAnyway || hint.isNullOrBlank()) "whatever-it-found" else hint
            return Result.success(StreamInfo(uri = "https://$video"))
        }

        override suspend fun downloadTo(
            track: FullTrack,
            dst: File,
            onProgress: (Int) -> Unit
        ): Result<File> = Result.failure(UnsupportedOperationException("not under test"))
    }

    /**
     * The bug, in one assertion.
     *
     * Deezer is first — which is the user's own drag-and-drop order, not an accident — and it can
     * stream this track. Before the fix it was asked, ignored the hint, succeeded, and the preview
     * played the original recording.
     */
    @Test
    fun `a backend that cannot play a chosen video is not asked for one`() = runBlocking {
        val deezer = FakeSource("deezer", honoursHint = false)
        val ytdlp = FakeSource("ytdlp", honoursHint = true)
        val chain = AudioSourceChain(listOf(deezer, ytdlp))

        val (providerId, info) = chain.resolveStreamUrl(track, hint = "dQw4w9WgXcQ").getOrThrow()

        assertEquals("ytdlp", providerId)
        assertEquals("https://dQw4w9WgXcQ", info.uri)
        assertTrue("Deezer cannot honour a video id and must not be asked", !deezer.asked)
    }

    /**
     * `lastGood` is the other half, and on its own it put the same wrong provider back in front.
     *
     * It remembers who served this *track* last time, which is the right answer to "play this track"
     * and the wrong answer to "play this video" — and it is written by every ordinary play, so it
     * names Deezer for exactly the tracks Deezer has.
     */
    @Test
    fun `the provider that served this track last time does not outrank the hint`() = runBlocking {
        val deezer = FakeSource("deezer", honoursHint = false)
        val ytdlp = FakeSource("ytdlp", honoursHint = true)
        val lastGood = ConcurrentHashMap<String, String>().apply { put(track.id!!, "deezer") }
        val chain = AudioSourceChain(listOf(ytdlp, deezer), lastGood = lastGood)

        val (providerId, _) = chain.resolveStreamUrl(track, hint = "dQw4w9WgXcQ").getOrThrow()

        assertEquals("ytdlp", providerId)
        assertTrue(!deezer.asked)
    }

    /**
     * With nothing left to ask, refuse.
     *
     * Falling back to a provider that ignores the hint is the failure that looks like success: the
     * music plays, so nothing appears wrong, and the user concludes the feature is broken rather
     * than that a backend is missing.
     */
    @Test
    fun `no backend that can play a chosen video is a failure, not a fallback`() = runBlocking {
        val deezer = FakeSource("deezer", honoursHint = false)
        val addon = FakeSource("addon:example", honoursHint = false)
        val chain = AudioSourceChain(listOf(deezer, addon))

        val result = chain.resolveStreamUrl(track, hint = "dQw4w9WgXcQ")

        assertTrue("a wrong recording must not be served as a fallback", result.isFailure)
        assertTrue(!deezer.asked)
        assertTrue(!addon.asked)
    }

    /** And with no hint, nothing changes: the remembered provider is still tried first. */
    @Test
    fun `without a hint the remembered provider is still preferred`() = runBlocking {
        val deezer = FakeSource("deezer", honoursHint = false)
        val ytdlp = FakeSource("ytdlp", honoursHint = true)
        val lastGood = ConcurrentHashMap<String, String>().apply { put(track.id!!, "deezer") }
        val chain = AudioSourceChain(listOf(ytdlp, deezer), lastGood = lastGood)

        val (providerId, _) = chain.resolveStreamUrl(track).getOrThrow()

        assertEquals("deezer", providerId)
        assertTrue("the preferred provider answered, so nothing below it is asked", !ytdlp.asked)
    }

    /** A hint-capable backend that is simply not usable right now still falls through to the next. */
    @Test
    fun `an unavailable hint-capable backend falls through to the next one`() = runBlocking {
        val invidious = FakeSource("invidious", honoursHint = true, available = false)
        val ytdlp = FakeSource("ytdlp", honoursHint = true)
        val chain = AudioSourceChain(listOf(invidious, ytdlp))

        val (providerId, info) = chain.resolveStreamUrl(track, hint = "dQw4w9WgXcQ").getOrThrow()

        assertEquals("ytdlp", providerId)
        assertEquals("https://dQw4w9WgXcQ", info.uri)
    }
}
