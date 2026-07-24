package com.varuna.rustify.audio

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deezer stream decryption (public deemix scheme, pure JCE).
 *
 * Tracks are encrypted with Blowfish/CBC/NoPadding in stripes: the file is split into 2048-byte
 * blocks and only 1 of every 3 is decrypted (blocks with index ≡ 0 mod 3); the rest is cleartext.
 * The key is derived from the MD5 of the track id (SNG_ID) combined with a fixed secret. The IV is a
 * fixed `00..07`. Each stripe is an independent CBC operation (the IV resets per block).
 *
 * All byte counts are aligned to 2048 from the start of the file, so to seek it is enough to align the
 * position down to the nearest multiple of 2048; the block index is offset/2048.
 */
object DeezerCrypto {
    private const val SECRET = "g4el58wc0zvf9na1"
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    const val CHUNK = 2048

    /** 16-byte Blowfish key for [sngId] (the Deezer track id, as an ASCII string). */
    fun blowfishKey(sngId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(sngId.toByteArray(Charsets.US_ASCII))
        val hex = md5.joinToString("") { "%02x".format(it) } // 32 chars hex
        val key = ByteArray(16)
        for (i in 0 until 16) key[i] = (hex[i].code xor hex[i + 16].code xor SECRET[i].code).toByte()
        return key
    }

    /** Decrypts a full 2048-byte stripe. */
    fun decryptChunk(key: ByteArray, chunk: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(IV))
        return cipher.doFinal(chunk)
    }

}
