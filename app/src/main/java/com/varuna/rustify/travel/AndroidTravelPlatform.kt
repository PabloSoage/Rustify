package com.varuna.rustify.travel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.varuna.rustify.ui.AppPlatform
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The Travel screen's map and location on Android, as they were before the screen moved to
 * `:sharedUi`: MapLibre over OpenFreeMap (or the bundled raster styles), `LocationManager` with
 * GPS and network, and Google Maps' own intents for directions.
 */
class AndroidTravelPlatform(private val context: Context) : TravelPlatform {

    override val attribution: String get() = "© OpenStreetMap · OpenFreeMap · Esri · OpenTopoMap"

    @Composable
    override fun Map(
        modifier: Modifier,
        styleIndex: Int,
        marks: TravelMarks,
        camera: TravelCamera,
        onLongPress: (lat: Double, lon: Double) -> Unit,
        onUserMove: () -> Unit
    ) {
        val map = remember { arrayOfNulls<MapLibreMap>(1) }
        val sources = remember { MapSources() }
        var ready by remember { mutableStateOf(false) }
        var styleSwitching by remember { mutableStateOf(false) }
        val longPress by rememberUpdatedState(onLongPress)
        val userMove by rememberUpdatedState(onUserMove)
        val currentMarks by rememberUpdatedState(marks)

        MapLibreView(
            modifier,
            styleUri = { TravelSettings.MAP_STYLES[styleIndex] },
            onMapReady = { m, style ->
                map[0] = m
                sources.register(style)
                // MapLibre's native compass is disabled: the screen has its own (Compose) one, always
                // visible and clickable to reset north.
                m.uiSettings.isCompassEnabled = false
                // Bearing rotates the screen's compass.
                m.addOnCameraMoveListener {
                    camera.bearing = m.cameraPosition.bearing
                    camera.zoom = m.cameraPosition.zoom
                }
                // When the user drags the map manually, unset followUser.
                // REASON_GESTURE = 1 (MapLibre/Mapbox OnCameraMoveStartedListener).
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == 1 && !styleSwitching) userMove()
                }
                m.addOnMapLongClickListener { point ->
                    longPress(point.latitude, point.longitude)
                    true
                }
                sources.show(currentMarks)
                ready = true
            }
        )

        // When the style changes from the dropdown, apply it to the already-loaded map
        // (MapView is created only once in the MapLibreView remember).
        var shownStyle by remember { mutableStateOf(styleIndex) }
        LaunchedEffect(styleIndex, ready) {
            val m = map[0] ?: return@LaunchedEffect
            if (styleIndex == shownStyle) return@LaunchedEffect
            shownStyle = styleIndex
            styleSwitching = true
            m.setStyle(Style.Builder().fromUri(TravelSettings.MAP_STYLES[styleIndex])) { style ->
                sources.register(style)
                // Reapply state: register recreates the GeoJsonSources with empty geojson, so the
                // current position/destination/route go back in so they don't disappear until the
                // next GPS callback (~2 s).
                sources.show(currentMarks)
                // Release the guard: from here on, user gestures do unset follow.
                styleSwitching = false
            }
        }
        LaunchedEffect(marks, ready) { if (ready) sources.show(marks) }
        LaunchedEffect(ready) {
            if (!ready) return@LaunchedEffect
            for (move in camera.moves) {
                val m = map[0] ?: continue
                when (move) {
                    is TravelCamera.Move.North -> {
                        m.animateCamera(
                            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0.0).build())
                        )
                        camera.bearing = 0.0
                    }
                    is TravelCamera.Move.To -> {
                        val update = CameraUpdateFactory.newLatLngZoom(
                            LatLng(move.lat, move.lon), move.zoom ?: m.cameraPosition.zoom
                        )
                        if (move.animate) m.animateCamera(update) else m.moveCamera(update)
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberLocation(): TravelLocation {
        val context = LocalContext.current
        val state = remember { AndroidTravelLocation(context) }
        val activity = context as? android.app.Activity
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state.hasPermission = granted
            state.enabled = isLocationEnabled(context)
            if (granted) {
                state.permanentlyDenied = false
                if (state.enabled) state.current = lastKnownLocation(context)
            } else {
                // If it can't be requested again (rationale=false after denial), it's permanently denied.
                state.permanentlyDenied = activity?.let {
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
                } ?: false
            }
        }
        val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            state.enabled = isLocationEnabled(context)
            if (state.enabled && state.hasPermission) state.current = lastKnownLocation(context)
        }
        state.askPermission = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        state.askSettings = { settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }

        // Re-check the permission when returning to the foreground: if the user revoked it from Android
        // settings, hasPermission goes back to false and the "Grant location" button reappears.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    state.hasPermission = hasLocationPermission(context)
                    state.enabled = isLocationEnabled(context)
                    if (state.hasPermission) {
                        state.permanentlyDenied = false
                        if (state.enabled && state.current == null) state.current = lastKnownLocation(context)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        LaunchedEffect(state.hasPermission, state.enabled) {
            if (state.hasPermission && state.enabled && state.current == null) state.current = lastKnownLocation(context)
        }
        DisposableEffect(state.hasPermission, state.enabled) {
            if (!state.hasPermission || !state.enabled) return@DisposableEffect onDispose {}
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@DisposableEffect onDispose {}
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    state.current = location.toGeo()
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                // Deprecated since API 29, but `LocationListener` only gained a default implementation in
                // API 30 and minSdk here is 29 - dropping the override would be an AbstractMethodError on a
                // device running 29.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, listener)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, listener)
            } catch (_: SecurityException) { }
            onDispose { try { lm.removeUpdates(listener) } catch (_: Exception) { } }
        }
        return state
    }

    override fun navigate(url: String, destLat: Double, destLon: Double, platform: AppPlatform) {
        val uri = url.toUri()
        // 1) try to open the Google Maps app directly; 2) if it isn't installed (or visible), open the
        // URL with any browser/maps app; 3) as a last resort, a geo: turn-by-turn intent.
        // FLAG_ACTIVITY_NEW_TASK in case the context is not an Activity.
        fun launch(i: Intent): Boolean = runCatching {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        val ok = launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) ||
            launch(Intent(Intent.ACTION_VIEW, uri)) ||
            launch(Intent(Intent.ACTION_VIEW, "google.navigation:q=$destLat,$destLon&mode=d".toUri()))
        if (!ok) android.widget.Toast.makeText(context, "Google Maps no disponible", android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
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
}

private class AndroidTravelLocation(context: Context) : TravelLocation {
    override val supported = true
    override var hasPermission by mutableStateOf(hasLocationPermission(context))
    override var enabled by mutableStateOf(AndroidTravelPlatform.isLocationEnabled(context))
    override var current by mutableStateOf<TravelRouting.Geo?>(null)
    // "Permanently denied": the user chose "don't ask again" or revoked the permission from Android
    // settings. In that case the system dialog no longer appears, so send them to app settings.
    var permanentlyDenied by mutableStateOf(false)
    var askPermission: () -> Unit = {}
    var askSettings: () -> Unit = {}
    private val appContext = context

    // Request the permission; if permanently denied, open the app settings instead.
    override fun requestPermission() {
        if (permanentlyDenied) {
            runCatching {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", appContext.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            askPermission()
        }
    }

    override fun openSettings() = askSettings()
}

private fun Location.toGeo() = TravelRouting.Geo(latitude, longitude, "")

/** The four GeoJSON sources and their layers, recreated with each style. */
private class MapSources {
    private var user: GeoJsonSource? = null
    private var dest: GeoJsonSource? = null
    private var origin: GeoJsonSource? = null
    private var route: GeoJsonSource? = null

    fun show(marks: TravelMarks) {
        user?.setGeoJson(marks.user?.let { pointFeatureJson(it.lat, it.lon) } ?: EMPTY_POINT_GEO_JSON)
        origin?.setGeoJson(marks.origin?.let { pointFeatureJson(it.lat, it.lon) } ?: EMPTY_POINT_GEO_JSON)
        dest?.setGeoJson(marks.destination?.let { pointFeatureJson(it.lat, it.lon) } ?: EMPTY_POINT_GEO_JSON)
        val geometry = marks.route?.geometryGeoJson
        if (geometry == null || marks.destination == null) {
            route?.setGeoJson(EMPTY_LINE_GEO_JSON)
            return
        }
        runCatching {
            val feature = JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject(geometry))
                put("properties", JSONObject())
            }
            val fc = JSONObject().apply {
                put("type", "FeatureCollection")
                put("features", JSONArray().apply { put(feature) })
            }
            route?.setGeoJson(fc.toString())
        }
    }

    fun register(style: Style) {
        runCatching {
            if (style.getSource("user-loc-source") == null) {
                val us = GeoJsonSource("user-loc-source", EMPTY_POINT_GEO_JSON)
                style.addSource(us)
                user = us
                style.addLayer(
                    CircleLayer("user-loc-layer", "user-loc-source")
                        .withProperties(
                            PropertyFactory.circleColor("#1DB954"),
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleOpacity(0.95f)
                        )
                )
            }
            if (style.getSource("dest-source") == null) {
                val ds = GeoJsonSource("dest-source", EMPTY_POINT_GEO_JSON)
                style.addSource(ds)
                dest = ds
                style.addLayer(
                    CircleLayer("dest-layer", "dest-source")
                        .withProperties(
                            PropertyFactory.circleColor("#E53935"),
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f)
                        )
                )
            }
            // Explicit origin (blue) — only visible when the user fixes an origin different from GPS.
            if (style.getSource("origin-source") == null) {
                val os = GeoJsonSource("origin-source", EMPTY_POINT_GEO_JSON)
                style.addSource(os)
                origin = os
                style.addLayer(
                    CircleLayer("origin-layer", "origin-source")
                        .withProperties(
                            PropertyFactory.circleColor("#2196F3"),
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f)
                        )
                )
            }
            if (style.getSource("route-source") == null) {
                val rs = GeoJsonSource("route-source", EMPTY_LINE_GEO_JSON)
                style.addSource(rs)
                route = rs
                style.addLayerBelow(
                    LineLayer("route-layer", "route-source")
                        .withProperties(
                            PropertyFactory.lineColor("#1DB954"),
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineOpacity(0.85f),
                            PropertyFactory.lineCap("round")
                        ),
                    "user-loc-layer"
                )
            }
        }
    }
}

