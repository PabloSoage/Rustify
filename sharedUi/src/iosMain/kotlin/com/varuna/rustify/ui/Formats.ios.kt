// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/Formats.ios.kt
package com.varuna.rustify.ui

import platform.Foundation.*
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// What Formats.kt names, over Foundation and the interface's locale (IosLocale.current, the twin of
// the JVM's default). The shapes are the JVM's: the same digits, decimals and rounding where the
// JVM call is arithmetic (FormatsIosTest pins them), and Foundation's own wording where it is
// language (the names of months, a medium date), as the JVM's is the JDK's.

// ── Templates ────────────────────────────────────────────────────────────────────────────────────

/** `%[n$][flags][width][.precision]conversion`, the part of `java.util.Formatter` the strings use. */
private val SPEC = Regex("%(\\d+\\$)?([-#+ 0,(]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])")

actual fun formatTemplate(template: String, vararg args: Any?): String {
    var next = 0
    return SPEC.replace(template) { m ->
        val (indexPart, flags, widthPart, precisionPart, conversion) = m.destructured
        if (conversion == "%") return@replace "%"
        if (conversion == "n") return@replace "\n"
        val arg = if (indexPart.isNotEmpty()) args.getOrNull(indexPart.dropLast(1).toInt() - 1) else args.getOrNull(next++)
        val precision = precisionPart.drop(1).toIntOrNull()
        val body = when (conversion) {
            "s" -> arg.toString().let { if (precision != null) it.take(precision) else it }
            "S" -> arg.toString().let { if (precision != null) it.take(precision) else it }.uppercase()
            "d" -> (arg as? Number)?.toLong()?.let { integer(it, flags) } ?: arg.toString()
            "f" -> (arg as? Number)?.toDouble()?.let { fixed(it, precision ?: 6, '+' in flags, decimalSeparator()) } ?: arg.toString()
            "x" -> (arg as? Number)?.toLong()?.toString(16) ?: arg.toString()
            "X" -> (arg as? Number)?.toLong()?.toString(16)?.uppercase() ?: arg.toString()
            "c" -> arg.toString()
            "b" -> (arg != null && arg != false).toString()
            else -> m.value
        }
        pad(body, widthPart.toIntOrNull(), flags)
    }
}

private fun integer(n: Long, flags: String): String {
    val digits = if (',' in flags) grouped(n) else abs(n).toString()
    val sign = if (n < 0) "-" else if ('+' in flags) "+" else ""
    return sign + digits
}

private fun grouped(n: Long): String =
    decimalFormatter(0).apply { usesGroupingSeparator = true }.stringFromNumber(NSNumber(longLong = abs(n))) ?: abs(n).toString()

/** Width, as `Formatter` pads: spaces before, after with `-`, zeros after the sign with `0`. */
private fun pad(body: String, width: Int?, flags: String): String {
    if (width == null || body.length >= width) return body
    return when {
        '-' in flags -> body.padEnd(width)
        '0' in flags -> {
            val signed = body.startsWith("-") || body.startsWith("+")
            if (signed) body.take(1) + body.drop(1).padStart(width - 1, '0') else body.padStart(width, '0')
        }
        else -> body.padStart(width)
    }
}

private fun decimalSeparator(): String = IosLocale.current.objectForKey(NSLocaleDecimalSeparator) as? String ?: "."

// ── Numbers ──────────────────────────────────────────────────────────────────────────────────────

actual fun formatMinSec(minutes: Long, seconds: Long): String = "$minutes:${seconds.toString().padStart(2, '0')}"

actual fun formatHourMinSec(hours: Long, minutes: Long, seconds: Long): String =
    "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

actual fun formatMinSecUs(minutes: Long, seconds: Long): String = formatMinSec(minutes, seconds)

actual fun formatFixedUs(value: Double, decimals: Int, signed: Boolean): String = fixed(value, decimals, signed, ".")

/** `%.nf` as the JVM writes it: rounded half up from the shortest decimal form of [value]. */
private fun fixed(value: Double, decimals: Int, signed: Boolean, separator: String): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) (if (signed) "+Infinity" else "Infinity") else "-Infinity"
    // Formatter keeps the sign of a negative value that rounds to zero ("-0.0"), and of -0.0 itself.
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val body = roundHalfUp(plainDigits(value), decimals).replace(".", separator)
    return (if (negative) "-" else if (signed) "+" else "") + body
}

actual fun formatTrimmed(value: Double, maxDecimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val rounded = roundHalfUp(plainDigits(value), maxDecimals)
    val body = if ('.' in rounded) rounded.trimEnd('0').trimEnd('.') else rounded
    return if (value < 0 && body.any { it in '1'..'9' }) "-$body" else body
}

/** |[value]| in plain decimal digits, never with an exponent: 1.0E-4 → "0.00010", 1.5E10 → "15000000000". */
private fun plainDigits(value: Double): String {
    val s = abs(value).toString()
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    if (e < 0) return s
    val mantissa = s.substring(0, e)
    val exponent = s.substring(e + 1).toInt()
    val whole = mantissa.substringBefore('.')
    val digits = whole + mantissa.substringAfter('.', "")
    val point = whole.length + exponent
    return when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
}

/** [plain] ("12.345") rounded half up to exactly [decimals] decimals ("12.35"), with no point for 0. */
private fun roundHalfUp(plain: String, decimals: Int): String {
    val whole = plain.substringBefore('.').ifEmpty { "0" }
    val fraction = plain.substringAfter('.', "")
    val kept = whole + fraction.padEnd(decimals, '0').take(decimals)
    val up = fraction.length > decimals && fraction[decimals] >= '5'
    val digits = if (up) increment(kept) else kept
    val wholeLength = digits.length - decimals
    val wholePart = digits.substring(0, wholeLength).trimStart('0').ifEmpty { "0" }
    return if (decimals == 0) wholePart else wholePart + "." + digits.substring(wholeLength)
}

private fun increment(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.lastIndex
    while (i >= 0) {
        if (chars[i] == '9') {
            chars[i] = '0'
            i--
        } else {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
    }
    return "1" + chars.concatToString()
}

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

actual fun monthName(index: Int): String = dateFormatter().monthSymbols?.getOrNull(index) as? String ?: ""

// Foundation lists the weekdays from Sunday; the core counts Monday as 0.
actual fun weekdayName(weekday: Int): String = dateFormatter().weekdaySymbols?.getOrNull((weekday + 1) % 7) as? String ?: ""

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
