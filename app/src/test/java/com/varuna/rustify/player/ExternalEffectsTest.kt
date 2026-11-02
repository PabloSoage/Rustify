package com.varuna.rustify.player

import android.media.audiofx.AudioEffect
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalEffectsTest {
    private val open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
    private val close = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION

    @Test
    fun aNewSessionClosesTheOldOneFirst() {
        assertEquals(listOf(open to 41), ExternalEffects.intentsFor(previous = -4, session = 41))
        assertEquals(listOf(close to 41, open to 57), ExternalEffects.intentsFor(previous = 41, session = 57))
    }

    @Test
    fun theSameSessionIsNotAnnouncedTwiceAndReleaseOnlyCloses() {
        assertEquals(emptyList<Pair<String, Int>>(), ExternalEffects.intentsFor(previous = 41, session = 41))
        assertEquals(listOf(close to 41), ExternalEffects.intentsFor(previous = 41, session = AudioEffect.ERROR_BAD_VALUE))
    }
}
