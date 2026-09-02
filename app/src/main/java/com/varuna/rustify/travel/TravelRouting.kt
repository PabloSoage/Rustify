package com.varuna.rustify.travel

import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Geocoding (Nominatim) + routing (OSRM). Both are public OpenStreetMap services with no API key.
 * Best-effort: heavy usage is throttled by the public demo servers; for production, self-host OSRM
 * and Nominatim. Every call returns null (or an empty list) on any failure.
 */
object TravelRouting {
    data class Geo(val lat: Double, val lon: Double, val label: String)
    /** [geometryGeoJson] is the OSRM route geometry as a GeoJSON LineString (for drawing). */
    data class Route(val durationSec: Long, val distanceM: Double, val geometryGeoJson: String?)

    private fun httpGet(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Rustify/1.0 (travel-playlist)")
            connectTimeout = 10_000; readTimeout = 15_000
            instanceFollowRedirects = true
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    /**
     * Geocoding via the Google **Geocoding API** (requires the user's API key).
     * Returns up to [limit] results with lat/lon and a formatted address, ordered by relevance.
     * Documentation: https://developers.google.com/maps/documentation/geocoding/overview
     */
    private suspend fun googleGeocode(query: String, apiKey: String, limit: Int = 8): List<Geo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://maps.googleapis.com/maps/api/geocode/json?address=" +
                URLEncoder.encode(query, "UTF-8") + "&key=" + apiKey
            val body = httpGet(url) ?: return@runCatching emptyList()
            val obj = JSONObject(body)
            if (obj.optString("status") != "OK" && obj.optString("status") != "ZERO_RESULTS") {
                return@runCatching emptyList()
            }
            val arr = obj.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                val loc = r.optJSONObject("geometry")?.optJSONObject("location")
                if (loc == null) null else {
                    val lat = loc.getDouble("lat")
                    val lon = loc.getDouble("lng")
                    val label = r.optString("formatted_address", query)
                    Geo(lat, lon, label)
                }
            }.take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Autocomplete via the Google **Places API (New)** or a fallback to the Geocoding API.
     * Uses the `place/autocomplete/json` endpoint (Places API) if the user has a key.
     * If Places is not enabled but Geocoding is, it is used as a best-effort fallback.
     */
    private suspend fun googlePlacesAutoComplete(query: String, apiKey: String, limit: Int = 8): List<Geo> = withContext(Dispatchers.IO) {
        runCatching {
            // Places Autocomplete (legacy) returns a place_id without lat/lng; fetching the
            // coordinates would require another call per result. To keep it simple and save
            // quota, use the Geocoding API directly, which resolves the query and returns
            // formatted_address plus lat/lng in a single call.
            googleGeocode(query, apiKey, limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Reverse geocoding (lat,lon → readable label). Uses Google if an API key is set, otherwise
     * Nominatim. Useful for labeling a manually placed point (long-press on the map).
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, context: Context? = null): String = withContext(Dispatchers.IO) {
        val key = context?.let { TravelSettings.geocodingApiKey(it).trim() } ?: ""
        if (key.isNotEmpty()) {
            runCatching {
                val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lon&language=en&key=$key"
                val body = httpGet(url) ?: return@runCatching null
                val obj = JSONObject(body)
                if (obj.optString("status") != "OK") return@runCatching null
                val arr = obj.optJSONArray("results") ?: return@runCatching null
                if (arr.length() == 0) return@runCatching null
                arr.getJSONObject(0).optString("formatted_address")
            }.getOrNull()?.let { return@withContext it }
        }
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18"
            val body = httpGet(url) ?: return@runCatching null
            JSONObject(body).optString("display_name")
        }.getOrNull() ?: "%.5f, %.5f".format(lat, lon)
    }

    /**
     * Single-result geocoding (backward compatible). Uses Nominatim `/search`.
     * Returns the first candidate or null.
     */
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

    /**
     * Geocoding suggestions for the search autocomplete. Combines **Photon** (Komoot, OSM) with
     * **Nominatim** and a **Nominatim structured** fallback (street + city) for very specific rural
     * addresses (e.g. "Castro 24, Cercedo-Cotobade"), deduplicating by lat+lon.
     *
     * If [biasLat]/[biasLon] (the user's current location) is passed, Photon uses it to order nearby
     * results first (greatly improving local recall). None of these require an API key.
     *
     * Photon format: features[i].geometry.coordinates=[lon,lat] and properties.name, city, street, ...
     * Nominatim format: [{lat,lon,display_name,...}].
     */
    suspend fun geocodeSuggestions(
        query: String,
        limit: Int = 12,
        biasLat: Double? = null,
        biasLon: Double? = null,
        context: Context? = null
    ): List<Geo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = query.trim()

        // Google first if an API key is available (much better recall for rural addresses).
        val googleKey = context?.let { TravelSettings.geocodingApiKey(it).trim() } ?: ""
        if (googleKey.isNotEmpty()) {
            val g = googlePlacesAutoComplete(q, googleKey, limit)
            if (g.isNotEmpty()) return@withContext g
        }

        val cc = context?.let { deviceCountry(it) } ?: ""
        val lang = context?.let { deviceLang(it) } ?: "en"
        val vb = viewboxParam(biasLat, biasLon)

        val photon = runCatching {
            val sb = StringBuilder("https://photon.komoot.io/api/?q=")
                .append(URLEncoder.encode(q, "UTF-8")).append("&limit=").append(limit).append("&lang=").append(lang)
            if (biasLat != null && biasLon != null && biasLat != 0.0 && biasLon != 0.0) {
                sb.append("&lon=").append(biasLon).append("&lat=").append(biasLat)
            }
            parsePhoton(httpGet(sb.toString()))
        }.getOrDefault(emptyList())

        // Nominatim free-form, biased (not restricted) toward the user's area via viewbox+bounded=0.
        val nominatim = runCatching {
            parseNominatim(httpGet(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb&q=" +
                    URLEncoder.encode(q, "UTF-8")), q)
        }.getOrDefault(emptyList())

        // Fallbacks for specific rural addresses (what Google finds and the free-form query does not).
        // Only triggered when the primary results are sparse, to avoid penalizing latency.
        val extra = ArrayList<Geo>()
        if (photon.size + nominatim.size < 4) {
            // (a) restricted to the device country — improves local recall.
            if (cc.isNotEmpty()) extra += runCatching {
                parseNominatim(httpGet(
                    "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&countrycodes=$cc&limit=$limit&q=" +
                        URLEncoder.encode(q, "UTF-8")), q)
            }.getOrDefault(emptyList())
            // (b) structured street+city (+ postalcode if a 5-digit code appears).
            if (q.contains(",")) extra += runCatching {
                val parts = q.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val street = parts.first()
                val postal = parts.firstOrNull { it.matches(Regex("\\d{5}")) } ?: ""
                val city = parts.drop(1).firstOrNull { !it.matches(Regex("\\d{5}")) } ?: ""
                val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit")
                if (cc.isNotEmpty()) sb.append("&countrycodes=").append(cc)
                sb.append("&street=").append(URLEncoder.encode(street, "UTF-8"))
                if (city.isNotEmpty()) sb.append("&city=").append(URLEncoder.encode(city, "UTF-8"))
                if (postal.isNotEmpty()) sb.append("&postalcode=").append(postal)
                parseNominatim(httpGet(sb.toString()), q)
            }.getOrDefault(emptyList())
            // (c) without a house number: OSM often lacks it; at least locate the street/place.
            stripTrailingNumber(q)?.let { stripped ->
                extra += runCatching {
                    val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb")
                    if (cc.isNotEmpty()) sb.append("&countrycodes=").append(cc)
                    sb.append("&q=").append(URLEncoder.encode(stripped, "UTF-8"))
                    parseNominatim(httpGet(sb.toString()), stripped)
                }.getOrDefault(emptyList())
            }
        }

        // Dedupe by (lat,lon) rounded to 5 decimals (~1 m) to avoid duplicates from house numbers.
        val seen = HashSet<Long>()
        val combined = ArrayList<Geo>()
        (photon + nominatim + extra).forEach { g ->
            val key = (Math.round(g.lat * 1e5) * 1000000L) + Math.round(g.lon * 1e5)
            if (seen.add(key)) combined.add(g)
        }
        // Reorder by distance to the user when a bias is available — nearest first.
        if (biasLat != null && biasLon != null) {
            combined.sortBy {
                val dx = it.lat - biasLat
                val dy = it.lon - biasLon
                dx * dx + dy * dy
            }
        }
        combined
    }

