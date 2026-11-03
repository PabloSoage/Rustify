// shared/src/androidMain/kotlin/com/varuna/rustify/platform/SharedPreferencesStore.kt
package com.varuna.rustify.platform

import android.content.SharedPreferences

/**
 * [KeyValueStore] over an existing preferences file. Pass the one the setting already lives in
 * (`rustify_settings`, `rustify_spotify_prefs`…): the keys stay where they are and nothing migrates.
 */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    // Typed, as the values were written: SharedPreferences throws reading an int as a long.
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
}
