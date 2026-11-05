// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/bridge/NativeLibrary.kt
package com.varuna.rustify.bridge

/**
 * How `libcore_engine` gets into the process, for both JVM targets.
 *
 * On Android it is `System.loadLibrary("core_engine")`, as it always was: the APK carries the `.so`
 * for its ABI and the system finds it. A desktop JVM has no such place — the library sits in the
 * packaged app's resources folder — so the desktop app names the file with [useLoader] **before**
 * the first call into [NativeEngine], and `loadLibrary` (which only searches `java.library.path`) is
 * never asked.
 *
 * Loaded once per process; the first caller wins and later ones are no-ops.
 */
object NativeLibrary {
    @Volatile
    private var loader: () -> Unit = { System.loadLibrary("core_engine") }

    @Volatile
    private var loaded = false

    /** Replaces how the library is loaded. Must run before anything touches [NativeEngine]. */
    fun useLoader(load: () -> Unit) {
        check(!loaded) { "core_engine is already loaded; set the loader before the first native call" }
        loader = load
    }

    @Synchronized
    fun load() {
        if (loaded) return
        loader()
        loaded = true
    }
}
