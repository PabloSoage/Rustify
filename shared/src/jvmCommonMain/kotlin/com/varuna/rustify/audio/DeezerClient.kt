package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.optArray
import com.varuna.rustify.bridge.optBoolean
import com.varuna.rustify.bridge.optInt
import com.varuna.rustify.bridge.optLong
import com.varuna.rustify.bridge.optObject
import com.varuna.rustify.bridge.optString
import com.varuna.rustify.bridge.parseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Deezer client based on the public deemix scheme (uses the user's ARL).
 *
 * Flow: `deezer.getUserData` (ARL -> api/license token) -> track id by ISRC (or search) ->
 * `song.getData` (TRACK_TOKEN) -> `media.deezer.com/v1/get_url` (encrypted CDN URL). Decryption lives
 * in the Rust core (`core_engine/src/audio/deezer.rs`), reached through the local player server —
 * see [DeezerAudioSource] and `docs/stremio-core/DEEZER-CRYPTO.md`.
 */
class DeezerClient(private val http: OkHttpClient = AudioHttp.client) {

    data class Session(
        val apiToken: String, val licenseToken: String, val arl: String, val sid: String,
        // Account streaming rights (from USER.OPTIONS in getUserData). Reliable signal of the plan.
        val hq: Boolean = false, val lossless: Boolean = false
    )
    /** [url] = encrypted CDN stream; [sngId] = Deezer track id (Blowfish key); [format] served. */
    data class Media(val url: String, val sngId: String, val format: String)

    private val gw = "https://www.deezer.com/ajax/gw-light.php"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Rustify"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Authenticates with an ARL. Returns null if the ARL is invalid (empty checkForm). */
    suspend fun auth(arl: String): Session? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$gw?method=deezer.getUserData&input=3&api_version=1.0&api_token="
            val req = Request.Builder().url(url)
                .header("Cookie", "arl=$arl").header("User-Agent", ua).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val sid = r.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                    Regex("sid=([^;]+)").find(c)?.groupValues?.get(1)
                } ?: ""
                val res = parseJsonObject(r.body.string())?.optObject("results")
                    ?: return@runCatching null
                val apiToken = res.optString("checkForm")
                if (apiToken.isBlank() || apiToken == "0") return@runCatching null // invalid/expired ARL
                val opts = res.optObject("USER")?.optObject("OPTIONS")
                val license = opts?.optString("license_token") ?: ""
                // Free account -> web_hq/web_lossless = false (30s preview only). Premium/HiFi -> true.
                val hq = opts?.optBoolean("web_hq") == true || opts?.optBoolean("mobile_hq") == true
                val lossless = opts?.optBoolean("web_lossless") == true || opts?.optBoolean("mobile_lossless") == true
                Session(apiToken, license, arl, sid, hq, lossless)
            }
        }.getOrNull()
    }

    /** Does this ARL work? (auth OK). Used by the ARL source tester. */
    suspend fun testArl(arl: String): Boolean = auth(arl) != null

    /** Result of a thorough ARL check: auth + whether it can actually play (not just authenticate). */
    data class ArlCheck(val auth: Boolean, val canStream: Boolean, val detail: String)

    // A very popular public track used as a canary to check whether the ARL has streaming rights
    // (Daft Punk — Harder, Better, Faster, Stronger). Auth OK but empty get_url => free/non-HiFi account.
    private val canarySngId = "3135556"

    /**
     * Real check: many ARLs from public sites belong to free accounts -> they authenticate (testArl
     * succeeds) but cannot play full tracks (Deezer only serves a 30s preview), so get_url returns
     * empty and "Test playback" fails. This distinguishes: premium / free / invalid.
     */
    suspend fun checkArl(arl: String): ArlCheck {
        val session = auth(arl) ?: return ArlCheck(auth = false, canStream = false, detail = "auth failed (invalid/expired)")
        // Primary, reliable signal: the account's own rights (independent of region/canary).
        if (session.lossless || session.hq) {
            val plan = if (session.lossless) "HiFi/FLAC" else "HQ/320"
            return ArlCheck(auth = true, canStream = true, detail = "premium ($plan)")
        }
        // Fallback: try a real get_url in case OPTIONS did not reflect the plan.
        val m = media(session, canarySngId, listOf("MP3_128", "MP3_320", "FLAC"))
        return if (m != null) ArlCheck(auth = true, canStream = true, detail = "premium (${m.format})")
               else ArlCheck(auth = true, canStream = false, detail = "auth OK, no stream rights (free?)")
    }

    /** Deezer track id from a Spotify track: by ISRC (clean) or search. */
    suspend fun deezerTrackId(track: FullTrack): String? = withContext(Dispatchers.IO) {
        val isrc = track.isrc
        if (isrc.isNotBlank()) {
            byIsrc(isrc)?.let { return@withContext it }
        }
        // Fallback: search by name + artist, validating duration (±5s).
        runCatching {
            val q = (track.name + " " + track.artists.joinToString(" ") { it.name }).trim()
            val url = "https://api.deezer.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=5"
            val body = http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute()
                .use { if (it.isSuccessful) it.body.string() else null } ?: return@runCatching null
            val arr = parseJsonObject(body)?.optArray("data") ?: return@runCatching null
            val targetSec = track.durationMs / 1000
            var best: String? = null
            for (i in arr.indices) {
                val o = arr.optObject(i) ?: continue
                val dur = o.optInt("duration")
                if (targetSec <= 0 || kotlin.math.abs(dur - targetSec) <= 5) { best = o.optLong("id").toString(); break }
                if (best == null) best = o.optLong("id").toString()
            }
            best
        }.getOrNull()
    }

    private fun byIsrc(isrc: String): String? = runCatching {
        val url = "https://api.deezer.com/track/isrc:$isrc"
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute()
            .use { if (it.isSuccessful) it.body.string() else null } ?: return null
        val o = parseJsonObject(body) ?: error("not a JSON object")
        if (o.containsKey("error")) null else o.optLong("id").takeIf { it > 0 }?.toString()
    }.getOrNull()

    /** TRACK_TOKEN via song.getData. */
    private suspend fun trackToken(session: Session, deezerId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$gw?method=song.getData&input=3&api_version=1.0&api_token=${session.apiToken}"
            val payload = buildJsonObject { put("sng_id", deezerId) }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url(url).post(payload)
                .header("Cookie", "arl=${session.arl}; sid=${session.sid}").header("User-Agent", ua).build()
            val res = http.newCall(req).execute().use {
                if (!it.isSuccessful) return@runCatching null
                (parseJsonObject(it.body.string()) ?: error("not a JSON object")).optObject("results")
            } ?: return@runCatching null
            res.optString("TRACK_TOKEN").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Encrypted CDN URL + sngId, requesting the formats in preference order (internal fallback). */
    suspend fun media(session: Session, deezerId: String, formats: List<String>): Media? = withContext(Dispatchers.IO) {
        runCatching {
            val token = trackToken(session, deezerId) ?: return@runCatching null
            val body = buildJsonObject {
                put("license_token", session.licenseToken)
                putJsonArray("media") {
                    add(buildJsonObject {
                        put("type", "FULL")
                        put("formats", JsonArray(formats.map { f ->
                            buildJsonObject { put("cipher", "BF_CBC_STRIPE"); put("format", f) }
                        }))
                    })
                }
                put("track_tokens", JsonArray(listOf(JsonPrimitive(token))))
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("https://media.deezer.com/v1/get_url").post(body)
                .header("User-Agent", ua).build()
            val res = http.newCall(req).execute().use {
                if (!it.isSuccessful) return@runCatching null
                parseJsonObject(it.body.string()) ?: error("not a JSON object")
            }
            val media = res.optArray("data")?.optObject(0)?.optArray("media")?.optObject(0)
                ?: return@runCatching null
            val url = media.optArray("sources")?.optObject(0)?.optString("url")
            val format = media.optString("format")
            if (url.isNullOrBlank()) null else Media(url, deezerId, format)
        }.getOrNull()
    }

    /** End-to-end: (ARL, Spotify track) -> encrypted Media ready to decrypt. */
    suspend fun resolve(track: FullTrack, arl: String, formats: List<String>): Media? {
        val session = auth(arl) ?: return null
        val deezerId = deezerTrackId(track) ?: return null
        return media(session, deezerId, formats)
    }
}
