package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * E61 — Preferencias del backend Invidious (en `rustify_settings`).
 *
 * Invidious es un frontend/proxy de YouTube auto-hostable: dado un **videoId** devuelve las URLs de
 * audio por HTTP puro (`/api/v1/videos/{id}`), sin yt-dlp/Python. Es **respaldo** (redundancia cuando
 * yt-dlp falla) y, de cara a un futuro iOS, la vía de extracción sin yt-dlp.
 *
 * El on/off y el orden respecto a yt-dlp los gobierna [AudioBackendSettings] (id "invidious"); aquí
 * solo va la config PROPIA de Invidious: modo (auto/fija), instancia fija, instancias extra del
 * usuario, y el enrutado por Tor.
 */
object InvidiousSettings {
    private const val PREFS = "rustify_settings"
    private const val K_MODE = "inv_mode"                 // "auto" | "fixed"
    private const val K_FIXED = "inv_fixed_instance"      // base url de la instancia fija
    private const val K_CUSTOM = "inv_custom_instances"   // JSON array de base urls extra (self-host)
    private const val K_HIDDEN = "inv_hidden_instances"   // JSON array de base urls ocultadas
    private const val K_TOR = "inv_tor_enabled"           // enrutar instancias .onion por SOCKS
    private const val K_TOR_HOST = "inv_tor_host"
    private const val K_TOR_PORT = "inv_tor_port"
    private const val K_ANON = "inv_allow_anon_networks"  // permitir .onion/.i2p/ygg en la selección

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = p(ctx).getString(K_MODE, "auto") ?: "auto"
    fun setMode(ctx: Context, v: String) = p(ctx).edit { putString(K_MODE, v) }

    fun fixedInstance(ctx: Context): String = p(ctx).getString(K_FIXED, "") ?: ""
    fun setFixedInstance(ctx: Context, v: String) = p(ctx).edit { putString(K_FIXED, v.trim().trimEnd('/')) }

    fun torEnabled(ctx: Context): Boolean = p(ctx).getBoolean(K_TOR, false)
    fun setTorEnabled(ctx: Context, v: Boolean) = p(ctx).edit { putBoolean(K_TOR, v) }
    fun torHost(ctx: Context): String = p(ctx).getString(K_TOR_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun torPort(ctx: Context): Int = p(ctx).getInt(K_TOR_PORT, 9050)
    fun setTor(ctx: Context, host: String, port: Int) = p(ctx).edit { putString(K_TOR_HOST, host.trim()); putInt(K_TOR_PORT, port) }

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
    fun toggleHidden(ctx: Context, url: String, hidden: Boolean) {
        val cur = hiddenInstances(ctx).toMutableSet()
        if (hidden) cur.add(url) else cur.remove(url)
        writeList(ctx, K_HIDDEN, cur.toList())
    }

    private fun readList(ctx: Context, key: String): List<String> = runCatching {
        val raw = p(ctx).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw); (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        p(ctx).edit { putString(key, arr.toString()) }
    }
}
