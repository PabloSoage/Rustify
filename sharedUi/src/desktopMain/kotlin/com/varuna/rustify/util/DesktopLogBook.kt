// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/util/DesktopLogBook.kt
package com.varuna.rustify.util

import com.varuna.rustify.platform.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [LogBook] on the desktop: what the shared [Log] writes, kept in memory as well as on standard
 * error, in logcat's `threadtime` shape so the viewer reads it the same. [install] once at start.
 */
object DesktopLogBook : LogBook {
    private const val CAP = 3000
    private val buf = ArrayDeque<LogEntry>(CAP)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = _entries
    override val error: StateFlow<String?> = MutableStateFlow(null)
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun install() {
        Log.sink = { level, tag, message -> add(level, tag, message) }
    }

    private fun add(level: Char, tag: String, message: String) {
        val raw = "${LocalDateTime.now().format(time)} ${ProcessHandle.current().pid()} ${Thread.currentThread().threadId()} $level $tag: $message"
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(LogEntry(raw, level, tag))
            _entries.value = buf.toList()
        }
    }

    override fun exportText(): String = synchronized(buf) { buf.joinToString("\n") { it.raw } }

    // No logcat to ask: the buffer is everything there is.
    override fun dumpNow(): String = exportText()

    override fun clear() {
        synchronized(buf) {
            buf.clear()
            _entries.value = emptyList()
        }
    }
}
