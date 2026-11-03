package com.varuna.rustify.ui

import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.lyrics_provider_asking
import com.varuna.rustify.ui.resources.inv_health_fmt
import com.varuna.rustify.ui.resources.lyrics_provider_file
import com.varuna.rustify.ui.resources.playlist_find_none
import com.varuna.rustify.ui.resources.ytm_library_empty
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strings came from Android's `res/values*` as they were, escapes and all. Compose Multiplatform
 * reads the same format; these pin the two escapes the files lean on, so a difference in how it
 * unescapes shows here and not as a stray backslash on a screen.
 */
class StringsTest {
    // The default strings, not the machine's language.
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @Test
    fun anEscapedApostropheIsAnApostrophe() = runBlocking {
        assertEquals("The file's own", getString(Res.string.lyrics_provider_file))
    }

    @Test
    fun quotesAPercentAndALineBreakComeOutAsOnThePhone() = runBlocking {
        assertEquals("Nothing in this playlist matches \"x\"", getString(Res.string.playlist_find_none, "x"))
        assertEquals("health 90%", getString(Res.string.inv_health_fmt, 90))
        assertEquals(
            "Your YouTube Music library is empty.\nTap search to get started.",
            getString(Res.string.ytm_library_empty),
        )
    }

    @Test
    fun positionalArgumentsAreFilledIn() = runBlocking {
        assertEquals("Asking LRCLIB…", getString(Res.string.lyrics_provider_asking, "LRCLIB"))
    }
}
