package com.varuna.rustify.webplayer

import android.content.Context
import androidx.core.content.edit
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * uBlock Origin filter lists for the in-app web player.
 *
 * Android's WebView cannot run browser extensions, so uBO itself cannot be installed. What carries
 * the actual value is its **filter lists**, which are plain text in EasyList/uBO syntax — and those
 * we can download, cache, refresh, and feed to the matching engine in the Rust core
 * (`adblock_engine`, backed by Brave's adblock-rust, which speaks the same syntax).
 *
 * Lists are fetched from uBlockOrigin's own CDN mirror and cached in `filesDir/adblock/`. Refresh is
 * time-based; a failed refresh keeps serving the cached copy rather than leaving the player
 * unfiltered.
 */
object AdblockFilters {

    /** uBO's default set: its own ad/privacy filters plus EasyList and EasyPrivacy. */
    private val LISTS = listOf(
        "https://ublockorigin.github.io/uAssetsCDN/filters/filters.min.txt",
        "https://ublockorigin.github.io/uAssetsCDN/filters/privacy.min.txt",
        "https://ublockorigin.github.io/uAssetsCDN/filters/quick-fixes.min.txt",
        "https://ublockorigin.github.io/uAssetsCDN/thirdparties/easylist.txt",
        "https://ublockorigin.github.io/uAssetsCDN/thirdparties/easyprivacy.txt",
    )

    private const val PREFS = "rustify_settings"
    private const val KEY_LAST_UPDATE = "adblock_lists_updated_at"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(5)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun dir(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }
    private fun fileFor(context: Context, url: String) =
        File(dir(context), url.substringAfterLast('/').ifBlank { url.hashCode().toString() })

    /** True when every list has a non-empty cached copy. */
    private fun hasCache(context: Context): Boolean =
        LISTS.all { fileFor(context, it).let { f -> f.exists() && f.length() > 0 } }

    private fun isStale(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        return System.currentTimeMillis() - last > MAX_AGE_MS
    }

    /** Downloads every list, writing each only if the response body is non-empty. */
    private fun download(context: Context): Boolean {
        var anyOk = false
        LISTS.forEach { url ->
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body.string()
                    if (body.isNotBlank()) {
                        fileFor(context, url).writeText(body)
                        anyOk = true
                    }
                }
            }
        }
        if (anyOk) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putLong(KEY_LAST_UPDATE, System.currentTimeMillis()) }
        }
        return anyOk
    }

    /**
     * Makes sure the engine is loaded, refreshing the lists first when they are missing or stale.
     * Safe to call on every web-player open: it no-ops once the engine holds a compiled list and the
     * cache is fresh.
     *
     * @return true when the engine can filter.
     */
    suspend fun ensureLoaded(context: Context, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            if (forceRefresh || !hasCache(appCtx) || isStale(appCtx)) {
                download(appCtx)
            }
            if (!hasCache(appCtx)) return@withContext false
            if (!forceRefresh && NativeEngine.adblockIsReadyNative()) return@withContext true

            val rules = buildString {
                LISTS.forEach { url ->
                    runCatching { fileFor(appCtx, url).readText() }
                        .getOrNull()
                        ?.let { append(it).append('\n') }
                }
            }
            if (rules.isBlank()) false else NativeEngine.adblockLoadRulesNative(rules)
        }

    /** Frees the compiled engine. Called when the web player screen goes away. */
    fun release() = runCatching { NativeEngine.adblockClearNative() }.let { }

    /** Last successful refresh, or 0 if never. */
    fun lastUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0L)
}
