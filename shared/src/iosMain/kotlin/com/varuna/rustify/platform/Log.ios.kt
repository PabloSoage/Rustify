// shared/src/iosMain/kotlin/com/varuna/rustify/platform/Log.ios.kt
package com.varuna.rustify.platform

import platform.Foundation.NSLog
import kotlin.concurrent.Volatile

/**
 * `NSLog`, which `log stream` and Console show; standard output reaches neither, only Xcode's console.
 * Its text goes as an argument: a '%' in a song title is not a format.
 */
actual object Log {
    /** Also handed every line (level, tag, message): the in-app log viewer's buffer, as on the desktop. */
    @Volatile
    var sink: ((level: Char, tag: String, message: String) -> Unit)? = null

    private fun line(level: Char, tag: String, message: String, error: Throwable?) {
        NSLog("%@", "$level/$tag: $message" + (error?.let { " — ${it.message}" } ?: ""))
        sink?.invoke(level, tag, if (error != null) "$message\n${error.stackTraceToString()}" else message)
    }

    actual fun d(tag: String, message: String) = line('D', tag, message, null)

    actual fun i(tag: String, message: String) = line('I', tag, message, null)

    actual fun w(tag: String, message: String, error: Throwable?) = line('W', tag, message, error)

    actual fun e(tag: String, message: String, error: Throwable?) = line('E', tag, message, error)
}
