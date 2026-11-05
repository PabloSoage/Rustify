// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/SecretStore.kt
package com.varuna.rustify.platform

import com.varuna.rustify.bridge.NativeLibrary
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The system's credential store through the core (`core_engine/src/secrets.rs`, feature
 * `secure-store`): Credential Manager on Windows, Keychain on macOS, Secret Service on Linux.
 *
 * Every call throws [IllegalStateException] when there is no store to use — a core built without
 * the feature, a Linux session with no keyring running — so the caller can fall back knowingly.
 */
object SecretStore {
    private external fun getNative(key: String): String
    private external fun setNative(key: String, value: String): String
    private external fun removeNative(key: String): String

    fun get(key: String): String? {
        NativeLibrary.load()
        return when (val answer = RustifyJson.parseToJsonElement(getNative(key))) {
            JsonNull -> null
            is JsonPrimitive -> answer.content
            is JsonObject -> error(answer["error"]?.toString() ?: "credential store unavailable")
            else -> null
        }
    }

    fun set(key: String, value: String) = check(setNative(key, value))

    fun remove(key: String) = check(removeNative(key))

    private fun check(answer: String) {
        if (!answer.contains("\"success\":true")) error("credential store: $answer")
    }
}

/**
 * A [KeyValueStore] that keeps [secretKeys] in the system's credential store and everything else in
 * [plain]. Settings code does not change: it asks this for a key like any other.
 *
 * When there is no credential store (see [SecretStore]) the secrets go to [plain], as they did
 * before, and the log says so once. When there is one and [plain] still holds a secret from before,
 * it is moved over and removed from [plain] the first time it is read.
 */
class SecureKeyValueStore(
    private val secretKeys: Set<String>,
    private val plain: KeyValueStore,
) : KeyValueStore by plain {
    private val available: Boolean by lazy {
        runCatching { SecretStore.get(PROBE_KEY); true }
            .onFailure { Log.w(TAG, "no credential store; secrets stay in plain preferences", it) }
            .getOrDefault(false)
    }

    private fun secure(key: String) = key in secretKeys && available

    override fun getString(key: String): String? {
        if (!secure(key)) return plain.getString(key)
        val stored = runCatching { SecretStore.get(key) }.getOrNull()
        if (stored != null) return stored
        // Written before the credential store was used: move it there.
        val legacy = plain.getString(key) ?: return null
        if (runCatching { SecretStore.set(key, legacy) }.isSuccess) plain.remove(key)
        return legacy
    }

    override fun putString(key: String, value: String?) {
        if (!secure(key)) return plain.putString(key, value)
        val stored = runCatching {
            if (value == null) SecretStore.remove(key) else SecretStore.set(key, value)
        }
        if (stored.isSuccess) plain.remove(key) else plain.putString(key, value)
    }

    override fun remove(key: String) {
        if (secure(key)) runCatching { SecretStore.remove(key) }
        plain.remove(key)
    }

    override fun contains(key: String): Boolean = getString(key) != null || plain.contains(key)

    private companion object {
        const val TAG = "SecureKeyValueStore"
        /** Read once to learn whether a store answers at all; never written. */
        const val PROBE_KEY = "availability_probe"
    }
}
