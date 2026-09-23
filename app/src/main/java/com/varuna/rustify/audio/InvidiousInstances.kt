package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Discovery and health of Invidious instances.
 *
 * Public list: `https://api.invidious.io/instances.json` (uptime from uptime.invidious.io). Each
 * element is `[host, {uri, type, api, monitor{...}}]`. `type` ∈ https | onion | i2p | ygg. We cache
 * the list and run our own health-check (a short GET) because that is the reliable signal.
 */
object InvidiousInstances {
    private const val PREFS = "rustify_settings"
    private const val K_CACHE = "inv_instances_cache"
    private const val K_CACHE_TS = "inv_instances_cache_ts"
    private const val LIST_URL = "https://api.invidious.io/instances.json?pretty=0"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /** [type]: "https" (clearnet) | "onion" (Tor) | "i2p" | "ygg". [health] = uptime % (0..100) or null. */
    data class Instance(
        val baseUrl: String,     // e.g. "https://inv.nadeko.net"
        val type: String,
        val apiUp: Boolean,
        val health: Double?,
        val custom: Boolean = false
    ) {
        val isAnon: Boolean get() = type == "onion" || type == "i2p" || type == "ygg"
    }

    // Fallback bootstrap list of public clearnet instances, used ONLY when the directory
    // (api.invidious.io/instances.json, frequently down) can't be fetched and there's no cache. Without
    // this, a dead directory leaves `selected()` empty and the Invidious backend silently never
    // resolves. These are best-effort defaults; the user can override them in Settings.
    //
    // Checked against the canary in September 2026, and the result is worth writing down: of the six
    // that were here, three answered 403 ("Endpoint disabled"), two did not answer and one redirected
    // away. None returned audio. invidious.f5.si is first because it was the only public instance that
    // did — through its Companion, see [resolveAudio].
    private val BOOTSTRAP: List<Instance> = listOf(
        "https://invidious.f5.si",
        "https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be",
        "https://invidious.jing.rocks", "https://iv.melmac.space", "https://invidious.privacyredirect.com"
    ).map { Instance(it, "https", apiUp = true, health = null) }

    // Short connect timeout on purpose: a dead instance should cost seconds, not the whole budget of
    // the chain, when the next one on the list may be the one that works.
    private val plainClient by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    /** Client for .onion/.i2p instances via SOCKS (Orbot 9050). Best-effort/experimental. */
    fun torClient(ctx: Context): OkHttpClient {
        val host = InvidiousSettings.torHost(ctx); val port = InvidiousSettings.torPort(ctx)
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun clientFor(ctx: Context, inst: Instance): OkHttpClient =
        if (inst.isAnon && InvidiousSettings.torEnabled(ctx)) torClient(ctx) else plainClient

    /** Combined list (cached remote + user custom), excluding hidden ones. */
    suspend fun list(ctx: Context, forceRefresh: Boolean = false): List<Instance> {
        val remote = cachedOrFetch(ctx, forceRefresh)
        val custom = InvidiousSettings.customInstances(ctx).map { Instance(it, guessType(it), true, null, custom = true) }
        val hidden = InvidiousSettings.hiddenInstances(ctx)
        return (custom + remote).distinctBy { it.baseUrl }.filter { it.baseUrl !in hidden }
    }

    /**
     * Instances to use, in preference order:
     *  - "fixed" mode -> only the user's fixed (or custom) instance;
     *  - "auto" mode  -> clearnet with api, ordered by health; + anonymous ones at the end if allowed.
     */
    suspend fun selected(ctx: Context): List<Instance> {
        val all = list(ctx)
        if (InvidiousSettings.mode(ctx) == "fixed") {
            val fixed = InvidiousSettings.fixedInstance(ctx)
            return all.filter { it.baseUrl.equals(fixed, true) }.ifEmpty {
                if (fixed.isNotBlank()) listOf(Instance(fixed, guessType(fixed), true, null, custom = true)) else emptyList()
            }
        }
        val allowAnon = InvidiousSettings.allowAnonNetworks(ctx)
        val clear = all.filter { it.type == "https" && it.apiUp }
            .sortedByDescending { it.health ?: 0.0 }
        val anon = if (allowAnon) all.filter { it.isAnon } else emptyList()
        // User custom instances first (if self-hosted, the user wants them to win).
        val custom = all.filter { it.custom }
        return (custom + clear + anon).distinctBy { it.baseUrl }
    }

    // "Me at the zoo" — the first YouTube video: public, 19s, virtually never blocked or age-gated.
    private const val CANARY_VIDEO = "jNQXAC9IVRw"

    /**
     * Real playback test: resolves the canary exactly the way [InvidiousAudioSource] resolves a song —
     * the same function, not a lookalike.
     *
     * It used to check `/api/v1/videos` on its own, and that is how the settings screen came to show
     * instances as healthy that could not play anything: answering 200 is not the same as returning
     * audio, and since 2025 the two have come apart (see [resolveAudio]).
     */
    suspend fun probe(ctx: Context, inst: Instance): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { resolveAudio(ctx, inst, CANARY_VIDEO) != null }.getOrDefault(false)
    }

