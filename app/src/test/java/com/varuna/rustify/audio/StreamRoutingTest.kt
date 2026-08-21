package com.varuna.rustify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StreamRouting.forget` deletes a cache entry by filename **without crossing JNI**, so its idea of
 * what a key becomes on disk has to be the Rust one, byte for byte. A name that disagreed would
 * delete nothing while looking like it had worked.
 *
 * These are the cases `env::storage::sanitise_key` pins on the other side.
 *
 * (The Deezer key-derivation tests that used to live here went with `DeezerCrypto.kt` in 3.3.
 * There is one implementation of that scheme now, in Rust, and it keeps its own vector.)
 */
class StreamRoutingTest {

    @Test
    fun `cache keys survive the trip through the filesystem unchanged in meaning`() {
        assertEquals("k.._.._etc_passwd", StreamRouting.sanitise("../../etc/passwd"))
        // `\\c` is one backslash followed by a `c`. Written `\c` this file did not compile at all,
        // which is how these tests came to have never run: nothing asks Gradle to build the test
        // source unless you ask it for the test task, and `assembleRelease` does not.
        assertEquals("a_b_c", StreamRouting.sanitise("a/b\\c"))
        assertEquals("k", StreamRouting.sanitise(""))
        assertEquals("k.", StreamRouting.sanitise("."))
        assertEquals("k..", StreamRouting.sanitise(".."))
        // And the shapes real cache keys have: a generation prefix plus a track id.
        assertEquals("g1-ytm_dQw4w9WgXcQ", StreamRouting.sanitise("g1-ytm:dQw4w9WgXcQ"))
        assertEquals("g7-4cOdK2wGLETKBW3PvgPWqT", StreamRouting.sanitise("g7-4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun `two different track ids never share a cache entry`() {
        // The failure this prevents is one track playing another track's audio, which looks like a
        // matching bug and is not one.
        val keys = listOf(
            "g1-ytm:dQw4w9WgXcQ",
            "g1-ytm:dQw4w9WgXcR",
            "g1-4cOdK2wGLETKBW3PvgPWqT",
            "g1-spotify:local:A:B:Title:180"
        ).map { StreamRouting.sanitise(it) }
        assertEquals("sanitising must not collapse distinct ids", keys.size, keys.toSet().size)
    }

    @Test
    fun `a generation bump moves every key`() {
        // The point of the generation: changing backend or quality has to make the whole cache
        // unreachable at once, not track by track.
        val before = StreamRouting.sanitise("g1-4cOdK2wGLETKBW3PvgPWqT")
        val after = StreamRouting.sanitise("g2-4cOdK2wGLETKBW3PvgPWqT")
        assertTrue("a bumped generation must not resolve to the old file", before != after)
    }
}
