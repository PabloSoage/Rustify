package com.varuna.rustify.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditedListenTest {
    @Test
    fun anAwakeTickCountsWhatPlayed() {
        assertEquals(500L, creditedListenMs(500, 500))
        assertEquals(400L, creditedListenMs(400, 500))
    }

    @Test
    fun aSeekForwardCountsOnlyTheClock() {
        assertEquals(500L, creditedListenMs(60_000, 500))
    }

    @Test
    fun minutesPlayedWhileTheCpuSleptForOffloadCount() {
        assertEquals(180_000L, creditedListenMs(180_000, 181_200))
    }

    @Test
    fun aLongPauseCountsNothing() {
        assertEquals(0L, creditedListenMs(0, 600_000))
        assertEquals(0L, creditedListenMs(1_000, 600_000))
    }

    @Test
    fun aLongGapWithASeekInsideCountsNothing() {
        assertEquals(0L, creditedListenMs(200_000, 60_000))
    }

    @Test
    fun goingBackwardsCountsNothing() {
        assertEquals(0L, creditedListenMs(-30_000, 500))
    }
}
