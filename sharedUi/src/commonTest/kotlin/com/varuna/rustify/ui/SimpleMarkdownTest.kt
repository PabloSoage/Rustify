package com.varuna.rustify.ui

import com.varuna.rustify.ui.screens.simpleMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleMarkdownTest {
    @Test
    fun boldLosesItsAsterisksAndBulletsBecomeDots() {
        val s = simpleMarkdown("1. **Led Zeppelin IV:** raw\n* one\nplain")
        assertEquals("1. Led Zeppelin IV: raw\n• one\nplain", s.text)
        assertEquals(1, s.spanStyles.size)
        assertEquals("Led Zeppelin IV:", s.text.substring(s.spanStyles[0].start, s.spanStyles[0].end))
    }

    @Test
    fun anUnclosedPairWhileStreamingStaysAsTyped() {
        assertEquals("so **far", simpleMarkdown("so **far").text)
    }
}
