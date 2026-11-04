package com.varuna.rustify.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Links as the system hands them over, whole. Here because this is where the core is linked into the
 * tests. iOS once passed `rustify://track/ID` on as it came, and the app asked Spotify for the track
 * `spotify:track:rustify://track/ID`: "API error 404: Track not found", for a track that exists.
 */
class LinkTokenTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"

    private fun token(link: String) = LinkTarget.tokenOf(link, emptyList())

    @Test
    fun theSchemeBecomesTheTokenTheAppRoutesOn() {
        assertEquals("track:$id", token("rustify://track/$id"))
        assertEquals("album:$id", token("rustify://album/$id"))
        assertEquals("playlist:$id", token("rustify://playlist/$id"))
        assertEquals("artist:$id", token("rustify://artist/$id"))
    }

    @Test
    fun theSchemeReadsAsAUriDoes() {
        assertEquals("track:$id", token("RUSTIFY://TRACK/$id"))
        assertEquals("track:$id", token("rustify://track/$id/"))
        assertEquals("track:$id", token("rustify://track/$id?si=abc#top"))
    }

    @Test
    fun whatTheSchemeDoesNotNameIsNotOpened() {
        assertNull(token("rustify://track/"))
        assertNull(token("rustify://track"))
        assertNull(token("rustify://nothing/$id"))
        assertNull(token("rustify://track/not an id"))
    }

    @Test
    fun theOtherLinksReadAsOnThePhone() {
        assertEquals("track:$id", token("https://open.spotify.com/track/$id"))
        assertEquals("track:$id", token("spotify:track:$id"))
        assertNull(token("https://example.com/track/$id"))
        assertNull(token(""))
        assertNull(token("x".repeat(3000)))
    }
}
