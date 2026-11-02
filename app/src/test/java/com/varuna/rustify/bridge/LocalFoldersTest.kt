package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFoldersTest {
    private val paths = listOf(
        "/storage/emulated/0/Music/Rock/b.mp3",
        "/storage/emulated/0/Music/Rock/Live/a.mp3",
        "/storage/emulated/0/Music/anime/c.flac",
        "/storage/emulated/0/Music/z.mp3",
    )

    @Test
    fun `the tree starts where the tracks part ways`() {
        assertEquals("/storage/emulated/0/Music", LocalFolders.root(paths))
        assertEquals("/storage/emulated/0/Music/Rock", LocalFolders.root(paths.take(2)))
        assertEquals("", LocalFolders.root(emptyList()))
    }

    @Test
    fun `a level lists sub-folders with their counts, then its own tracks`() {
        val root = LocalFolders.root(paths)
        val top = LocalFolders.level(paths, { it }, root)
        assertEquals(
            listOf(LocalFolders.Folder("anime", "$root/anime", 1), LocalFolders.Folder("Rock", "$root/Rock", 2)),
            top.folders
        )
        assertEquals(listOf("/storage/emulated/0/Music/z.mp3"), top.tracks)
        val rock = LocalFolders.level(paths, { it }, "$root/Rock")
        assertEquals(listOf(LocalFolders.Folder("Live", "$root/Rock/Live", 1)), rock.folders)
        assertEquals(listOf("/storage/emulated/0/Music/Rock/b.mp3"), rock.tracks)
    }

    @Test
    fun `a sibling with a longer name is not under the folder`() {
        assertTrue(LocalFolders.isUnder("/a/Rock/x.mp3", "/a/Rock"))
        assertTrue(LocalFolders.isUnder("/a/Rock", "/a/Rock/"))
        assertFalse(LocalFolders.isUnder("/a/Rockabilly/x.mp3", "/a/Rock"))
    }
}
