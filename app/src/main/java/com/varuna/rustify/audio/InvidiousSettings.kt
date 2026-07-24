package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Preferences for the Invidious backend (in `rustify_settings`).
 *
 * Invidious is a self-hostable YouTube frontend/proxy: given a videoId it returns audio URLs over
 * plain HTTP (`/api/v1/videos/{id}`), without yt-dlp/Python. It serves as a fallback (redundancy when
 * yt-dlp fails) and, for a possible future iOS, as an extraction path without yt-dlp.
 *
 * On/off and ordering relative to yt-dlp are governed by [AudioBackendSettings] (id "invidious"); here
 * we only store Invidious's own config: mode (auto/fixed), fixed instance, the user's extra instances,
 * and Tor routing.
 */
object InvidiousSettings {
    private const val PREFS = "rustify_settings"
    private const val K_MODE = "inv_mode"                 // "auto" | "fixed"
    private const val K_FIXED = "inv_fixed_instance"      // base url of the fixed instance
    private const val K_CUSTOM = "inv_custom_instances"   // JSON array of extra base urls (self-host)
    private const val K_HIDDEN = "inv_hidden_instances"   // JSON array of hidden base urls
    private const val K_TOR = "inv_tor_enabled"           // route .onion instances over SOCKS
    private const val K_TOR_HOST = "inv_tor_host"
    private const val K_TOR_PORT = "inv_tor_port"
    private const val K_ANON = "inv_allow_anon_networks"  // allow .onion/.i2p/ygg in the selection

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = p(ctx).getString(K_MODE, "auto") ?: "auto"
    fun setMode(ctx: Context, v: String) = p(ctx).edit { putString(K_MODE, v) }

    fun fixedInstance(ctx: Context): String = p(ctx).getString(K_FIXED, "") ?: ""
    fun setFixedInstance(ctx: Context, v: String) = p(ctx).edit { putString(K_FIXED, v.trim().trimEnd('/')) }

    fun torEnabled(ctx: Context): Boolean = p(ctx).getBoolean(K_TOR, false)
    fun setTorEnabled(ctx: Context, v: Boolean) = p(ctx).edit { putBoolean(K_TOR, v) }
    fun torHost(ctx: Context): String = p(ctx).getString(K_TOR_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun torPort(ctx: Context): Int = p(ctx).getInt(K_TOR_PORT, 9050)

    fun allowAnonNetworks(ctx: Context): Boolean = p(ctx).getBoolean(K_ANON, false)
    fun setAllowAnonNetworks(ctx: Context, v: Boolean) = p(ctx).edit { putBoolean(K_ANON, v) }

    fun customInstances(ctx: Context): List<String> = readList(ctx, K_CUSTOM)
    fun addCustomInstance(ctx: Context, url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return
        val cur = customInstances(ctx).toMutableList()
        if (cur.none { it.equals(u, true) }) { cur.add(u); writeList(ctx, K_CUSTOM, cur) }
    }
    fun removeCustomInstance(ctx: Context, url: String) =
        writeList(ctx, K_CUSTOM, customInstances(ctx).filterNot { it.equals(url, true) })

    fun hiddenInstances(ctx: Context): Set<String> = readList(ctx, K_HIDDEN).toSet()

    private fun readList(ctx: Context, key: String): List<String> = runCatching {
        val raw = p(ctx).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw); (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        p(ctx).edit { putString(key, arr.toString()) }
    }
}
