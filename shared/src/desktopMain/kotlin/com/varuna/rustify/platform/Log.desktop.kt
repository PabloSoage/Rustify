// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/Log.desktop.kt
package com.varuna.rustify.platform

actual object Log {
    /** Also handed every line (level, tag, message): the in-app log viewer's buffer. */
    @Volatile
    var sink: ((level: Char, tag: String, message: String) -> Unit)? = null

    private fun line(level: Char, tag: String, message: String, error: Throwable?) {
        System.err.println("$level/$tag: $message")
        error?.printStackTrace()
        sink?.invoke(level, tag, if (error != null) "$message\n${error.stackTraceToString()}" else message)
    }

    actual fun d(tag: String, message: String) = line('D', tag, message, null)

    actual fun i(tag: String, message: String) = line('I', tag, message, null)

    actual fun w(tag: String, message: String, error: Throwable?) = line('W', tag, message, error)

    actual fun e(tag: String, message: String, error: Throwable?) = line('E', tag, message, error)
}
