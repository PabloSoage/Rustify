package com.varuna.rustify.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * F1.B — Captura de logs in-app leyendo el logcat del PROPIO proceso (§4.B.1 del doc 40).
 *
 * Una app puede leer su propio logcat sin permisos especiales (filtrando por `--pid=<mi_pid>`).
 * Se arranca un proceso `logcat` de larga duración en modo stream, se lee su stdout en un hilo
 * daemon, se parsea cada línea a [Entry] y se encola en un buffer acotado expuesto por [flow].
 *
 * Ventaja: NO requiere migrar ninguno de los `android.util.Log.*` existentes; además captura los
 * logs de librerías (ExoPlayer/yt-dlp/JNI) que van a logcat de por sí.
 */
object LogCapture {

    /** Una entrada parseada de una línea de logcat. `level` = V/D/I/W/E/F, `tag` = etiqueta. */
    data class Entry(val raw: String, val level: Char, val tag: String)

    /** Tope del buffer circular en memoria. */
    private const val CAP = 3000

    private val buf = ArrayDeque<Entry>(CAP)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow

    /** Último error de arranque (p.ej. logcat no disponible en la ROM), para avisar en el visor. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var proc: Process? = null
    private var readerThread: Thread? = null

    /** true si el stream continuo está activo. */
    val isCapturing: Boolean
        @Synchronized get() = proc != null

    // Formato threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: mensaje"
    private val threadtimeRe =
        Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEFvdiwef])\s+(.*?)\s*:\s?(.*)$""")

    /**
     * Arranca el stream continuo del logcat del propio proceso. No-op si ya está capturando.
     * @param clearFirst si true, ejecuta `logcat -c` antes para empezar limpio.
     */
    @Synchronized
    fun start(clearFirst: Boolean = true) {
        if (proc != null) return
        _error.value = null
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

    /** Mata el proceso logcat y detiene el stream. Conserva el buffer. */
    @Synchronized
    fun stop() {
        proc?.destroy()
        proc = null
        readerThread = null
    }

    /** Vacía el buffer en memoria. */
    fun clear() {
        synchronized(buf) {
            buf.clear()
            _flow.value = emptyList()
        }
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
            // Continuación / línea no estándar: hereda nivel/tag previos.
            Entry(line, lastLevel, lastTag)
        }
    }
}
