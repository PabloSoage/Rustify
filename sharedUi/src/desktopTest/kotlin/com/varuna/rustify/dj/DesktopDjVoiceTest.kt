package com.varuna.rustify.dj

import com.varuna.rustify.ui.DesktopAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDjVoiceTest {

    @Test
    fun desktopVoiceOffersExpectedEngines() {
        val platform = DesktopAppPlatform("ES")
        val voice = DesktopDjVoice(platform = platform)
        assertEquals(
            listOf("native", "edge", "pollinations", "gtranslate", "openai"),
            voice.engines
        )
    }

    @Test
    fun onlineTtsChunkingPreservesWords() {
        val text = "One two three four five six seven eight nine ten eleven twelve thirteen"
        val chunks = OnlineDjTts.chunkForTts(text, maxLen = 20)
        assertFalse(chunks.isEmpty())
        assertEquals(text, chunks.joinToString(" "))
        for (chunk in chunks) {
            assertTrue(chunk.length <= 20, "Chunk exceeded max length: '$chunk'")
        }
    }

    @Test
    fun onlineTtsLooksLikeAudioIdentifiesFormats() {
        // ID3 tag
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0)
        assertTrue(OnlineDjTts.looksLikeAudio(id3))

        // MP3 frame sync
        val mp3Sync = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        assertTrue(OnlineDjTts.looksLikeAudio(mp3Sync))

        // RIFF WAV
        val riff = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        assertTrue(OnlineDjTts.looksLikeAudio(riff))

        // Ogg
        val ogg = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
        assertTrue(OnlineDjTts.looksLikeAudio(ogg))

        // JSON error response
        val jsonErr = "{\"error\": \"failed\"}".encodeToByteArray()
        assertFalse(OnlineDjTts.looksLikeAudio(jsonErr))

        // HTML error response
        val htmlErr = "<html>Error</html>".encodeToByteArray()
        assertFalse(OnlineDjTts.looksLikeAudio(htmlErr))

        // Plain text error
        val textErr = "Error: rate limited".encodeToByteArray()
        assertFalse(OnlineDjTts.looksLikeAudio(textErr))
    }
}
