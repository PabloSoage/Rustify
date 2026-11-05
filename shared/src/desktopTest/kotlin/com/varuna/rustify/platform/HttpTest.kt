package com.varuna.rustify.platform

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The requests that moved from HttpURLConnection to Ktor are meant to leave as they did: the query
 * and form values encoded as URLEncoder encoded them, and the User-Agent the JDK sent by itself.
 */
class HttpTest {

    @Test
    fun `form encoding is URLEncoder's, byte for byte`() {
        val samples = listOf(
            "", "plain", "Daft Punk", "AC/DC", "Björk — Jóga", "a+b=c&d", "100% *real* ~tilde~",
            "日本語の歌", "emoji 🎶", "tab\tand\nnewline", "quote\"apostrophe'", "-._*",
        )
        for (s in samples) {
            assertEquals(URLEncoder.encode(s, "UTF-8"), formUrlEncode(s), "for <$s>")
        }
    }

    @Test
    fun `the user agent is the one HttpURLConnection sends without one set`() {
        val version = System.getProperty("java.version")
        val agent = System.getProperty("http.agent")
        assertEquals(if (agent == null) "Java/$version" else "$agent Java/$version", systemHttpUserAgent())
    }
}
