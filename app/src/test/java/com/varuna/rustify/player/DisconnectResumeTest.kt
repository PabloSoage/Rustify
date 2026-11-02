package com.varuna.rustify.player

import android.media.AudioDeviceInfo
import com.varuna.rustify.player.DisconnectResume.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectResumeTest {
    private var window = 60_000L
    private var ignorePlay = false
    private val dr = DisconnectResume({ window }, { ignorePlay })

    private val bt = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    @Test
    fun `a car that drops for a few seconds while starting carries on`() {
        assertEquals(Action.Pause, dr.onNoisy(0, isPlaying = true))
        assertEquals(Action.ResumeAfter(DisconnectResume.SETTLE_MS), dr.onOutputsAdded(10_000, bt))
        assertTrue(dr.confirmResume(11_500, inCall = false))
        assertFalse(dr.isPending)
    }

    @Test
    fun `coming back after the window stays paused`() {
        dr.onNoisy(0, isPlaying = true)
        assertEquals(Action.None, dr.onOutputsAdded(70_000, bt))
        assertFalse(dr.isPending)
    }

    @Test
    fun `never resume when the window is off`() {
        window = 0
        assertEquals(Action.Pause, dr.onNoisy(0, isPlaying = true))
        assertEquals(Action.None, dr.onOutputsAdded(1_000, bt))
    }

    @Test
    fun `a choice made in between wins`() {
        dr.onNoisy(0, isPlaying = true)
        dr.onUserCommand(3_000)
        assertEquals(Action.None, dr.onOutputsAdded(5_000, bt))
    }

    @Test
    fun `the echo of its own pause does not cancel the resume`() {
        dr.onNoisy(0, isPlaying = true)
        dr.onUserCommand(5)
        assertEquals(Action.ResumeAfter(DisconnectResume.SETTLE_MS), dr.onOutputsAdded(5_000, bt))
    }

    @Test
    fun `a tap during the settling delay cancels the resume`() {
        dr.onNoisy(0, isPlaying = true)
        dr.onOutputsAdded(5_000, bt)
        dr.onUserCommand(5_500)
        assertFalse(dr.confirmResume(6_500, inCall = false))
    }

    @Test
    fun `no resume over a call`() {
        dr.onNoisy(0, isPlaying = true)
        dr.onOutputsAdded(5_000, bt)
        assertFalse(dr.confirmResume(6_500, inCall = true))
    }

    @Test
    fun `nothing to do when it was already paused`() {
        assertEquals(Action.None, dr.onNoisy(0, isPlaying = false))
        assertEquals(Action.None, dr.onOutputsAdded(5_000, bt))
    }

    @Test
    fun `the speaker or a microphone is not a reconnection`() {
        dr.onNoisy(0, isPlaying = true)
        assertEquals(Action.None, dr.onOutputsAdded(5_000, listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertTrue(dr.isPending)
        assertEquals(Action.ResumeAfter(DisconnectResume.SETTLE_MS), dr.onOutputsAdded(6_000, listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
    }

    @Test
    fun `a spurious play on connecting is ignored only with the option on`() {
        dr.onOutputsAdded(0, bt)
        assertFalse(dr.shouldIgnoreControllerPlay(1_000))
        ignorePlay = true
        assertTrue(dr.shouldIgnoreControllerPlay(1_000))
        assertFalse(dr.shouldIgnoreControllerPlay(DisconnectResume.CONNECT_PLAY_WINDOW_MS + 1))
    }

    @Test
    fun `a play after a disconnection pause is never ignored`() {
        ignorePlay = true
        dr.onNoisy(0, isPlaying = true)
        dr.onOutputsAdded(2_000, bt)
        assertFalse(dr.shouldIgnoreControllerPlay(2_500))
    }
}
