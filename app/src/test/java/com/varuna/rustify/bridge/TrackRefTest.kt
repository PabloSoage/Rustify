package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the identity codec, checked against exactly the same cases as
 * `core_engine/src/types/id.rs`. If the two ever disagree, one of the two test files goes red —
 * which is the only reason it is safe to have the same format implemented twice.
 */
class TrackRefTest {

    /** Every shape the app actually stores, and the exact string each must produce. */
    private val everyShape = listOf(
        "4uLU6hMCjMI75M1A2tKUQC",
        "ytm:dQw4w9WgXcQ",
        "local:content://media/external/audio/media/1234",
        "local:file:///storage/emulated/0/Music/a.mp3",
        "spotify:local:Nick+Cave:The+Boatman%27s+Call:Into+My+Arms:249"
    )

    @Test
    fun `every shape round trips`() {
        for (raw in everyShape) {
            assertEquals("round trip failed for $raw", raw, TrackRef.parse(raw)?.raw)
        }
    }

    @Test
    fun `a local uri is never split on colons`() {
        val ref = TrackRef.parse("local:content://media/external/audio/media/1234")
        assertEquals("content://media/external/audio/media/1234", ref?.localUri)
        assertNull(ref?.spotifyId)
        assertNull(ref?.youtubeVideoId)
        assertTrue(ref?.isOnDevice == true)
    }

    @Test
    fun `v2_11_3 is no longer expressible`() {
        // The bug: this URI was split on ':' and "249" became the track id, so every local track of
        // the same length collided and duplicate LazyColumn keys crashed the screen.
        val ref = TrackRef.parse("spotify:local:Nick+Cave:The+Boatman%27s+Call:Into+My+Arms:249")
        assertTrue(ref is TrackRef.SpotifyLocal)
        assertNull(ref?.spotifyId)
        assertEquals("spotify-local", ref?.kind)

        // Two different local tracks of the same duration stay different.
        assertTrue(ref != TrackRef.parse("spotify:local:Other:Album:Song:249"))
    }

    @Test
    fun `v2_11_8 is no longer expressible`() {
        // The bug: a ytm: id reached the Spotify resolver. spotifyId is the only way to get one.
        val ref = TrackRef.parse("ytm:dQw4w9WgXcQ")
        assertNull(ref?.spotifyId)
        assertEquals("dQw4w9WgXcQ", ref?.youtubeVideoId)
    }

    @Test
    fun `a bare id is a spotify track`() {
        val ref = TrackRef.parse("4uLU6hMCjMI75M1A2tKUQC")
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", ref?.spotifyId)
        assertNull(ref?.youtubeVideoId)
        assertFalse(ref?.isOnDevice == true)
    }

    @Test
    fun `blank and truncated ids are rejected`() {
        assertNull(TrackRef.parse(null))
        assertNull(TrackRef.parse(""))
        assertNull(TrackRef.parse("   "))
        assertNull(TrackRef.parse("ytm:"))
        assertNull(TrackRef.parse("local:"))
    }

    @Test
    fun `a prefix followed only by whitespace has no payload`() {
        // The predicates answer without building a TrackRef, so they must agree with parse on the
        // awkward inputs too — a length check would have called "ytm: " a valid video id.
        for (raw in listOf("ytm: ", "local:   ", "ytm:\t")) {
            assertNull("parse accepted $raw", TrackRef.parse(raw))
            assertFalse("isYtm accepted $raw", TrackRef.isYtm(raw))
            assertFalse("isLocal accepted $raw", TrackRef.isLocal(raw))
            assertNull(TrackRef.youtubeVideoIdOf(raw))
            assertNull(TrackRef.localUriOf(raw))
        }
    }

    @Test
    fun `the predicates agree with parse on every shape`() {
        // parse() and the allocation-free predicates are two code paths over one prefix table.
        // This is what stops them drifting.
        for (raw in everyShape + listOf("localpl:x", "ytm:", "local:", "", "   ")) {
            val ref = TrackRef.parse(raw)
            assertEquals("isSpotify disagrees for '$raw'", ref is TrackRef.Spotify, TrackRef.isSpotify(raw))
            assertEquals("isYtm disagrees for '$raw'", ref is TrackRef.Ytm, TrackRef.isYtm(raw))
            assertEquals("isLocal disagrees for '$raw'", ref is TrackRef.Local, TrackRef.isLocal(raw))
            assertEquals(
                "isSpotifyLocal disagrees for '$raw'",
                ref is TrackRef.SpotifyLocal,
                TrackRef.isSpotifyLocal(raw)
            )
            assertEquals(ref?.youtubeVideoId, TrackRef.youtubeVideoIdOf(raw))
            assertEquals(ref?.localUri, TrackRef.localUriOf(raw))
        }
    }

    @Test
    fun `localpl is not a local track`() {
        // "localpl:" shares five characters with "local:" and must not be mistaken for it.
        val ref = TrackRef.parse("localpl:0f1e2d3c")
        assertFalse(ref?.isOnDevice == true)
        assertNull(ref?.localUri)
    }

    @Test
    fun `playlist shapes round trip`() {
        for (raw in listOf("37i9dQZF1DXcBWIGoYBM5M", "localpl:0f1e-2d3c", "ytmpl:9a8b")) {
            assertEquals(raw, PlaylistRef.parse(raw)?.raw)
        }
        assertTrue(PlaylistRef.parse("localpl:0f1e") is PlaylistRef.Local)
        assertTrue(PlaylistRef.parse("ytmpl:9a8b") is PlaylistRef.YtmLocal)
        assertFalse(PlaylistRef.parse("37i9dQZF1DXcBWIGoYBM5M")?.isDeviceOnly == true)
    }

    @Test
    fun `a remote ytm playlist still prints bare`() {
        // It cannot be parsed into this variant, but it must survive being printed.
        assertEquals("VLPL1234", PlaylistRef.Ytm("VLPL1234").raw)
    }
}
