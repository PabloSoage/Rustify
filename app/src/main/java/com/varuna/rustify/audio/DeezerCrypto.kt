package com.varuna.rustify.audio

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E62 — Descifrado de streams de Deezer (esquema público de deemix, JCE puro).
 *
 * Los tracks van cifrados con **Blowfish/CBC/NoPadding** a "rayas" (stripes): el archivo se divide en
 * bloques de 2048 bytes y **solo se descifra 1 de cada 3** (bloques con índice ≡ 0 mod 3); el resto va
 * en claro. La **clave** se deriva del MD5 del track id (SNG_ID) combinado con un secreto fijo. **IV**
 * fijo `00..07`. Cada stripe es una operación CBC independiente (IV se reinicia por bloque).
 *
 * Todo el bytecount está alineado a 2048 desde el inicio del archivo, así que para *seek* basta con
 * alinear la posición hacia abajo al múltiplo de 2048 y saber el índice de bloque = offset/2048.
 */
object DeezerCrypto {
    private const val SECRET = "g4el58wc0zvf9na1"
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    const val CHUNK = 2048

    /** Clave Blowfish de 16 bytes para [sngId] (el track id de Deezer, como cadena ASCII). */
    fun blowfishKey(sngId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(sngId.toByteArray(Charsets.US_ASCII))
        val hex = md5.joinToString("") { "%02x".format(it) } // 32 chars hex
        val key = ByteArray(16)
        for (i in 0 until 16) key[i] = (hex[i].code xor hex[i + 16].code xor SECRET[i].code).toByte()
        return key
    }

    /** Descifra un stripe completo de 2048 bytes. */
    fun decryptChunk(key: ByteArray, chunk: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(IV))
        return cipher.doFinal(chunk)
    }

    /**
     * Aplica el patrón de rayas sobre [data] (que empieza en el bloque [startBlockIndex]): descifra
     * los bloques ≡ 0 mod 3 que sean de 2048 bytes; el último bloque parcial se deja tal cual.
     * Devuelve un nuevo array con el resultado en claro.
     */
    fun destripe(key: ByteArray, data: ByteArray, startBlockIndex: Long): ByteArray {
        val out = data.copyOf()
        var idx = startBlockIndex
        var off = 0
        while (off < out.size) {
            val len = minOf(CHUNK, out.size - off)
            if (idx % 3 == 0L && len == CHUNK) {
                val dec = decryptChunk(key, out.copyOfRange(off, off + CHUNK))
                System.arraycopy(dec, 0, out, off, CHUNK)
            }
            off += len; idx++
        }
        return out
    }
}
