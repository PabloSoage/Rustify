package com.varuna.rustify.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Deezer decryption exists twice: here, for the first play of a track that is not cached yet,
 * and in `core_engine/src/audio/deezer.rs`, which decrypts on the way into the stream cache so that
 * every play after the first is an ordinary file behind an ordinary URL.
 *
 * Two implementations of a cipher scheme is a drift risk, and the drift would be silent — a wrong
 * key does not throw, it produces noise. So they are pinned to the same vector, exactly as
 * `TrackRef` and `TrackId` are pinned to the same ids: the constant below is the one
 * `deezer.rs::VECTOR_KEY` asserts, byte for byte.
 */
class DeezerCryptoTest {

    private val vectorSngId = "3135556"

    /** md5("3135556") in hex, then `hex[i] xor hex[i+16] xor SECRET[i]`. */
    private val vectorKey = byteArrayOf(
        0x6c, 0x6c, 0x66, 0x6b, 0x39, 0x66, 0x2c, 0x37,
        0x65, 0x25, 0x75, 0x60, 0x3c, 0x64, 0x34, 0x39
    )

    @Test
    fun `the key matches the vector shared with the Rust side`() {
        assertArrayEquals(vectorKey, DeezerCrypto.blowfishKey(vectorSngId))
    }

    @Test
    fun `the key depends on the id and only on the id`() {
        assertArrayEquals(
            DeezerCrypto.blowfishKey("3135556"),
            DeezerCrypto.blowfishKey("3135556")
        )
        assertFalse(
            DeezerCrypto.blowfishKey("3135556").contentEquals(DeezerCrypto.blowfishKey("3135557"))
        )
    }

    @Test
    fun `a stripe round-trips through the cipher`() {
        // Encrypt the way the CDN would and assert the decryption undoes exactly that. The only
        // test here that would catch a wrong IV, a wrong mode or a wrong block order.
        val key = DeezerCrypto.blowfishKey(vectorSngId)
        val plain = ByteArray(DeezerCrypto.CHUNK) { (it % 251).toByte() }

        val cipher = javax.crypto.Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "Blowfish"),
            javax.crypto.spec.IvParameterSpec(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        )
        val encrypted = cipher.doFinal(plain)
        assertNotEquals("the vector has to actually be encrypted", plain.toList(), encrypted.toList())

        assertArrayEquals(plain, DeezerCrypto.decryptChunk(key, encrypted))
    }

    @Test
    fun `the stripe size is the one both sides agree on`() {
        // 2048 is not an implementation detail: every offset in the scheme is aligned to it, and a
        // different value on either side shifts which stripes get decrypted.
        assertEquals(2048, DeezerCrypto.CHUNK)
    }

    @Test
    fun `cache keys survive the trip through the filesystem unchanged in meaning`() {
        // `StreamRouting.forget` deletes a cache entry by name without crossing JNI, so its idea of
        // the filename has to be the Rust one. These are the cases `env::storage::sanitise_key`
        // pins on the other side.
        assertEquals("k.._.._etc_passwd", StreamRouting.sanitise("../../etc/passwd"))
        assertEquals("a_b_c", StreamRouting.sanitise("a/b\\c"))
        assertEquals("k", StreamRouting.sanitise(""))
        assertEquals("k.", StreamRouting.sanitise("."))
        assertEquals("k..", StreamRouting.sanitise(".."))
        // And the shapes real track ids actually have.
        assertEquals("ytm_dQw4w9WgXcQ", StreamRouting.sanitise("ytm:dQw4w9WgXcQ"))
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", StreamRouting.sanitise("4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun `two different track ids never share a cache entry`() {
        // The failure this prevents is one track playing another track's audio, which looks like a
        // matching bug and is not one.
        val ids = listOf(
            "ytm:dQw4w9WgXcQ",
            "ytm:dQw4w9WgXcR",
            "4cOdK2wGLETKBW3PvgPWqT",
            "spotify:local:A:B:Title:180"
        )
        val names = ids.map { StreamRouting.sanitise(it) }
        assertTrue("sanitising must not collapse distinct ids", names.toSet().size == ids.size)
    }
}
