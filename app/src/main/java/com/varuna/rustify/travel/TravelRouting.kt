package com.varuna.rustify.travel

import android.content.Context
import android.location.LocationManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * E99 — geocoding (Nominatim) + routing (OSRM). Ambos son servicios públicos OpenStreetMap
 * sin API key. Best-effort: el uso intensivo está limitado por los servidores demo públicos;
 * para producción deberías self-hostear OSRM / Nominatim. Todas las llamadas devuelven null
 * (o listas vacías) ante cualquier fallo.
 */
object TravelRouting {
    data class Geo(val lat: Double, val lon: Double, val label: String)
    /** [geometryGeoJson] es la geometría de la ruta OSRM como LineString GeoJSON (para dibujar). */
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
     * Geocoding via Google **Geocoding API** (requiere API key del usuario).
     * Devuelve hasta [limit] resultados con lat/lon + dirección formateada. Ordenados por relevancia.
     * Documentación: https://developers.google.com/maps/documentation/geocoding/overview
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
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                val loc = r.optJSONObject("geometry")?.optJSONObject("location")
                if (loc == null) null else {
                    val lat = loc.getDouble("lat")
                    val lon = loc.getDouble("lng")
                    val label = r.optString("formatted_address", query)
                    Geo(lat, lon, label)
                }
            }.filterNotNull().take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Autocomplete via Google **Places API (New)** o fallback al Geocoding API.
     * Usa el endpoint `place/autocomplete/json` (Places API) si el usuario tiene key.
     * Si Places no está habilitado pero Geocoding sí, lo utiliza como best-effort.
     */
    private suspend fun googlePlacesAutoComplete(query: String, apiKey: String, limit: Int = 8): List<Geo> = withContext(Dispatchers.IO) {
        runCatching {
            // Places Autocomplete (legacy) devuelve place_id sin lat/lng; para obtener las
            // coordenadas haría falta otra llamada por cada resultado. Para simplificar y
            // ahorrar cuota, usamos Geocoding API directamente (que sí resuelve la query y
            // devuelve formatted_address + lat/lng de una).
            googleGeocode(query, apiKey, limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Reverse geocoding (lat,lon → etiqueta legible). Usa Google si hay API key, sinon Nominatim.
     * Útil para etiquetar un punto marcado a mano (long-press en el mapa).
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
     * Geocodificación de un único resultado (compat hacia atrás). Usa Nominatim `/search`.
     * Devuelve el primer candidato o null.
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
     * Sugerencias de geocoding para autocomplete del buscador. Combina **Photon** (Komoot, OSM)
     * con **Nominatim** y un fallback **Nominatim structured** (street + city) para direcciones
     * rurales muy concretas (ej. "Castro 24, Cercedo-Cotobade"), deduplicando por lat+lon.
     *
     * Si se pasa [biasLat]/[biasLon] (ubicación actual del usuario), Photon los usa para ordenar
     * resultados cercanos primero (recall en Galicia mejora muchísimo). Sin API key en ninguno.
     *
     * Photon format: features[i].geometry.coordinates=[lon,lat] y properties.name, city, street, ...
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

        // Google primero si hay API key (mucho mejor recall para direcciones rurales).
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

        // Nominatim free-form, sesgado (no restringido) a la zona del usuario con viewbox+bounded=0.
        val nominatim = runCatching {
            parseNominatim(httpGet(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb&q=" +
                    URLEncoder.encode(q, "UTF-8")), q)
        }.getOrDefault(emptyList())

        // Fallbacks para direcciones rurales concretas (lo que Google encuentra y el free-form no).
        // Solo se disparan si los resultados principales son escasos, para no penalizar la latencia.
        val extra = ArrayList<Geo>()
        if (photon.size + nominatim.size < 4) {
            // (a) restringido al país del dispositivo — mejora el recall local (ej. Galicia).
            if (cc.isNotEmpty()) extra += runCatching {
                parseNominatim(httpGet(
                    "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&countrycodes=$cc&limit=$limit&q=" +
                        URLEncoder.encode(q, "UTF-8")), q)
            }.getOrDefault(emptyList())
            // (b) structured street+city (+ postalcode si aparece un código de 5 cifras).
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
            // (c) sin número de portal: OSM a menudo no lo tiene; al menos ubica la calle/lugar.
            stripTrailingNumber(q)?.let { stripped ->
                extra += runCatching {
                    val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&accept-language=$lang&limit=$limit$vb")
                    if (cc.isNotEmpty()) sb.append("&countrycodes=").append(cc)
                    sb.append("&q=").append(URLEncoder.encode(stripped, "UTF-8"))
                    parseNominatim(httpGet(sb.toString()), stripped)
                }.getOrDefault(emptyList())
            }
        }

        // Dedupe por (lat,lon) redondeado a 5 decimales (~1 m) para no mostrar duplicados por número de casa.
        val seen = HashSet<Long>()
        val combined = ArrayList<Geo>()
        (photon + nominatim + extra).forEach { g ->
            val key = (Math.round(g.lat * 1e5) * 1000000L) + Math.round(g.lon * 1e5)
            if (seen.add(key)) combined.add(g)
        }
        // Reordena por distancia al usuario si tenemos bias — los más cercanos arriba.
        if (biasLat != null && biasLon != null) {
            combined.sortBy {
                val dx = it.lat - biasLat
                val dy = it.lon - biasLon
                dx * dx + dy * dy
            }
        }
        combined
    }

    // ── Helpers de geocoding ─────────────────────────────────────────────────────────────
    private fun deviceCountry(context: Context): String = runCatching {
        context.resources.configuration.locales[0].country?.takeIf { it.isNotBlank() }?.lowercase() ?: ""
    }.getOrDefault("")

    private fun deviceLang(context: Context): String = runCatching {
        context.resources.configuration.locales[0].language?.takeIf { it.isNotBlank() } ?: "en"
    }.getOrDefault("en")

    /** viewbox = lonMin,latMin,lonMax,latMax con bounded=0 ⇒ **sesga** hacia la zona sin excluir. */
    private fun viewboxParam(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null || lat == 0.0 || lon == 0.0) return ""
        val d = 1.5
        return "&viewbox=${lon - d},${lat - d},${lon + d},${lat + d}&bounded=0"
    }

    /** Quita un número de portal final ("Rúa X 5" → "Rúa X") para al menos ubicar la vía. */
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
     * Comprueba si los servicios de ubicación del sistema (GPS / network provider) están activados.
     * Keyless: usa `LocationManager.isProviderEnabled` (sin Play Services Location).
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Lanza la página de Ajustes del sistema para activar la ubicación (keyless, sin Play Services),
     * ya que Android no permite activar GPS mediante un diálogo sin permisos privilegiados.
     * El llamador envuelve este Intent en un try/catch y guía al usuario.
     */
    fun intentEnableLocation() = IntentData(
        Settings.ACTION_LOCATION_SOURCE_SETTINGS
    )

    data class IntentData(val action: String)
}