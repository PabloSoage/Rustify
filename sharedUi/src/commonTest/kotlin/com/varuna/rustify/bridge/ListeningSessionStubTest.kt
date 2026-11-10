package com.varuna.rustify.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The stand-in a resume plays first carries the **track's** artwork. It used to carry the context's,
 * so resuming a playlist showed the playlist's cover in the miniplayer as if it were the song's.
 */
class ListeningSessionStubTest {

    private fun session(id: String, contextImage: String, trackImage: String) = ListeningSession(
        id = id,
        label = "Context",
        subtitle = "",
        imageUrl = contextImage,
        trackTitle = "Hells Bells",
        trackArtist = "AC/DC",
        trackImageUrl = trackImage,
        queue = listOf("a", "b"),
        index = 1,
        positionMs = 60_000,
        durationMs = 312_000,
        updatedAtMs = 1
    )

    private fun FullTrack.cover() = album?.images?.single()?.url

    @Test
    fun aPlaylistStandInShowsTheTrackCoverNotThePlaylistCover() {
        val stub = session("playlist:p1", "https://img/playlist", "https://img/track").stub()!!
        assertEquals("https://img/track", stub.cover())
    }

    @Test
    fun anOldPlaylistSessionWithoutTrackArtworkShowsNoneRatherThanThePlaylistCover() {
        val stub = session("playlist:p1", "https://img/playlist", "").stub()!!
        assertNull(stub.album)
    }

    @Test
    fun anOldAlbumSessionStillUsesTheAlbumCoverWhichIsTheTracks() {
        val stub = session("album:x", "https://img/album", "").stub()!!
        assertEquals("https://img/album", stub.cover())
    }

    @Test
    fun theTrackArtworkSurvivesTheRoundTripThroughTheCore() {
        val original = session("playlist:p1", "https://img/playlist", "https://img/track")
        assertEquals(original, ListeningSession.fromJson(original.toJson()))
    }
}
