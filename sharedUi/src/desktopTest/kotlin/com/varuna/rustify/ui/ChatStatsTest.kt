package com.varuna.rustify.ui

import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.chat_stats_times
import com.varuna.rustify.ui.resources.nano_chat_stats
import com.varuna.rustify.ui.screens.fillDecimals
import com.varuna.rustify.ui.screens.oneDecimal
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line under each chat answer. Android wrote it with `String.format("%.1f")`, which Compose
 * Multiplatform's resources do not do; the screen fills the template itself, and these pin that it
 * reads as it did.
 */
class ChatStatsTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @Test
    fun oneDecimalRoundsHalfUpAsPrintfDid() {
        assertEquals("0.3", oneDecimal(0.25))
        assertEquals("3.0", oneDecimal(3.0))
        assertEquals("12.3", oneDecimal(12.345))
        assertEquals("0.0", oneDecimal(0.0))
    }

    @Test
    fun theStatsTemplateIsFilledIn() = runBlocking {
        assertEquals(
            "first word 0.3 s · total 3.0 s · 12.3 tokens/s",
            fillDecimals(getString(Res.string.nano_chat_stats), 0.25, 3.0, 12.345),
        )
        assertEquals("first word 0.3 s · total 3.0 s", getString(Res.string.chat_stats_times, oneDecimal(0.25), oneDecimal(3.0)))
    }
}
