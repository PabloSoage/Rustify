package com.varuna.rustify.travel

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Travel screen reads from the services it asks, over answers recorded from them (trimmed),
 * and the arithmetic of the map drawn where there is no MapLibre.
 */
class TravelRoutingTest {
    // nominatim.openstreetmap.org/search?format=json&q=A+Coruña: lat and lon are strings there.
    private val nominatim = """[{"place_id":1,"lat":"43.3709703","lon":"-8.3959425","display_name":"A Coruña, Galicia, España","type":"city"},
        {"place_id":2,"lat":"43.30","lon":"-8.50","display_name":"Provincia da Coruña, Galicia, España"}]"""

    // photon.komoot.io/api/?q=Santiago de Compostela: coordinates are [lon, lat].
    private val photon = """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[-8.5448445,42.8804475]},
         "properties":{"name":"Santiago de Compostela","state":"Galicia","country":"España"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[-8.40,43.36]},
         "properties":{"street":"Rúa Santiago de Compostela","housenumber":"5","postcode":"15004","city":"A Coruña","country":"España"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[1.0]},"properties":{"name":"broken"}}]}"""

    // router.project-osrm.org/route/v1/driving/…?overview=full&geometries=geojson
    private val osrm = """{"code":"Ok","routes":[{"geometry":{"coordinates":[[-8.3959,43.3709],[-8.45,43.1],[-8.5448,42.8804]],"type":"LineString"},
        "legs":[],"distance":73412.3,"duration":3381.9,"weight":3381.9}],"waypoints":[]}"""

    // maps.googleapis.com/maps/api/geocode/json?address=…
    private val google = """{"results":[{"formatted_address":"Castro, 24, 36130 Cercedo-Cotobade, Pontevedra, España",
        "geometry":{"location":{"lat":42.4879,"lng":-8.4791}}}],"status":"OK"}"""

    @Test
    fun nominatimReadsItsCoordinatesFromStrings() {
        val geo = TravelRouting.parseNominatim(nominatim, "A Coruña")
        assertEquals(2, geo.size)
        assertEquals(TravelRouting.Geo(43.3709703, -8.3959425, "A Coruña, Galicia, España"), geo[0])
        assertEquals(emptyList(), TravelRouting.parseNominatim("<html>rate limited</html>", "x"))
        assertEquals(emptyList(), TravelRouting.parseNominatim(null, "x"))
    }

    @Test
    fun photonTurnsLonLatRoundAndBuildsTheLabel() {
        val geo = TravelRouting.parsePhoton(photon)
        assertEquals(2, geo.size, "the feature with one coordinate is skipped")
        assertEquals(42.8804475, geo[0].lat)
        assertEquals(-8.5448445, geo[0].lon)
        assertEquals("Santiago de Compostela, Galicia, España", geo[0].label)
        assertEquals("5 Rúa Santiago de Compostela, 15004, A Coruña, España", geo[1].label)
    }

