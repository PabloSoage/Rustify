package com.varuna.rustify.metrics

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimpleAlbum
import com.varuna.rustify.bridge.SimpleArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzTest {
    /** The same cases as `scrobble::tests` in the core: the two rules must not drift. */
    @Test
    fun `a listen is four minutes or half and never a short track`() {
        assertTrue(ListenBrainz.qualifies(100_000, 200_000))
        assertFalse(ListenBrainz.qualifies(99_000, 200_000))
        assertTrue(ListenBrainz.qualifies(240_000, 3_600_000))
        assertFalse(ListenBrainz.qualifies(30_000, 30_000))
        assertTrue(ListenBrainz.qualifies(240_000, 0))
    }

    @Test
    fun `a listen carries the start in seconds and no placeholder album`() {
        val track = FullTrack(
            id = "local:x", name = "Usseewa", durationMs = 200_000, isrc = "JPU902002345",
            artists = listOf(SimpleArtist("a", "Ado", "", emptyList())),
            album = SimpleAlbum("local_album:Unknown Album", "Unknown Album", "", null, null, emptyList(), emptyList(), null)
        )
        val l = ListenBrainz.listenOf(track, 1_700_000_123_456)
        assertEquals(1_700_000_123L, l.listenedAt)
        assertEquals("", l.release)
        assertEquals("Ado", l.artist)
    }
}
