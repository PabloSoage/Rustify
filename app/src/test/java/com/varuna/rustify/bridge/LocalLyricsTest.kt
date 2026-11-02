package com.varuna.rustify.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLyricsTest {
    @Test
    fun `the lrc next to a file has its name and folder`() {
        assertEquals("primary:Music/A/song.lrc", LocalLyrics.siblingDocId("primary:Music/A/song.mp3", "lrc"))
        assertEquals("primary:a.b.lrc", LocalLyrics.siblingDocId("primary:a.b.flac", "lrc"))
    }

    @Test
    fun `a file without an extension has no sibling`() {
        assertNull(LocalLyrics.siblingDocId("primary:Music/README", "lrc"))
        assertNull(LocalLyrics.siblingDocId("primary:Music/.hidden", "lrc"))
    }
}
