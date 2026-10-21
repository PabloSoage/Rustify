package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsScriptTest {
    @Test
    fun kanaIsJapaneseEvenWithEnglishLinesInBetween() {
        val song = "夜に駆ける\nOh baby, take my hand\n沈むように溶けてゆくように\nYeah yeah yeah\n二人だけの空が広がる夜に"
        assertEquals("ja", LyricsTranslator.scriptLanguage(song))
    }

    @Test
    fun hangulIsKoreanAndHanAloneIsChinese() {
        assertEquals("ko", LyricsTranslator.scriptLanguage("사랑해요 너무 보고 싶어"))
        assertEquals("zh", LyricsTranslator.scriptLanguage("月亮代表我的心"))
    }

    @Test
    fun latinScriptIsLeftToTheModel() {
        // Romaji included: only ML Kit can tell it is Japanese, and then it says ja-Latn.
        assertNull(LyricsTranslator.scriptLanguage("Yoru ni kakeru, shizumu you ni"))
        assertNull(LyricsTranslator.scriptLanguage("Dis-moi, où es-tu caché ?"))
    }

    @Test
    fun aStrayCjkTitleDoesNotMakeALatinSongCjk() {
        assertNull(LyricsTranslator.scriptLanguage("We sang 東京 all night long, singing in the rain again and again"))
    }
}
