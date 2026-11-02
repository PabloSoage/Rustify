package com.varuna.rustify.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseFadeTest {
    @Test
    fun `a fade out ends at silence and only ever goes down`() {
        val r = PauseFade.ramp(0.8f, 0f)
        assertEquals(PauseFade.STEPS, r.size)
        assertEquals(0f, r.last(), 0f)
        assertTrue(r.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `a fade in ends exactly on the track's level`() {
        val r = PauseFade.ramp(0f, 0.63f)
        assertEquals(0.63f, r.last(), 1e-6f)
        assertTrue(r.first() > 0f)
    }
}
