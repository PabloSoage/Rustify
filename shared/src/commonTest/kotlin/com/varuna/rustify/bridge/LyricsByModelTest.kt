package com.varuna.rustify.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsByModelTest {
    @Test
    fun thePromptCarriesTheSongAndTheBlockAsJson() {
        val song = listOf("一行目", "二行目", "\"三\"行目")
        val p = LyricsByModel.prompt("es", song, listOf("二行目", "\"三\"行目"))
        assertTrue(p.contains("into Spanish"))
        assertTrue(p.contains("一行目\n二行目\n\"三\"行目"))
        assertTrue(p.contains("""["二行目","\"三\"行目"]"""))
        assertTrue(p.contains("exactly 2 strings"))
    }

    @Test
    fun theObjectAskedForIsRead() {
        val answer = """{"language": "ja", "lines": ["Sigo adelante", " sin mirar atrás "]}"""
        assertEquals(LyricsByModel.Answer.Lines("ja", listOf("Sigo adelante", "sin mirar atrás")), LyricsByModel.parse(answer, 2))
    }

    @Test
    fun aFenceOrASentenceAroundItDoesNotMatter() {
        val fenced = "Here you go:\n```json\n{\"language\":\"JA-jp\",\"lines\":[\"uno\",null]}\n```"
        assertEquals(LyricsByModel.Answer.Lines("ja", listOf("uno", "")), LyricsByModel.parse(fenced, 2))
        val bare = "Sure! [\"uno\", \"dos\"] Hope it helps."
        assertEquals(LyricsByModel.Answer.Lines(null, listOf("uno", "dos")), LyricsByModel.parse(bare, 2))
    }

    @Test
    fun aLineShortIsRefusedNotShifted() {
        assertEquals(LyricsByModel.Answer.WrongCount(1, 2), LyricsByModel.parse("""{"language":"ja","lines":["uno"]}""", 2))
        assertEquals(LyricsByModel.Answer.WrongCount(3, 2), LyricsByModel.parse("""["uno","dos","tres"]""", 2))
    }

    @Test
    fun anythingElseIsNotJson() {
        assertEquals(LyricsByModel.Answer.NotJson, LyricsByModel.parse("Lo siento, no puedo.", 1))
        assertEquals(LyricsByModel.Answer.NotJson, LyricsByModel.parse("""{"language":"ja"}""", 1))
        assertEquals(LyricsByModel.Answer.NotJson, LyricsByModel.parse("""{"lines":[1, 2]}""", 2))
        assertEquals(LyricsByModel.Answer.NotJson, LyricsByModel.parse("""{"lines":[["uno"]]}""", 1))
    }

    @Test
    fun theLanguageIsACodeOrNothing() {
        assertEquals("ja", LyricsByModel.languageCode("jp"))
        assertEquals("pt", LyricsByModel.languageCode("pt_BR"))
        assertEquals("yue", LyricsByModel.languageCode("yue"))
        assertNull(LyricsByModel.languageCode("Japanese"))
        assertNull(LyricsByModel.languageCode(""))
        assertNull(LyricsByModel.languageCode(null))
    }
}
