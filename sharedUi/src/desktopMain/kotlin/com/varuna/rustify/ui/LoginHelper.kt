// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/LoginHelper.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val TAG = "LoginHelper"

/** How a sign-in in the Spotify window ended. */
internal sealed interface LoginOutcome {
    data class SignedIn(val spDc: String) : LoginOutcome

    /** The window was closed, or the sign-in was stopped from here. */
    data object Closed : LoginOutcome

    /** It never got as far as a window, or answered something it should not have. */
    data class Failed(val reason: String) : LoginOutcome
}

/**
 * `rustify-login` (desktopApp/login): the Spotify login page in the system's own webview, in a
 * process of its own so its event loop never meets AWT's. It answers one line on standard output —
 * `{"sp_dc":"…"}` and exit 0, nothing and exit 1 when the window is closed, `{"error":"…"}` and exit
 * 2 when it cannot start — and with `--watch-stdin` it closes when its standard input does, which is
 * how [LoginSession.stop] asks and why it does not outlive the app.
 */
internal object LoginHelper {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val fileName = if (windows) "rustify-login.exe" else "rustify-login"

    /**
     * The helper, found the way `CoreLoader` finds the core:
     * 1. `RUSTIFY_LOGIN_HELPER`, a path to the file, for trying one built by hand.
     * 2. The app's resources (`compose.application.resources.dir`), where the build copies it next
     *    to the core (desktopApp/build.gradle.kts). Also set by `./gradlew :desktopApp:run`.
     * 3. The build's own output, for a run from the repository.
     *
     * `null` when there is none (a build with `-Prustify.loginHelper=false`): the pasted cookie is
     * then the only way in.
     */
    fun locate(): File? {
        val candidates = buildList {
            System.getenv("RUSTIFY_LOGIN_HELPER")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, fileName)) }
            add(File("desktopApp/build/login-target/release", fileName))
            add(File("build/login-target/release", fileName))
        }
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * [file], runnable. A package that lost the executable bit on the way gets it back if the folder
     * allows; a read-only install runs a copy from the cache instead.
     */
    fun runnable(file: File): File {
        if (windows || file.canExecute()) return file
        if (file.setExecutable(true) && file.canExecute()) return file
        Log.w(TAG, "${file.absolutePath} is not executable; running a copy from the cache")
        val copy = File(AppDirs.cache, fileName)
        file.copyTo(copy, overwrite = true)
        if (!copy.setExecutable(true) || !copy.canExecute()) throw IOException("${copy.absolutePath} cannot be made executable")
        return copy
    }

    /** The helper's one line, read: what it answered and how it ended. */
    fun outcome(line: String?, exitCode: Int?): LoginOutcome {
        val answer = line?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
        val spDc = (answer?.get("sp_dc") as? JsonPrimitive)?.contentOrNull
        if (!spDc.isNullOrBlank()) return LoginOutcome.SignedIn(spDc)
        val error = (answer?.get("error") as? JsonPrimitive)?.contentOrNull
        return when {
            error != null -> LoginOutcome.Failed(error)
            line == null && exitCode == 1 -> LoginOutcome.Closed
            line != null -> LoginOutcome.Failed("unexpected answer: ${line.take(200)}")
            exitCode == null -> LoginOutcome.Failed("it did not exit")
            else -> LoginOutcome.Failed("it exited with code $exitCode")
        }
    }
}

/**
 * One run of the helper: [await] starts it and waits for its answer, [stop] closes it. [stop] may come
 * first, from any thread, and more than once.
 */
internal class LoginSession(private val helper: File) {
    private val lock = Any()
    private var process: Process? = null
    private var stopped = false

    /** Blocks an IO thread, not the caller's, until the helper answers or ends. */
    suspend fun await(): LoginOutcome = withContext(Dispatchers.IO) {
        val p = try {
            synchronized(lock) {
                if (stopped) return@withContext LoginOutcome.Closed
                ProcessBuilder(LoginHelper.runnable(helper).absolutePath, "--watch-stdin")
                    // WebKitGTK and WebView2 talk on stderr; to the app's, not into a pipe nobody empties.
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .also { process = it }
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not start ${helper.absolutePath}", e)
            return@withContext LoginOutcome.Failed(e.message ?: e.toString())
        }
        val line = try {
            p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        } catch (e: IOException) {
            null
        }
        // An answer with the cookie is the whole answer; the helper tidies up and exits on its own.
        LoginHelper.outcome(line, null).takeIf { it is LoginOutcome.SignedIn }?.let { return@withContext it }
        val exitCode = if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else null
        if (synchronized(lock) { stopped }) return@withContext LoginOutcome.Closed
        LoginHelper.outcome(line, exitCode).also {
            if (it is LoginOutcome.Failed) Log.w(TAG, "the Spotify window failed: ${it.reason}")
        }
    }

    /**
     * Asks the helper to close (its standard input ends) and kills it if it has not within a few
     * seconds. Never blocks the caller: the composition calls this when the screen goes away.
     */
    fun stop() {
        val p = synchronized(lock) {
            stopped = true
            process
        } ?: return
        if (!p.isAlive) return
        runCatching { p.outputStream.close() }
        thread(isDaemon = true, name = "rustify-login-stop") {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }
}
