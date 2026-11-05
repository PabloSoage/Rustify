// shared/src/iosMain/kotlin/com/varuna/rustify/ui/Formats.ios.kt
package com.varuna.rustify.ui

import platform.Foundation.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// What Formats.kt names, over Foundation and the interface's locale (IosLocale.current, the twin of
// the JVM's default). The shapes are the JVM's: the same digits, decimals and rounding where the
// JVM call is arithmetic (FormatsIosTest pins them), and Foundation's own wording where it is
// language (the names of months, a medium date), as the JVM's is the JDK's.

internal actual fun decimalSeparator(): String = IosLocale.current.objectForKey(NSLocaleDecimalSeparator) as? String ?: "."

internal actual fun groupedDigits(n: Long): String =
    decimalFormatter(0).apply { usesGroupingSeparator = true }.stringFromNumber(NSNumber(longLong = n)) ?: n.toString()

private fun decimalFormatter(fractionDigits: Int): NSNumberFormatter = NSNumberFormatter().apply {
    locale = IosLocale.current
    numberStyle = NSNumberFormatterDecimalStyle
    minimumFractionDigits = fractionDigits.toULong()
    maximumFractionDigits = fractionDigits.toULong()
    roundingMode = NSNumberFormatterRoundHalfUp
}

actual fun formatCount(n: Long): String =
    decimalFormatter(0).stringFromNumber(NSNumber(longLong = n)) ?: n.toString()

// ── Dates ────────────────────────────────────────────────────────────────────────────────────────

/** The offset an ISO-8601 date-time ends with, in seconds; null when it has none (the JVM refuses those). */
private fun isoOffsetSeconds(iso: String): Int? {
    if (iso.endsWith("Z") || iso.endsWith("z")) return 0
    val m = Regex("([+-])(\\d{2}):?(\\d{2})(?::?(\\d{2}))?$").find(iso) ?: return null
    val (sign, hours, minutes, seconds) = m.destructured
    val total = hours.toInt() * 3600 + minutes.toInt() * 60 + (seconds.toIntOrNull() ?: 0)
    return if (sign == "-") -total else total
}

private fun nsDate(epochMs: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)

private fun NSDate.epochMillis(): Long = (timeIntervalSince1970 * 1000.0).toLong()

// The date where its offset says, as OffsetDateTime.format does, not in this device's zone.
@OptIn(ExperimentalTime::class)
actual fun formatIsoDateMedium(isoOffsetDateTime: String): String? = runCatching {
    val offset = isoOffsetSeconds(isoOffsetDateTime) ?: return null
    val instant = Instant.parse(isoOffsetDateTime)
    NSDateFormatter().apply {
        locale = IosLocale.current
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(offset.toLong())
    }.stringFromDate(nsDate(instant.toEpochMilliseconds()))
}.getOrNull()

private fun dateFormatter(): NSDateFormatter = NSDateFormatter().apply { locale = IosLocale.current }

actual fun monthName(index: Int): String = dateFormatter().monthSymbols.getOrNull(index) as? String ?: ""

// Foundation lists the weekdays from Sunday; the core counts Monday as 0.
actual fun weekdayName(weekday: Int): String = dateFormatter().weekdaySymbols.getOrNull((weekday + 1) % 7) as? String ?: ""

/** The user's calendar in this device's zone, its first weekday the interface's locale's. */
private fun calendar(): NSCalendar = NSCalendar.currentCalendar.also { it.locale = IosLocale.current }

actual fun currentYear(): Int = calendar().component(NSCalendarUnitYear, fromDate = NSDate()).toInt()

actual fun currentHourOfDay(): Int = calendar().component(NSCalendarUnitHour, fromDate = NSDate()).toInt()

actual fun startOfYearMillis(year: Int): Long {
    val parts = NSDateComponents().apply {
        this.year = year.toLong()
        month = 1
        day = 1
    }
    return calendar().dateFromComponents(parts)?.epochMillis() ?: 0L
}

actual fun startOfSpan(span: CalendarSpan, epochMs: Long): Long {
    val cal = calendar()
    val today = cal.startOfDayForDate(nsDate(epochMs))
    val start: NSDate? = when (span) {
        CalendarSpan.DAY -> today
        CalendarSpan.WEEK -> {
            val weekday = cal.component(NSCalendarUnitWeekday, fromDate = today)
            val back = (weekday - cal.firstWeekday.toLong() + 7) % 7
            cal.dateByAddingUnit(NSCalendarUnitDay, value = -back, toDate = today, options = 0uL)
        }
        CalendarSpan.MONTH -> cal.dateFromComponents(cal.components(NSCalendarUnitYear or NSCalendarUnitMonth, fromDate = today))
        CalendarSpan.YEAR -> cal.dateFromComponents(cal.components(NSCalendarUnitYear, fromDate = today))
    }
    return (start ?: today).epochMillis()
}

// ── Languages ────────────────────────────────────────────────────────────────────────────────────

actual fun languageDisplayName(tag: String): String =
    IosLocale.current.localizedStringForLanguageCode(tag.substringBefore('-').substringBefore('_')) ?: tag

actual fun localeDisplayName(tag: String, inTag: String?): String {
    val inLocale = if (inTag == null) IosLocale.current else NSLocale(localeIdentifier = inTag)
    return inLocale.displayNameForKey(NSLocaleIdentifier, value = tag) ?: tag
}
