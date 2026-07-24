package com.varuna.rustify.travel

import android.content.Context
import androidx.core.content.edit
import com.varuna.rustify.travel.TravelSettings.mapTilerKey
import com.varuna.rustify.travel.TravelSettings.styleUri

/**
 * Persistence for the Travel module settings in `rustify_settings`, on par with the rest of the
 * app's settings (no new dependencies).
 *
 * Keyless by default: if [mapTilerKey] is empty, the map uses the CARTO Voyager style bundled in
 * `assets/style_keyless.json` (OSM + CARTO, no API key). If a MapTiler key is configured, the
 * MapTiler Cloud vector `streets` style is used (more polished, but requires a free maptiler.com
 * account).
 */
object TravelSettings {
    const val PREFS = "rustify_settings"

    const val KEY_MAPTILER_KEY = "travel_maptiler_key"
    const val KEY_MAP_STYLE = "travel_map_style"   // 0=Voyager 1=Dark 2=Satellite 3=Topo
    const val KEY_GEOCODING_API_KEY = "travel_geocoding_api_key"  // Google Geocoding API key (optional)

    /** Keyless styles bundled in `assets/`. Matches [styleUri] by index. */
    val ASSET_STYLES = listOf(
        "asset://style_keyless.json",  // 0 — CARTO Voyager (light, streets)
        "asset://style_dark.json",     // 1 — CARTO Dark (night)
        "asset://style_satellite.json",// 2 — Esri World Imagery (satellite)
        "asset://style_topo.json"      // 3 — OpenTopoMap (hiking/contours)
    )

    /** Readable names (in English) for the UI selector; each should have its own i18n. */
    val STYLE_LABELS = listOf("Voyager", "Dark", "Satellite", "Topo")

    /** "Dark" index used to tint the overlays accordingly (title, etc.). */
    const val STYLE_DARK_INDEX = 1

    /** MapTiler Cloud vector style (streets). Replace {key} at runtime. */
    const val MAPTILER_STYLE_URL = "https://api.maptiler.com/maps/streets-v2/style.json?key="

    fun mapTilerKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAPTILER_KEY, "") ?: ""

    fun mapStyleIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAP_STYLE, 0).coerceIn(0, ASSET_STYLES.lastIndex)

    fun setMapStyleIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_MAP_STYLE, index.coerceIn(0, ASSET_STYLES.lastIndex)) }
    }

    /**
     * Optional Google Cloud Console API key (Geocoding API + Places API).
     * If present, search and reverse geocoding use Google (better recall, resolving addresses and
     * POIs that OSM cannot find). If empty, the keyless services (Photon + Nominatim) are used.
     * The user creates a Google Cloud account, enables the Geocoding API (free tier: $200/month
     * ≈ 11k requests) and pastes the key here — the same pattern as the Spotify client_id.
     */
    fun geocodingApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GEOCODING_API_KEY, "") ?: ""

    /**
     * Returns the MapLibre style URI to apply to the map:
     * - If a MapTiler key is set → MapTiler Cloud vector style (Streets-v2).
     * - Otherwise → the bundled asset style for the saved index.
     */
    fun styleUri(context: Context): String {
        val key = mapTilerKey(context).trim()
        if (key.isNotEmpty()) return MAPTILER_STYLE_URL + key
        return ASSET_STYLES[mapStyleIndex(context)]
    }
}