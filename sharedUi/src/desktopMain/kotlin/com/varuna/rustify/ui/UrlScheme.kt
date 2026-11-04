// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/UrlScheme.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `rustify://` opens this Rustify, where the installer could not say so.
 *
 * - **macOS**: the bundle's `Info.plist` declares the scheme (`CFBundleURLTypes`,
 *   desktopApp/build.gradle.kts) and the URL arrives through `Desktop.setOpenURIHandler`. Nothing to
 *   do here.
 * - **Windows**: Compose's DSL has nothing for URL schemes and jpackage only knows file extensions, so
 *   the MSI cannot write the keys. The app writes them for this user, under
 *   `HKCU\Software\Classes\rustify`, with `reg.exe import` — only when the scheme is not registered,
 *   or names a launcher that is gone (the app was reinstalled elsewhere). Uninstalling leaves them,
 *   pointing at nothing, until a Rustify starts again.
 * - **Linux**: jpackage's `.desktop` file has no `MimeType` and no `%u` (its template is fixed, and
 *   Compose clears the resource folder that could replace it), so the app adds a hidden entry of its
 *   own, `rustify-url-handler.desktop` in the user's applications folder, and makes it the handler with
 *   `xdg-mime` — again only when nothing handles `rustify://` yet or the entry is ours and stale. The
 *   AppImage's own `.desktop` declares the scheme as well (tools/desktop/make-appimage.sh).
 *
 * Only for a packaged launcher (jpackage's, or the AppImage), and never for a portable copy: carrying a
 * folder to a computer should not change what that computer does with links.
 */
object UrlScheme {
    private const val TAG = "UrlScheme"
    const val SCHEME = "rustify"
    const val LINUX_ENTRY = "rustify-url-handler.desktop"

    /** Registers the scheme for this user if it is not; slow (it runs tools), so off the UI thread. */
    fun registerIfNeeded() {
        if (AppDirs.portable) return
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val launcher = launcher() ?: return
        runCatching {
            when {
                os.contains("win") -> registerWindows(launcher)
                os.contains("mac") -> Unit
                else -> registerLinux(launcher)
            }
        }.onFailure { Log.w(TAG, "could not register $SCHEME:// for this user: ${it.message}") }
    }

    /**
     * What starts this Rustify: the AppImage file itself (its mount point changes every run), else
     * jpackage's launcher. `null` for a run from Gradle or the IDE, which is `java` and not ours.
     */
    private fun launcher(): File? {
        System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isFile) }
        return System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::isFile)
    }

    // --- Windows ---------------------------------------------------------------------------------

    private const val WINDOWS_KEY = "HKCU\\Software\\Classes\\$SCHEME"

    /** The `shell\open\command` value for [exe]: the browser substitutes the link for `%1`. */
    fun windowsCommand(exe: String): String = "\"$exe\" \"%1\""

    /**
     * The `.reg` file that registers [exe] for this user. UTF-16 is what `reg import` reads for a
     * "Version 5.00" file; backslashes and quotes are escaped as the format wants.
     */
    fun windowsRegFile(exe: String): String {
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val root = "HKEY_CURRENT_USER\\Software\\Classes\\$SCHEME"
        return listOf(
            "Windows Registry Editor Version 5.00",
            "",
            "[$root]",
            "@=" + quoted("URL:Rustify"),
            "\"URL Protocol\"=\"\"",
            "",
            "[$root\\DefaultIcon]",
            "@=" + quoted("\"$exe\",0"),
            "",
            "[$root\\shell\\open\\command]",
            "@=" + quoted(windowsCommand(exe)),
            "",
        ).joinToString("\r\n")
    }

    /**
     * The registered command's value out of `reg query … /ve`: the text after the type on the line
     * that has one. `(Default)` is translated per Windows language, the type name is not.
     */
    fun windowsRegisteredCommand(regQueryOutput: String): String? =
        regQueryOutput.lineSequence()
            .firstOrNull { "REG_SZ" in it || "REG_EXPAND_SZ" in it }
            ?.let { line -> line.substringAfter(if ("REG_EXPAND_SZ" in line) "REG_EXPAND_SZ" else "REG_SZ").trim() }
            ?.takeIf { it.isNotEmpty() }

    /** The program a registered command starts: its first, quoted, word. */
    fun windowsCommandProgram(command: String): String? =
        if (command.startsWith("\"")) command.drop(1).substringBefore('"').takeIf { it.isNotEmpty() }
        else command.substringBefore(' ').takeIf { it.isNotEmpty() }

    private fun registerWindows(launcher: File) {
        val exe = launcher.absolutePath
        val query = exec("reg", "query", "$WINDOWS_KEY\\shell\\open\\command", "/ve")
        val registered = query?.takeIf { it.first == 0 }?.second?.let { windowsRegisteredCommand(it) }
        if (registered != null) {
            if (registered == windowsCommand(exe)) return
            val program = windowsCommandProgram(registered)
            // Another Rustify that is still installed (a second copy) keeps it; one that is gone does not.
            if (program != null && File(program).isFile) {
                Log.i(TAG, "$SCHEME:// already opens $program; left as it is")
                return
            }
        }
        val reg = File.createTempFile("rustify-scheme", ".reg", AppDirs.cache)
        try {
            reg.writeText("﻿" + windowsRegFile(exe), Charsets.UTF_16LE)
            val result = exec("reg", "import", reg.absolutePath)
            if (result?.first == 0) Log.i(TAG, "$SCHEME:// now opens $exe (for this user)")
            else Log.w(TAG, "reg import answered ${result?.first}: ${result?.second?.trim()}")
        } finally {
            reg.delete()
        }
    }

    // --- Linux -----------------------------------------------------------------------------------

    /**
     * The hidden entry that opens `rustify://` links with [launcher], or `null` for a path the
     * `Exec` key cannot hold without escapes (quotes, `$`, backticks, backslashes, `%`, line breaks)
     * — rare enough to skip rather than get the escaping wrong.
     */
    fun linuxDesktopEntry(launcher: String): String? {
        if (launcher.any { it in "\"`$\\%\n\r" }) return null
        return listOf(
            "[Desktop Entry]",
            "Type=Application",
            "Name=Rustify",
            "Comment=Opens rustify:// links in Rustify",
            "Exec=\"$launcher\" %u",
            "Terminal=false",
            "NoDisplay=true",
            "MimeType=x-scheme-handler/$SCHEME;",
            "",
        ).joinToString("\n")
    }

    private fun registerLinux(launcher: File) {
        val entry = linuxDesktopEntry(launcher.absolutePath) ?: run {
            Log.w(TAG, "not registering $SCHEME://: the launcher's path needs escaping (${launcher.absolutePath})")
            return
        }
        val home = System.getProperty("user.home").orEmpty()
        val applications = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "applications")
        val file = File(applications, LINUX_ENTRY)
        val current = exec("xdg-mime", "query", "default", "x-scheme-handler/$SCHEME")
            ?.takeIf { it.first == 0 }?.second?.trim().orEmpty()
        if (current.isNotEmpty() && current != LINUX_ENTRY) {
            Log.i(TAG, "$SCHEME:// already opens with $current; left as it is")
            return
        }
        if (current == LINUX_ENTRY && file.isFile && file.readText() == entry) return
        applications.mkdirs()
        file.writeText(entry)
        val set = exec("xdg-mime", "default", LINUX_ENTRY, "x-scheme-handler/$SCHEME")
        // Refreshes the cache some desktops read MimeType from; absent on others, and then not needed.
        exec("update-desktop-database", applications.absolutePath)
        if (set?.first == 0) Log.i(TAG, "$SCHEME:// now opens ${launcher.absolutePath} (for this user)")
        else Log.w(TAG, "xdg-mime answered ${set?.first}: ${set?.second?.trim()}")
    }

    /** Runs a tool and answers its exit code and output, or `null` when it is not there or hangs. */
    private fun exec(vararg command: String): Pair<Int, String>? {
        val process = runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }.getOrNull() ?: return null
        return runCatching {
            process.outputStream.close()
            // What these tools print fits in the pipe, so waiting first cannot block them.
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.exitValue() to process.inputStream.bufferedReader().readText()
            }
        }.getOrNull()
    }
}
