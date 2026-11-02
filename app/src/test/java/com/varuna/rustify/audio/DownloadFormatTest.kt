package com.varuna.rustify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFormatTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII).toList()
            is Int -> listOf(p.toByte())
            else -> error("bad part")
        }
    }.toByteArray()

    @Test
    fun `each container is told apart by its first bytes`() {
        assertEquals(DownloadFormat.FLAC, DownloadFormat.sniff(bytes("fLaC", 0, 0, 0, 34)))
        assertEquals(DownloadFormat.M4A, DownloadFormat.sniff(bytes(0, 0, 0, 32, "ftypM4A ")))
        assertEquals(DownloadFormat.WEBM, DownloadFormat.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F)))
        assertEquals(DownloadFormat.WAV, DownloadFormat.sniff(bytes("RIFF", 0, 0, 0, 0, "WAVEfmt ")))
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff(bytes("ID3", 4, 0)))
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
    }

    @Test
    fun `an Ogg file is Opus only when its first page says so`() {
        val opus = bytes("OggS", 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 1, 19, "OpusHead", 1, 2)
        val vorbis = bytes("OggS", 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 1, 30, 1, "vorbis")
        assertEquals(DownloadFormat.OPUS, DownloadFormat.sniff(opus))
        assertEquals(DownloadFormat.OGG, DownloadFormat.sniff(vorbis))
    }

    @Test
    fun `unknown or too short is null, never a guess`() {
        assertNull(DownloadFormat.sniff(bytes("<html>")))
        assertNull(DownloadFormat.sniff(bytes(0xFF)))
        assertNull(DownloadFormat.sniff(ByteArray(0)))
    }
}
