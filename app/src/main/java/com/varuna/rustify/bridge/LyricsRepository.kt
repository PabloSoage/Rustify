package com.varuna.rustify.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Represents a single synchronized lyric line.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * Lyrics result from LRCLIB.
 */
data class LyricsResult(
    val synced: List<LyricLine>,   // empty if no synced lyrics
    val plain: String?             // plain text fallback
)

object LyricsRepository {

    private val cache = mutableMapOf<String, LyricsResult?>()

    suspend fun getLyrics(
        trackId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? {
        val key = trackId
        if (cache.containsKey(key)) return cache[key]

        val result = fetchLyrics(artist, title, durationSec)
        cache[key] = result
        return result
    }

    private suspend fun fetchLyrics(
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val urlStr = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle&duration=$durationSec"

            val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Rustify/1.0 (Android)")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                parseResponse(body)
            } else if (conn.responseCode == 404) {
                // Try without duration as fallback
                fetchLyricsNoDuration(artist, title)
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("LyricsRepository", "Failed to fetch lyrics", e)
            null
        }
    }

    private suspend fun fetchLyricsNoDuration(artist: String, title: String): LyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val urlStr = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"

                val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Rustify/1.0 (Android)")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    parseResponse(body)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun parseResponse(body: String): LyricsResult? {
        return try {
            val json = JSONObject(body)
            val syncedLrc = json.optString("syncedLyrics", "")
            val plainLyrics = json.optString("plainLyrics", "").takeIf { it.isNotBlank() }

            val synced = if (syncedLrc.isNotBlank()) parseLrc(syncedLrc) else emptyList()
            LyricsResult(synced = synced, plain = plainLyrics)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse LRC format: [mm:ss.xx] lyric line
     */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})\]\s*(.*)$""")
        for (line in lrc.lines()) {
            val match = pattern.matchEntire(line.trim()) ?: continue
            val (mm, ss, cs, text) = match.destructured
            val minutes = mm.toLongOrNull() ?: continue
            val seconds = ss.toLongOrNull() ?: continue
            val centiseconds = cs.padEnd(3, '0').take(3).toLongOrNull() ?: continue
            val timeMs = minutes * 60_000 + seconds * 1_000 + centiseconds
            // Only add non-empty lines (or instrumental markers)
            lines.add(LyricLine(timeMs = timeMs, text = text.trim()))
        }
        return lines.sortedBy { it.timeMs }
    }

    fun clearCache() {
        cache.clear()
    }
}
