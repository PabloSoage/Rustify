// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/util/WebLogBook.kt
package com.varuna.rustify.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun timeNow(): String = js("new Date().toISOString().slice(5, 23).replace('T', ' ')")

/** [LogBook] in the browser: what the shared log writes, kept in memory in logcat's `threadtime` shape. */
object WebLogBook : LogBook {
    private const val CAP = 3000
    private val buf = ArrayDeque<LogEntry>(CAP)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = _entries
    override val error: StateFlow<String?> = MutableStateFlow(null)

    fun install() {
        com.varuna.rustify.platform.Log.sink = { level, tag, message -> add(level, tag, message) }
    }

    private fun add(level: Char, tag: String, message: String) {
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast(LogEntry("${timeNow()} 0 0 $level $tag: $message", level, tag))
        _entries.value = buf.toList()
    }

    override fun exportText(): String = buf.joinToString("\n") { it.raw }

    override fun dumpNow(): String = exportText()

    override fun clear() {
        buf.clear()
        _entries.value = emptyList()
    }
}
