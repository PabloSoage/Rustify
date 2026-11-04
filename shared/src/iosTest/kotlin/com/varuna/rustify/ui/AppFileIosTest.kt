package com.varuna.rustify.ui

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppFile over Foundation, against a real folder: what the local music, the downloads and every store
 * on iOS stand on. On the JVMs it is java.io.File; these pin that this one behaves as the callers
 * expect from that one, where Foundation's calls differ (a move refuses to replace, for one).
 */
class AppFileIosTest {
    private val dir = appFileAt(NSTemporaryDirectory().trimEnd('/') + "/rustify-" + NSUUID().UUIDString)

    @BeforeTest
    fun create() {
        assertTrue(dir.makeDirs())
    }

    @AfterTest
    fun remove() {
        dir.deleteFile()
    }

    @Test
    fun textKeepsEveryCharacter() {
        val file = dir.child("Ñandú.txt")
        val text = "Björk — 日本語 🎵\nsecond line"
        file.writeAllText(text)
        assertEquals(text, file.readAllText())
        assertEquals(text.encodeToByteArray().size.toLong(), file.byteSize())
    }

    @Test
    fun anEmptyFileExists() {
        val file = dir.child("empty")
        file.writeAllBytes(ByteArray(0))
        assertTrue(file.fileExists())
        assertEquals(0L, file.byteSize())
        assertEquals(0, file.readHeadBytes(16).size)
    }

    @Test
    fun appendingCreatesTheFileThenAddsToIt() {
        val file = dir.child("part")
        file.appendAllBytes("ab".encodeToByteArray())
        file.appendAllBytes(ByteArray(0))
        file.appendAllBytes("cd".encodeToByteArray())
        assertEquals("abcd", file.readAllText())
    }

    @Test
    fun theHeadIsAsMuchAsThereIs() {
        val file = dir.child("head")
        file.writeAllText("abcd")
        assertContentEquals("ab".encodeToByteArray(), file.readHeadBytes(2))
        assertContentEquals("abcd".encodeToByteArray(), file.readHeadBytes(100))
        assertEquals(0, dir.child("missing").readHeadBytes(2).size)
    }

    @Test
    fun aMissingFileReadsAsNothing() {
        val missing = dir.child("missing")
        assertFalse(missing.fileExists())
        assertNull(missing.readTextOrNull())
        assertFails { missing.readAllText() }
        assertEquals(0L, missing.byteSize())
        assertEquals(0L, missing.modifiedMillis())
    }

    @Test
    fun aRenameReplacesTheTarget() {
        val from = dir.child("from")
        val to = dir.child("to")
        from.writeAllText("new")
        to.writeAllText("old")
        assertTrue(from.renameToFile(to))
        assertEquals("new", to.readAllText())
        assertFalse(from.fileExists())
    }

    @Test
    fun anAtomicWriteLeavesTheFileAndNoTemporaryOne() {
        val file = dir.child("store.json")
        file.writeTextAtomically("""{"a":1}""")
        file.writeTextAtomically("""{"a":2}""")
        assertEquals("""{"a":2}""", file.readTextOrNull())
        assertFalse(dir.child("store.json.tmp").fileExists())
    }

    @Test
    fun aFolderIsNotAFileAndAMissingPathIsNeither() {
        val folder = dir.child("a").child("b")
        assertTrue(folder.makeDirs())
        assertTrue(folder.isFolder())
        assertFalse(folder.isPlainFile())
        val file = folder.child("f")
        file.writeAllText("x")
        assertTrue(file.isPlainFile())
        assertFalse(file.isFolder())
        assertFalse(folder.child("missing").isFolder())
        assertFalse(folder.child("missing").isPlainFile())
        assertTrue(file.modifiedMillis() > 0L)
    }

    @Test
    fun aFolderListsItsChildren() {
        dir.child("one").writeAllText("1")
        dir.child("two").makeDirs()
        assertEquals(listOf("one", "two"), dir.listChildren().map { it.fileName }.sorted())
        assertEquals(emptyList(), dir.child("missing").listChildren())
    }

    @Test
    fun aPathHasItsNameAndItsParent() {
        val folder = appFileAt("/a/b/")
        assertEquals("/a/b/c", folder.child("c").fullPath)
        assertEquals("b", folder.fileName)
        assertEquals("/a/b", folder.child("c").parentDir?.fullPath)
    }

    @Test
    fun aFileUriComesBackAsTheSameFile() {
        val folder = dir.child("Música con ñ y espacios")
        folder.makeDirs()
        val file = folder.child("a b.flac")
        file.writeAllText("audio")
        val uri = file.fileUri()
        assertTrue(uri.startsWith("file://"), uri)
        assertFalse(' ' in uri, uri)
        // NSURL hands the accents back decomposed, so not the same string: the same file, though, and
        // the same URI from then on, which is what a local track's id is made of.
        val back = appFileOfUri(uri)
        assertEquals("audio", back?.readAllText())
        assertEquals(uri, back?.fileUri())
        assertNull(appFileOfUri("https://example.com/a.flac"))
    }
}
