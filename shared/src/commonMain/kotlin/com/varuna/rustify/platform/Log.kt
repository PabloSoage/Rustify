// shared/src/commonMain/kotlin/com/varuna/rustify/platform/Log.kt
package com.varuna.rustify.platform

/**
 * Logging for shared code: `android.util.Log` on Android, standard error on the desktop, `NSLog` on
 * iOS. Same shape as `android.util.Log` so moving a file out of the app is a change of import.
 */
expect object Log {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
