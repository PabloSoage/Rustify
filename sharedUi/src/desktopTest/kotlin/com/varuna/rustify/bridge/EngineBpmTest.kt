package com.varuna.rustify.bridge

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class EngineBpmTest {
    private val dir: File = Files.createTempDirectory("bpm").toFile()
    private val file = "local:file:///music/a.flac"

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun answering(json: String, calls: MutableList<String> = mutableListOf()) =
        EngineBpm(dir) { uri ->
            calls += uri
            json
        }

    @Test
    fun aFileIsMeasuredOnceAndComesBackAfterARestart() = runBlocking {
        val calls = mutableListOf<String>()
        val bpm = answering("""{"bpm":120.5,"confidence":4.0}""", calls)

        assertEquals(120.5f, bpm.analyze(file))
        assertEquals(120.5f, bpm.analyze(file))
        assertEquals(listOf("file:///music/a.flac"), calls)

        assertEquals(120.5f, answering("null").cached(file), "kept in bpm.json")
    }

    @Test
    fun noClearBeatOrAFailureIsRememberedAsNothing() = runBlocking {
        assertNull(answering("""{"bpm":90.0,"confidence":1.1}""").analyze(file), "noise scores about 1.1")
        assertNull(answering("null").analyze(file))
        assertNull(answering("""{"success":false,"error":"bad","kind":"permanent"}""").analyze(file))
        assertNull(answering("null").cached(file))
    }

    @Test
    fun onlyAFileOnThisMachineIsAsked() = runBlocking {
        val calls = mutableListOf<String>()
        val bpm = answering("""{"bpm":120.0,"confidence":4.0}""", calls)

        assertNull(bpm.analyze("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(bpm.analyze("local:content://media/1"))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun anOctaveErrorIsCorrectedAndKept() = runBlocking {
        val bpm = answering("""{"bpm":60.3,"confidence":4.0}""")
        bpm.analyze(file)

        assertEquals(120.6f, bpm.scale(file, 2f))
        assertEquals(120.6f, answering("null").cached(file))
        assertNull(bpm.scale("local:file:///other.flac", 2f), "nothing measured to correct")
    }
}
