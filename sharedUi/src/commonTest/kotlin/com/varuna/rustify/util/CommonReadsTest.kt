package com.varuna.rustify.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every platform's data layer stands on, so it runs on Kotlin/Native as well as on the JVM. */
class CommonReadsTest {
    @Test
    fun theOptReadsForgiveWhatOrgJsonForgave() {
        assertEquals("", null.optString())
        assertEquals("x", JsonNull.optString("x"))
        assertEquals("12", JsonPrimitive(12).optString())
        assertEquals("true", JsonPrimitive(true).optString())
        assertNull(JsonNull.optStringOrNull())
        assertEquals(12, JsonPrimitive("12").optInt())
        assertEquals(1, JsonPrimitive(1.9).optInt())
        assertEquals(7, JsonPrimitive("x").optInt(7))
        assertEquals(1_700_000_000_123L, JsonPrimitive(1_700_000_000_123L).optLong())
        assertEquals(0L, JsonNull.optLong())
        assertTrue(JsonPrimitive("TRUE").optBoolean())
        assertFalse(JsonPrimitive("nope").optBoolean(false))
        assertEquals(listOf("a", "b"), parseArray("""["a","b"]""").map { it.optString() })
        assertEquals("v", parseObject("""{"k":"v"}""")["k"].optString())
    }

    @Test
    fun aLockedMapIsAMap() {
        val map = LockedMap<String, Int>()
        map["a"] = 1
        map.getOrPut("b") { 2 }
        assertEquals(mapOf("a" to 1, "b" to 2), map)
        assertEquals(1, map.remove("a"))
        map.clear()
        assertTrue(map.isEmpty())
    }
}