    // ── Geocoding helpers ────────────────────────────────────────────────────────────────
    private fun deviceCountry(context: Context): String = runCatching {
        context.resources.configuration.locales[0].country.takeIf { it.isNotBlank() }?.lowercase() ?: ""
    }.getOrDefault("")

    private fun deviceLang(context: Context): String = runCatching {
        context.resources.configuration.locales[0].language.takeIf { it.isNotBlank() } ?: "en"
    }.getOrDefault("en")

    /** viewbox = lonMin,latMin,lonMax,latMax with bounded=0 ⇒ **biases** toward the area without excluding. */
    private fun viewboxParam(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null || lat == 0.0 || lon == 0.0) return ""
        val d = 1.5
        return "&viewbox=${lon - d},${lat - d},${lon + d},${lat + d}&bounded=0"
    }

    /** Strips a trailing house number ("Rúa X 5" → "Rúa X") to at least locate the street. */
    private fun stripTrailingNumber(q: String): String? {
        val t = q.trim()
        val m = Regex("^(.*?)[,\\s]+\\d{1,4}\\s*$").find(t) ?: return null
        val base = m.groupValues[1].trim()
        return if (base.length >= 3 && base != t) base else null
    }

    private fun parseNominatim(body: String?, fallbackLabel: String): List<Geo> {
        body ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Geo(o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("display_name", fallbackLabel))
            }
        }.getOrDefault(emptyList())
    }

    private fun parsePhoton(body: String?): List<Geo> {
        body ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("features") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val f = arr.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                if (coords.length() < 2) return@mapNotNull null
                val lon = coords.getDouble(0); val lat = coords.getDouble(1)
                val p = f.optJSONObject("properties") ?: JSONObject()
                val name = p.optString("name", "")
                val street = p.optString("street", "")
                val housenumber = p.optString("housenumber", "")
                val city = p.optString("city", "")
                val postcode = p.optString("postcode", "")
                val state = p.optString("state", "")
                val country = p.optString("country", "")
                val parts = listOf(name, (if (housenumber.isNotEmpty()) "$housenumber $street" else street), postcode, city, state, country)
                    .filter { it.isNotBlank() }
                Geo(lat, lon, if (parts.isNotEmpty()) parts.joinToString(", ") else "$lat, $lon")
            }
        }.getOrDefault(emptyList())
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

    /**
     * Checks whether the system location services (GPS / network provider) are enabled.
     * Keyless: uses `LocationManager.isProviderEnabled` (no Play Services Location).
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

}