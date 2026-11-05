// shared/src/wasmJsMain/kotlin/com/varuna/rustify/ui/Formats.wasm.kt
package com.varuna.rustify.ui

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The interface's language, a BCP 47 tag; the browser's until Settings picks another. */
object WebLocale {
    /**
     * Setting it also tells Compose: its resources read `Locale.current`, which on the web is the
     * browser's language unless `window.__customLocale` says otherwise. Without that, picking Spanish
     * in Settings changed the dates and numbers and left every string in the browser's language.
     */
    var tag: String = browserLanguage()
        set(value) {
            field = value
            setComposeLocale(value)
        }

    /** Back to the browser's language ("System" in Settings). */
    fun useBrowsers() {
        tag = browserLanguage()
    }
}

private fun browserLanguage(): String = js("navigator.language || 'en'")

private fun setComposeLocale(tag: String) {
    js("window.__customLocale = tag")
}

private fun numberParts(tag: String, kind: String): String = js(
    """(function() {
        var parts = new Intl.NumberFormat(tag).formatToParts(1234567.5);
        for (var i = 0; i < parts.length; i++) { if (parts[i].type === kind) return parts[i].value; }
        return '';
    })()"""
)

private fun groupedText(tag: String, n: Double): String = js("new Intl.NumberFormat(tag, { maximumFractionDigits: 0 }).format(n)")

private fun mediumDate(tag: String, iso: String, offsetMinutes: Int): String = js(
    """(function() {
        var shifted = new Date(new Date(iso).getTime() + offsetMinutes * 60000);
        return new Intl.DateTimeFormat(tag, { dateStyle: 'medium', timeZone: 'UTC' }).format(shifted);
    })()"""
)

private fun nameOfMonth(tag: String, index: Int): String =
    js("new Intl.DateTimeFormat(tag, { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2021, index, 15)))")

// 2021-03-01 was a Monday, the core's weekday 0.
private fun nameOfWeekday(tag: String, weekday: Int): String =
    js("new Intl.DateTimeFormat(tag, { weekday: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2021, 2, 1 + weekday)))")

private fun yearNow(): Int = js("new Date().getFullYear()")

private fun hourNow(): Int = js("new Date().getHours()")

private fun dateTimeOf(tag: String, ms: Double): String =
    js("new Date(ms).toLocaleString(tag, { dateStyle: 'medium', timeStyle: 'medium' })")

private fun todayNow(): String =
    js("(d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'))(new Date())")

private fun yearStart(year: Int): Double = js("new Date(year, 0, 1).getTime()")

private fun spanStart(span: Int, epochMs: Double, firstWeekday: Int): Double = js(
    """(function() {
        var d = new Date(epochMs);
        if (span === 1) { d.setDate(d.getDate() - ((d.getDay() - firstWeekday + 7) % 7)); }
        if (span === 2) { d = new Date(d.getFullYear(), d.getMonth(), 1); }
        if (span === 3) { d = new Date(d.getFullYear(), 0, 1); }
        d.setHours(0, 0, 0, 0);
        return d.getTime();
    })()"""
)

private fun firstDayOfWeek(tag: String): Int = js(
    """(function() {
        try {
            var l = new Intl.Locale(tag);
            var info = l.getWeekInfo ? l.getWeekInfo() : l.weekInfo;
            return info ? info.firstDay % 7 : 1;
        } catch (e) { return 1; }
    })()"""
)

private fun displayName(tag: String, inTag: String): String =
    js("(function() { try { return new Intl.DisplayNames([inTag], { type: 'language' }).of(tag) || tag; } catch (e) { return tag; } })()")

internal actual fun decimalSeparator(): String = numberParts(WebLocale.tag, "decimal").ifEmpty { "." }

internal actual fun groupedDigits(n: Long): String = groupedText(WebLocale.tag, n.toDouble())

actual fun formatCount(n: Long): String = groupedText(WebLocale.tag, n.toDouble())

// The date where its offset says, as OffsetDateTime.format does, not in this browser's zone.
@OptIn(ExperimentalTime::class)
actual fun formatIsoDateMedium(isoOffsetDateTime: String): String? = runCatching {
    val zone = Regex("([+-])(\\d{2}):?(\\d{2})$").find(isoOffsetDateTime)
    val offset = when {
        isoOffsetDateTime.endsWith("Z") || isoOffsetDateTime.endsWith("z") -> 0
        zone != null -> {
            val (sign, hours, minutes) = zone.destructured
            (hours.toInt() * 60 + minutes.toInt()) * (if (sign == "-") -1 else 1)
        }
        else -> return null
    }
    Instant.parse(isoOffsetDateTime)
    mediumDate(WebLocale.tag, isoOffsetDateTime, offset)
}.getOrNull()

actual fun monthName(index: Int): String = if (index in 0..11) nameOfMonth(WebLocale.tag, index) else ""

actual fun weekdayName(weekday: Int): String = nameOfWeekday(WebLocale.tag, weekday)

actual fun currentYear(): Int = yearNow()

actual fun todayIso(): String = todayNow()

actual fun formatDateTime(epochMs: Long): String = dateTimeOf(WebLocale.tag, epochMs.toDouble())

actual fun currentHourOfDay(): Int = hourNow()

actual fun startOfYearMillis(year: Int): Long = yearStart(year).toLong()

actual fun startOfSpan(span: CalendarSpan, epochMs: Long): Long =
    spanStart(span.ordinal, epochMs.toDouble(), firstDayOfWeek(WebLocale.tag)).toLong()

actual fun languageDisplayName(tag: String): String = displayName(tag.substringBefore('-').substringBefore('_'), WebLocale.tag)

actual fun localeDisplayName(tag: String, inTag: String?): String = displayName(tag, inTag ?: WebLocale.tag)
