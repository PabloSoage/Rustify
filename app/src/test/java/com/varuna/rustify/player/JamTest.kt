package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Test

class JamTest {
    @Test
    fun aPlayingHostIsAheadByTheTimeInFlight() {
        assertEquals(62_500L, Jam.expectedPosition(60_000, atMs = 1_000, playing = true, nowMs = 3_500))
    }

    @Test
    fun pausedOrWithClocksThatDisagreeThePublishedPositionStands() {
        assertEquals(60_000L, Jam.expectedPosition(60_000, atMs = 1_000, playing = false, nowMs = 3_500))
        assertEquals(60_000L, Jam.expectedPosition(60_000, atMs = 5_000, playing = true, nowMs = 3_500))
        assertEquals(60_000L, Jam.expectedPosition(60_000, atMs = 0, playing = true, nowMs = 600_000))
    }
}
