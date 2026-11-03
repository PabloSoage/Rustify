package com.varuna.rustify.bridge

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class LyricsInContextTest {
    @Test
    fun `the prompt carries the whole song and numbers only the block`() {
        val song = listOf("一行目", "二行目", "三行目")
        val p = LyricsInContext.prompt("ja", "es", song, listOf(1 to "二行目"))
        assertTrue(p.contains("from Japanese into Spanish"))
        assertTrue(p.contains("一行目\n二行目\n三行目"))
        assertTrue(p.trimEnd().endsWith("1: 二行目"))
    }

    @Test
    fun `answers are matched back by number, whatever the model wraps them in`() {
        val answer = """
            Here is the translation:
            12: Sigo adelante
            **13**: "Sin mirar atrás"
            14) 「ya no hay vuelta」
            99: not asked for
            15:
        """.trimIndent()
        assertEquals(
            mapOf(12 to "Sigo adelante", 13 to "Sin mirar atrás", 14 to "ya no hay vuelta"),
            LyricsInContext.parse(answer, setOf(12, 13, 14, 15))
        )
    }

    @Test
    fun `a repeated number keeps the first answer`() {
        assertEquals(mapOf(3 to "uno"), LyricsInContext.parse("3: uno\n3: dos", setOf(3)))
    }

    @Test
    fun `a long song is cut in the context, not in the block`() {
        val song = List(400) { "line number $it with some words" }
        val p = LyricsInContext.prompt("en", "es", song, listOf(399 to song[399]))
        assertTrue(p.contains("399: line number 399"))
        assertTrue(p.length < LyricsInContext.MAX_CONTEXT_CHARS + 1_000)
    }
}
