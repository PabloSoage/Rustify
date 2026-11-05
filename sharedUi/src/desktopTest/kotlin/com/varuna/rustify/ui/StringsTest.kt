package com.varuna.rustify.ui

import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.lyrics_provider_asking
import com.varuna.rustify.ui.resources.inv_health_fmt
import com.varuna.rustify.ui.resources.lyrics_provider_file
import com.varuna.rustify.ui.resources.logs_levels
import com.varuna.rustify.ui.resources.lyrics_translate_failed
import com.varuna.rustify.ui.resources.playlist_find_none
import com.varuna.rustify.ui.resources.track_count
import com.varuna.rustify.ui.resources.ytm_library_empty
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
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
        assertEquals(
            "Could not translate: offline. Tap to try again",
            getString(Res.string.lyrics_translate_failed, "offline"),
        )
    }

    // Android fills a bare %d; Compose Multiplatform only the numbered %1$d, so a plural written
    // the Android way showed "%d songs" on every album and playlist header.
    @Test
    fun aPluralShowsItsCount() = runBlocking {
        assertEquals("1 song", getPluralString(Res.plurals.track_count, 1, 1))
        assertEquals("12 songs", getPluralString(Res.plurals.track_count, 12, 12))
    }

    // Android's resource compiler collapsed runs of spaces; Compose Multiplatform keeps them.
    @Test
    fun noRunOfSpacesSurvivesFromTheAndroidFiles() = runBlocking {
        assertEquals(false, getString(Res.string.logs_levels).contains("  "))
    }

    /**
     * Compose's `stringResource(res, args)` fills `%1$s` and `%1$d` and leaves `%1$.1f` as it is: the
     * audio lab read "%1$.0f µs per frame" on the phone once it moved here. A string with a decimal is
     * read whole and filled with `formatTemplate`, never passed its arguments.
     */
    @Test
    fun aStringWithADecimalIsNeverGivenItsArgumentsByStringResource() {
        val root = java.io.File(System.getProperty("user.dir"))
        val withDecimals = java.io.File(root, "src/commonMain/composeResources/values").listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .flatMap { file -> Regex("""<string name="(\w+)">([^<]*)</string>""").findAll(file.readText()).toList() }
            .filter { Regex("""%(\d\$)?\.?\d*f""").containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
        val sources = java.io.File(root, "src").walk().filter { it.extension == "kt" && "Test" !in it.name }.toList()
        val misuse = withDecimals.flatMap { name ->
            sources.filter { Regex("""stringResource\(\s*Res\.string\.$name\s*,""").containsMatchIn(it.readText()) }
                .map { "${it.name}: $name" }
        }
        kotlin.test.assertTrue(withDecimals.isNotEmpty(), "the strings were found")
        assertEquals(emptyList(), misuse)
    }}