    @Test
    fun osrmGivesTheTimeTheDistanceAndTheLine() {
        val route = TravelRouting.parseRoute(osrm)!!
        assertEquals(3381L, route.durationSec)
        assertEquals(73412.3, route.distanceM)
        assertEquals(listOf(43.3709 to -8.3959, 43.1 to -8.45, 42.8804 to -8.5448), route.points)
        assertTrue(route.geometryGeoJson!!.contains("\"LineString\""), "MapLibre's GeoJSON is kept")
        assertNull(TravelRouting.parseRoute("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun googleIsReadWhenItSaysOk() {
        val geo = TravelRouting.parseGoogle(google, "Castro 24, Cercedo-Cotobade")
        assertEquals(listOf(TravelRouting.Geo(42.4879, -8.4791, "Castro, 24, 36130 Cercedo-Cotobade, Pontevedra, España")), geo)
        assertEquals(emptyList(), TravelRouting.parseGoogle("""{"results":[],"status":"REQUEST_DENIED"}""", "x"))
    }

    @Test
    fun photonIsAskedInALanguageItHasOrInNone() {
        // photon.komoot.io answered "400 Bad Request" to lang=es, seen from the web build.
        assertEquals("https://photon.komoot.io/api/?q=A+Coru%C3%B1a&limit=12", TravelRouting.photonUrl("A Coruña", 12, "es", null, null))
        assertEquals("https://photon.komoot.io/api/?q=x&limit=5&lang=de&lon=-8.4&lat=43.3", TravelRouting.photonUrl("x", 5, "de", 43.3, -8.4))
    }

    @Test
    fun aHouseNumberIsDroppedToFindTheStreet() {
        assertEquals("Rúa X", TravelRouting.stripTrailingNumber("Rúa X 5"))
        assertEquals("Castro", TravelRouting.stripTrailingNumber("Castro, 24"))
        assertNull(TravelRouting.stripTrailingNumber("A Coruña"))
    }

    @Test
    fun duplicatesGoAndTheNearestComesFirst() {
        val far = TravelRouting.Geo(40.4168, -3.7038, "Madrid")
        val near = TravelRouting.Geo(43.36, -8.40, "Rúa Madrid, A Coruña")
        val again = near.copy(label = "Rúa Madrid 3, A Coruña")
        val ranked = TravelRouting.rank(listOf(far, near, again), "zzz", 43.37, -8.39)
        assertEquals(listOf(near, far), ranked)
    }

    @Test
    fun theDistanceIsTheGreatCircle() {
        // A Coruña to Santiago in a straight line: about 57 km.
        val m = TravelRouting.distanceM(43.3709703, -8.3959425, 42.8804475, -8.5448445)
        assertTrue(abs(m - 55_700) < 1_500, "was $m")
        assertEquals(0.0, TravelRouting.distanceM(1.0, 2.0, 1.0, 2.0))
    }

    @Test
    fun aPointPlacedByHandIsNamedByItsCoordinates() {
        assertEquals("43.37097, -8.39594", java.util.Locale.ROOT.let {
            val saved = java.util.Locale.getDefault()
            java.util.Locale.setDefault(java.util.Locale.US)
            try { TravelRouting.coordinatesLabel(43.3709703, -8.3959425) } finally { java.util.Locale.setDefault(saved) }
        })
    }

    @Test
    fun theMapFindsThePointUnderTheFingerAgain() {
        val view = MapView(MapView.x(-8.3959), MapView.y(43.3709), 13.0)
        val at = view.screenOf(42.88, -8.5448, 800f, 600f, 256f)
        val (lat, lon) = view.latLonAt(at, 800, 600, 256f)
        assertTrue(abs(lat - 42.88) < 1e-6 && abs(lon + 8.5448) < 1e-6, "$lat, $lon")
        // The centre is the centre.
        assertEquals(Offset(400f, 300f), view.screenOf(43.3709, -8.3959, 800f, 600f, 256f))
    }

    @Test
    fun zoomingKeepsThePointUnderThePointer() {
        val view = MapView(MapView.x(-3.7), MapView.y(40.0), 5.0)
        val pointer = Offset(100f, 500f)
        val before = view.latLonAt(pointer, 800, 600, 256f)
        view.zoomAround(pointer, 2.5, 800, 600, 256f, maxZoom = 19)
        val after = view.latLonAt(pointer, 800, 600, 256f)
        assertEquals(7.5, view.zoom)
        assertTrue(abs(before.first - after.first) < 1e-9 && abs(before.second - after.second) < 1e-9, "$before $after")
        view.zoomAround(pointer, 30.0, 800, 600, 256f, maxZoom = 17)
        assertEquals(19.0, view.zoom, "two levels past the source's deepest tiles, no further")
    }

    @Test
    fun theMapWrapsEastWestAndStopsAtThePoles() {
        val view = MapView(MapView.x(179.9), MapView.y(0.0), 3.0)
        view.pan(10_000f, 0f, 256f)
        assertTrue(view.x in 0.0..1.0)
        view.pan(0f, 1e9f, 256f)
        assertEquals(1.0, view.y)
    }

    @Test
    fun tileUrlsFillTheirTemplate() {
        assertEquals("https://tile.openstreetmap.org/5/15/11.png", TravelSettings.RASTER_TILES[0].url(5, 15, 11))
        // Esri's is row before column.
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/5/11/15",
            TravelSettings.RASTER_TILES[2].url(5, 15, 11)
        )
        assertEquals(TravelSettings.MAP_STYLES.size, TravelSettings.RASTER_TILES.size, "a raster for every style")
    }
}
