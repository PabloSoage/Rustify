package com.varuna.rustify.player

import com.varuna.rustify.player.HeadsetButton.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadsetButtonTest {
    private val b = HeadsetButton()

    private fun presses(n: Int, double: Action = Action.NEXT, triple: Action = Action.PREVIOUS): Action {
        repeat(n) { b.press() }
        return b.resolve(double, triple)
    }

    @Test
    fun `one press plays or pauses`() = assertEquals(Action.TOGGLE, presses(1))

    @Test
    fun `two and three presses do what was chosen`() {
        assertEquals(Action.NEXT, presses(2))
        assertEquals(Action.PREVIOUS, presses(3))
        assertEquals(Action.PREVIOUS, presses(2, double = Action.PREVIOUS))
        assertEquals(Action.NOTHING, presses(3, triple = Action.NOTHING))
    }

    @Test
    fun `more than three counts as three`() = assertEquals(Action.PREVIOUS, presses(5))

    @Test
    fun `the count starts over after each resolve`() {
        presses(2)
        assertEquals(Action.TOGGLE, presses(1))
    }

}
