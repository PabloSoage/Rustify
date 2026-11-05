// shared/src/wasmJsMain/kotlin/com/varuna/rustify/platform/ServerKeyValueStore.kt
package com.varuna.rustify.platform

import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.readAllText
import com.varuna.rustify.ui.writeAllText
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Settings on the web: one JSON file on the server per preferences name, so every browser that signs in
 * to the same server sees the same app (docs serie-4.x/28 §2.2). Read once, written through.
 */
class ServerKeyValueStore(private val file: AppFile) : KeyValueStore {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val values: MutableMap<String, String> = runCatching {
        if (file.fileExists()) RustifyJson.decodeFromString(serializer, file.readAllText()).toMutableMap() else mutableMapOf()
    }.getOrElse { mutableMapOf() }

    private fun save() = runCatching { file.writeAllText(RustifyJson.encodeToString(serializer, values)) }
        .onFailure { Log.w("ServerKeyValueStore", "could not save ${file.path}", it) }

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        save()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getLong(key: String, default: Long): Long = values[key]?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) = putString(key, value.toString())

    override fun remove(key: String) = putString(key, null)

    override fun contains(key: String): Boolean = key in values
}
