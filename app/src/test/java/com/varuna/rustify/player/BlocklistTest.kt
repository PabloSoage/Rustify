package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {
    private val lists = Blocklist.Lists(
        tracks = listOf(Blocklist.Entry("spotify:track:bad", "Bad Song")),
        artists = listOf(Blocklist.Entry("artist-x", "X")),
    )

    @Test
    fun aSongIsBlockedByItselfOrByAnyOfItsArtists() {
        assertTrue(lists.blocks("spotify:track:bad", emptyList()))
        assertTrue("featuring a blocked artist", lists.blocks("spotify:track:ok", listOf("artist-y", "artist-x")))
        assertFalse(lists.blocks("spotify:track:ok", listOf("artist-y")))
        assertFalse("no id is never blocked", lists.blocks(null, emptyList()))
    }

    @Test
    fun theQueueStepsOverBlockedTracksInTheDirectionItWasGoing() {
        val blocked = setOf(2, 3)
        assertEquals(4, Blocklist.firstAllowed(6, 2, +1) { it in blocked })
        assertEquals(1, Blocklist.firstAllowed(6, 3, -1) { it in blocked })
        assertEquals(2, Blocklist.firstAllowed(6, 2, +1) { false })
    }

    @Test
    fun allBlockedFromThereOnMeansNothingPlays() {
        assertNull(Blocklist.firstAllowed(4, 2, +1) { it >= 2 })
        assertNull(Blocklist.firstAllowed(4, 1, -1) { it <= 1 })
    }
}
