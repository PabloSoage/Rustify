package com.varuna.rustify.audio

import com.varuna.rustify.bridge.optString
import com.varuna.rustify.bridge.parseJsonArray
import com.varuna.rustify.platform.KeyValueStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Preferences for the Invidious backend (in the sources' settings store: `rustify_settings` on
 * Android, as before).
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
    private const val K_MODE = "inv_mode"                 // "auto" | "fixed"
    private const val K_FIXED = "inv_fixed_instance"      // base url of the fixed instance
    private const val K_CUSTOM = "inv_custom_instances"   // JSON array of extra base urls (self-host)
    private const val K_HIDDEN = "inv_hidden_instances"   // JSON array of hidden base urls
    private const val K_TOR = "inv_tor_enabled"           // route .onion instances over SOCKS
    private const val K_TOR_HOST = "inv_tor_host"
    private const val K_TOR_PORT = "inv_tor_port"
    private const val K_ANON = "inv_allow_anon_networks"  // allow .onion/.i2p/ygg in the selection

    fun mode(p: KeyValueStore): String = p.getString(K_MODE) ?: "auto"
    fun setMode(p: KeyValueStore, v: String) = p.putString(K_MODE, v)

    fun fixedInstance(p: KeyValueStore): String = p.getString(K_FIXED) ?: ""
    fun setFixedInstance(p: KeyValueStore, v: String) = p.putString(K_FIXED, v.trim().trimEnd('/'))

    fun torEnabled(p: KeyValueStore): Boolean = p.getBoolean(K_TOR, false)
    fun setTorEnabled(p: KeyValueStore, v: Boolean) = p.putBoolean(K_TOR, v)
    fun torHost(p: KeyValueStore): String = p.getString(K_TOR_HOST) ?: "127.0.0.1"
    fun torPort(p: KeyValueStore): Int = p.getInt(K_TOR_PORT, 9050)

    fun allowAnonNetworks(p: KeyValueStore): Boolean = p.getBoolean(K_ANON, false)
    fun setAllowAnonNetworks(p: KeyValueStore, v: Boolean) = p.putBoolean(K_ANON, v)

    fun customInstances(p: KeyValueStore): List<String> = readList(p, K_CUSTOM)
    fun addCustomInstance(p: KeyValueStore, url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return
        val cur = customInstances(p).toMutableList()
        if (cur.none { it.equals(u, true) }) { cur.add(u); writeList(p, K_CUSTOM, cur) }
    }
    fun removeCustomInstance(p: KeyValueStore, url: String) =
        writeList(p, K_CUSTOM, customInstances(p).filterNot { it.equals(url, true) })

    fun hiddenInstances(p: KeyValueStore): Set<String> = readList(p, K_HIDDEN).toSet()

    private fun readList(p: KeyValueStore, key: String): List<String> {
        val arr = parseJsonArray(p.getString(key)) ?: return emptyList()
        return arr.indices.map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun writeList(p: KeyValueStore, key: String, list: List<String>) {
        p.putString(key, JsonArray(list.map { JsonPrimitive(it) }).toString())
    }
}
