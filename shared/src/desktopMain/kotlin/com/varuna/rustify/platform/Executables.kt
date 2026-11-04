// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/Executables.kt
package com.varuna.rustify.platform

import java.io.File
import java.util.concurrent.TimeUnit

/** The programs the desktop app runs that the system provides (speech, `nvidia-smi`, `tar`…). */
object Executables {
    val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** [name] in a directory of `PATH`, as an absolute path; with `.exe` on Windows. */
    fun onPath(name: String, windows: Boolean = this.windows, path: String? = System.getenv("PATH")): String? {
        val names = if (windows) listOf("$name.exe", name) else listOf(name)
        return path.orEmpty().split(File.pathSeparatorChar).asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.asSequence().map { File(dir.trim('"'), it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /**
     * Runs [command] and answers its exit code and output (stderr included), or null when it could
     * not start or did not end within [timeoutSeconds] (then it is killed).
     */
    fun run(command: List<String>, timeoutSeconds: Long = 10, directory: File? = null): Pair<Int, String>? = runCatching {
        val p = ProcessBuilder(command).redirectErrorStream(true).apply { if (directory != null) directory(directory) }.start()
        p.outputStream.close()
        // Read on another thread, so a chatty program cannot fill the pipe and never end.
        val output = StringBuilder()
        val reader = Thread { runCatching { output.append(p.inputStream.bufferedReader().readText()) } }.apply {
            isDaemon = true
            start()
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        reader.join(2_000)
        p.exitValue() to synchronized(output) { output.toString() }
    }.getOrNull()
}
