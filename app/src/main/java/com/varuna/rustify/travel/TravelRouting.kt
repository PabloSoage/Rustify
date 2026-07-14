package com.varuna.rustify.travel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * E99 — geocoding (Nominatim) + routing (OSRM). Both are public, keyless OpenStreetMap services.
 * Best-effort: heavy use is rate-limited by the public demo servers; for production you'd self-host
 * OSRM / a Nominatim instance. All calls return null on any failure.
 */
object TravelRouting {
    data class Geo(val lat: Double, val lon: Double, val label: String)
    /** [geometryGeoJson] is the OSRM route geometry as a GeoJSON LineString (for drawing on the map). */
    data class Route(val durationSec: Long, val distanceM: Double, val geometryGeoJson: String?)

    private fun httpGet(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Rustify/1.0 (travel-playlist)")
            connectTimeout = 10_000; readTimeout = 15_000
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    suspend fun geocode(query: String): Geo? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                URLEncoder.encode(query, "UTF-8")) ?: return@runCatching null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@runCatching null
            val o = arr.getJSONObject(0)
            Geo(o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("display_name", query))
        }.getOrNull()
    }

    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
            val body = httpGet(url) ?: return@runCatching null
            val routes = JSONObject(body).optJSONArray("routes") ?: return@runCatching null
            if (routes.length() == 0) return@runCatching null
            val r = routes.getJSONObject(0)
            Route(
                durationSec = r.getDouble("duration").toLong(),
                distanceM = r.getDouble("distance"),
                geometryGeoJson = r.optJSONObject("geometry")?.toString()
            )
        }.getOrNull()
    }
}
