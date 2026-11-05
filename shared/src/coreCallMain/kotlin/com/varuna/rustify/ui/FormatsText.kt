// shared/src/coreCallMain/kotlin/com/varuna/rustify/ui/FormatsText.kt
package com.varuna.rustify.ui

import kotlin.math.abs

// The arithmetic half of Formats.kt, where there is no java.util.Formatter: what is language is each
// platform's. The shapes are the JVM's, the same digits, decimals and rounding (FormatsIosTest pins them).

/** The interface's locale's decimal separator. */
internal expect fun decimalSeparator(): String

/** [n], not negative, with the interface's locale's grouping. */
internal expect fun groupedDigits(n: Long): String

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
    val digits = if (',' in flags) groupedDigits(abs(n)) else abs(n).toString()
    val sign = if (n < 0) "-" else if ('+' in flags) "+" else ""
    return sign + digits
}

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
