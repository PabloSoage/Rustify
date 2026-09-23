package com.varuna.rustify.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where an Invidious instance's Companion lives.
 *
 * The API does not say. The instance says it in its own `Content-Security-Policy`, because the page it
 * serves has to be allowed to play media from there — and since 2025 that is the only public route to
 * audio through Invidious at all (see `InvidiousInstances.resolveAudio`).
 */
class InvidiousCompanionTest {

    /** Verbatim from `invidious.f5.si`, September 2026 — the one public instance that served audio. */
    private val measured = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; font-src 'self' data:; connect-src 'self' https://jp1-cmp.invidious.f5.si; " +
        "manifest-src 'self'; media-src https://*.googlevideo.com:443 https://*.youtube.com:443 'self' blob: " +
        "https://jp1-cmp.invidious.f5.si; child-src 'self' blob:; frame-src 'self'; frame-ancestors 'none'"

    @Test
    fun `the companion is read out of the policy, then the instance itself`() {
        assertEquals(
            listOf("https://jp1-cmp.invidious.f5.si", "https://invidious.f5.si"),
            InvidiousInstances.companionsFor("https://invidious.f5.si", measured)
        )
    }

    @Test
    fun `YouTube's own hosts and wildcards are not companions`() {
        val csp = "media-src https://*.googlevideo.com:443 https://www.youtube.com https://rr1.googlevideo.com"
        assertEquals(
            listOf("https://inv.example"),
            InvidiousInstances.companionsFor("https://inv.example", csp)
        )
    }

    @Test
    fun `no policy still leaves the instance to try`() {
        // A self-hosted setup that mounts Companion under /companion on the same host sends no CSP
        // naming another origin, and must still be tried.
        assertEquals(listOf("https://my.own"), InvidiousInstances.companionsFor("https://my.own/", null))
    }

    @Test
    fun `an explicit port 443 and a trailing slash name the same origin once`() {
        val csp = "connect-src https://cmp.example:443/; media-src https://cmp.example"
        assertEquals(
            listOf("https://cmp.example", "https://inv.example"),
            InvidiousInstances.companionsFor("https://inv.example", csp)
        )
    }
}
