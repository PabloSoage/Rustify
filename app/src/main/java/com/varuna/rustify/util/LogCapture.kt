package com.varuna.rustify.util

import android.content.Context
import com.varuna.rustify.util.LogCapture.flow
import com.varuna.rustify.util.LogCapture.start
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * In-app log capture that reads the logcat of the app's OWN process.
 *
 * An app can read its own logcat without special permissions (by filtering with `--pid=<my_pid>`).
 * A long-lived `logcat` process is started in stream mode, its stdout is read on a daemon thread,
 * each line is parsed into an [Entry] and enqueued in a bounded buffer exposed by [flow].
 *
 * **Crash-resistant persistence:** each entry is also written to `filesDir/rustify_log.txt`.
 * On startup, if the file exists (from a previous session that crashed), it is loaded into the
 * buffer, so logs survive a crash and are available when the app reopens.
 */
object LogCapture {

    /** A parsed entry from a logcat line. `level` = V/D/I/W/E/F, `tag` = the tag. */
    data class Entry(val raw: String, val level: Char, val tag: String)

    /** Cap of the in-memory circular buffer. */
    private const val CAP = 3000
    /** Cap of the persistence file (bytes). It rotates once exceeded. */
    private const val MAX_FILE_BYTES = 512_000L

    private val buf = ArrayDeque<Entry>(CAP)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow

    /** Last startup error (e.g. logcat unavailable on the ROM), to surface in the viewer. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var proc: Process? = null
    private var readerThread: Thread? = null
    private var logFile: File? = null

    // threadtime format: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message"
    private val threadtimeRe =
        Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEFvdiwef])\s+(.*?)\s*:\s?(.*)$""")

    /**
     * Initializes the persistence file path. Must be called once before [start]
     * (e.g. from [android.app.Application.onCreate]). Idempotent if already called.
     */
    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "rustify_log.txt")
        }
    }

    /**
     * Starts the continuous stream of the process's own logcat. No-op if already capturing.
     * If the previous process died (app crash), clears the reference so it can restart.
     *
     * If a log file from a previous session exists, it is loaded into the buffer before capturing
     * starts, so the crash logs stay visible.
     *
     * @param clearFirst if true, runs `logcat -c` and truncates the file to start clean.
     */
    @Synchronized
    fun start(clearFirst: Boolean = true) {
        // If the logcat process died (e.g., app killed by crash), clean up stale ref.
        if (proc != null && !proc!!.isAlive) {
            proc = null
            readerThread = null
        }
        if (proc != null) return
        _error.value = null

        // Load any persisted logs from a previous (possibly crashed) session BEFORE starting.
        val file = logFile
        if (clearFirst) {
            file?.delete()
        } else if (file != null && file.exists() && file.length() > 0) {
            loadFromFile(file)
        }

        val pid = android.os.Process.myPid()
        try {
            if (clearFirst) {
                runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() }
            }
            val p = Runtime.getRuntime()
                .exec(arrayOf("logcat", "-v", "threadtime", "--pid=$pid"))
            proc = p
            readerThread = Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val e = parseLine(line)
                            synchronized(buf) {
                                if (buf.size >= CAP) buf.removeFirst()
                                buf.addLast(e)
                                _flow.value = buf.toList()
                            }
                            appendToFile(e)
                        }
                    }
                } catch (_: Exception) {
                    // The process was destroyed (stop) or the stream closed: end of the thread.
                }
            }.apply { isDaemon = true; name = "LogCapture-reader"; start() }
        } catch (e: Exception) {
            // Some ROMs may restrict logcat: degrade gracefully; the viewer can use dumpNow().
            proc = null
            readerThread = null
            _error.value = e.message ?: "logcat no disponible"
        }
    }

    /** Kills the logcat process and stops the stream. Keeps the buffer and the file. */
    @Synchronized
    fun stop() {
        proc?.destroy()
        proc = null
        readerThread = null
    }

    /** Empties the in-memory buffer and truncates the file. */
    fun clear() {
        synchronized(buf) {
            buf.clear()
            _flow.value = emptyList()
        }
        logFile?.delete()
    }

    /** Dumps the buffer as plain text (one `raw` line per entry). */
    fun exportText(): String = synchronized(buf) { buf.joinToString("\n") { it.raw } }

    /**
     * One-off snapshot of the process's own logcat without relying on the stream (the `-d` option).
     * Useful as a fallback if `start()` fails on the ROM.
     */
    fun dumpNow(): String = try {
        val pid = android.os.Process.myPid()
        Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"))
            .inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "logcat -d falló: ${e.message}"
    }

    // ── file persistence ──────────────────────────────────────────────────

    private fun appendToFile(e: Entry) {
        val file = logFile ?: return
        try {
            // Rotate if the file is getting too large: keep only the second half
            // of the in-memory buffer so we have at least some recent history.
            if (file.length() > MAX_FILE_BYTES) {
                val recent = synchronized(buf) {
                    buf.takeLast(buf.size / 2).joinToString("\n") { it.raw }
                }
                file.writeText(recent + "\n")
            }
            file.appendText(e.raw + "\n")
        } catch (_: Exception) {
            // Disk full or other I/O error — log is best-effort.
        }
    }

    private fun loadFromFile(file: File) {
        try {
            val lines = file.readLines()
            val entries = lines.mapNotNull { line ->
                if (line.isBlank()) null else parseLine(line)
            }
            synchronized(buf) {
                // Keep only the last CAP entries from the file.
                val toLoad = if (entries.size > CAP) entries.takeLast(CAP) else entries
                buf.addAll(toLoad)
                _flow.value = buf.toList()
            }
        } catch (_: Exception) {
            // Corrupt or missing file — start fresh.
        }
    }

    /**
     * Parses a `threadtime` line. Lines that do not match (continuations/stacktraces) are kept as
     * entries with the level/tag inherited from the last valid entry.
     */
    private var lastLevel = 'I'
    private var lastTag = ""
    private fun parseLine(line: String): Entry {
        val m = threadtimeRe.find(line)
        return if (m != null) {
            val level = m.groupValues[1].uppercase()[0]
            val tag = m.groupValues[2].trim()
            lastLevel = level
            lastTag = tag
            Entry(line, level, tag)
        } else {
            Entry(line, lastLevel, lastTag)
        }
    }
}
