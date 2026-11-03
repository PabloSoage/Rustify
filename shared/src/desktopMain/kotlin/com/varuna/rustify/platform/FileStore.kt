// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/FileStore.kt
package com.varuna.rustify.platform

import java.io.File

/**
 * [KeyValueStore] with one file per key under [dir], for values that outgrow [PreferencesStore]'s
 * 8 KiB — a saved queue of a hundred songs is several times that.
 *
 * Writes go to a temporary beside the file and are renamed over it, the same rule the core keeps for
 * its own storage: a crash mid-write leaves the previous value, never half of the new one.
 */
class FileStore(private val dir: File) : KeyValueStore {
    init {
        dir.mkdirs()
    }

    /** Keys are ours and simple; anything else is mapped to a safe file name rather than trusted. */
    private fun fileOf(key: String): File = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override fun getString(key: String): String? = fileOf(key).takeIf { it.isFile }?.readText()

    override fun putString(key: String, value: String?) {
        val target = fileOf(key)
        if (value == null) {
            target.delete()
            return
        }
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(target)) {
            // Windows will not rename over an existing file.
            target.delete()
            if (!tmp.renameTo(target)) error("could not replace ${target.absolutePath}")
        }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getLong(key: String, default: Long): Long = getString(key)?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) = putString(key, value.toString())

    override fun remove(key: String) {
        fileOf(key).delete()
    }

    override fun contains(key: String): Boolean = fileOf(key).isFile
}
