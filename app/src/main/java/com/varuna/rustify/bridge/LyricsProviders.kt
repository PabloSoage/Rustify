package com.varuna.rustify.bridge

import android.content.Context
import com.varuna.rustify.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics providers (inspired by EeveeSpotify's set). The [LyricsRepository] tries the ENABLED
 * providers in the user-configured order (Settings) and returns the first non-empty result — so an
 * upper provider that resolves stops the chain. LRCLIB is enabled by default (current behaviour);
 * the rest are opt-in.
 *
 * ⚠️ The non-LRCLIB providers are **best-effort** (private/undocumented endpoints, scraping, or
 * rotating tokens) and are NOT tested here; on any failure they return null and the chain falls
 * through to the next provider.
 */
interface LyricsProvider {
    val id: String
    val displayNameRes: Int
    suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult?
}

internal object LyricsHttp {
    fun get(urlStr: String, headers: Map<String, String> = emptyMap()): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Rustify")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 10_000; readTimeout = 10_000
        }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    fun postForm(urlStr: String, form: Map<String, String>): String? = try {
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Rustify")
            connectTimeout = 10_000; readTimeout = 10_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }
}

/** Shared LRC parser: `[mm:ss.xx] text`. */
internal fun parseLrcText(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val pattern = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})]\s*(.*)$""")
    for (line in lrc.lines()) {
        val m = pattern.matchEntire(line.trim()) ?: continue
        val (mm, ss, cs, text) = m.destructured
        val min = mm.toLongOrNull() ?: continue
        val sec = ss.toLongOrNull() ?: continue
        val centi = cs.padEnd(3, '0').take(3).toLongOrNull() ?: continue
        lines.add(LyricLine(min * 60_000 + sec * 1_000 + centi, text.trim()))
    }
    return lines.sortedBy { it.timeMs }
}

private fun stripHtml(html: String): String =
    html.replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
        .replace("&gt;", ">").replace("&lt;", "<")
        .lines().joinToString("\n") { it.trim() }.trim()

// ── Providers ──────────────────────────────────────────────────────────────────────────

object LrcLibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayNameRes = R.string.lyrics_provider_lrclib
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        val a = URLEncoder.encode(artist, "UTF-8"); val t = URLEncoder.encode(title, "UTF-8")
        fun byGet(url: String) = LyricsHttp.get(url)?.let { parse(it) }
        (if (durationSec > 0) byGet("https://lrclib.net/api/get?artist_name=$a&track_name=$t&duration=$durationSec") else null)
            ?: byGet("https://lrclib.net/api/get?artist_name=$a&track_name=$t")
            ?: run {
                val body = LyricsHttp.get("https://lrclib.net/api/search?q=" + URLEncoder.encode("$artist $title", "UTF-8")) ?: return@run null
                runCatching { JSONArray(body).takeIf { it.length() > 0 }?.getJSONObject(0)?.toString()?.let { parse(it) } }.getOrNull()
            }
    }
    private fun parse(body: String): LyricsResult? = runCatching {
        val j = JSONObject(body)
        val synced = j.optString("syncedLyrics", "").let { if (it.isNotBlank()) parseLrcText(it) else emptyList() }
        val plain = j.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        if (synced.isEmpty() && plain == null) null else LyricsResult(synced, plain)
    }.getOrNull()
}

object MusixmatchProvider : LyricsProvider {
    override val id = "musixmatch"
    override val displayNameRes = R.string.lyrics_provider_musixmatch
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val base = "https://apic-desktop.musixmatch.com/ws/1.1"
            val common = "app_id=web-desktop-app-v1.0&format=json"
            val cookie = mapOf("Cookie" to "x-mxm-token-guid=")
            val tokenJson = LyricsHttp.get("$base/token.get?$common", cookie) ?: return@runCatching null
            val userToken = JSONObject(tokenJson).optJSONObject("message")?.optJSONObject("body")?.optString("user_token")
                ?.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") } ?: return@runCatching null
            val q = "q_artist=${URLEncoder.encode(artist, "UTF-8")}&q_track=${URLEncoder.encode(title, "UTF-8")}"
            val body = LyricsHttp.get("$base/macro.subtitles.get?$common&$q&usertoken=$userToken&subtitle_format=lrc", cookie) ?: return@runCatching null
            val macro = JSONObject(body).optJSONObject("message")?.optJSONObject("body")?.optJSONObject("macro_calls")
            val subtitle = macro?.optJSONObject("track.subtitles.get")?.optJSONObject("message")?.optJSONObject("body")
                ?.optJSONArray("subtitle_list")?.optJSONObject(0)?.optJSONObject("subtitle")?.optString("subtitle_body")
            if (!subtitle.isNullOrBlank()) LyricsResult(parseLrcText(subtitle), null)
            else macro?.optJSONObject("track.lyrics.get")?.optJSONObject("message")?.optJSONObject("body")
                ?.optJSONObject("lyrics")?.optString("lyrics_body")?.takeIf { it.isNotBlank() }
                ?.let { LyricsResult(emptyList(), it) }
        }.getOrNull()
    }
}

object GeniusProvider : LyricsProvider {
    override val id = "genius"
    override val displayNameRes = R.string.lyrics_provider_genius
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val search = LyricsHttp.get("https://genius.com/api/search/song?q=$q") ?: return@runCatching null
            val secs = JSONObject(search).optJSONObject("response")?.optJSONArray("sections") ?: return@runCatching null
            val url = (0 until secs.length()).firstNotNullOfOrNull { i ->
                secs.getJSONObject(i).optJSONArray("hits")?.optJSONObject(0)?.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
            } ?: return@runCatching null
            val html = LyricsHttp.get(url) ?: return@runCatching null
            val blocks = Regex("<div[^>]*data-lyrics-container=\"true\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).joinToString("\n") { it.groupValues[1] }
            stripHtml(blocks).takeIf { it.isNotBlank() }?.let { LyricsResult(emptyList(), it) }
        }.getOrNull()
    }
}

object PetitLyricsProvider : LyricsProvider {
    override val id = "petitlyrics"
    override val displayNameRes = R.string.lyrics_provider_petitlyrics
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val xml = LyricsHttp.postForm("https://petitlyrics.com/api/GetPetitLyricsData/", mapOf(
                "key_title" to title, "key_artist" to artist, "lyricsType" to "1", "clientAppId" to "p1110417", "terminalType" to "10"
            )) ?: return@runCatching null
            val b64 = Regex("<lyricsData>(.*?)</lyricsData>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            val decoded = b64?.takeIf { it.isNotBlank() }?.let {
                runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull()
            } ?: return@runCatching null
            if (decoded.isBlank()) return@runCatching null
            val synced = parseLrcText(decoded)
            if (synced.isNotEmpty()) LyricsResult(synced, null) else LyricsResult(emptyList(), decoded)
        }.getOrNull()
    }
}

object LyricsProviders {
    val ALL: List<LyricsProvider> = listOf(LrcLibProvider, MusixmatchProvider, GeniusProvider, PetitLyricsProvider)
    fun byId(id: String): LyricsProvider? = ALL.firstOrNull { it.id == id }
}

/** Persisted provider order + enabled flags (mirrors AudioBackendSettings). */
object LyricsSettings {
    private const val PREFS = "rustify_settings"
    private const val KEY = "lyrics_provider_order"
    data class ProviderEntry(val id: String, val enabled: Boolean)

    private val DEFAULT = listOf(
        ProviderEntry("lrclib", true),
        ProviderEntry("musixmatch", false),
        ProviderEntry("genius", false),
        ProviderEntry("petitlyrics", false),
    )

    fun load(context: Context): List<ProviderEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return DEFAULT
        val parsed = raw.split(",").mapNotNull {
            val p = it.split(":")
            if (p.size == 2 && LyricsProviders.byId(p[0]) != null) ProviderEntry(p[0], p[1] == "1") else null
        }
        val known = parsed.map { it.id }.toSet()
        return (parsed + DEFAULT.filter { it.id !in known }).ifEmpty { DEFAULT }
    }

    fun save(context: Context, entries: List<ProviderEntry>) {
        val raw = entries.joinToString(",") { "${it.id}:${if (it.enabled) 1 else 0}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}
