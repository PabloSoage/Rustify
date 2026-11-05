package com.varuna.rustify.ui

import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone
import platform.Foundation.setDefaultTimeZone
import platform.Foundation.timeZoneWithAbbreviation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Formats.ios.kt writes by hand what `java.util.Formatter` does on the JVMs, so the expected values
 * here are what FormatsTest and the JVM print. Where the wording is Foundation's (a medium date, the
 * names of months) it is the English one under the "en" locale.
 */
class FormatsIosTest {
    @BeforeTest
    fun english() = IosLocale.apply("en")

    @AfterTest
    fun restore() = IosLocale.apply(IosLocale.SYSTEM)

    @Test
    fun clockTimes() {
        assertEquals("3:07", formatMinSec(3, 7))
        assertEquals("1:03:07", formatHourMinSec(1, 3, 7))
        assertEquals("12:00", formatMinSecUs(12, 0))
    }

    @Test
    fun decimalsRoundHalfUpAsTheJvmDoes() {
        assertEquals("+1.5", formatFixedUs(1.5, 1, signed = true))
        assertEquals("-0.5", formatFixedUs(-0.5, 1, signed = true))
        assertEquals("120.0", formatFixedUs(120.0, 1))
        assertEquals("0.3", formatFixedUs(0.25, 1))
        assertEquals("0.13", formatFixedUs(0.125, 2))
        assertEquals("12.3", formatFixedUs(12.345, 1))
        assertEquals("10.0", formatFixedUs(9.96, 1))
        assertEquals("-0.0", formatFixedUs(-0.04, 1))
        assertEquals("NaN", formatFixedUs(Double.NaN, 1))
    }

    @Test
    fun aValueWithAnExponentIsWrittenInFull() {
        assertEquals("15000000000.0", formatFixedUs(1.5E10, 1))
        assertEquals("0.0001", formatTrimmed(1.0E-4, 5))
    }

    @Test
    fun trimmedDropsTrailingZeros() {
        assertEquals("1.25", formatTrimmed(1.25, 2))
        assertEquals("1.5", formatTrimmed(1.5, 2))
        assertEquals("2", formatTrimmed(2.0, 2))
        // A Float speed widened to a Double is 1.100000023841858.
        assertEquals("1.1", formatTrimmed(1.1f.toDouble(), 2))
    }

    @Test
    fun templatesReadAsFormatterDoes() {
        assertEquals("b a", formatTemplate("%2\$s %1\$s", "a", "b"))
        assertEquals("3 songs", formatTemplate("%d songs", 3))
        assertEquals("12,345", formatTemplate("%,d", 12_345))
        assertEquals("007", formatTemplate("%03d", 7))
        assertEquals("-07", formatTemplate("%03d", -7))
        assertEquals("a  |", formatTemplate("%-3s|", "a"))
        assertEquals("0.3 s", formatTemplate("%.1f s", 0.25))
        assertEquals("100%", formatTemplate("%d%%", 100))
        assertEquals("ff", formatTemplate("%x", 255))
    }

    @Test
    fun countsAreGroupedTheLocalesWay() {
        assertEquals("12,345", formatCount(12_345))
        IosLocale.apply("de")
        assertEquals("12.345", formatCount(12_345))
        assertEquals("1,5", formatTemplate("%.1f", 1.5))
    }

    @Test
    fun datesAndNames() {
        assertEquals("Nov 20, 2026", formatIsoDateMedium("2026-11-20T21:00:00+01:00"))
        // The day where its offset says, not where the device is.
        assertEquals("Nov 20, 2026", formatIsoDateMedium("2026-11-20T23:30:00-11:00"))
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
        val zone = NSTimeZone.defaultTimeZone
        try {
            NSTimeZone.setDefaultTimeZone(NSTimeZone.timeZoneWithAbbreviation("UTC")!!)
            // The week starts on Monday in the UK.
            IosLocale.apply("en_GB")
            val wednesday = 1_793_806_200_000L // 2026-11-04T15:30:00Z
            assertEquals(1_793_750_400_000L, startOfSpan(CalendarSpan.DAY, wednesday)) // 11-04
            assertEquals(1_793_577_600_000L, startOfSpan(CalendarSpan.WEEK, wednesday)) // 11-02
            assertEquals(1_793_491_200_000L, startOfSpan(CalendarSpan.MONTH, wednesday)) // 11-01
            assertEquals(1_767_225_600_000L, startOfSpan(CalendarSpan.YEAR, wednesday)) // 01-01
            assertEquals(1_767_225_600_000L, startOfYearMillis(2026))
        } finally {
            NSTimeZone.setDefaultTimeZone(zone)
        }
    }
}
