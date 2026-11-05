// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/RelativeTime.ios.kt
package com.varuna.rustify.ui

import platform.Foundation.*

/**
 * Foundation's relative formatter, the twin of Android's `DateUtils.getRelativeTimeSpanString`:
 * "5 minutes ago", and "yesterday" rather than "1 day ago" (the named style), in the interface's
 * language.
 */
actual fun relativeTimeSpan(epochMs: Long): String {
    val formatter = NSRelativeDateTimeFormatter().apply {
        locale = IosLocale.current
        dateTimeStyle = NSRelativeDateTimeFormatterStyleNamed
        unitsStyle = NSRelativeDateTimeFormatterUnitsStyleFull
    }
    return formatter.localizedStringForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0), relativeToDate = NSDate())
}
