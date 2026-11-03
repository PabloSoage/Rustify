package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSanityTest {

    @Test
    fun `a position that follows the clock is believed`() {
        assertNull(PositionSanity.check(previousMs = 10_000, elapsedMs = 500, positionMs = 10_480, durationMs = 200_000))
        // Ticks minutes apart while the CPU sleeps for offload are still consistent.
        assertNull(PositionSanity.check(previousMs = 10_000, elapsedMs = 180_000, positionMs = 190_100, durationMs = 200_000))
    }

    @Test
    fun `looping back to the start is a desync and the expected place is answered`() {
        assertEquals(20_500L, PositionSanity.check(previousMs = 20_000, elapsedMs = 500, positionMs = 300, durationMs = 200_000))
    }

    @Test
    fun `running past the end of the song is a desync`() {
        // 1:43 of a 1:29 song, as reported.
        assertEquals(89_000L, PositionSanity.check(previousMs = 88_500, elapsedMs = 500, positionMs = 103_000, durationMs = 89_000))
    }

    @Test
    fun `running ahead of the clock is a desync`() {
        assertEquals(30_500L, PositionSanity.check(previousMs = 30_000, elapsedMs = 500, positionMs = 45_000, durationMs = 200_000))
    }

    @Test
    fun `the playback speed is part of the clock`() {
        assertNull(PositionSanity.check(previousMs = 0, elapsedMs = 10_000, positionMs = 15_000, durationMs = 200_000, speed = 1.5f))
    }

    @Test
    fun `small jitter is not a desync`() {
        assertNull(PositionSanity.check(previousMs = 50_000, elapsedMs = 500, positionMs = 49_000, durationMs = 200_000))
    }

    @Test
    fun `a long pause or a lost focus rebuilds the output only with offload`() {
        assertTrue(PositionSanity.shouldResyncOnResume(offloaded = true, pausedForMs = 300_000, focusLostWhilePaused = false))
        assertTrue(PositionSanity.shouldResyncOnResume(offloaded = true, pausedForMs = 5_000, focusLostWhilePaused = true))
        assertFalse(PositionSanity.shouldResyncOnResume(offloaded = true, pausedForMs = 5_000, focusLostWhilePaused = false))
        assertFalse(PositionSanity.shouldResyncOnResume(offloaded = false, pausedForMs = 300_000, focusLostWhilePaused = true))
    }
}
