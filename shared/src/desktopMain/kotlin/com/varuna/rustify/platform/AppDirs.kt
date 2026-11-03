// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/AppDirs.kt
package com.varuna.rustify.platform

import java.io.File

/**
 * Where the desktop app keeps its files, by each system's convention:
 *
 * - Windows: `%APPDATA%\Rustify` (data) and `%LOCALAPPDATA%\Rustify\cache`
 * - macOS:   `~/Library/Application Support/Rustify` and `~/Library/Caches/Rustify`
 * - Linux:   `$XDG_DATA_HOME/rustify` (`~/.local/share/rustify`) and `$XDG_CACHE_HOME/rustify`
 *
 * The core's storage (`initCacheDir`) goes under [data]: it holds the listening history, which is
 * not a cache and must not live where a cleaner may delete it.
 */
object AppDirs {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val home = File(System.getProperty("user.home").orEmpty())

    val data: File by lazy {
        when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Rustify")
            os.contains("mac") -> File(home, "Library/Application Support/Rustify")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "rustify")
        }.also { it.mkdirs() }
    }

    val cache: File by lazy {
        when {
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "Rustify/cache")
            os.contains("mac") -> File(home, "Library/Caches/Rustify")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "rustify")
        }.also { it.mkdirs() }
    }
}
