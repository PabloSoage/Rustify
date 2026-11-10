package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOffloadTest {
    @Test
    fun offUnlessTheUserTurnsItOn() {
        // 5.7: it cut songs short on the F8 Ultra with Android 17 (docs serie-4.x/31 §2). A phone
        // that never touched the switch must get SWITCHED_OFF, not offload.
        assertEquals(false, AudioOffload.DEFAULT_ENABLED)
        assertEquals(
            AudioOffload.Blocker.SWITCHED_OFF,
            AudioOffload.blocker(enabled = AudioOffload.DEFAULT_ENABLED, equalizerOn = false, fadeSeconds = 0)
        )
    }

    @Test
    fun usedWhenTurnedOnWithNothingThatNeedsTheCpu() {
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
    fun aPositionThatDriftedUnderOffloadTurnsItOffForTheSession() {
        assertEquals(AudioOffload.Blocker.POSITION_DRIFT, AudioOffload.blocker(true, true, 4, drifted = true))
        assertEquals(null, AudioOffload.blocker(true, false, 0, drifted = false))
    }

    @Test
    fun theSwitchWinsOverEverything() {
        assertEquals(AudioOffload.Blocker.SWITCHED_OFF, AudioOffload.blocker(false, true, 4))
    }
}
