package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOffloadTest {
    @Test
    fun onByDefaultWithNothingThatNeedsTheCpu() {
        assertNull(AudioOffload.blocker(enabled = true, equalizerOn = false, fadeSeconds = 0))
    }

    @Test
    fun theEqualizerAndTheFadeEachTurnItOff() {
        assertEquals(AudioOffload.Blocker.EQUALIZER, AudioOffload.blocker(true, true, 0))
        assertEquals(AudioOffload.Blocker.FADE, AudioOffload.blocker(true, false, 4))
    }

    @Test
    fun theSwitchWinsOverEverything() {
        assertEquals(AudioOffload.Blocker.SWITCHED_OFF, AudioOffload.blocker(false, true, 4))
    }
}
