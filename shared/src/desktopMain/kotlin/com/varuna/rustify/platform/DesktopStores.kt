// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/DesktopStores.kt
package com.varuna.rustify.platform

import java.io.File
import java.util.prefs.Preferences

/**
 * The desktop's settings stores, in one place so a portable copy ([AppDirs.portable]) keeps them in
 * its own folder instead of the user's (the registry on Windows, `~/.java` on Linux, a plist on
 * macOS). An installed copy reads the same `java.util.prefs` nodes it always did.
 */
object DesktopStores {
    /** The store called [name] (`rustify_settings`, …), or the root node when `null`. */
    fun named(name: String?): KeyValueStore =
        if (AppDirs.portable) {
            FileStore(File(AppDirs.data, "settings/${name ?: "root"}"))
        } else {
            val path = if (name == null) "com/varuna/rustify" else "com/varuna/rustify/$name"
            PreferencesStore(Preferences.userRoot().node(path))
        }
}