private const val EMPTY_POINT_GEO_JSON = """{"type":"FeatureCollection","features":[]}"""
private const val EMPTY_LINE_GEO_JSON = """{"type":"FeatureCollection","features":[]}"""

private fun pointFeatureJson(lat: Double, lon: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}"""

@Composable
private fun MapLibreView(
    modifier: Modifier,
    styleUri: () -> String,
    onMapReady: (MapLibreMap, Style) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            try { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } catch (_: Exception) { }
        }
    }

    AndroidView(modifier = modifier, factory = {
        mapView.getMapAsync { map ->
            // Hide MapLibre's logo + (i): the (i) crashes when opening the attribution sheet over
            // asset:// styles without valid URLs. Attribution is shown as a Text overlay instead.
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isCompassEnabled = true
            map.setStyle(Style.Builder().fromUri(styleUri())) { style ->
                map.uiSettings.apply {
                    isRotateGesturesEnabled = true
                    isScrollGesturesEnabled = true
                    isZoomGesturesEnabled = true
                    isTiltGesturesEnabled = true
                    isDoubleTapGesturesEnabled = true
                }
                onMapReady(map, style)
            }
        }
        mapView
    })
}

private fun hasLocationPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun lastKnownLocation(context: Context): TravelRouting.Geo? = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.toGeo()
} catch (e: SecurityException) { null } catch (e: Exception) { null }
