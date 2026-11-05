package com.varuna.rustify.player

import com.varuna.rustify.player.PlaybackRecovery.Decision
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PlaybackRecoveryTest {
    @Test
    fun `no network waits on the same song however many times it fails`() {
        for (retries in 0..10) {
            assertEquals(Decision.WaitForNetwork, PlaybackRecovery.onFailure(networkUp = false, networkError = true, retries = retries))
        }
    }

    @Test
    fun `a connection error with the network up retries but never skips`() {
        for (retries in 0..10) {
            val d = PlaybackRecovery.onFailure(networkUp = true, networkError = true, retries = retries)
            assertTrue(d is Decision.Retry, "retries=$retries gave $d")
            assertTrue((d as Decision.Retry).delayMs <= 30_000L)
        }
    }

    @Test
    fun `a broken song is skipped after two retries`() {
        assertTrue(PlaybackRecovery.onFailure(true, false, 0) is Decision.Retry)
        assertTrue(PlaybackRecovery.onFailure(true, false, 1) is Decision.Retry)
        assertEquals(Decision.Skip, PlaybackRecovery.onFailure(true, false, 2))
    }

    @Test
    fun `an expired url is retried at once`() {
        assertEquals(Decision.Retry(100L), PlaybackRecovery.onFailure(true, false, 0, expiredUrl = true))
    }

    @Test
    fun `stopping well before the declared end is a cut`() {
        assertTrue(PlaybackRecovery.isPrematureEnd(150_000, 200_000, 0))
        assertFalse(PlaybackRecovery.isPrematureEnd(197_000, 200_000, 0))
    }

    @Test
    fun `the third track failing in a row stops playback instead of skipping on`() {
        assertFalse(PlaybackRecovery.shouldStopSkipping(1))
        assertFalse(PlaybackRecovery.shouldStopSkipping(2))
        assertTrue(PlaybackRecovery.shouldStopSkipping(3))
    }

    @Test
    fun `without a declared duration or after two resumes the end is believed`() {
        assertFalse(PlaybackRecovery.isPrematureEnd(150_000, 0, 0))
        assertFalse(PlaybackRecovery.isPrematureEnd(150_000, 200_000, 2))
    }
}
