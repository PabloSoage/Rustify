package com.varuna.rustify.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreDispatchTest {
    @Test
    fun `a string of JSON crosses as the value it describes`() {
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), CoreDispatch.value("""["a","b"]"""))
    }

    @Test
    fun `text that is not JSON crosses as null for the core to refuse`() {
        assertEquals(JsonNull, CoreDispatch.value("not json"))
        assertEquals(JsonNull, CoreDispatch.value(""))
    }

    @Test
    fun `a JSON string answer reads back as the bare string the JNI twin answers`() {
        assertEquals("dQw4w9WgXcQ", CoreDispatch.string("\"dQw4w9WgXcQ\""))
        assertEquals("", CoreDispatch.string("\"\""))
    }

    @Test
    fun `a failure where a string was expected reads as nothing`() {
        assertEquals("", CoreDispatch.string("""{"success":false,"error":"x","kind":"permanent"}"""))
        assertEquals("", CoreDispatch.string("42"))
        assertEquals("", CoreDispatch.string(""))
    }

    @Test
    fun `a JSON number answer reads back as a long`() {
        assertEquals(123456789012L, CoreDispatch.long("123456789012", 0L))
        assertEquals(-1L, CoreDispatch.long("-1", 0L))
        assertEquals(-1L, CoreDispatch.long("""{"success":false}""", -1L))
    }

    @Test
    fun `only true reads as true`() {
        assertTrue(CoreDispatch.boolean("true"))
        assertFalse(CoreDispatch.boolean("false"))
        assertFalse(CoreDispatch.boolean("""{"success":false}"""))
    }
}
