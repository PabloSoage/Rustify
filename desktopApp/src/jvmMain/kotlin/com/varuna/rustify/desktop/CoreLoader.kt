// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/CoreLoader.kt
package com.varuna.rustify.desktop

import com.varuna.rustify.bridge.NativeLibrary
import java.io.File

/**
 * Finds the core's library for [NativeLibrary] — before anything touches `NativeEngine`.
 *
 * In order:
 * 1. `RUSTIFY_CORE_LIB`, a path to the file, for trying a core built by hand.
 * 2. The packaged app's resources (`compose.application.resources.dir`), where the build copies it
 *    (desktopApp/build.gradle.kts). Also set by `./gradlew :desktopApp:run`.
 * 3. `core_engine/target/desktop/release/` under the working directory, for a run from the repo.
 *
 * None found is an error with every place it looked, rather than `UnsatisfiedLinkError: no
 * core_engine in java.library.path`, which names a place nobody put it.
 */
object CoreLoader {
    fun install() {
        NativeLibrary.useLoader {
            val name = System.mapLibraryName("core_engine")
            val candidates = buildList {
                System.getenv("RUSTIFY_CORE_LIB")?.let { add(File(it)) }
                System.getProperty("compose.application.resources.dir")?.let { add(File(it, name)) }
                add(File("core_engine/target/desktop/release", name))
                add(File("../core_engine/target/desktop/release", name))
            }
            val found = candidates.firstOrNull { it.isFile }
                ?: error("core_engine not found; looked at:\n" + candidates.joinToString("\n") { "  ${it.absolutePath}" })
            System.load(found.absolutePath)
        }
    }
}
