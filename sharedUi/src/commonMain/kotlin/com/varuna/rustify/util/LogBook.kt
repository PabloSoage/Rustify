// sharedUi/src/commonMain/kotlin/com/varuna/rustify/util/LogBook.kt
package com.varuna.rustify.util

import kotlinx.coroutines.flow.StateFlow

/** One line of the app's log: as written, its level (V/D/I/W/E/F) and its tag. */
data class LogEntry(val raw: String, val level: Char, val tag: String)

/**
 * The app's own log, for the log viewer: Android reads its process's logcat (`LogCapture`), the
 * desktop keeps what the shared `Log` writes (`DesktopLogBook`).
 */
interface LogBook {
    val entries: StateFlow<List<LogEntry>>

    /** Why capturing could not start, if it could not (logcat restricted on some ROMs). */
    val error: StateFlow<String?>

    /** Everything held, one line per entry. */
    fun exportText(): String

    /** A snapshot taken now, not from the buffer: logcat's `-d` on Android. */
    fun dumpNow(): String

    fun clear()
}
