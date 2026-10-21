package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsPlanTest {
    private val any: (String) -> Boolean = { !it.endsWith("-Latn") }

    @Test
    fun aSpanishSongThatOpensInEnglishIsNotTranslatedIntoSpanishAgain() {
        // BbY WOW on a Spanish phone: the English hook used to make the whole song "English".
        val langs = listOf("en", "es", "es", "es", null, "es", "es", "es", "es", "es", "und")
        val p = LyricsTranslator.plan(langs, "es", any)
        assertNull(p.source)
        assertEquals("es", p.dominant)
    }

    @Test
    fun aBilingualSongTranslatesOnlyTheLinesNotInYourLanguage() {
        val langs = listOf("en", "en", "es", "es", "en", "es")
        val p = LyricsTranslator.plan(langs, "es", any)
        assertEquals("en", p.source)
        assertEquals(setOf(0, 1, 4), p.lines)
    }

    @Test
    fun romajiLinesUnderJapaneseOnesAreLeftAlone() {
        val langs = listOf("ja", "ja-Latn", "ja", "ja-Latn", "ja", "ja-Latn")
        val p = LyricsTranslator.plan(langs, "en", any)
        assertEquals("ja", p.source)
        assertEquals(setOf(0, 2, 4), p.lines)
    }

    @Test
    fun onlyRomajiSaysSoInsteadOfTranslating() {
        val p = LyricsTranslator.plan(listOf("ja-Latn", "ja-Latn", "und"), "es", any)
        assertNull(p.source)
        assertEquals("ja-Latn", p.dominant)
    }

    @Test
    fun unknownShortLinesGoWithTheSongsOwnLanguage() {
        val p = LyricsTranslator.plan(listOf("fr", "und", "fr", "fr"), "es", any)
        assertEquals(setOf(0, 1, 2, 3), p.lines)
    }

    @Test
    fun nothingKnownIsUnknown() {
        assertEquals("und", LyricsTranslator.plan(listOf(null, "und"), "es", any).dominant)
    }
}
