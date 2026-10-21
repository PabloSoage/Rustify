package com.varuna.rustify.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun newestFirst() {
        assertEquals(listOf("b", "a"), RecentSearches.push(listOf("a"), "b"))
    }

    @Test
    fun repeatedQueryMovesToTopOnce() {
        assertEquals(listOf("Rosalia", "stromae"), RecentSearches.push(listOf("stromae", "rosalia"), "Rosalia"))
    }

    @Test
    fun blankIsIgnoredAndSpacesCollapse() {
        assertEquals(listOf("a"), RecentSearches.push(listOf("a"), "   "))
        assertEquals("daft punk", RecentSearches.push(emptyList(), "  daft   punk ").first())
    }

    @Test
    fun keepsAtMostMax() {
        val full = (1..RecentSearches.MAX).map { "q$it" }
        val next = RecentSearches.push(full, "new")
        assertEquals(RecentSearches.MAX, next.size)
        assertEquals("new", next.first())
        assertEquals("q${RecentSearches.MAX - 1}", next.last())
    }
}
