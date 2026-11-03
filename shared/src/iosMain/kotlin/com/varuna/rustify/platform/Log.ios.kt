// shared/src/iosMain/kotlin/com/varuna/rustify/platform/Log.ios.kt
package com.varuna.rustify.platform

/**
 * Standard output, which Xcode's console and `log stream` both show. Not `NSLog`: it is variadic
 * with a format string, and a '%' in a song title is not a format.
 */
actual object Log {
    private fun line(level: Char, tag: String, message: String, error: Throwable?) {
        println("$level/$tag: $message" + (error?.let { " — ${it.message}" } ?: ""))
    }

    actual fun d(tag: String, message: String) = line('D', tag, message, null)

    actual fun i(tag: String, message: String) = line('I', tag, message, null)

    actual fun w(tag: String, message: String, error: Throwable?) = line('W', tag, message, error)

    actual fun e(tag: String, message: String, error: Throwable?) = line('E', tag, message, error)
}
