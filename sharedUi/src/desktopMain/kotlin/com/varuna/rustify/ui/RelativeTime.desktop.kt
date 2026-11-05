// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/RelativeTime.desktop.kt
package com.varuna.rustify.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The JDK has no relative-time formatter (Android's comes from ICU), so: today's events by the
 * clock, older ones by the date, both in the locale's own short form.
 */
actual fun relativeTimeSpan(epochMs: Long): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val format = if (at.toLocalDate() == LocalDate.now()) FormatStyle.SHORT.let(DateTimeFormatter::ofLocalizedTime)
    else FormatStyle.SHORT.let(DateTimeFormatter::ofLocalizedDate)
    return at.format(format)
}
