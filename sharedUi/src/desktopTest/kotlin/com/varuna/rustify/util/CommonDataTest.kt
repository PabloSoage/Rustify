package com.varuna.rustify.util

import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeTextAtomically
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The common replacements in the data layer print and read what the JVM calls they replaced did,
 * since the files and the strings they make are read back by older versions of the app.
 */
class CommonDataTest {

    private val millis = listOf(0L, 1L, 999L, 1_000L, 1_700_000_000_123L, 1_700_000_000_000L, 1_730_000_000_100L, 253_402_300_799_999L)

    @Test
    fun `a play's timestamp prints as java time's Instant`() {
        for (ms in millis) {
            assertEquals(java.time.Instant.ofEpochMilli(ms).toString(), Instant.fromEpochMilliseconds(ms).toString(), "at $ms")
        }
    }

    @Test
    fun `a like's timestamp prints as the SimpleDateFormat it replaced`() {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (ms in millis) {
            assertEquals(format.format(Date(ms)), Instant.fromEpochSeconds(ms.floorDiv(1000L)).toString(), "at $ms")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `a new playlist id has java util UUID's shape`() {
        repeat(20) {
            val id = Uuid.random().toString()
            val parsed = UUID.fromString(id)
            assertEquals(parsed.toString(), id)
            assertEquals(4, parsed.version())
        }
    }

    @Test
    fun `an atomic write leaves the file and no temporary one`() {
        val dir = Files.createTempDirectory("rustify-data").toFile()
        try {
            val file = dir.child("store.json")
            assertNull(file.readTextOrNull())
            file.writeTextAtomically("""{"a":1}""")
            file.writeTextAtomically("""{"a":2}""")
            assertEquals("""{"a":2}""", file.readTextOrNull())
            assertFalse(dir.child("store.json.tmp").fileExists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
