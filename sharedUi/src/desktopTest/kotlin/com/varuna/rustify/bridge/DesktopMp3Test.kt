package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Executables
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopMp3Test {
    private val dir: File = Files.createTempDirectory("mp3").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun withNoProgramOrAFileThatAlreadyIsOneNothingHappens() {
        val m4a = File(dir, "a.m4a").apply { writeText("x") }
        assertSame(m4a, DesktopMp3.convert(m4a, exe = null))
        val mp3 = File(dir, "a.mp3").apply { writeText("x") }
        assertSame(mp3, DesktopMp3.convert(mp3, exe = "ffmpeg"))
    }

    @Test
    fun aFileThatCannotBeConvertedIsKept() {
        val exe = Executables.onPath("ffmpeg") ?: return
        val broken = File(dir, "broken.m4a").apply { writeText("not audio") }

        assertSame(broken, DesktopMp3.convert(broken, exe))
        assertTrue(broken.isFile)
        assertFalse(File(dir, "broken.mp3").exists())
    }

    @Test
    fun aRealFileBecomesAnMp3AndTheOriginalGoes() {
        val exe = Executables.onPath("ffmpeg") ?: return
        val source = File(dir, "tone.m4a")
        val made = Executables.run(listOf(exe, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", source.path), 60)
        assertEquals(0, made?.first, made?.second)
        File(dir, "tone.mp3").writeText("someone else's")

        val out = DesktopMp3.convert(source, exe)

        assertEquals("tone (2).mp3", out.name, "nothing already there is overwritten")
        assertFalse(source.exists())
        val head = out.readBytes().take(3)
        assertNotEquals(0, out.length())
        assertTrue(head == "ID3".toByteArray().toList() || head[0] == 0xFF.toByte(), "an MP3's first bytes")
    }
}
