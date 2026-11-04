package com.varuna.rustify.ui

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The text the shared screens format (Formats.kt). Only the desktop's actual runs here, so the first
 * test is what says the phone prints the same: its actual is the same code.
 */
class FormatsTest {
    private val locale = Locale.getDefault()

    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @AfterTest
    fun restore() = Locale.setDefault(locale)

    @Test
    fun theAndroidAndDesktopActualsAreTheSameCode() {
        // Gradle runs the tests from the module's folder.
        fun body(path: String) = File(path).readLines().dropWhile { !it.startsWith("package ") }
        assertEquals(
            body("src/androidMain/kotlin/com/varuna/rustify/ui/Formats.android.kt"),
            body("src/desktopMain/kotlin/com/varuna/rustify/ui/Formats.desktop.kt"),
            "Formats.android.kt and Formats.desktop.kt differ",
        )
    }

    @Test
    fun clockTimes() {
        assertEquals("3:07", formatMinSec(3, 7))
        assertEquals("1:03:07", formatHourMinSec(1, 3, 7))
        assertEquals("12:00", formatMinSecUs(12, 0))
    }

    @Test
    fun decimals() {
        assertEquals("+1.5", formatFixedUs(1.5, 1, signed = true))
        assertEquals("-0.5", formatFixedUs(-0.5, 1, signed = true))
        assertEquals("120.0", formatFixedUs(120.0, 1))
        assertEquals("1.25", formatTrimmed(1.25, 2))
        assertEquals("1.5", formatTrimmed(1.5, 2))
        assertEquals("2", formatTrimmed(2.0, 2))
        // A Float speed widened to a Double is 1.100000023841858.
        assertEquals("1.1", formatTrimmed(1.1f.toDouble(), 2))
    }

    @Test
    fun countsAreGroupedTheLocalesWay() {
        assertEquals("12,345", formatCount(12_345))
        Locale.setDefault(Locale.GERMAN)
        assertEquals("12.345", formatCount(12_345))
    }

    @Test
    fun datesAndNames() {
        assertEquals("Nov 20, 2026", formatIsoDateMedium("2026-11-20T21:00:00+01:00"))
        assertNull(formatIsoDateMedium("soon"))
        assertEquals("January", monthName(0))
        assertEquals("Monday", weekdayName(0))
        assertEquals("Sunday", weekdayName(6))
        assertEquals("Japanese", languageDisplayName("ja"))
        assertEquals("Spanish", localeDisplayName("es", null))
        assertEquals("español", localeDisplayName("es", "es"))
    }

    @Test
    fun spansStartAtMidnightOfTheirFirstDay() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // The week starts on Monday in the UK.
            Locale.setDefault(Locale.UK)
            val wednesday = Instant.parse("2026-11-04T15:30:00Z").toEpochMilli()
            fun at(iso: String) = Instant.parse(iso).toEpochMilli()
            assertEquals(at("2026-11-04T00:00:00Z"), startOfSpan(CalendarSpan.DAY, wednesday))
            assertEquals(at("2026-11-02T00:00:00Z"), startOfSpan(CalendarSpan.WEEK, wednesday))
            assertEquals(at("2026-11-01T00:00:00Z"), startOfSpan(CalendarSpan.MONTH, wednesday))
            assertEquals(at("2026-01-01T00:00:00Z"), startOfSpan(CalendarSpan.YEAR, wednesday))
            assertEquals(at("2026-01-01T00:00:00Z"), startOfYearMillis(2026))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
