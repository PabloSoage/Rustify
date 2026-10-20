package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionFadeTest {

    @Test
    fun `off means full volume everywhere`() {
        assertEquals(1f, TransitionFade.gain(0, 200_000, 0), 0f)
        assertEquals(1f, TransitionFade.gain(199_999, 200_000, 0), 0f)
    }

    @Test
    fun `fades in at the start and out at the end`() {
        assertEquals(0f, TransitionFade.gain(0, 200_000, 6_000), 0.001f)
        assertEquals(0.5f, TransitionFade.gain(3_000, 200_000, 6_000), 0.001f)
        assertEquals(1f, TransitionFade.gain(100_000, 200_000, 6_000), 0.001f)
        assertEquals(0.5f, TransitionFade.gain(197_000, 200_000, 6_000), 0.001f)
        assertEquals(0f, TransitionFade.gain(200_000, 200_000, 6_000), 0.001f)
    }

    @Test
    fun `a short song is never silent in the middle`() {
        // 9 s long with 6 s fades: each fade shrinks to 3 s, the middle plays at full volume.
        assertEquals(1f, TransitionFade.gain(4_500, 9_000, 6_000), 0.001f)
    }

    @Test
    fun `unknown duration fades in only`() {
        assertEquals(1f, TransitionFade.gain(500_000, 0, 6_000), 0.001f)
        assertEquals(0.5f, TransitionFade.gain(3_000, 0, 6_000), 0.001f)
    }
}
