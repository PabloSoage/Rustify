package com.varuna.rustify.bridge

import kotlin.test.assertEquals
import kotlin.test.Test

class ArtistSeparatorsTest {
    @Test
    fun `a semicolon separates by default and names with other marks survive`() {
        assertEquals(listOf("Yoasobi", "Ado"), ArtistSeparators.split("Yoasobi; Ado", ArtistSeparators.DEFAULT))
        assertEquals(listOf("AC/DC"), ArtistSeparators.split("AC/DC", ArtistSeparators.DEFAULT))
        assertEquals(listOf("Simon & Garfunkel"), ArtistSeparators.split("Simon & Garfunkel", ArtistSeparators.DEFAULT))
    }

    @Test
    fun `opt-in separators split - and a backslash keeps one literal`() {
        assertEquals(listOf("A", "B", "C"), ArtistSeparators.split("A / B & C", ";/&"))
        assertEquals(listOf("AC/DC", "Ado"), ArtistSeparators.split("AC\\/DC/Ado", ";/"))
    }

    @Test
    fun `blanks and repeats are dropped - and nothing to split stays whole`() {
        assertEquals(listOf("A"), ArtistSeparators.split("A;; A ;", ";"))
        assertEquals(listOf("A; B"), ArtistSeparators.split("A; B", ""))
    }
}
