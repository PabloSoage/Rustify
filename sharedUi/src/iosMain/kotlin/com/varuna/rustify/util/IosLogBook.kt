// sharedUi/src/iosMain/kotlin/com/varuna/rustify/util/IosLogBook.kt
package com.varuna.rustify.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

/**
 * [LogBook] on iOS: what the shared log writes, kept in memory in logcat's `threadtime` shape so the
 * viewer reads it the same. [install] once at start.
 */
object IosLogBook : LogBook {
    private const val CAP = 3000
    private val lock = Lock()
    private val buf = ArrayDeque<LogEntry>(CAP)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = _entries
    override val error: StateFlow<String?> = MutableStateFlow(null)
    private val time = NSDateFormatter().apply { dateFormat = "MM-dd HH:mm:ss.SSS" }

    fun install() {
        com.varuna.rustify.platform.Log.sink = { level, tag, message -> add(level, tag, message) }
    }

    private fun add(level: Char, tag: String, message: String) {
        lock.locked {
            val raw = "${time.stringFromDate(NSDate())} 0 0 $level $tag: $message"
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(LogEntry(raw, level, tag))
            _entries.value = buf.toList()
        }
    }

    override fun exportText(): String = lock.locked { buf.joinToString("\n") { it.raw } }

    override fun dumpNow(): String = exportText()

    override fun clear() {
        lock.locked {
            buf.clear()
            _entries.value = emptyList()
        }
    }
}
