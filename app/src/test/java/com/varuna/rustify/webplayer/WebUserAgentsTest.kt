package com.varuna.rustify.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web player's user agents.
 *
 * The case that matters is the tablet: its system WebView agent has no `Mobile` token, and Spotify
 * answers such an agent with "unsupported browser" — measured on a Mi Pad 6S Pro, where the mobile
 * web player therefore never played anything at all.
 */
class WebUserAgentsTest {

    /** The Mi Pad 6S Pro's system WebView agent, as read from the device. */
    private val tabletWebView = "Mozilla/5.0 (Linux; Android 16; 24018RPACG Build/BP2A.250605.031.A3; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Safari/537.36"

    private val phoneWebView = "Mozilla/5.0 (Linux; Android 10; POCOPHONE F1 Build/QKQ1.190828.002; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36"

    @Test
    fun `a tablet presents itself as a phone, because Spotify refuses the tablet agent`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; 24018RPACG Build/BP2A.250605.031.A3) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.36 Mobile Safari/537.36",
            WebUserAgents.mobile(tabletWebView)
        )
    }

    @Test
    fun `a phone agent keeps its single Mobile token`() {
        val ua = WebUserAgents.mobile(phoneWebView)
        assertEquals(1, Regex("Mobile").findAll(ua).count())
        assertTrue(ua.endsWith("Mobile Safari/537.36"))
    }

    @Test
    fun `the embedded-view markers never reach the page`() {
        for (ua in listOf(WebUserAgents.mobile(tabletWebView), WebUserAgents.mobile(phoneWebView))) {
            assertFalse(ua, ua.contains("; wv"))
            assertFalse(ua, ua.contains("Version/4.0"))
        }
    }

    @Test
    fun `the desktop agent keeps the device's Chrome major version`() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
            WebUserAgents.desktop(tabletWebView)
        )
    }
}
