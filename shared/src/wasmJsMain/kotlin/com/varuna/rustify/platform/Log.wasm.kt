// shared/src/wasmJsMain/kotlin/com/varuna/rustify/platform/Log.wasm.kt
package com.varuna.rustify.platform

private fun consoleLine(text: String): Unit = js("console.log(text)")

/** The browser's console, and whatever the in-app log viewer hands its buffer to, as on iOS. */
actual object Log {
    var sink: ((level: Char, tag: String, message: String) -> Unit)? = null

    private fun line(level: Char, tag: String, message: String, error: Throwable?) {
        consoleLine("$level/$tag: $message" + (error?.let { " — ${it.message}" } ?: ""))
        sink?.invoke(level, tag, if (error != null) "$message\n${error.stackTraceToString()}" else message)
    }

    actual fun d(tag: String, message: String) = line('D', tag, message, null)

    actual fun i(tag: String, message: String) = line('I', tag, message, null)

    actual fun w(tag: String, message: String, error: Throwable?) = line('W', tag, message, error)

    actual fun e(tag: String, message: String, error: Throwable?) = line('E', tag, message, error)
}
