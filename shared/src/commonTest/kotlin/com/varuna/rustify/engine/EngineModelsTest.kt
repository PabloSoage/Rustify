// shared/src/commonTest/kotlin/com/varuna/rustify/engine/EngineModelsTest.kt
package com.varuna.rustify.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The same JSON strings the Rust tests in `core_engine/src/engine/tests.rs` assert, read from this
 * side. Change one, change the other.
 */
class EngineModelsTest {
    @Test
    fun readyDecodesFromTheRustShape() {
        assertEquals(
            EngineEvent.Ready(token = 12, durationMs = 1000),
            EngineEvent.parse("""{"type":"ready","token":12,"durationMs":1000}"""),
        )
    }

    @Test
    fun errorDecodesWithItsCodeAndKind() {
        assertEquals(
            EngineEvent.Error(token = 1, code = "http", kind = "transient", message = "the source answered 503"),
            EngineEvent.parse(
                """{"type":"error","token":1,"code":"http","kind":"transient","message":"the source answered 503"}""",
            ),
        )
    }

    @Test
    fun noEventAndUnknownEventsAreNull() {
        assertNull(EngineEvent.parse("null"))
        assertNull(EngineEvent.parse("""{"type":"somethingNew","x":1}"""))
    }

    @Test
    fun loadEncodesInTheShapeRustReads() {
        val json = EngineCommand.Load(token = 4, uri = "https://x/a.m4a", startMs = 1500, gainDb = -3.5f, play = true).toJson()
        assertEquals(
            """{"type":"load","token":4,"uri":"https://x/a.m4a","startMs":1500,"gainDb":-3.5,"play":true}""",
            json,
        )
    }

    @Test
    fun objectsEncodeAsBareTypes() {
        assertEquals("""{"type":"pause"}""", EngineCommand.Pause.toJson())
        assertEquals("""{"type":"seek","positionMs":30000}""", EngineCommand.Seek(30_000).toJson())
    }
}
