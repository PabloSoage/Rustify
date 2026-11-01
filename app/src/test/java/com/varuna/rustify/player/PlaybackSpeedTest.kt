package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {
    @Test
    fun `a slider's float noise still reads as normal speed`() {
        assertEquals(1f, PlaybackSpeed.snap(1.0000001f))
        assertEquals(1.25f, PlaybackSpeed.snap(1.2499f))
        assertTrue(PlaybackSpeed.isDefault(0.99999f, 0))
    }

    @Test
    fun `speed stays inside its range`() {
        assertEquals(0.5f, PlaybackSpeed.snap(0.1f))
        assertEquals(2f, PlaybackSpeed.snap(3f))
    }

    @Test
    fun `twelve semitones is an octave`() {
        assertEquals(2f, PlaybackSpeed.pitchFactor(12), 1e-6f)
        assertEquals(1f, PlaybackSpeed.pitchFactor(0), 1e-6f)
        assertEquals(0.8909f, PlaybackSpeed.pitchFactor(-2), 1e-4f)
    }

    @Test
    fun `a change of pitch alone takes offload off`() {
        assertFalse(PlaybackSpeed.isDefault(1f, -1))
        assertEquals(
            AudioOffload.Blocker.SPEED,
            AudioOffload.blocker(enabled = true, equalizerOn = false, fadeSeconds = 0, speedChanged = true)
        )
    }
}