    /** A playable audio stream, as resolved through one instance. */
    data class Resolved(val url: String, val mimeType: String?, val via: String)

    /**
     * Audio for [videoId] through [inst], or null if this instance cannot provide it.
     *
     * ## Two routes, because Invidious split in two
     *
     * Since 2025 an Invidious deployment is usually **two services**: Invidious itself (metadata, the
     * API) and **Invidious Companion**, which does the stream extraction. On such an instance
     * `/api/v1/videos/{id}` answers `200` with an **empty body** — measured on the one public instance
     * with its API open, September 2026 — and every public instance that still answers the old way
     * answers `403`. So the old route alone reaches nothing public.
     *
     * 1. `/api/v1/videos/{id}` — still right for an instance that extracts by itself (a self-hosted
     *    one without Companion, or an older deployment).
     * 2. `…/companion/latest_version?id=…&itag=…&local=true` against the Companion. Which Companion is
     *    not in the API; the instance names it in its own `Content-Security-Policy`, because the page
     *    it serves plays media from there. The instance's own origin is tried as well, for setups that
     *    mount Companion under `/companion` on the same host.
     *
     * `local=true` on both: the audio then flows through the instance or Companion. Without it the
     * answer is a googlevideo URL minted for **their** IP (`ip=` in the query), which this phone is
     * not — it happened to play when measured, and nothing promises it will.
     */
    suspend fun resolveAudio(ctx: Context, inst: Instance, videoId: String): Resolved? {
        val client = clientFor(ctx, inst)
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started

        val videos = Request.Builder()
            .url("${inst.baseUrl}/api/v1/videos/$videoId?fields=adaptiveFormats,formatStreams&local=true")
            .header("User-Agent", UA)
            .build()
        val (fromApi, csp) = try {
            client.newCall(videos).await().use { r ->
                val body = if (r.isSuccessful) r.body.string() else ""
                Log.d(TAG, "${inst.baseUrl} api ${r.code}, ${body.length} bytes, ${elapsed()} ms")
                bestAudioFrom(body) to r.header("Content-Security-Policy")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No answer at all — not a status code, no connection. Without a CSP the only Companion
            // left to try is this same host, and asking it twice more costs two more connect
            // timeouts: measured, a dead instance ate 10 of the 15 seconds before the live one was
            // reached. It is remembered as down for a while so the next song does not pay again.
            deadUntil[inst.baseUrl] = System.currentTimeMillis() + DEAD_FOR_MS
            Log.d(TAG, "${inst.baseUrl} unreachable (${e.javaClass.simpleName}), ${elapsed()} ms")
            return null
        }
        if (fromApi != null) {
            // Verified like any other candidate. Measured: the API answered with 24 formats whose URLs
            // pointed at the instance's own /videoplayback — and that host is behind a bot check, so
            // every one of them answered `200 text/html`. Handing that to the player is a success
            // that plays nothing.
            val ok = attempt { verified(client, fromApi, "api") }
            Log.d(TAG, "${inst.baseUrl} api url ${if (ok != null) "plays" else "is not audio"}, ${elapsed()} ms")
            if (ok != null) return ok.also { lastGood = inst.baseUrl }
        }

        for (companion in companionsFor(inst.baseUrl, csp)) {
            // Opus first (~160 kb/s), AAC 128 if the video has no Opus track.
            for ((itag, label) in listOf(251 to "opus", 140 to "aac")) {
                val url = "$companion/companion/latest_version?id=$videoId&itag=$itag&local=true"
                val found = attempt { verified(client, url, "companion $label ${companion.substringAfter("://")}") }
                Log.d(TAG, "$companion itag $itag ${if (found != null) "plays" else "failed"}, ${elapsed()} ms")
                if (found != null) return found.also { lastGood = inst.baseUrl }
            }
        }
        return null
    }

    /**
     * [instances] in the order worth trying them: the one that last worked, then the rest, then any
     * known to be down.
     *
     * Down ones are tried last rather than dropped, so a host that came back is found again without
     * waiting out [DEAD_FOR_MS] when nothing else works.
     */
    fun inTryOrder(instances: List<Instance>): List<Instance> {
        val now = System.currentTimeMillis()
        val (down, up) = instances.partition { (deadUntil[it.baseUrl] ?: 0L) > now }
        val preferred = up.sortedByDescending { it.baseUrl == lastGood }
        return preferred + down
    }

    private val deadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastGood: String? = null
    private const val DEAD_FOR_MS = 10 * 60 * 1000L

    /** Highest-bitrate audio-only format in a `/api/v1/videos` answer, or the first muxed one. */
    private fun bestAudioFrom(body: String): String? {
        if (body.isBlank()) return null
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1L
        obj.optJSONArray("adaptiveFormats")?.let { adaptive ->
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                if (!f.optString("type").startsWith("audio", true)) continue
                val br = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0)
                val u = f.optString("url")
                if (u.isNotBlank() && br > bestBitrate) { bestBitrate = br; bestUrl = u }
            }
        }
        return bestUrl
            ?: obj.optJSONArray("formatStreams")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /**
     * Follows [url] to the bytes and returns the **final** URL, or null if what is there is not media.
     *
     * One byte is asked for, and that is the whole point: a host that answers with an error page, a
     * bot check or a `502` is found out here and falls through to the next candidate, instead of being
     * handed to the player as a success. Both failures were measured on the same public instance in
     * the same afternoon — the API's URLs answering `200 text/html`, Companion answering `502`/`504`
     * for a while and then `206 audio/mp4`.
     *
     * The final URL and not the one asked, because for Companion the one asked is `latest_version`,
     * which extracts the video again on every request — and a player issues a new request on every
     * seek. The final one (`/companion/videoplayback?…&expire=…`) is the stream itself.
     *
     * A `video/` type is accepted as well, for the muxed `formatStreams` fallback, which carries audio.
     */
    private suspend fun verified(client: OkHttpClient, url: String, via: String): Resolved? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-0")
            .build()
        return client.newCall(req).await().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val media = type.startsWith("audio/", ignoreCase = true) || type.startsWith("video/", ignoreCase = true)
            if ((r.code == 200 || r.code == 206) && media) {
                Resolved(r.request.url.toString(), type.substringBefore(';').trim(), via)
            } else null
        }
    }

