package com.varuna.rustify.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StereoToolsTest {
    private fun run(s: StereoTools.Settings, frames: Int, l: Float, r: Float): FloatArray {
        val buf = FloatArray(frames * 2) { if (it % 2 == 0) l else r }
        StereoTools.process(buf, 48_000, s, StereoTools.State())
        return buf
    }

    @Test
    fun nothingOnChangesNothing() {
        val out = run(StereoTools.Settings(), 4, 0.5f, -0.25f)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f, 0.5f, -0.25f, 0.5f, -0.25f, 0.5f, -0.25f), out, 0f)
        assertEquals(false, StereoTools.Settings().active)
    }

    @Test
    fun monoPutsTheSameInBothEars() {
        val out = run(StereoTools.Settings(mono = true), 2, 0.8f, 0f)
        assertEquals(0.4f, out[0], 1e-6f)
        assertEquals(0.4f, out[1], 1e-6f)
    }

    @Test
    fun balanceTurnsTheOtherSideDown() {
        assertEquals(1f to 1f, StereoTools.balanceGains(0))
        assertEquals(1f to 0.5f, StereoTools.balanceGains(-50))
        assertEquals(0f to 1f, StereoTools.balanceGains(100))
    }

    @Test
    fun crossfeedLetsTheOtherEarHearAHardPannedSourceButKeepsACentredOneLevel() {
        // Settled: after a second of steady signal the low-pass has caught up.
        val panned = run(StereoTools.Settings(crossfeed = true), 48_000, 1f, 0f)
        assertTrue("right ear hears some of the left", panned.last() > 0.3f)
        assertTrue("left stays the louder", panned[panned.size - 2] > panned.last())
        val centred = run(StereoTools.Settings(crossfeed = true), 48_000, 0.5f, 0.5f)
        assertEquals(0.5f, centred.last(), 1e-3f)
    }

    @Test
    fun voiceReductionCancelsTheCentreAndKeepsTheSides() {
        // A steady centred tone at a high pitch is the voice; alternate samples make it ~24 kHz.
        val buf = FloatArray(9_600) { i -> if ((i / 2) % 2 == 0) 0.5f else -0.5f }
        StereoTools.process(buf, 48_000, StereoTools.Settings(voiceReduction = true), StereoTools.State())
        assertTrue("centre gone", buf.takeLast(100).all { abs(it) < 0.05f })
        val side = run(StereoTools.Settings(voiceReduction = true), 2, 0.5f, -0.5f)
        assertEquals(0.5f, side[0], 1e-3f)
        assertEquals(-0.5f, side[1], 1e-3f)
    }
}
