// shared/src/iosSimulatorArm64Test/kotlin/com/varuna/rustify/platform/KeychainKeyValueStoreTest.kt
package com.varuna.rustify.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Keychain in memory, which can be told to refuse every write. */
private class FakeKeychain(var refuse: Boolean = false) : SecretStorage {
    val values = mutableMapOf<String, String>()

    override fun secretFor(key: String): String? = values[key]

    override fun storeSecret(key: String, value: String): Boolean {
        if (refuse) return false
        values[key] = value
        return true
    }

    override fun removeSecret(key: String): Boolean {
        if (refuse) return false
        values.remove(key)
        return true
    }
}

class KeychainKeyValueStoreTest {
    private val secretKeys = setOf("sp_dc_cookie", "access_token")

    @Test
    fun secretsGoToTheKeychainAndTheRestStaysPlain() {
        val keychain = FakeKeychain()
        val plain = MemoryStore()
        val store = KeychainKeyValueStore(secretKeys, keychain, plain)

        store.putString("sp_dc_cookie", "cookie")
        store.putLong("expiration_timestamp", 42)

        assertEquals("cookie", keychain.values["sp_dc_cookie"])
        assertNull(plain.getString("sp_dc_cookie"))
        assertEquals("cookie", store.getString("sp_dc_cookie"))
        assertEquals(42L, plain.getLong("expiration_timestamp", 0))
    }

    @Test
    fun aSessionFromBeforeMovesToTheKeychainOnCreation() {
        val keychain = FakeKeychain()
        val plain = MemoryStore().apply {
            putString("sp_dc_cookie", "old cookie")
            putString("access_token", "old token")
            putLong("expiration_timestamp", 7)
        }

        val store = KeychainKeyValueStore(secretKeys, keychain, plain)

        assertEquals("old cookie", keychain.values["sp_dc_cookie"])
        assertEquals("old token", keychain.values["access_token"])
        assertFalse(plain.contains("sp_dc_cookie"))
        assertFalse(plain.contains("access_token"))
        assertEquals(7L, plain.getLong("expiration_timestamp", 0), "the expiry is not a secret")
        assertEquals("old cookie", store.getString("sp_dc_cookie"))
    }

    @Test
    fun aRefusedWriteIsKeptPlainAndMovedOnALaterRun() {
        val keychain = FakeKeychain(refuse = true)
        val plain = MemoryStore()
        KeychainKeyValueStore(secretKeys, keychain, plain).putString("access_token", "token")

        assertEquals("token", plain.getString("access_token"), "nothing is lost when the Keychain refuses")

        keychain.refuse = false
        val later = KeychainKeyValueStore(secretKeys, keychain, plain)

        assertEquals("token", keychain.values["access_token"])
        assertNull(plain.getString("access_token"))
        assertEquals("token", later.getString("access_token"))
    }

    @Test
    fun removingASecretClearsBothPlaces() {
        val keychain = FakeKeychain()
        val plain = MemoryStore()
        val store = KeychainKeyValueStore(secretKeys, keychain, plain)
        store.putString("sp_dc_cookie", "cookie")

        store.remove("sp_dc_cookie")

        assertFalse(store.contains("sp_dc_cookie"))
        assertTrue(keychain.values.isEmpty())
    }

    @Test
    fun writingNullRemovesTheSecret() {
        val keychain = FakeKeychain()
        val store = KeychainKeyValueStore(secretKeys, keychain, MemoryStore())
        store.putString("access_token", "token")

        store.putString("access_token", null)

        assertNull(store.getString("access_token"))
        assertTrue(keychain.values.isEmpty())
    }
}
