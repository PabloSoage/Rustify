package com.varuna.rustify.travel

import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.formUrlEncode
import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.formatTemplate
import com.varuna.rustify.util.optArray
import com.varuna.rustify.util.optObject
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.parseArray
import com.varuna.rustify.util.parseObject
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geocoding (Nominatim) + routing (OSRM). Both are public OpenStreetMap services with no API key.
 * Best-effort: heavy usage is throttled by the public demo servers; for production, self-host OSRM
 * and Nominatim. Every call returns null (or an empty list) on any failure.
 */
object TravelRouting {
    data class Geo(val lat: Double, val lon: Double, val label: String)
    /**
     * [geometryGeoJson] is the OSRM route geometry as a GeoJSON LineString (for MapLibre); [points]
     * is the same line as (lat, lon) pairs, for a map that draws it itself.
     */
    data class Route(
        val durationSec: Long,
        val distanceM: Double,
        val geometryGeoJson: String?,
        val points: List<Pair<Double, Double>> = emptyList()
    )

    /** Ten seconds to connect and fifteen between bytes, HttpURLConnection's timeouts before Ktor. */
    private val http by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    private suspend fun httpGet(url: String): String? = try {
        val response = http.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (e: Exception) { null }

    /** What the OpenStreetMap services' usage policies ask for: an agent that names the app. */
    const val USER_AGENT = "Rustify/1.0 (travel-playlist)"

    /**
     * Geocoding via the Google **Geocoding API** (requires the user's API key).
     * Returns up to [limit] results with lat/lon and a formatted address, ordered by relevance.
     * Documentation: https://developers.google.com/maps/documentation/geocoding/overview
     */
    private suspend fun googleGeocode(query: String, apiKey: String, limit: Int = 8): List<Geo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://maps.googleapis.com/maps/api/geocode/json?address=" +
                formUrlEncode(query) + "&key=" + apiKey
            parseGoogle(httpGet(url) ?: return@runCatching emptyList(), query).take(limit)
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
     * Reverse geocoding (lat,lon → readable label). Uses Google if [googleKey] is set, otherwise
     * Nominatim. Useful for labeling a manually placed point (long-press on the map).
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, googleKey: String = ""): String = withContext(Dispatchers.IO) {
        val key = googleKey.trim()
        if (key.isNotEmpty()) {
            runCatching {
                val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lon&language=en&key=$key"
                val obj = jsonObject(httpGet(url)) ?: return@runCatching null
                if (obj["status"].optString() != "OK") return@runCatching null
                val arr = obj["results"].optArray() ?: return@runCatching null
                if (arr.isEmpty()) return@runCatching null
                (arr[0] as JsonObject)["formatted_address"].optString()
            }.getOrNull()?.let { return@withContext it }
        }
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18"
            jsonObject(httpGet(url) ?: return@runCatching null)?.get("display_name").optString()
        }.getOrNull() ?: coordinatesLabel(lat, lon)
    }

    /** "43.37100, -8.39600", what a point placed by hand is called until its address arrives. */
    fun coordinatesLabel(lat: Double, lon: Double): String = formatTemplate("%.5f, %.5f", lat, lon)

    /**
     * Single-result geocoding (backward compatible). Uses Nominatim `/search`.
     * Returns the first candidate or null.
     */
    suspend fun geocode(query: String): Geo? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                formUrlEncode(query)) ?: return@runCatching null
            parseNominatim(body, query).firstOrNull()
        }.getOrNull()
    }

    /**
     * Geocoding suggestions for the search autocomplete. Combines **Photon** (Komoot, OSM) with
     * **Nominatim** and a **Nominatim structured** fallback (street + city) for very specific rural
     * addresses (e.g. "Castro 24, Cercedo-Cotobade"), deduplicating by lat+lon.
     *
     * If [biasLat]/[biasLon] (the user's current location) is passed, Photon uses it to order nearby
     * results first (greatly improving local recall). None of these require an API key; [googleKey],
     * when set, is asked first. [country] and [lang] are the device's (`es`, `en`).
     *
     * Photon format: features[i].geometry.coordinates=[lon,lat] and properties.name, city, street, ...
     * Nominatim format: [{lat,lon,display_name,...}].
     */
    suspend fun geocodeSuggestions(
        query: String,
        limit: Int = 12,
        biasLat: Double? = null,
        biasLon: Double? = null,
        googleKey: String = "",
        country: String = "",
        lang: String = "en"
    ): List<Geo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = query.trim()

        // Google first if an API key is available (much better recall for rural addresses).
        val key = googleKey.trim()
        if (key.isNotEmpty()) {
            val g = googlePlacesAutoComplete(q, key, limit)
            if (g.isNotEmpty()) return@withContext g
        }

        val cc = country.lowercase()
        val vb = viewboxParam(biasLat, biasLon)

        val photon = runCatching {
            parsePhoton(httpGet(photonUrl(q, limit, lang, biasLat, biasLon)))
        }.getOrDefault(emptyList())

        // Nominatim free-form, biased (not restricted) toward the user's area via viewbox+bounded=0.
        val nominatim = runCatching {
            parseNominatim(httpGet(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb&q=" +
                    formUrlEncode(q)), q)
        }.getOrDefault(emptyList())

        // Fallbacks for specific rural addresses (what Google finds and the free-form query does not).
        // Only triggered when the primary results are sparse, to avoid penalizing latency.
        val extra = ArrayList<Geo>()
        if (photon.size + nominatim.size < 4) {
            // (a) restricted to the device country — improves local recall.
            if (cc.isNotEmpty()) extra += runCatching {
                parseNominatim(httpGet(
                    "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&countrycodes=$cc&limit=$limit&q=" +
                        formUrlEncode(q)), q)
            }.getOrDefault(emptyList())
            // (b) structured street+city (+ postalcode if a 5-digit code appears).
            if (q.contains(",")) extra += runCatching {
                val parts = q.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val street = parts.first()
                val postal = parts.firstOrNull { it.matches(Regex("\\d{5}")) } ?: ""
                val city = parts.drop(1).firstOrNull { !it.matches(Regex("\\d{5}")) } ?: ""
                val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit")
                if (cc.isNotEmpty()) sb.append("&countrycodes=").append(cc)
                sb.append("&street=").append(formUrlEncode(street))
                if (city.isNotEmpty()) sb.append("&city=").append(formUrlEncode(city))
                if (postal.isNotEmpty()) sb.append("&postalcode=").append(postal)
                parseNominatim(httpGet(sb.toString()), q)
            }.getOrDefault(emptyList())
            // (c) without a house number: OSM often lacks it; at least locate the street/place.
            stripTrailingNumber(q)?.let { stripped ->
                extra += runCatching {
                    val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb")
                    if (cc.isNotEmpty()) sb.append("&countrycodes=").append(cc)
                    sb.append("&q=").append(formUrlEncode(stripped))
                    parseNominatim(httpGet(sb.toString()), stripped)
                }.getOrDefault(emptyList())
            }
        }

        rank(photon + nominatim + extra, q, biasLat, biasLon)
    }

    /**
     * The suggestions in the order they are offered: deduplicated, nearest first when there is a
     * bias, and a place whose own name is what was typed before everything else.
     */
    internal fun rank(results: List<Geo>, q: String, biasLat: Double?, biasLon: Double?): List<Geo> {
        // Dedupe by (lat,lon) rounded to 5 decimals (~1 m) to avoid duplicates from house numbers.
        val seen = HashSet<Long>()
        val combined = ArrayList<Geo>()
        results.forEach { g ->
            val key = ((g.lat * 1e5).roundToLong() * 1000000L) + (g.lon * 1e5).roundToLong()
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
        // But a place whose own name *is* what was typed goes first. Nearest-first alone put
        // "Rúa Santiago de Compostela" (a street nearby) and a charger above the city itself when
        // "Santiago de Compostela" was typed — seen on the emulator in 4.4. The match is ranked by the
        // core's search over each result's own name (the label's first part), not by hand.
        val ranked = runCatching {
            com.varuna.rustify.util.LocalSearch.index("travel-geo", combined, { it.label }) {
                listOf(it.label.substringBefore(","))
            }
            com.varuna.rustify.util.LocalSearch.filter("travel-geo", combined, q) { it.label }
        }.getOrDefault(emptyList())
        val exact = ranked.filter { it.label.substringBefore(",").trim().length <= q.length + 2 }
        return (exact + combined).distinct()
    }

    // ── Geocoding helpers ────────────────────────────────────────────────────────────────

    /**
     * Photon answers 400 to a language it does not have, and it has few: asked in Spanish, every
     * search lost its Photon half and lived on Nominatim alone. Without `lang` it names places in
     * their own language, which is what a Spanish speaker in Spain reads anyway.
     */
    private val PHOTON_LANGUAGES = setOf("en", "de", "fr")

    internal fun photonUrl(q: String, limit: Int, lang: String, biasLat: Double?, biasLon: Double?): String {
        val sb = StringBuilder("https://photon.komoot.io/api/?q=").append(formUrlEncode(q)).append("&limit=").append(limit)
        if (lang in PHOTON_LANGUAGES) sb.append("&lang=").append(lang)
        if (biasLat != null && biasLon != null && biasLat != 0.0 && biasLon != 0.0) {
            sb.append("&lon=").append(biasLon).append("&lat=").append(biasLat)
        }
        return sb.toString()
    }

    /** viewbox = lonMin,latMin,lonMax,latMax with bounded=0 ⇒ **biases** toward the area without excluding. */
    private fun viewboxParam(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null || lat == 0.0 || lon == 0.0) return ""
        val d = 1.5
        return "&viewbox=${lon - d},${lat - d},${lon + d},${lat + d}&bounded=0"
    }

    /** Strips a trailing house number ("Rúa X 5" → "Rúa X") to at least locate the street. */
    internal fun stripTrailingNumber(q: String): String? {
        val t = q.trim()
        val m = Regex("^(.*?)[,\\s]+\\d{1,4}\\s*$").find(t) ?: return null
        val base = m.groupValues[1].trim()
        return if (base.length >= 3 && base != t) base else null
    }

    /** Nominatim's lat and lon are strings ("43.3713"); a number is read as well. */
    private fun JsonObject.coordinate(key: String): Double =
        this[key]?.jsonPrimitive?.let { it.doubleOrNull ?: it.content.toDouble() } ?: error("no $key")

    /** A number, or NaN where there is none: org.json's `optDouble`. */
    private fun JsonElement?.number(): Double =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: Double.NaN

    private fun jsonObject(text: String?): JsonObject? = text?.let { runCatching { parseObject(it) }.getOrNull() }

    private fun jsonArray(text: String?): JsonArray? = text?.let { runCatching { parseArray(it) }.getOrNull() }

    internal fun parseNominatim(body: String?, fallbackLabel: String): List<Geo> {
        body ?: return emptyList()
        return runCatching {
            val arr = jsonArray(body) ?: return@runCatching emptyList()
            arr.map { e ->
                val o = e as JsonObject
                Geo(o.coordinate("lat"), o.coordinate("lon"), o["display_name"].optString(fallbackLabel))
            }
        }.getOrDefault(emptyList())
    }

    internal fun parsePhoton(body: String?): List<Geo> {
        body ?: return emptyList()
        return runCatching {
            val arr = jsonObject(body)?.get("features").optArray() ?: JsonArray(emptyList())
            arr.mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val coords = f["geometry"].optObject()?.get("coordinates").optArray() ?: return@mapNotNull null
                if (coords.size < 2) return@mapNotNull null
                val lon = coords[0].jsonPrimitive.content.toDouble()
                val lat = coords[1].jsonPrimitive.content.toDouble()
                val p = f["properties"].optObject() ?: JsonObject(emptyMap())
                val name = p["name"].optString("")
                val street = p["street"].optString("")
                val housenumber = p["housenumber"].optString("")
                val city = p["city"].optString("")
                val postcode = p["postcode"].optString("")
                val state = p["state"].optString("")
                val country = p["country"].optString("")
                val parts = listOf(name, (if (housenumber.isNotEmpty()) "$housenumber $street" else street), postcode, city, state, country)
                    .filter { it.isNotBlank() }
                Geo(lat, lon, if (parts.isNotEmpty()) parts.joinToString(", ") else "$lat, $lon")
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseGoogle(body: String, query: String): List<Geo> {
        val obj = jsonObject(body) ?: return emptyList()
        if (obj["status"].optString() != "OK" && obj["status"].optString() != "ZERO_RESULTS") return emptyList()
        val arr = obj["results"].optArray() ?: return emptyList()
        return arr.mapNotNull { e ->
            val r = e as JsonObject
            val loc = r["geometry"].optObject()?.get("location").optObject() ?: return@mapNotNull null
            Geo(loc["lat"].number(), loc["lng"].number(), r["formatted_address"].optString(query))
        }
    }

    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
            parseRoute(httpGet(url) ?: return@runCatching null)
        }.getOrNull()
    }

    internal fun parseRoute(body: String): Route? {
        val routes = jsonObject(body)?.get("routes").optArray() ?: return null
        if (routes.isEmpty()) return null
        val r = routes[0] as JsonObject
        val geometry = r["geometry"].optObject()
        val points = geometry?.get("coordinates").optArray()?.mapNotNull { c ->
            val pair = c as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) null
            else pair[1].jsonPrimitive.content.toDouble() to pair[0].jsonPrimitive.content.toDouble()
        }.orEmpty()
        return Route(
            durationSec = r["duration"].number().toLong(),
            distanceM = r["distance"].number(),
            geometryGeoJson = geometry?.toString(),
            points = points
        )
    }

    /** Great-circle distance in metres, what `Location.distanceTo` gave to within a few metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rad = PI / 180
        val dLat = (lat2 - lat1) * rad
        val dLon = (lon2 - lon1) * rad
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_008.8 * asin(sqrt(a))
    }
}
