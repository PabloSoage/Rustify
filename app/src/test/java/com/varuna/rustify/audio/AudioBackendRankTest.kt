package com.varuna.rustify.audio

import com.varuna.rustify.audio.AudioBackendSettings.BackendEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBackendRankTest {

    @Test
    fun `the default order is fast first, stable second, unreliable last`() {
        val stored = listOf(
            BackendEntry("deezer", true),
            BackendEntry("ytdlp", true),
            BackendEntry("invidious", false),
            BackendEntry("addon:pipe", true),
            BackendEntry("innertube", true)
        )
        assertEquals(
            listOf("innertube", "ytdlp", "addon:pipe", "invidious", "deezer"),
            AudioBackendSettings.ranked(stored).map { it.id }
        )
    }

    @Test
    fun `reordering keeps each one on or off`() {
        val stored = listOf(BackendEntry("deezer", true), BackendEntry("innertube", false))
        assertEquals(
            listOf(BackendEntry("innertube", false), BackendEntry("deezer", true)),
            AudioBackendSettings.ranked(stored)
        )
    }

    @Test
    fun `two add-ons keep the order the user gave them`() {
        val stored = listOf(BackendEntry("addon:b", true), BackendEntry("addon:a", true))
        assertEquals(listOf("addon:b", "addon:a"), AudioBackendSettings.ranked(stored).map { it.id })
    }
}
