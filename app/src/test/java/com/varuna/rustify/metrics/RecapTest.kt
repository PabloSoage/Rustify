package com.varuna.rustify.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecapTest {

    private fun hours(vararg pairs: Pair<Int, Int>): List<Int> =
        MutableList(24) { 0 }.also { l -> pairs.forEach { (h, n) -> l[h] = n } }

    @Test
    fun `a year with no plays has nothing to tell`() {
        assertNull(RecapBuilder.build(2026, PlayStats()))
    }

    @Test
    fun `the listener type is the part of the day with the most plays`() {
        assertEquals(ListenerType.NIGHT_OWL, RecapBuilder.listenerType(hours(23 to 5, 1 to 5, 12 to 3)))
        assertEquals(ListenerType.EARLY_BIRD, RecapBuilder.listenerType(hours(7 to 9, 20 to 2)))
        assertEquals(ListenerType.DAYTIME, RecapBuilder.listenerType(hours(12 to 4, 15 to 4, 19 to 5)))
        assertEquals(ListenerType.EVENING, RecapBuilder.listenerType(hours(19 to 6, 9 to 2)))
        assertEquals(ListenerType.NONE, RecapBuilder.listenerType(hours()))
    }

    @Test
    fun `the recap picks the top of each list and the busiest month and weekday`() {
        val stats = PlayStats(
            totalPlays = 120,
            totalMs = 6 * 3_600_000L,
            distinctArtists = 14,
            topArtists = listOf(TopRow(name = "Björk", plays = 40), TopRow(name = "Radiohead", plays = 30)),
            topTracks = listOf(TopRow(name = "Jóga", plays = 12)),
            playsByMonth = List(12) { if (it == 9) 50 else 5 },
            playsByWeekday = List(7) { if (it == 5) 30 else 10 },
            playsByHour = hours(23 to 50),
            longestStreakDays = 9,
        )
        val r = RecapBuilder.build(2026, stats)!!
        assertEquals(360L, r.minutes)
        assertEquals("Björk", r.topArtist?.name)
        assertEquals("Jóga", r.topTrack?.name)
        assertNull(r.topAlbum)
        assertEquals(9, r.peakMonth)
        assertEquals(5, r.peakWeekday)
        assertEquals(ListenerType.NIGHT_OWL, r.listener)
        assertEquals(9, r.longestStreakDays)
    }
}
