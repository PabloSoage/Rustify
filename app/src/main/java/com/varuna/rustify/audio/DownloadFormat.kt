package com.varuna.rustify.audio

import android.content.Context
import java.io.File

/**
 * What a downloaded file is, read from its first bytes, and whether yt-dlp should convert to MP3.
 *
 * Until 4.9 every download was saved as `Artist - Title.mp3` with type `audio/mpeg`, whatever it
 * held: a Deezer FLAC, an Invidious webm, an add-on's m4a, and yt-dlp's own fallback when the MP3
 * conversion failed. Players that trust the extension refused them or played them as noise. The
 * name now comes from the bytes, so every provider is covered without each one having to report
 * its format.
 *
 * And yt-dlp no longer converts by default. YouTube Music serves Opus at ~130–160 kbps; turning it
 * into a 320 kbps MP3 made the file twice as large and could only lose quality, never gain it.
 * MP3 stays as an option for players that read nothing else.
 */
object DownloadFormat {
    data class Kind(val extension: String, val mime: String)

    val FLAC = Kind("flac", "audio/flac")
    val OPUS = Kind("opus", "audio/ogg")
    val OGG = Kind("ogg", "audio/ogg")
    val M4A = Kind("m4a", "audio/mp4")
    val WEBM = Kind("webm", "audio/webm")
    val WAV = Kind("wav", "audio/wav")
    val MP3 = Kind("mp3", "audio/mpeg")

    /** Enough for every signature below, the Opus one included (it sits in the first Ogg page). */
    const val HEADER_BYTES = 64

    /** The format of a file from its header; null when nothing matches. */
    fun sniff(header: ByteArray): Kind? {
        fun at(offset: Int, ascii: String) =
            header.size >= offset + ascii.length &&
                ascii.indices.all { header[offset + it] == ascii[it].code.toByte() }
        return when {
            at(0, "fLaC") -> FLAC
            at(0, "OggS") -> if (containsAscii(header, "OpusHead")) OPUS else OGG
            at(4, "ftyp") -> M4A
            header.size >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> WEBM
            at(0, "RIFF") && at(8, "WAVE") -> WAV
            at(0, "ID3") -> MP3
            // A bare MPEG audio frame: 11 sync bits.
            header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> MP3
            else -> null
        }
    }

    fun sniff(file: File): Kind? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(HEADER_BYTES)
            val n = input.read(buf)
            if (n <= 0) null else sniff(buf.copyOf(n))
        }
    }.getOrNull()

    private fun containsAscii(bytes: ByteArray, ascii: String): Boolean {
        val needle = ascii.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private const val KEY_MP3 = "download_convert_mp3"

    private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

    /** Off by default: keep what the source serves. */
    fun convertToMp3(context: Context): Boolean = prefs(context).getBoolean(KEY_MP3, false)
    fun setConvertToMp3(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_MP3, on).apply()
}
