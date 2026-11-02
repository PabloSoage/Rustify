package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    private val doc = "local:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/"

    private val library = listOf(
        M3u.Known("${doc}primary%3AMusic%2FA%2Fintro.mp3", "/storage/emulated/0/Music/A/intro.mp3", "Artist A", "Intro"),
        M3u.Known("${doc}primary%3AMusic%2FB%2Fintro.mp3", "/storage/emulated/0/Music/B/intro.mp3", "Artist B", "Intro"),
        M3u.Known("${doc}primary%3AMusic%2FC%2F%E3%83%80.flac", "/storage/emulated/0/Music/C/ダ.flac", "ダイナ", "マインド"),
    )

    @Test
    fun `a document id becomes the path on its volume`() {
        assertEquals("/storage/emulated/0/Music/C/ダ.flac", M3u.pathOf(library[2].id))
        assertEquals(
            "/storage/1A2B-3C4D/Music/x.mp3",
            M3u.pathOf("local:content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic/document/1A2B-3C4D%3AMusic%2Fx.mp3")
        )
        assertNull(M3u.pathOf("local:content://com.cloud.provider/document/opaque123"))
    }

    @Test
    fun `extinf gives the title and duration of the next line`() {
        val entries = M3u.parse("\uFEFF#EXTM3U\r\n#EXTINF:215 tvg-id=\"x\",Artist A - Intro\r\nA\\intro.mp3\r\n\r\nB/intro.mp3\r\n")
        assertEquals(listOf(M3u.Entry("A\\intro.mp3", "Artist A - Intro", 215), M3u.Entry("B/intro.mp3")), entries)
    }

    @Test
    fun `the longest shared tail picks between files of the same name`() {
        val text = """
            C:\Users\me\Music\B\intro.mp3
            ../A/intro.mp3
            /sdcard/Music/C/ダ.flac
            #EXTINF:1,ダイナ - マインド
            elsewhere/renamed.flac
            nowhere.mp3
        """.trimIndent()
        val r = M3u.resolve(M3u.parse(text), library)
        assertEquals(listOf(library[1].id, library[0].id, library[2].id, library[2].id), r.ids)
        assertEquals(listOf(M3u.Entry("nowhere.mp3")), r.missing)
    }

    @Test
    fun `what is written reads back to the same tracks`() {
        val text = M3u.write("Mine", library, listOf(215, 200))
        assertEquals(true, text.startsWith("#EXTM3U\n#PLAYLIST:Mine\n#EXTINF:215,Artist A - Intro\n"))
        assertEquals(library.map { it.id }, M3u.resolve(M3u.parse(text), library).ids)
    }
}
