package com.varuna.rustify.travel

import com.varuna.rustify.platform.KeyValueStore

/**
 * Persistence for the Travel module settings in `rustify_settings`, on par with the rest of the
 * app's settings (no new dependencies).
 *
 * Keyless by default: if [mapTilerKey] is empty, the map uses OpenFreeMap's Liberty style
 * (OpenStreetMap, no API key). If a MapTiler key is configured, the
 * MapTiler Cloud vector `streets` style is used (more polished, but requires a free maptiler.com
 * account).
 */
object TravelSettings {
    const val PREFS = "rustify_settings"

    const val KEY_MAPTILER_KEY = "travel_maptiler_key"
    const val KEY_MAP_STYLE = "travel_map_style"   // 0=Streets 1=Dark 2=Satellite 3=Topo
    const val KEY_GEOCODING_API_KEY = "travel_geocoding_api_key"  // Google Geocoding API key (optional)

    /**
     * Keyless styles, by index ([styleUri]). Streets and night were CARTO raster tiles, which since
     * 2026 answer every request with an "API KEY REQUIRED" image (seen on the emulator in 4.4);
     * they are OpenFreeMap's vector styles now — free, no key, OpenStreetMap data. Satellite and
     * topo stay the bundled raster styles in `assets/`.
     */
    val MAP_STYLES = listOf(
        "https://tiles.openfreemap.org/styles/liberty",  // 0 — OpenFreeMap Liberty (light, streets)
        "https://tiles.openfreemap.org/styles/dark",     // 1 — OpenFreeMap Dark (night)
        "asset://style_satellite.json",// 2 — Esri World Imagery (satellite)
        "asset://style_topo.json"      // 3 — OpenTopoMap (hiking/contours)
    )

    /**
     * The same four styles as raster tiles, for the map drawn in Compose where there is no MapLibre
     * (`{z}/{x}/{y}` filled in per tile). OpenFreeMap serves only vectors, so streets are
     * OpenStreetMap's own tiles, and night is those drawn inverted ([STYLE_DARK_INDEX]). Satellite
     * and topo are the asset styles' own URLs, with their zoom limits.
     */
    val RASTER_TILES = listOf(
        RasterTiles("https://tile.openstreetmap.org/{z}/{x}/{y}.png", maxZoom = 19),
        RasterTiles("https://tile.openstreetmap.org/{z}/{x}/{y}.png", maxZoom = 19),
        RasterTiles("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", maxZoom = 19),
        RasterTiles("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", maxZoom = 17)
    )

    /** A raster tile source: the URL template and the deepest zoom it has. */
    data class RasterTiles(val template: String, val maxZoom: Int) {
        fun url(z: Int, x: Int, y: Int): String =
            template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
    }

    /** Readable names (in English) for the UI selector; each should have its own i18n. */
    val STYLE_LABELS = listOf("Streets", "Dark", "Satellite", "Topo")

    /** "Dark" index used to tint the overlays accordingly (title, etc.). */
    const val STYLE_DARK_INDEX = 1

    /** MapTiler Cloud vector style (streets). Replace {key} at runtime. */
    const val MAPTILER_STYLE_URL = "https://api.maptiler.com/maps/streets-v2/style.json?key="

    /** MapTiler Cloud's raster tiles of the same style, for the Compose map. */
    const val MAPTILER_RASTER_URL = "https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key="

    fun mapTilerKey(prefs: KeyValueStore): String = prefs.getString(KEY_MAPTILER_KEY) ?: ""

    fun mapStyleIndex(prefs: KeyValueStore): Int =
        prefs.getInt(KEY_MAP_STYLE, 0).coerceIn(0, MAP_STYLES.lastIndex)

    fun setMapStyleIndex(prefs: KeyValueStore, index: Int) {
        prefs.putInt(KEY_MAP_STYLE, index.coerceIn(0, MAP_STYLES.lastIndex))
    }

    /**
     * Optional Google Cloud Console API key (Geocoding API + Places API).
     * If present, search and reverse geocoding use Google (better recall, resolving addresses and
     * POIs that OSM cannot find). If empty, the keyless services (Photon + Nominatim) are used.
     * The user creates a Google Cloud account, enables the Geocoding API (free tier: $200/month
     * ≈ 11k requests) and pastes the key here — the same pattern as the Spotify client_id.
     */
    fun geocodingApiKey(prefs: KeyValueStore): String = prefs.getString(KEY_GEOCODING_API_KEY) ?: ""

    /**
     * Returns the MapLibre style URI to apply to the map:
     * - If a MapTiler key is set → MapTiler Cloud vector style (Streets-v2).
     * - Otherwise → the bundled asset style for the saved index.
     */
    fun styleUri(prefs: KeyValueStore): String {
        val key = mapTilerKey(prefs).trim()
        if (key.isNotEmpty()) return MAPTILER_STYLE_URL + key
        return MAP_STYLES[mapStyleIndex(prefs)]
    }

    /** [styleUri]'s choice for the Compose map: MapTiler's streets with a key, else [RASTER_TILES]. */
    fun rasterTiles(prefs: KeyValueStore, index: Int): RasterTiles {
        val key = mapTilerKey(prefs).trim()
        if (key.isNotEmpty() && index != 2 && index != 3) return RasterTiles(MAPTILER_RASTER_URL + key, maxZoom = 20)
        return RASTER_TILES[index.coerceIn(0, RASTER_TILES.lastIndex)]
    }
}
