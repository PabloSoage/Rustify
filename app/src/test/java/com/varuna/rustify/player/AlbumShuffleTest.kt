package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlbumShuffleTest {
    // "album/track"
    private val album: (String) -> String? = { it.substringBefore('/').takeIf { a -> a != "-" } }

    @Test
    fun `albums stay whole and in their own order`() {
        val tracks = listOf("A/1", "B/1", "A/2", "C/1", "B/2", "A/3")
        for (seed in 0 until 20) {
            val out = AlbumShuffle.arrange(tracks, album, random = Random(seed))
            assertEquals(tracks.sorted(), out.sorted())
            // Each album appears as one contiguous run, in its original order.
            val runs = out.map { it.substringBefore('/') }.fold(mutableListOf<String>()) { acc, a ->
                if (acc.lastOrNull() != a) acc.add(a); acc
            }
            assertEquals(runs.distinct(), runs)
            assertEquals(listOf("A/1", "A/2", "A/3"), out.filter { it.startsWith("A/") })
        }
    }

    @Test
    fun `the current album goes first`() {
        val out = AlbumShuffle.arrange(listOf("A/1", "B/1", "B/2", "C/1"), album, firstAlbum = "B", random = Random(3))
        assertEquals(listOf("B/1", "B/2"), out.take(2))
    }

    @Test
    fun `tracks without an album are not glued together`() {
        val tracks = listOf("-/x", "-/y", "-/z", "A/1")
        val seen = (0 until 30).map { AlbumShuffle.arrange(tracks, album, random = Random(it)) }.toSet()
        assertTrue("singles should move independently", seen.any { it.indexOf("-/x") + 1 != it.indexOf("-/y") })
    }
}
