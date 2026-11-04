// shared/src/desktopMain/kotlin/com/varuna/rustify/ui/Formats.desktop.kt
package com.varuna.rustify.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar
import java.util.Locale

// Formats.android.kt and Formats.desktop.kt are the same code below their first line, so that the
// phone and the desktop print the same text (FormatsTest compares them). Change both, or neither.

actual fun formatTemplate(template: String, vararg args: Any?): String = String.format(template, *args)

actual fun formatMinSec(minutes: Long, seconds: Long): String = "%d:%02d".format(minutes, seconds)

actual fun formatHourMinSec(hours: Long, minutes: Long, seconds: Long): String =
    "%d:%02d:%02d".format(hours, minutes, seconds)

actual fun formatMinSecUs(minutes: Long, seconds: Long): String = String.format(Locale.US, "%d:%02d", minutes, seconds)

actual fun formatFixedUs(value: Double, decimals: Int, signed: Boolean): String =
    String.format(Locale.US, if (signed) "%+.${decimals}f" else "%.${decimals}f", value)

actual fun formatTrimmed(value: Double, maxDecimals: Int): String =
    BigDecimal(value).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

actual fun formatCount(n: Long): String = NumberFormat.getIntegerInstance().format(n)

actual fun formatIsoDateMedium(isoOffsetDateTime: String): String? =
    runCatching { OffsetDateTime.parse(isoOffsetDateTime).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
        .getOrNull()

actual fun monthName(index: Int): String = DateFormatSymbols.getInstance().months[index]

// DateFormatSymbols counts Sunday as 1; the core counts Monday as 0.
actual fun weekdayName(weekday: Int): String = DateFormatSymbols.getInstance().weekdays[(weekday + 1) % 7 + 1]

actual fun currentYear(): Int = LocalDate.now().year

actual fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

actual fun startOfYearMillis(year: Int): Long =
    LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

actual fun startOfSpan(span: CalendarSpan, epochMs: Long): Long {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (span) {
        CalendarSpan.DAY -> startOfToday.timeInMillis
        CalendarSpan.WEEK -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis
        CalendarSpan.MONTH -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        CalendarSpan.YEAR -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    }
}

actual fun languageDisplayName(tag: String): String = Locale.forLanguageTag(tag).displayLanguage

actual fun localeDisplayName(tag: String, inTag: String?): String =
    Locale.forLanguageTag(tag).getDisplayName(if (inTag == null) Locale.getDefault() else Locale.forLanguageTag(inTag))
