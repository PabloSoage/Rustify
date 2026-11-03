// shared/src/commonMain/kotlin/com/varuna/rustify/platform/KeyValueStore.kt
package com.varuna.rustify.platform

/**
 * Settings, as one interface every platform implements once (docs 24 §3, "Ajustes").
 *
 * Today the Android app reads and writes `SharedPreferences` directly from dozens of files, each with
 * its own copy of the key name. That cannot move to shared code as it is, so this is the seam: shared
 * code takes a [KeyValueStore], Android passes [SharedPreferencesStore] over the same preferences file
 * it already uses (nothing is migrated, the keys stay where they are), the desktop a
 * `PreferencesStore`, iOS a `UserDefaultsStore`.
 *
 * Deliberately small: strings, booleans and longs are all the existing settings use. A JSON document
 * is a string.
 */
interface KeyValueStore {
    fun getString(key: String): String?

    /** `null` removes the key. */
    fun putString(key: String, value: String?)

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun getLong(key: String, default: Long): Long

    fun putLong(key: String, value: Long)

    fun remove(key: String)

    fun contains(key: String): Boolean

    // Ints and floats, which Android's settings also hold. A store that keeps types apart (Android's,
    // where an int read as a long throws) overrides these; for the rest a long or a string will do.
    fun getInt(key: String, default: Int): Int = getLong(key, default.toLong()).toInt()

    fun putInt(key: String, value: Int) = putLong(key, value.toLong())

    fun getFloat(key: String, default: Float): Float = getString(key)?.toFloatOrNull() ?: default

    fun putFloat(key: String, value: Float) = putString(key, value.toString())
}

/** In memory, for tests and for code that runs before the platform store exists. */
class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = key in values
}
