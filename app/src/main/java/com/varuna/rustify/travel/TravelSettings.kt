package com.varuna.rustify.travel

import android.content.Context

/**
 * Persistencia de la configuración del módulo Travel (E99) en `rustify_settings`,
 * en paridad con el resto de los ajustes de la app (sin deps nuevas).
 *
 * Keyless por defecto: si [mapTilerKey] está vacío, el mapa usa el estilo CARTO
 * Voyager embebido en `assets/style_keyless.json` (OSM + CARTO, sin API key).
 * Si se configura una key de MapTiler, se usa el estilo vectorial `streets` de
 * MapTiler Cloud (más pulido, pero requiere cuenta gratuita en maptiler.com).
 */
object TravelSettings {
    const val PREFS = "rustify_settings"

    const val KEY_MAPTILER_KEY = "travel_maptiler_key"
    const val KEY_MAP_STYLE = "travel_map_style"   // 0=Voyager 1=Dark 2=Satellite 3=Topo
    const val KEY_GEOCODING_API_KEY = "travel_geocoding_api_key"  // Google Geocoding API key (opcional)

    /** Estilos keyless embebidos en `assets/`. Coincide con [styleUri] por índice. */
    val ASSET_STYLES = listOf(
        "asset://style_keyless.json",  // 0 — CARTO Voyager (claro, calles)
        "asset://style_dark.json",     // 1 — CARTO Dark (nocturno)
        "asset://style_satellite.json",// 2 — Esri World Imagery (satélite)
        "asset://style_topo.json"      // 3 — OpenTopoMap (senderismo/contornos)
    )

    /** Nombres legibles (en EN) para el selector de UI; cada uno debería tener i18n aparte. */
    val STYLE_LABELS = listOf("Voyager", "Dark", "Satellite", "Topo")

    /** Índice "oscuro" para teñir los overlays en consecuencia (título etc.). */
    const val STYLE_DARK_INDEX = 1

    /** Estilo vectorial de MapTiler Cloud (streets). Reemplazar {key} en runtime. */
    const val MAPTILER_STYLE_URL = "https://api.maptiler.com/maps/streets-v2/style.json?key="

    fun mapTilerKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAPTILER_KEY, "") ?: ""

    fun mapStyleIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAP_STYLE, 0).coerceIn(0, ASSET_STYLES.lastIndex)

    fun setMapStyleIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAP_STYLE, index.coerceIn(0, ASSET_STYLES.lastIndex)).apply()
    }

    /**
     * API key opcional de Google Cloud Console (Geocoding API + Places API).
     * Si está presente, las búsquedas y reverse geocoding usan Google (mejor recall, soporta
     * direcciones y POIs que OSM no encuentra). Si está vacío, se usan los servicios keyless
     * (Photon + Nominatim). El usuario crea su cuenta Google Cloud, habilita Geocoding API
     * (free tier: $200/mes ≈ 11k peticiones) y pega la key aquí — patrón del Spotify client_id.
     */
    fun geocodingApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GEOCODING_API_KEY, "") ?: ""

    /** `true` si el estilo activo es oscuro (para pintar el texto/título en blanco). */
    fun isDarkStyle(context: Context): Boolean = mapStyleIndex(context) == STYLE_DARK_INDEX

    /**
     * Devuelve la URI del estilo MapLibre que debe aplicarse al mapa:
     * - Si hay key de MapTiler → estilo vectorial MapTiler Cloud ( Streets-v2 ).
     * - Si no → estilo embebido en assets según el índice guardado.
     */
    fun styleUri(context: Context): String {
        val key = mapTilerKey(context).trim()
        if (key.isNotEmpty()) return MAPTILER_STYLE_URL + key
        return ASSET_STYLES[mapStyleIndex(context)]
    }
}