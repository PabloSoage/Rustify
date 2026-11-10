package com.varuna.rustify.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The stored form of a context id is read back as what was written, for every kind that exists. */
class ContextRefTest {

    @Test
    fun everyKnownKindRoundTrips() {
        val refs = listOf(
            ContextRef.Album("4aawyAB9vmqN3uQ7FjRGTy"),
            ContextRef.Playlist("37i9dQZF1DXcBWIGoYBM5M"),
            ContextRef.Radio("3n3Ppam7vgaVa1iaRUc9Lp"),
            ContextRef.Liked,
        )
        for (ref in refs) assertEquals(ref, ContextRef.parse(ref.wire), ref.wire)
    }

    @Test
    fun theWireFormIsTheOneAlreadyStored() {
        // Sessions saved before the type existed, and those from an older version over Drive.
        assertEquals("album:a", ContextRef.Album("a").wire)
        assertEquals("playlist:p", ContextRef.Playlist("p").wire)
        assertEquals("radio:t", ContextRef.Radio("t").wire)
        assertEquals(PlaybackContext.LIKED_SONGS_ID, ContextRef.Liked.wire)
    }

    @Test
    fun aValueKeepsEverythingAfterTheFirstColon() {
        assertEquals(ContextRef.Playlist("a:b"), ContextRef.parse("playlist:a:b"))
    }

    @Test
    fun anUnknownOrMalformedIdIsKeptAsItIs() {
        for (id in listOf("podcast:x", "album:", ":x", "album", "")) {
            val ref = ContextRef.parse(id)
            assertIs<ContextRef.Unknown>(ref, id)
            assertEquals(id, ref.wire, id)
        }
    }
}
