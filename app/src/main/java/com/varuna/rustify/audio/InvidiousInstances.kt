package com.varuna.rustify.audio

import android.content.Context
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
    private val BOOTSTRAP: List<Instance> = listOf(
        "https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be",
        "https://invidious.jing.rocks", "https://iv.melmac.space", "https://invidious.privacyredirect.com"
    ).map { Instance(it, "https", apiUp = true, health = null) }

    private val plainClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
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
     * Real playback test: requests `/api/v1/videos/{canary}` and checks that it returns an audio URL.
     * Many instances answer 200 on `/api/v1/stats` (ping OK) but fail to resolve the video (rate-limit
     * / googlevideo block) -> they looked good yet did not work as a backend. This reflects what
     * [InvidiousAudioSource] actually does.
     */
    suspend fun probe(ctx: Context, inst: Instance): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val url = "${inst.baseUrl}/api/v1/videos/$CANARY_VIDEO?fields=adaptiveFormats&local=false"
            val req = Request.Builder().url(url).header("User-Agent", "Rustify/1.0").build()
            val body = clientFor(ctx, inst).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                r.body?.string() ?: return@runCatching false
            }
            val adaptive = org.json.JSONObject(body).optJSONArray("adaptiveFormats") ?: return@runCatching false
            (0 until adaptive.length()).any { i ->
                val f = adaptive.optJSONObject(i)
                f != null && f.optString("type").startsWith("audio", true) && f.optString("url").isNotBlank()
            }
        }.getOrDefault(false)
    }

    private suspend fun cachedOrFetch(ctx: Context, force: Boolean): List<Instance> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(K_CACHE_TS, 0L)
        val fresh = System.currentTimeMillis() - ts < CACHE_TTL_MS
        if (!force && fresh) {
            val cached = prefs.getString(K_CACHE, null)
            if (cached != null) return@withContext parse(cached)
        }
        val fetched = runCatching {
            val req = Request.Builder().url(LIST_URL).header("User-Agent", "Rustify/1.0").build()
            plainClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
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
