// shared/src/iosMain/kotlin/com/varuna/rustify/platform/UserDefaultsStore.kt
package com.varuna.rustify.platform

import platform.Foundation.NSUserDefaults

/** [KeyValueStore] over `NSUserDefaults`. */
class UserDefaultsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (contains(key)) defaults.boolForKey(key) else default

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun getLong(key: String, default: Long): Long =
        if (contains(key)) defaults.integerForKey(key) else default

    override fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null
}
