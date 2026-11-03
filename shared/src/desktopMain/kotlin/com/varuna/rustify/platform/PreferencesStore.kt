// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/PreferencesStore.kt
package com.varuna.rustify.platform

import kotlinx.coroutines.channels.awaitClose
import java.util.prefs.Preferences

/**
 * [KeyValueStore] over `java.util.prefs`: the registry on Windows, `~/.java/.userPrefs` on Linux,
 * a plist on macOS. Small values only — Preferences caps a value at 8 KiB, so anything that can grow
 * (a library cache, a queue) belongs in a file under [AppDirs.data], not here.
 */
class PreferencesStore(private val node: Preferences = Preferences.userRoot().node("com/varuna/rustify")) :
    KeyValueStore {
    override fun getString(key: String): String? = node.get(key, null)

    override fun putString(key: String, value: String?) {
        if (value == null) node.remove(key) else node.put(key, value)
        node.flush()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        node.putBoolean(key, value)
        node.flush()
    }

    override fun getLong(key: String, default: Long): Long = node.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        node.putLong(key, value)
        node.flush()
    }

    override fun remove(key: String) {
        node.remove(key)
        node.flush()
    }

    override fun contains(key: String): Boolean = node.get(key, null) != null

    // java.util.prefs tells listeners on its own event thread, after the write.
    override fun changes(key: String): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val listener = java.util.prefs.PreferenceChangeListener { if (it.key == key) trySend(Unit) }
        node.addPreferenceChangeListener(listener)
        awaitClose { node.removePreferenceChangeListener(listener) }
    }
}
