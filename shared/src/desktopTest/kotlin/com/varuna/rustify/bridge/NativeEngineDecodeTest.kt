package com.varuna.rustify.bridge

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeEngineDecodeTest {

    private fun isCoreAvailable(): Boolean = try {
        NativeLibrary.load()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @Test
    fun decodeToWavFailsOnMissingFile() = runBlocking {
        if (!isCoreAvailable()) return@runBlocking
        val ok = NativeEngine.decodeToWav("missing_input_file.mp3", "out.wav")
        assertFalse(ok)
    }

    @Test
    fun decodeToWavConvertsValidPcmWav() = runBlocking {
        if (!isCoreAvailable()) return@runBlocking
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val src = File(tmp, "test_input_${System.nanoTime()}.wav")
        val dst = File(tmp, "test_output_${System.nanoTime()}.wav")
        try {
            // Write a valid 16-bit 44.1kHz mono PCM WAV header and 4410 samples (0.1s silence)
            val sampleCount = 4410
            val dataLen = sampleCount * 2
            val header = ByteArray(44 + dataLen)
            val h = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt(36 + dataLen)
            h.put("WAVEfmt ".toByteArray())
            h.putInt(16) // Subchunk1Size
            h.putShort(1) // PCM
            h.putShort(1) // Channels
            h.putInt(44100) // SampleRate
            h.putInt(44100 * 2) // ByteRate
            h.putShort(2) // BlockAlign
            h.putShort(16) // BitsPerSample
            h.put("data".toByteArray())
            h.putInt(dataLen)
            src.writeBytes(header)

            val ok = NativeEngine.decodeToWav(src.absolutePath, dst.absolutePath)
            assertTrue(ok)
            assertTrue(dst.exists())
            assertTrue(dst.length() >= 44)
        } finally {
            src.delete()
            dst.delete()
        }
    }
}
