package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * E62 — Cliente Deezer basado en el esquema público de deemix (usa el **ARL del usuario**).
 *
 * Flujo: `deezer.getUserData` (ARL → api/license token) → track id por **ISRC** (o búsqueda) →
 * `song.getData` (TRACK_TOKEN) → `media.deezer.com/v1/get_url` (URL cifrada del CDN). El descifrado va
 * en [DeezerCrypto] (streaming en [DeezerDecryptingDataSource], descarga en [DeezerAudioSource]).
 */
class DeezerClient(private val http: OkHttpClient = AudioHttp.client) {

    data class Session(
        val apiToken: String, val licenseToken: String, val arl: String, val sid: String,
        // Derechos de streaming de la cuenta (de USER.OPTIONS de getUserData). Señal fiable de plan.
        val hq: Boolean = false, val lossless: Boolean = false
    )
    /** [url] = stream cifrado del CDN; [sngId] = track id de Deezer (clave Blowfish); [format] servido. */
    data class Media(val url: String, val sngId: String, val format: String)

    private val gw = "https://www.deezer.com/ajax/gw-light.php"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Rustify"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Autentica con un ARL. Devuelve null si el ARL no vale (checkForm vacío). */
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
                val res = JSONObject(r.body?.string() ?: return@runCatching null).optJSONObject("results")
                    ?: return@runCatching null
                val apiToken = res.optString("checkForm")
                if (apiToken.isBlank() || apiToken == "0") return@runCatching null // ARL inválido/caducado
                val opts = res.optJSONObject("USER")?.optJSONObject("OPTIONS")
                val license = opts?.optString("license_token") ?: ""
                // Cuenta gratis → web_hq/web_lossless = false (solo preview 30 s). Premium/HiFi → true.
                val hq = opts?.optBoolean("web_hq") == true || opts?.optBoolean("mobile_hq") == true
                val lossless = opts?.optBoolean("web_lossless") == true || opts?.optBoolean("mobile_lossless") == true
                Session(apiToken, license, arl, sid, hq, lossless)
            }
        }.getOrNull()
    }

    /** ¿Este ARL sirve? (auth OK). Para el tester de la fuente de ARLs. */
    suspend fun testArl(arl: String): Boolean = auth(arl) != null

    /** Resultado de comprobar un ARL a fondo: auth + si de verdad puede **reproducir** (no solo autenticar). */
    data class ArlCheck(val auth: Boolean, val canStream: Boolean, val detail: String)

    // Pista pública muy popular usada como "canario" para saber si el ARL tiene derechos de streaming
    // (Daft Punk — Harder, Better, Faster, Stronger). Auth OK pero get_url vacío ⇒ cuenta gratuita/sin HiFi.
    private val CANARY_SNG_ID = "3135556"

    /**
     * Comprobación real: muchos ARLs de webs públicas son de cuentas **gratuitas** → autentican (testArl
     * ✅) pero NO pueden reproducir pistas completas (Deezer solo da preview de 30 s), así que get_url
     * devuelve vacío y "Test playback" falla. Esto lo distingue: ✅ premium / ⚠️ gratis / ❌ inválido.
     */
    suspend fun checkArl(arl: String): ArlCheck {
        val session = auth(arl) ?: return ArlCheck(false, false, "auth failed (invalid/expired)")
        // Señal PRIMARIA y fiable: los derechos de la propia cuenta (no depende de la región/canario).
        if (session.lossless || session.hq) {
            val plan = if (session.lossless) "HiFi/FLAC" else "HQ/320"
            return ArlCheck(true, true, "premium ($plan)")
        }
        // Fallback: intenta un get_url real por si las OPTIONS no reflejasen el plan.
        val m = media(session, CANARY_SNG_ID, listOf("MP3_128", "MP3_320", "FLAC"))
        return if (m != null) ArlCheck(true, true, "premium (${m.format})")
               else ArlCheck(true, false, "auth OK, no stream rights (free?)")
    }

    /** track id de Deezer a partir de un track de Spotify: por ISRC (limpio) o búsqueda. */
    suspend fun deezerTrackId(track: FullTrack): String? = withContext(Dispatchers.IO) {
        val isrc = track.isrc
        if (isrc.isNotBlank()) {
            byIsrc(isrc)?.let { return@withContext it }
        }
        // Fallback: búsqueda nombre + artista, validando duración (±5 s).
        runCatching {
            val q = (track.name + " " + track.artists.joinToString(" ") { it.name }).trim()
            val url = "https://api.deezer.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=5"
            val body = http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching null
            val arr = JSONObject(body).optJSONArray("data") ?: return@runCatching null
            val targetSec = track.durationMs / 1000
            var best: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
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
            .use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val o = JSONObject(body)
        if (o.has("error")) null else o.optLong("id").takeIf { it > 0 }?.toString()
    }.getOrNull()

    /** TRACK_TOKEN vía song.getData. */
    private suspend fun trackToken(session: Session, deezerId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$gw?method=song.getData&input=3&api_version=1.0&api_token=${session.apiToken}"
            val payload = JSONObject().put("sng_id", deezerId).toString().toRequestBody(jsonType)
            val req = Request.Builder().url(url).post(payload)
                .header("Cookie", "arl=${session.arl}; sid=${session.sid}").header("User-Agent", ua).build()
            val res = http.newCall(req).execute().use {
                if (!it.isSuccessful) return@runCatching null
                JSONObject(it.body?.string() ?: return@runCatching null).optJSONObject("results")
            } ?: return@runCatching null
            res.optString("TRACK_TOKEN").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** URL cifrada del CDN + sngId, pidiendo los formatos en orden de preferencia (fallback interno). */
    suspend fun media(session: Session, deezerId: String, formats: List<String>): Media? = withContext(Dispatchers.IO) {
        runCatching {
            val token = trackToken(session, deezerId) ?: return@runCatching null
            val fmts = JSONArray()
            formats.forEach { fmts.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", it)) }
            val body = JSONObject()
                .put("license_token", session.licenseToken)
                .put("media", JSONArray().put(JSONObject().put("type", "FULL").put("formats", fmts)))
                .put("track_tokens", JSONArray().put(token))
                .toString().toRequestBody(jsonType)
            val req = Request.Builder().url("https://media.deezer.com/v1/get_url").post(body)
                .header("User-Agent", ua).build()
            val res = http.newCall(req).execute().use {
                if (!it.isSuccessful) return@runCatching null
                JSONObject(it.body?.string() ?: return@runCatching null)
            }
            val media = res.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("media")?.optJSONObject(0)
                ?: return@runCatching null
            val url = media.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
            val format = media.optString("format")
            if (url.isNullOrBlank()) null else Media(url, deezerId, format)
        }.getOrNull()
    }

    /** End-to-end: (ARL, track Spotify) → Media cifrada lista para descifrar. */
    suspend fun resolve(track: FullTrack, arl: String, formats: List<String>): Media? {
        val session = auth(arl) ?: return null
        val deezerId = deezerTrackId(track) ?: return null
        return media(session, deezerId, formats)
    }
}
