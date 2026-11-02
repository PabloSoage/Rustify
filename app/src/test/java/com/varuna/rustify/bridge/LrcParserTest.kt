package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun `plain lines keep working`() {
        assertEquals(
            listOf(LyricLine(12_340, "hello"), LyricLine(65_000, "world")),
            parseLrcText("[ar:Someone]\n[00:12.34] hello\n[01:05.000]world\nnot a line")
        )
    }

    @Test
    fun `a repeated chorus with several stamps becomes several lines`() {
        assertEquals(
            listOf(LyricLine(12_000, "chorus"), LyricLine(20_000, "verse"), LyricLine(90_000, "chorus")),
            parseLrcText("[00:12.00][01:30.00]chorus\n[00:20]verse")
        )
    }

    @Test
    fun `per-word stamps are dropped, one-digit minutes are read`() {
        assertEquals(
            listOf(LyricLine(11_220, "hello i am lyric")),
            parseLrcText("[0:11.22] <00:11.22> hello <00:12.85> i am <00:13.23> lyric")
        )
    }
}
