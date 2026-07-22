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
 * E61 — Descubrimiento y salud de instancias Invidious.
 *
 * Lista pública: `https://api.invidious.io/instances.json` (uptime de uptime.invidious.io). Cada
 * elemento es `[host, {uri, type, api, monitor{...}}]`. `type` ∈ https | onion | i2p | ygg. Cacheamos
 * la lista y hacemos un **health-check propio** (un GET corto) porque es la señal fiable.
 */
object InvidiousInstances {
    private const val PREFS = "rustify_settings"
    private const val K_CACHE = "inv_instances_cache"
    private const val K_CACHE_TS = "inv_instances_cache_ts"
    private const val LIST_URL = "https://api.invidious.io/instances.json?pretty=0"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /** [type]: "https" (clearnet) | "onion" (Tor) | "i2p" | "ygg". [health] = % uptime (0..100) o null. */
    data class Instance(
        val baseUrl: String,     // p. ej. "https://inv.nadeko.net"
        val type: String,
        val apiUp: Boolean,
        val health: Double?,
        val custom: Boolean = false
    ) {
        val isAnon: Boolean get() = type == "onion" || type == "i2p" || type == "ygg"
    }

    // E101 — Fallback bootstrap list of public clearnet instances, used ONLY when the directory
    // (api.invidious.io/instances.json, frequently down) can't be fetched and there's no cache. Without
    // this, a dead directory left `selected()` empty → the Invidious backend silently never resolved
    // ("Invidious no funciona"). These are best-effort defaults; the user can override in Settings.
    private val BOOTSTRAP: List<Instance> = listOf(
        "https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be",
        "https://invidious.jing.rocks", "https://iv.melmac.space", "https://invidious.privacyredirect.com"
    ).map { Instance(it, "https", apiUp = true, health = null) }

    private val plainClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Cliente para instancias .onion/.i2p vía SOCKS (Orbot 9050). Best-effort/experimental. */
    fun torClient(ctx: Context): OkHttpClient {
        val host = InvidiousSettings.torHost(ctx); val port = InvidiousSettings.torPort(ctx)
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun clientFor(ctx: Context, inst: Instance): OkHttpClient =
        if (inst.isAnon && InvidiousSettings.torEnabled(ctx)) torClient(ctx) else plainClient

    /** Lista combinada (remota cacheada + custom del usuario), sin ocultadas. */
    suspend fun list(ctx: Context, forceRefresh: Boolean = false): List<Instance> {
        val remote = cachedOrFetch(ctx, forceRefresh)
        val custom = InvidiousSettings.customInstances(ctx).map { Instance(it, guessType(it), true, null, custom = true) }
        val hidden = InvidiousSettings.hiddenInstances(ctx)
        return (custom + remote).distinctBy { it.baseUrl }.filter { it.baseUrl !in hidden }
    }

    /**
     * Instancias a usar, en orden de preferencia:
     *  - modo "fixed" → solo la fija (o custom) del usuario;
     *  - modo "auto"  → clearnet con api, ordenadas por salud; + anónimas al final si están permitidas.
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
        // custom del usuario primero (si es self-host, quiere que gane)
        val custom = all.filter { it.custom }
        return (custom + clear + anon).distinctBy { it.baseUrl }
    }

    /** Health-check rápido: GET /api/v1/stats (o trending) con timeout corto. */
    suspend fun ping(ctx: Context, inst: Instance): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${inst.baseUrl}/api/v1/stats").header("User-Agent", "Rustify/1.0").build()
            clientFor(ctx, inst).newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // "Me at the zoo" — el primer vídeo de YouTube: público, 19 s, prácticamente nunca bloqueado/con edad.
    private const val CANARY_VIDEO = "jNQXAC9IVRw"

    /**
     * Prueba REAL de reproducción: pide `/api/v1/videos/{canario}` y comprueba que devuelve una URL de
     * audio. Muchas instancias responden 200 en `/api/v1/stats` (ping ✅) pero fallan al resolver el
     * vídeo (rate-limit / bloqueo de googlevideo) → parecían buenas y luego no iban como backend. Esto
     * refleja lo que hace [InvidiousAudioSource] de verdad.
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
                // Directory reachable but empty/format changed → fall back to cache, then bootstrap.
                prefs.getString(K_CACHE, null)?.let { parse(it) }?.takeIf { it.isNotEmpty() } ?: BOOTSTRAP
            }
        } else {
            // Directory unreachable → last-known cache, else bootstrap so Invidious still has candidates.
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
            // El sufijo de la URL manda para redes anónimas (el `type` del directorio a veces viene mal,
            // p. ej. una .ygg marcada como "https"); si no, usamos el type del directorio o "https".
            val type = when {
                uri.contains(".onion") -> "onion"
                uri.contains(".i2p") -> "i2p"
                uri.contains(".ygg") -> "ygg"
                else -> d.optString("type", "https").ifBlank { "https" }
            }
            val api = d.optBoolean("api", type == "https")
            // monitor.uptime varía; probamos varios campos con tolerancia.
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
     * Descarta URIs basura del directorio (que ahora devuelve entradas rotas): exige esquema http(s) y un
     * host con dominio real. Así se van "http://", "http://inv" y similares que salían en la lista.
     */
    private fun isValidInstanceUri(uri: String): Boolean {
        if (!uri.startsWith("http", ignoreCase = true)) return false
        val host = uri.substringAfter("://", "").substringBefore('/')
        // Debe tener al menos un punto (dominio) y una longitud mínima razonable.
        return host.length >= 4 && host.contains('.')
    }
}
