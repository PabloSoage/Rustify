// shared/src/commonMain/kotlin/com/varuna/rustify/ui/Formats.kt
package com.varuna.rustify.ui

/*
 * What the shared screens write with the locale or the calendar: clock times, decimals, counts,
 * dates, the names of months, weekdays and languages. Each one names the JVM call the screen made
 * before it moved here, and on Android and the desktop that is what runs — the two actuals are the
 * same code, which FormatsTest checks — so both print the same text. iOS and the web bring theirs.
 *
 * "The default locale" is the JVM's, which MainActivity and the desktop set to the language picked
 * in Settings.
 */

/** [template] with its `%1$s` and `%1$d` filled in: `String.format(template, *args)`, default locale. */
expect fun formatTemplate(template: String, vararg args: Any?): String

/** "3:07": `"%d:%02d".format(minutes, seconds)`, in the default locale's digits. */
expect fun formatMinSec(minutes: Long, seconds: Long): String

/** "1:03:07": `"%d:%02d:%02d".format(hours, minutes, seconds)`, in the default locale's digits. */
expect fun formatHourMinSec(hours: Long, minutes: Long, seconds: Long): String

/** "3:07" in ASCII digits whatever the language: `String.format(Locale.US, "%d:%02d", …)`. */
expect fun formatMinSecUs(minutes: Long, seconds: Long): String

/**
 * [value] with exactly [decimals] decimals and a point, rounded half up: `String.format(Locale.US,
 * "%.1f", value)`, or `"%+.1f"` when [signed] ("+0.5", "-1.2").
 */
expect fun formatFixedUs(value: Double, decimals: Int, signed: Boolean = false): String

/**
 * [value] rounded half up to at most [maxDecimals], without trailing zeros: 1.25 → "1.25",
 * 1.5 → "1.5", 2 → "2". `BigDecimal(value).setScale(…, HALF_UP).stripTrailingZeros().toPlainString()`.
 */
expect fun formatTrimmed(value: Double, maxDecimals: Int): String

/** A count grouped the default locale's way ("12,345"; "12.345" in Spanish): `NumberFormat.getIntegerInstance()`. */
expect fun formatCount(n: Long): String

/**
 * An ISO-8601 date-time with an offset ("2026-11-20T21:00:00+01:00") as the default locale's medium
 * date ("Nov 20, 2026"): `OffsetDateTime.parse` and `DateTimeFormatter.ofLocalizedDate(MEDIUM)`.
 * Null when it is not one.
 */
expect fun formatIsoDateMedium(isoOffsetDateTime: String): String?

/** Month [index] (0 = January) in the default locale: `DateFormatSymbols.getInstance().months`. */
expect fun monthName(index: Int): String

/** [weekday], counted as the core does (0 = Monday), in the default locale: `DateFormatSymbols.weekdays`. */
expect fun weekdayName(weekday: Int): String

/** This year in the system's time zone: `LocalDate.now().year`. */
expect fun currentYear(): Int

/** Today as "2026-11-05": `SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())`. */
expect fun todayIso(): String

/** A moment as date and time: `DateFormat.getDateTimeInstance().format(Date(epochMs))`, default locale. */
expect fun formatDateTime(epochMs: Long): String

/** The hour of the day, 0 to 23, in the system's time zone: `Calendar.HOUR_OF_DAY`. */
expect fun currentHourOfDay(): Int

/** Midnight of January 1 of [year] in the system's time zone, as epoch milliseconds. */
expect fun startOfYearMillis(year: Int): Long

/** A stretch of the calendar that [startOfSpan] finds the beginning of. */
enum class CalendarSpan { DAY, WEEK, MONTH, YEAR }

/**
 * Midnight of the first day of the [span] that holds [epochMs], in the system's time zone: today,
 * this week (from the default locale's first day of the week), this month, this year. A
 * `java.util.Calendar` set field by field, as the metrics screen did.
 */
expect fun startOfSpan(span: CalendarSpan, epochMs: Long): Long

/** The language of [tag] named in the default locale ("ja" → "japonés"): `Locale.displayLanguage`. */
expect fun languageDisplayName(tag: String): String

/**
 * The full name of [tag] in the language [inTag], or in the default locale when that is null:
 * `Locale.forLanguageTag(tag).getDisplayName(…)`.
 */
expect fun localeDisplayName(tag: String, inTag: String?): String
