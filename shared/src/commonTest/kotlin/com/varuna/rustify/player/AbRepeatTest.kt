package com.varuna.rustify.player

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class AbRepeatTest {
    @Test
    fun `three presses mark A - mark B - and clear`() {
        val a = AbRepeat.NONE.press("t", 10_000)
        assertEquals(10_000L, a.a); assertFalse(a.isLooping)
        val ab = a.press("t", 25_000)
        assertTrue(ab.isLooping)
        assertEquals(AbRepeat.NONE, ab.press("t", 30_000))
    }

    @Test
    fun `passing B jumps back to A - only on the same track`() {
        val ab = AbRepeat("t", 10_000, 25_000)
        assertNull(ab.jumpFrom("t", 24_900))
        assertEquals(10_000L, ab.jumpFrom("t", 25_000))
        assertNull(ab.jumpFrom("other", 30_000))
    }

    @Test
    fun `a B before or right on A starts again from there`() {
        val a = AbRepeat("t", 10_000, null)
        assertEquals(AbRepeat("t", 9_000, null), a.press("t", 9_000))
        assertEquals(AbRepeat("t", 10_500, null), a.press("t", 10_500))
    }

    @Test
    fun `changing track drops the loop`() {
        assertEquals(AbRepeat.NONE, AbRepeat("t", 1, 5_000).onTrack("u"))
        assertEquals(AbRepeat("t", 1, 5_000), AbRepeat("t", 1, 5_000).onTrack("t"))
    }
}
