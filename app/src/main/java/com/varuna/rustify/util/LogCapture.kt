package com.varuna.rustify.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * F1.B — Captura de logs in-app leyendo el logcat del PROPIO proceso (§4.B.1 del doc 40).
 *
 * Una app puede leer su propio logcat sin permisos especiales (filtrando por `--pid=<mi_pid>`).
 * Se arranca un proceso `logcat` de larga duración en modo stream, se lee su stdout en un hilo
 * daemon, se parsea cada línea a [Entry] y se encola en un buffer acotado expuesto por [flow].
 *
 * **Persistencia anti-crash:** cada entrada se apunta también a `filesDir/rustify_log.txt`.
 * Al arrancar, si el fichero existe (sesión anterior que crasheó), se carga en el buffer.
 * Así los logs sobreviven a un crash y están disponibles al reabrir la app.
 */
object LogCapture {

    /** Una entrada parseada de una línea de logcat. `level` = V/D/I/W/E/F, `tag` = etiqueta. */
    data class Entry(val raw: String, val level: Char, val tag: String)

    /** Tope del buffer circular en memoria. */
    private const val CAP = 3000
    /** Tope del fichero de persistencia (bytes). Al superarlo se rota. */
    private const val MAX_FILE_BYTES = 512_000L

    private val buf = ArrayDeque<Entry>(CAP)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow

    /** Último error de arranque (p.ej. logcat no disponible en la ROM), para avisar en el visor. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var proc: Process? = null
    private var readerThread: Thread? = null
    private var logFile: File? = null

    /** true si el stream continuo está activo. */
    val isCapturing: Boolean
        @Synchronized get() = proc != null

    // Formato threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: mensaje"
    private val threadtimeRe =
        Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEFvdiwef])\s+(.*?)\s*:\s?(.*)$""")

    /**
     * Inicializa la ruta del fichero de persistencia. Debe llamarse una vez antes de [start]
     * (p.ej. desde [android.app.Application.onCreate]). Idempotente si ya se llamó.
     */
    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "rustify_log.txt")
        }
    }

    /**
     * Arranca el stream continuo del logcat del propio proceso. No-op si ya está capturando.
     * Si el proceso anterior murió (crash de la app) limpia la referencia para poder rearrancar.
     *
     * Si existe un fichero de logs de una sesión anterior, lo carga en el buffer antes de
     * empezar a capturar, para que los logs del crash estén visibles.
     *
     * @param clearFirst si true, ejecuta `logcat -c` y trunca el fichero para empezar limpio.
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
                    // El proceso fue destruido (stop) o el stream se cerró: fin del hilo.
                }
            }.apply { isDaemon = true; name = "LogCapture-reader"; start() }
        } catch (e: Exception) {
            // Alguna ROM puede restringir logcat: se degrada; el visor puede usar dumpNow().
            proc = null
            readerThread = null
            _error.value = e.message ?: "logcat no disponible"
        }
    }

    /** Mata el proceso logcat y detiene el stream. Conserva el buffer y el fichero. */
    @Synchronized
    fun stop() {
        proc?.destroy()
        proc = null
        readerThread = null
    }

    /** Vacía el buffer en memoria y trunca el fichero. */
    fun clear() {
        synchronized(buf) {
            buf.clear()
            _flow.value = emptyList()
        }
        logFile?.delete()
    }

    /** Volcado del buffer como texto plano (una línea `raw` por entrada). */
    fun exportText(): String = synchronized(buf) { buf.joinToString("\n") { it.raw } }

    /**
     * Snapshot puntual del logcat del propio proceso sin depender del stream (opción `-d`).
     * Útil como fallback si `start()` falla en la ROM.
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
     * Parsea una línea `threadtime`. Las líneas que no casan (continuaciones/stacktraces) se
     * conservan como entradas con nivel/tag heredados de la última entrada válida.
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