    /**
     * Where this instance's Companion lives: the `https` origins its CSP allows media or connections
     * from, minus YouTube's own, then the instance itself.
     */
    internal fun companionsFor(instanceBase: String, csp: String?): List<String> {
        val fromCsp = csp.orEmpty().split(';')
            .map { it.trim() }
            .filter { it.startsWith("media-src", true) || it.startsWith("connect-src", true) }
            .flatMap { it.split(' ').drop(1) }
            .filter { it.startsWith("https://", true) && '*' !in it }
            .map { it.trimEnd('/').removeSuffix(":443") }
            .filterNot { it.contains("googlevideo.com", true) || it.contains("youtube.com", true) }
        return (fromCsp + instanceBase.trimEnd('/')).distinct()
    }

    private const val UA = "Rustify/1.0"
    private const val TAG = "InvidiousInstances"

    /**
     * `runCatching` for a suspending network call, except that cancellation is let through.
     *
     * Plain `runCatching` would catch the `CancellationException` a timed-out chain throws in here,
     * and every attempt after it would fail instantly and be reported as "no audio" — the time limit
     * still held, but the log would name the wrong reason, which is how a diagnosis goes astray.
     */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun cachedOrFetch(ctx: Context, force: Boolean): List<Instance> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(K_CACHE_TS, 0L)
        val fresh = System.currentTimeMillis() - ts < CACHE_TTL_MS
        if (!force && fresh) {
            val cached = prefs.getString(K_CACHE, null)
            if (cached != null) return@withContext parse(cached)
        }
        // `await`, not `execute`: this runs from `isAvailableFor`, inside the chain's timeout, and a
        // blocking call would sit there past it (see `Call.await`).
        val fetched = runCatching {
            val req = Request.Builder().url(LIST_URL).header("User-Agent", UA).build()
            plainClient.newCall(req).await().use { r -> if (r.isSuccessful) r.body.string() else null }
        }.getOrNull()
        if (fetched != null) {
            val parsed = parse(fetched)
            if (parsed.isNotEmpty()) {
                prefs.edit { putString(K_CACHE, fetched); putLong(K_CACHE_TS, System.currentTimeMillis()) }
                parsed
            } else {
                // Directory reachable but empty/format changed -> fall back to cache, then bootstrap.
                prefs.getString(K_CACHE, null)?.let { parse(it) }?.takeIf { it.isNotEmpty() } ?: BOOTSTRAP
            }
        } else {
            // Directory unreachable -> last-known cache, else bootstrap so Invidious still has candidates.
            prefs.getString(K_CACHE, null)?.let { parse(it) }?.takeIf { it.isNotEmpty() } ?: BOOTSTRAP
        }
    }

    private fun parse(json: String): List<Instance> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val d = pair.optJSONObject(1) ?: return@mapNotNull null
            val uri = d.optString("uri").trimEnd('/')
            if (!isValidInstanceUri(uri)) return@mapNotNull null
            // The URL suffix wins for anonymous networks (the directory `type` is sometimes wrong,
            // e.g. a .ygg marked as "https"); otherwise use the directory type or "https".
            val type = when {
                uri.contains(".onion") -> "onion"
                uri.contains(".i2p") -> "i2p"
                uri.contains(".ygg") -> "ygg"
                else -> d.optString("type", "https").ifBlank { "https" }
            }
            val api = d.optBoolean("api", type == "https")
            // monitor.uptime varies; try several fields leniently.
            val monitor = d.optJSONObject("monitor")
            val health = monitor?.let {
                it.optJSONObject("30dRatio")?.optString("ratio")?.toDoubleOrNull()
                    ?: it.optJSONObject("90dRatio")?.optString("ratio")?.toDoubleOrNull()
                    ?: it.optDouble("uptime", Double.NaN).takeIf { v -> !v.isNaN() }
            }
            Instance(uri, type, api, health)
        }
    }.getOrDefault(emptyList())

    private fun guessType(url: String): String = when {
        url.contains(".onion") -> "onion"
        url.contains(".i2p") -> "i2p"
        url.contains(".ygg") -> "ygg"
        else -> "https"
    }

    /**
     * Discards junk URIs from the directory (which sometimes returns broken entries): requires an
     * http(s) scheme and a host with a real domain, filtering out "http://", "http://inv" and similar.
     */
    private fun isValidInstanceUri(uri: String): Boolean {
        if (!uri.startsWith("http", ignoreCase = true)) return false
        val host = uri.substringAfter("://", "").substringBefore('/')
        // Must have at least one dot (domain) and a reasonable minimum length.
        return host.length >= 4 && host.contains('.')
    }
}
