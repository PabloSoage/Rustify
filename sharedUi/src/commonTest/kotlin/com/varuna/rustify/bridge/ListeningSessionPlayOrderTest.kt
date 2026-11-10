package com.varuna.rustify.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A shuffled session resumes in the order it was playing. It used to come back in the list's
 * order: the right song, then the rest of the list from that point on.
 */
class ListeningSessionPlayOrderTest {

    private fun track(id: String) = FullTrack(
        id = id, name = id, externalUri = "", explicit = false, durationMs = 1,
        isrc = "", artists = emptyList(), album = null
    )

    private fun session(queue: List<String>, shuffle: Boolean) = ListeningSession(
        id = "liked:spotify", label = "Liked", subtitle = "", imageUrl = "",
        trackTitle = "c", trackArtist = "", queue = queue, index = 0, positionMs = 60_000, durationMs = 1,
        updatedAtMs = 1, shuffle = shuffle
    )

    private fun ids(tracks: List<FullTrack>?) = tracks?.map { it.id }

    private val list = listOf("a", "b", "c", "d", "e").map(::track)

    @Test
    fun withShuffleOffTheListsOrderIsKept() {
        assertNull(session(listOf("c", "a", "e"), shuffle = false).playOrder(list))
    }

    @Test
    fun aShuffledSessionComesBackInTheOrderItWasPlaying() {
        val order = session(listOf("d", "b", "e", "a", "c"), shuffle = true).playOrder(list)
        assertEquals(listOf("d", "b", "e", "a", "c"), ids(order))
    }

    @Test
    fun aTrackGoneSinceIsSkippedAndOneAddedSincePlaysAfterTheRest() {
        // "x" was unliked since; "c" and "e" were liked since and were never in the stored order.
        val order = session(listOf("d", "x", "b", "a"), shuffle = true).playOrder(list)
        assertEquals(listOf("d", "b", "a", "c", "e"), ids(order))
    }

    @Test
    fun aRepeatedSongKeepsBothCopies() {
        val withRepeat = listOf("a", "b", "a").map(::track)
        val order = session(listOf("a", "a", "b"), shuffle = true).playOrder(withRepeat)
        assertEquals(listOf("a", "a", "b"), ids(order))
    }

    @Test
    fun shuffleSurvivesTheRoundTripThroughTheCore() {
        val original = session(listOf("a", "b"), shuffle = true)
        assertEquals(original, ListeningSession.fromJson(original.toJson()))
    }
}
