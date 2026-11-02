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
    fun videoAndTheLabBlockItToo() {
        assertEquals(AudioOffload.Blocker.VIDEO, AudioOffload.blocker(true, false, 0, labOpen = false, video = true))
        assertEquals(AudioOffload.Blocker.AUDIO_LAB, AudioOffload.blocker(true, false, 0, labOpen = true, video = true))
    }

    @Test
    fun skippingSilenceTurnsItOff() {
        assertEquals(AudioOffload.Blocker.SKIP_SILENCE, AudioOffload.blocker(true, false, 0, skipSilence = true))
    }

    @Test
    fun anAutoEqProfileTurnsItOff() {
        assertEquals(AudioOffload.Blocker.AUTOEQ, AudioOffload.blocker(true, false, 0, autoEq = true))
    }

    @Test
    fun bitPerfectUsbTurnsItOffBeforeTheEffects() {
        assertEquals(AudioOffload.Blocker.BIT_PERFECT, AudioOffload.blocker(true, true, 4, bitPerfect = true))
    }

    @Test
    fun theSwitchWinsOverEverything() {
        assertEquals(AudioOffload.Blocker.SWITCHED_OFF, AudioOffload.blocker(false, true, 4))
    }
}
