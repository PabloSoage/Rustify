// shared/src/iosMain/kotlin/com/varuna/rustify/platform/KeychainKeyValueStore.kt
package com.varuna.rustify.platform

import com.varuna.rustify.spotify.SpotifyLibrary

/**
 * The iOS Keychain, as the app reaches it. Implemented in Swift (`iosApp/Sources/KeychainStore.swift`)
 * and handed to `IosController` at launch: the Security framework's calls take and answer
 * `CFDictionary`, which is plain Swift and a page of bridging from Kotlin.
 *
 * None of these throw — a Swift implementation cannot throw into Kotlin. A failure reads as "not
 * there" ([secretFor]) or `false` ([storeSecret], [removeSecret]), and the caller falls back.
 */
interface SecretStorage {
    /** The value under [key]; null when there is none or the Keychain cannot be read. */
    fun secretFor(key: String): String?

    /** Stores [value] under [key], replacing what was there; true when it was stored. */
    fun storeSecret(key: String, value: String): Boolean

    /** True when [key] is gone afterwards, including when it was never there. */
    fun removeSecret(key: String): Boolean
}

/**
 * A [KeyValueStore] that keeps [secretKeys] in [secrets] (the Keychain) and everything else in
 * [plain] (`NSUserDefaults`). The iOS twin of the desktop's `SecureKeyValueStore`: callers ask it for a
 * key like any other.
 *
 * A secret is in [plain] only when it was written there before the Keychain was used, or when the
 * Keychain refused a write; either way [plain] holds the newest copy. So on creation every such
 * secret is moved over and removed from [plain] — the first run after an update moves the session
 * out of `NSUserDefaults` — and until a move succeeds, reads fall back to [plain].
 */
class KeychainKeyValueStore(
    private val secretKeys: Set<String>,
    private val secrets: SecretStorage,
    private val plain: KeyValueStore,
) : KeyValueStore by plain {

    init {
        for (key in secretKeys) {
            val legacy = plain.getString(key) ?: continue
            if (secrets.storeSecret(key, legacy)) {
                plain.remove(key)
            } else {
                Log.w(TAG, "Keychain refused $key; it stays in plain preferences for now")
            }
        }
    }

    override fun getString(key: String): String? {
        if (key !in secretKeys) return plain.getString(key)
        return secrets.secretFor(key) ?: plain.getString(key)
    }

    override fun putString(key: String, value: String?) {
        if (key !in secretKeys) return plain.putString(key, value)
        if (value == null) {
            remove(key)
        } else if (secrets.storeSecret(key, value)) {
            plain.remove(key)
        } else {
            Log.w(TAG, "Keychain refused $key; kept in plain preferences")
            plain.putString(key, value)
        }
    }

    override fun remove(key: String) {
        if (key in secretKeys && !secrets.removeSecret(key)) Log.w(TAG, "Keychain could not remove $key")
        plain.remove(key)
    }

    override fun contains(key: String): Boolean =
        if (key in secretKeys) getString(key) != null else plain.contains(key)

    companion object {
        private const val TAG = "KeychainKeyValueStore"

        /** The two the desktop keeps in its credential store too; the expiry stays in [plain]. */
        val SPOTIFY_SECRETS: Set<String> = setOf(SpotifyLibrary.KEY_SP_DC, SpotifyLibrary.KEY_ACCESS_TOKEN)
    }
}
