package com.varuna.rustify.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.rotate
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.varuna.rustify.MiniPlayer
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.travel.TravelPlaylist
import com.varuna.rustify.travel.TravelRouting
import com.varuna.rustify.travel.TravelSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
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
 * E99 — "Travel playlist": full-screen interactive map (CARTO Voyager OSM basemap by default,
 * or MapTiler Cloud if the user provides a key), con autocomplete de destino (Photon + Nominatim
 * keyless), ubicación en vivo dibujada en el mapa, ruta OSRM dibujada sobre el mapa, prompt para
 * activar GPS, y dos modos de playlist:
 *  - **Auto**: rellena a partir de favoritas.
 *  - **Manual**: empieza vacía; añades canciones y una barrita muestra cuánto cubres del ETA.
 * En ambos modos puedes **previsualizar, reordenar (drag), añadir/quitar** y **guardar como
 * playlist de Spotify** (via GraphQL `createSpotifyPlaylist` + `addTracksToPlaylist`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelScreen(
    spotifyRepo: SpotifyRepository,
    onPlayTracks: (List<FullTrack>) -> Unit,
    onBack: () -> Unit,
    currentTrack: FullTrack?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrev: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    initialDestination: TravelRouting.Geo? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    var hasPerm by remember { mutableStateOf(hasLocationPermission(context)) }
    var locationEnabled by remember { mutableStateOf(TravelRouting.isLocationEnabled(context)) }
    var current by remember { mutableStateOf<Location?>(null) }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<TravelRouting.Geo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var pickedDestination by remember { mutableStateOf<TravelRouting.Geo?>(null) }
    // Origen explícito opcional: si es null se usa la ubicación GPS actual como origen.
    var originOverride by remember { mutableStateOf<TravelRouting.Geo?>(null) }
    // Lo que fija el buscador / long-press: 0 = destino, 1 = origen.
    var pickMode by remember { mutableStateOf(0) }

    var route by remember { mutableStateOf<TravelRouting.Route?>(null) }
    var bufferMin by remember { mutableStateOf(10f) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var manualMode by remember { mutableStateOf(false) }
    val manualTracks = remember { mutableStateListOf<FullTrack>() }
    var showPicker by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var likedCache by remember { mutableStateOf<List<FullTrack>>(emptyList()) }

    var useManualDuration by remember { mutableStateOf(false) }
    var manualDurationH by remember { mutableStateOf(1) }
    var manualDurationM by remember { mutableStateOf(0) }
    var showManualCard by remember { mutableStateOf(false) }

    fun currentTargetMs(): Long {
        val s = route?.durationSec
        if (s != null && !useManualDuration) return (s + bufferMin.toLong() * 60L) * 1000L
        return (manualDurationH * 3600L + manualDurationM * 60L) * 1000L
    }

    var followUser by remember { mutableStateOf(true) }

    var mapStyleIndex by remember { mutableStateOf(TravelSettings.mapStyleIndex(context)) }
    var showStyleMenu by remember { mutableStateOf(false) }
    var mapBearing by remember { mutableStateOf(0.0) }
    var styleSwitching by remember { mutableStateOf(false) }
    val isDarkMap = mapStyleIndex == TravelSettings.STYLE_DARK_INDEX
    val titleColor = if (isDarkMap) Color.White else Color.Black
    val onMapTextColor = if (isDarkMap) Color.White else Color.Black
    val onMapIconColor = if (isDarkMap) Color.White else Color.Black
    val scrimColor = if (isDarkMap) Color(0xCC000000) else Color(0xCCFFFFFF)

    // Cuando se cambia el estilo desde el dropdown, se lo aplicamos al mapa YA cargado
    // (recordar: MapView se crea una sola vez en el remember del MapLibreView).
    LaunchedEffect(mapStyleIndex) {
        val m = mapRef[0] ?: return@LaunchedEffect
        val uri = TravelSettings.ASSET_STYLES[mapStyleIndex]
        styleSwitching = true
        m.setStyle(Style.Builder().fromUri(uri)) { style ->
            registerLayers(style)
            // Re-aplicar estado: registerLayers recrea las GeoJsonSource con geojson vacío,
            // así que volcamos la posición/destino/ruta actuales para que no "desaparezcan"
            // hasta el próximo callback del GPS (~2 s).
            current?.let { loc ->
                userSourceRef[0]?.setGeoJson(pointFeatureJson(loc.latitude, loc.longitude))
            }
            originOverride?.let { o -> originSourceRef[0]?.setGeoJson(pointFeatureJson(o.lat, o.lon)) }
            pickedDestination?.let { d ->
                destSourceRef[0]?.setGeoJson(pointFeatureJson(d.lat, d.lon))
                route?.geometryGeoJson?.let { geoStr ->
                    runCatching {
                        val feature = org.json.JSONObject().apply {
                            put("type", "Feature")
                            put("geometry", org.json.JSONObject(geoStr))
                            put("properties", org.json.JSONObject())
                        }
                        val fc = org.json.JSONObject().apply {
                            put("type", "FeatureCollection")
                            put("features", org.json.JSONArray().apply { put(feature) })
                        }
                        routeSourceRef[0]?.setGeoJson(fc.toString())
                    }
                }
            }
            // Liberamos el guard: a partir de aquí los gestos del usuario sí desmarcan follow.
            styleSwitching = false
        }
    }

    val noLocationMsg = stringResource(R.string.travel_no_location)
    val routeFailedMsg = stringResource(R.string.travel_route_failed)
    val noTracksMsg = stringResource(R.string.travel_no_tracks)

    val activity = context as? android.app.Activity
    // "Denegado para siempre": el usuario marcó "no volver a preguntar" o revocó el permiso desde
    // Ajustes de Android. En ese caso el diálogo del sistema ya no aparece; hay que mandarlo a Ajustes.
    var permPermanentlyDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
        locationEnabled = TravelRouting.isLocationEnabled(context)
        if (granted) {
            permPermanentlyDenied = false
            if (locationEnabled) current = lastKnownLocation(context)
        } else {
            // Si no se puede volver a pedir (rationale=false tras denegar) → denegado para siempre.
            permPermanentlyDenied = activity?.let {
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
            } ?: false
        }
    }

    // Re-chequea el permiso al volver a primer plano: si el usuario lo REVOCÓ desde Ajustes de Android,
    // hasPerm vuelve a false y el botón "Conceder ubicación" reaparece (antes se quedaba oculto para
    // siempre porque hasPerm solo se leía una vez al entrar).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPerm = hasLocationPermission(context)
                locationEnabled = TravelRouting.isLocationEnabled(context)
                if (hasPerm) { permPermanentlyDenied = false; if (locationEnabled && current == null) current = lastKnownLocation(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Pide el permiso; si está denegado para siempre, abre los Ajustes de la app en su lugar.
    fun requestLocationPermission() {
        if (permPermanentlyDenied) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val locationSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            locationEnabled = TravelRouting.isLocationEnabled(context)
            if (locationEnabled && hasPerm) current = lastKnownLocation(context)
        }

    fun ensureCache() {
        scope.launch {
            if (likedCache.isEmpty()) {
                likedCache = spotifyRepo.likedTracks.toList().ifEmpty {
                    runCatching { spotifyRepo.getSavedTracks(limit = 100).items }.getOrDefault(emptyList())
                }
            }
        }
    }

    LaunchedEffect(Unit) { locationEnabled = TravelRouting.isLocationEnabled(context) }
    // Precarga las favoritas al entrar para que "Empezar viaje" en modo Auto funcione sin tener que
    // abrir antes la hoja de Preview (que era lo único que las cargaba).
    LaunchedEffect(Unit) { ensureCache() }
    // Pre-cargar destino si vino desde un link compartido (geo: o Google Maps).
    LaunchedEffect(initialDestination) {
        val dest = initialDestination ?: return@LaunchedEffect
        pickedDestination = dest
        query = dest.label
        ensureCache()
        // Al cargar el mapa (mapRef se setea en onMapReady), computaremos la ruta desde
        // la ubicación actual. Pero si aún no hay ubicación, al menos marcamos el destino.
        destSourceRef[0]?.setGeoJson(pointFeatureJson(dest.lat, dest.lon))
    }
    LaunchedEffect(hasPerm, locationEnabled) {
        if (hasPerm && locationEnabled && current == null) current = lastKnownLocation(context)
    }

    DisposableEffect(hasPerm, locationEnabled) {
        if (!hasPerm || !locationEnabled) return@DisposableEffect onDispose {}
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
            ?: return@DisposableEffect onDispose {}
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                current = location
                userSourceRef[0]?.setGeoJson(pointFeatureJson(location.latitude, location.longitude))
                if (followUser) {
                    mapRef[0]?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(location.latitude, location.longitude),
                            mapRef[0]?.cameraPosition?.zoom ?: 13.0
                        )
                    )
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, listener)
        } catch (_: SecurityException) { }
        onDispose { try { lm.removeUpdates(listener) } catch (_: Exception) { } }
    }

    var debounceJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { showSuggestions = false; suggestions = emptyList(); return@LaunchedEffect }
        searching = true
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(400)
            val sugg = TravelRouting.geocodeSuggestions(
                query, limit = 12,
                biasLat = current?.latitude,
                biasLon = current?.longitude,
                context = context
            )
            suggestions = sugg
            searching = false
            showSuggestions = true
        }
    }

    fun clearDestination() {
        pickedDestination = null
        route = null
        query = ""
        suggestions = emptyList()
        showSuggestions = false
        manualTracks.clear()
        showManualCard = false
        destSourceRef[0]?.setGeoJson(EMPTY_POINT_GEO_JSON)
        routeSourceRef[0]?.setGeoJson(EMPTY_LINE_GEO_JSON)
    }

    // Origen efectivo: el punto fijado a mano si existe; si no, la ubicación GPS actual.
    fun originLatLon(): Pair<Double, Double>? {
        originOverride?.let { return it.lat to it.lon }
        current?.let { return it.latitude to it.longitude }
        return null
    }

    fun computeRoute(dest: TravelRouting.Geo) {
        val o = originLatLon()
        if (o == null) {
            status = noLocationMsg
            return
        }
        val (oLat, oLon) = o
        loading = true; status = null; route = null
        scope.launch {
            val r = TravelRouting.route(oLat, oLon, dest.lat, dest.lon)
            route = r
            loading = false
            if (r == null) {
                status = routeFailedMsg
            } else {
                runCatching {
                    val feature = org.json.JSONObject().apply {
                        put("type", "Feature")
                        put("geometry", org.json.JSONObject(r.geometryGeoJson ?: EMPTY_LINE_GEO_JSON))
                        put("properties", org.json.JSONObject())
                    }
                    val fc = org.json.JSONObject().apply {
                        put("type", "FeatureCollection")
                        put("features", org.json.JSONArray().apply { put(feature) })
                    }
                    routeSourceRef[0]?.setGeoJson(fc.toString())
                }
                val centerLat = (oLat + dest.lat) / 2.0
                val centerLon = (oLon + dest.lon) / 2.0
                val approxDistanceKm = Location("a").apply {
                    latitude = oLat; longitude = oLon
                }.distanceTo(Location("b").apply {
                    latitude = dest.lat; longitude = dest.lon
                }) / 1000.0
                val zoom = when {
                    approxDistanceKm < 5 -> 15.0
                    approxDistanceKm < 20 -> 12.0
                    approxDistanceKm < 80 -> 9.5
                    approxDistanceKm < 400 -> 6.5
                    else -> 4.0
                }
                mapRef[0]?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), zoom)
                )
            }
        }
    }

    // Fija (o limpia con null) el origen explícito, dibuja su marcador azul y recomputa la ruta.
    fun setOrigin(g: TravelRouting.Geo?) {
        originOverride = g
        originSourceRef[0]?.setGeoJson(if (g == null) EMPTY_POINT_GEO_JSON else pointFeatureJson(g.lat, g.lon))
        pickedDestination?.let { computeRoute(it) }
    }

    fun startTrip(map: List<FullTrack>) {
        if (map.isNotEmpty()) onPlayTracks(map) else status = noTracksMsg
    }

    // Pantalla completa: Box raíz, mapa llena todo, los overlays se pintan encima.
    Box(Modifier.fillMaxSize()) {

        MapLibreView(
            Modifier.fillMaxSize(),
            styleUri = { TravelSettings.ASSET_STYLES[mapStyleIndex] },
            onMapReady = { map, style ->
                mapRef[0] = map
                registerLayers(style)
                // Brújula nativa de MapLibre desactivada: usamos una propia (Compose) siempre
                // visible y clickable para resetear el norte.
                map.uiSettings.isCompassEnabled = false
                // bearing → rota nuestra brújula.
                map.addOnCameraMoveListener { mapBearing = map.cameraPosition.bearing }
                // Cuando el usuario arrastra el mapa manualmente, desmarca followUser.
                // REASON_GESTURE = 1 (MapLibre/Mapbox OnCameraMoveStartedListener).
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == 1 && !styleSwitching) followUser = false
                }
                // Long-press en el mapa → marcar destino y calcular ruta hasta ese punto.
                map.addOnMapLongClickListener { point ->
                    val lat = point.latitude
                    val lon = point.longitude
                    val coordLabel = "%.5f, %.5f".format(lat, lon)
                    if (pickMode == 1) {
                        // Long-press en modo Origen → fija el origen en ese punto.
                        val g = TravelRouting.Geo(lat, lon, coordLabel)
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 11.0))
                        ensureCache()
                        setOrigin(g)
                        scope.launch {
                            val label = TravelRouting.reverseGeocode(lat, lon, context)
                            if (originOverride?.lat == g.lat && originOverride?.lon == g.lon) {
                                originOverride = originOverride?.copy(label = label)
                            }
                        }
                    } else {
                        pickedDestination = TravelRouting.Geo(lat, lon, coordLabel)
                        query = ""
                        suggestions = emptyList()
                        showSuggestions = false
                        destSourceRef[0]?.setGeoJson(pointFeatureJson(lat, lon))
                        routeSourceRef[0]?.setGeoJson(EMPTY_LINE_GEO_JSON)
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 11.0))
                        ensureCache()
                        computeRoute(pickedDestination!!)
                        // Reverse geocode en segundo plano para reemplazar "lat, lon" por la etiqueta.
                        scope.launch {
                            val label = TravelRouting.reverseGeocode(lat, lon, context)
                            pickedDestination = pickedDestination?.copy(label = label)
                            query = label
                        }
                    }
                    true
                }
                current?.let { loc ->
                    userSourceRef[0]?.setGeoJson(pointFeatureJson(loc.latitude, loc.longitude))
                }
                originOverride?.let { o -> originSourceRef[0]?.setGeoJson(pointFeatureJson(o.lat, o.lon)) }
                pickedDestination?.let { d ->
                    destSourceRef[0]?.setGeoJson(pointFeatureJson(d.lat, d.lon))
                    route?.geometryGeoJson?.let { geoStr ->
                        runCatching {
                            val feature = org.json.JSONObject().apply {
                                put("type", "Feature")
                                put("geometry", org.json.JSONObject(geoStr))
                                put("properties", org.json.JSONObject())
                            }
                            val fc = org.json.JSONObject().apply {
                                put("type", "FeatureCollection")
                                put("features", org.json.JSONArray().apply { put(feature) })
                            }
                            routeSourceRef[0]?.setGeoJson(fc.toString())
                        }
                    }
                }
                if (followUser) {
                    current?.let { loc ->
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 13.0)
                        )
                    }
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -3.7), 5.0))
                }
                // Si vino un destino inicial desde un link compartido, centrarlo y computar ruta.
                initialDestination?.let { d ->
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(d.lat, d.lon), 11.0))
                    if (current != null) computeRoute(d)
                }
            }
        )

        // ── TopAppBar transparente sobre el mapa (status bar padding manual) ───────────────
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.travel_title),
                    color = titleColor,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                // Modo manual (tiempo sin destino) — abre el card.
                IconButton(onClick = {
                    showManualCard = !showManualCard
                    if (showManualCard) useManualDuration = true
                }) {
                    Icon(
                        Icons.Filled.AccessTime,
                        contentDescription = null,
                        tint = if (showManualCard) green else onMapIconColor
                    )
                }
                // Follow-me (mi ubicación).
                IconButton(onClick = {
                    followUser = true
                    current?.let { loc ->
                        // Zoom-in al centrar: si el zoom actual es muy lejano (vista de mundo/país),
                        // acercamos a 14. Si ya está más cerca, respetamos el zoom del usuario.
                        val z = (mapRef[0]?.cameraPosition?.zoom ?: 14.0).coerceAtLeast(14.0)
                        mapRef[0]?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), z)
                        )
                    }
                }) {
                    Icon(
                        if (followUser) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                        contentDescription = null,
                        tint = if (followUser) green else onMapIconColor
                    )
                }
                // Brújula propia (siempre visible, rota con el bearing, click = norte arriba).
                IconButton(onClick = {
                    val pos = mapRef[0]?.cameraPosition ?: return@IconButton
                    mapRef[0]?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            org.maplibre.android.camera.CameraPosition.Builder(pos).bearing(0.0).build()
                        )
                    )
                    mapBearing = 0.0
                }) {
                    Icon(
                        Icons.Filled.Explore,
                        contentDescription = null,
                        tint = onMapIconColor,
                        modifier = Modifier.rotate(mapBearing.toFloat())
                    )
                }
                // Selector de estilo de mapa (capas). Lanza un dropdown con los 4 estilos.
                Box {
                    IconButton(onClick = { showStyleMenu = true }) {
                        Icon(Icons.Filled.Layers, contentDescription = null, tint = onMapIconColor)
                    }
                    DropdownMenu(expanded = showStyleMenu, onDismissRequest = { showStyleMenu = false }) {
                        TravelSettings.STYLE_LABELS.forEachIndexed { idx, label ->
                            DropdownMenuItem(
                                text = { Text(if (idx == mapStyleIndex) "✔ $label" else label, color = Color.Black) },
                                onClick = {
                                    mapStyleIndex = idx
                                    TravelSettings.setMapStyleIndex(context, idx)
                                    showStyleMenu = false
                                }
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = onMapIconColor)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.statusBarsPadding()
        )

        // ── Overlay búsqueda (debajo de la top bar, sobre el mapa) ─────────────────────────
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp).padding(horizontal = 10.dp)) {
            // Selector de qué fija el buscador/long-press: Destino (rojo) u Origen (azul).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = pickMode == 0, onClick = { pickMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.travel_pick_destination), color = if (pickMode == 0) Color.Black else onMapTextColor) }
                SegmentedButton(
                    selected = pickMode == 1, onClick = { pickMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.travel_pick_origin), color = if (pickMode == 1) Color.Black else onMapTextColor) }
            }
            Spacer(Modifier.height(4.dp))
            // Chip del origen fijado (si lo hay), con botón para volver a la ubicación actual.
            originOverride?.let { o ->
                Surface(color = scrimColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.travel_origin_prefix, o.label),
                            color = onMapTextColor, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { setOrigin(null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = null, tint = onMapIconColor, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Surface(color = scrimColor, shape = RoundedCornerShape(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.travel_search_hint), color = onMapTextColor.copy(alpha = 0.6f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = onMapIconColor) },
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = green)
                        else if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; suggestions = emptyList(); showSuggestions = false }) {
                                Icon(Icons.Filled.Clear, contentDescription = null, tint = onMapIconColor)
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = onMapTextColor, unfocusedTextColor = onMapTextColor,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = green
                    )
                )
            }
            if (showSuggestions && suggestions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Surface(color = scrimColor, shape = RoundedCornerShape(10.dp)) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(suggestions) { s ->
                            Text(
                                text = s.label,
                                color = onMapTextColor,
                                fontSize = 13.sp,
                                maxLines = 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSuggestions = false
                                        mapRef[0]?.animateCamera(
                                            CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 10.0)
                                        )
                                        ensureCache()
                                        if (pickMode == 1) {
                                            query = ""
                                            suggestions = emptyList()
                                            setOrigin(s)
                                        } else {
                                            pickedDestination = s
                                            query = s.label
                                            destSourceRef[0]?.setGeoJson(pointFeatureJson(s.lat, s.lon))
                                            routeSourceRef[0]?.setGeoJson(EMPTY_LINE_GEO_JSON)
                                            computeRoute(s)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

// ── Atribución minimal (sobre el mapa, en lugar del logo/(i) de MapLibre que crashea) ─
        Text(
            "© OpenStreetMap, CARTO · Esri · OpenTopoMap",
            color = onMapTextColor.copy(alpha = 0.65f),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 6.dp, bottom = 4.dp)
        )

        // ── Card inferior: destino/tiempo + buffer + modo + acciones ─────────────────────────
        // Mostramos la card siempre que haya un destino elegido (aunque la ruta OSRM falle o no haya
        // GPS), para que el botón "Navegar en Google Maps" y el modo manual sigan disponibles.
        if (route != null || showManualCard || pickedDestination != null) {
            val cardScrim = if (isDarkMap) Color(0xCC000000) else Color(0xCCFFFFFF)
            val cardText = if (isDarkMap) Color.White else Color.Black
            val cardSub = if (isDarkMap) Color(0xFFCCCCCC) else Color(0xFF555555)
            Surface(
                color = cardScrim,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = if (currentTrack != null) 76.dp else 12.dp)
                    .fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    // Toggle Ruta / Manual (tiempo)
                    if (route != null) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !useManualDuration, onClick = { useManualDuration = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) { Text(stringResource(R.string.travel_route_mode), color = if (!useManualDuration) Color.Black else cardText) }
                            SegmentedButton(
                                selected = useManualDuration, onClick = { useManualDuration = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) { Text(stringResource(R.string.travel_time_mode), color = if (useManualDuration) Color.Black else cardText) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (useManualDuration || route == null) {
                        // Selector de horas / minutos
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.travel_duration), color = cardText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Hours
                            OutlinedTextField(
                                value = manualDurationH.toString(),
                                onValueChange = { it.toIntOrNull()?.coerceIn(0, 24)?.let { v -> manualDurationH = v } },
                                label = { Text("h", color = cardSub) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).padding(end = 4.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = cardText, unfocusedTextColor = cardText,
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = green, cursorColor = green
                                )
                            )
                            // Minutes
                            OutlinedTextField(
                                value = manualDurationM.toString(),
                                onValueChange = { it.toIntOrNull()?.coerceIn(0, 59)?.let { v -> manualDurationM = v } },
                                label = { Text("min", color = cardSub) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = cardText, unfocusedTextColor = cardText,
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = green, cursorColor = green
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.travel_duration_total, TravelPlaylist.formatDuration(currentTargetMs())),
                            color = cardSub, fontSize = 11.sp
                        )
                        if (route != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.travel_route_available), color = cardSub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Button(onClick = { clearDestination() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = green)) {
                                    Text(stringResource(R.string.travel_clear), fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        // Ruta calculada (modo normal)
                        route?.let { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.travel_eta, r.durationSec / 60, (r.distanceM / 1000).toInt()),
                                    color = cardText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { clearDestination() }) {
                                    Icon(Icons.Filled.Clear, contentDescription = null, tint = cardText)
                                }
                            }
                            pickedDestination?.let {
                                Text(it.label, color = cardSub, fontSize = 11.sp, maxLines = 2)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.travel_buffer, bufferMin.toInt()), color = cardSub, fontSize = 12.sp)
                            Slider(value = bufferMin, onValueChange = { bufferMin = it }, valueRange = 0f..30f, steps = 29)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.travel_playlist_mode), color = cardSub, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !manualMode, onClick = { manualMode = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.travel_mode_auto), color = if (!manualMode) Color.Black else cardText) }
                        SegmentedButton(
                            selected = manualMode, onClick = { manualMode = true; ensureCache() },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.travel_mode_manual), color = if (manualMode) Color.Black else cardText) }
                    }

                    val tMs = currentTargetMs()
                    if (manualMode) {
                        Spacer(Modifier.height(10.dp))
                        val covered = TravelPlaylist.totalMs(manualTracks.toList())
                        val ratio = if (tMs > 0) (covered.toFloat() / tMs.toFloat()).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { ratio },
                            color = green, trackColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFDDDDDD),
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.travel_coverage, TravelPlaylist.formatDuration(covered), TravelPlaylist.formatDuration(tMs)),
                            color = cardText, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showPicker = true; ensureCache() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = cardText, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.travel_add_tracks), color = cardText, fontSize = 13.sp)
                            }
                            if (manualTracks.isNotEmpty()) {
                                Button(
                                    onClick = { showPreview = true; ensureCache() },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = cardText, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.travel_preview), color = cardText, fontSize = 13.sp)
                                }
                            }
                        }
                        if (manualTracks.isNotEmpty()) {
                            Text(
                                stringResource(R.string.travel_manual_count, manualTracks.size),
                                color = cardSub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { showPreview = true; ensureCache() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Visibility, contentDescription = null, tint = cardText, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.travel_preview_auto), color = cardText, fontSize = 13.sp)
                        }
                    }

                    // Navegar en Google Maps: abre la ruta (respetando el origen fijado) para conducir
                    // mientras Rustify reproduce la playlist. Sin API key ni SDK — vía intent al mapa.
                    pickedDestination?.let { dest ->
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
                                originOverride?.let { sb.append("&origin=${it.lat},${it.lon}") }
                                sb.append("&destination=${dest.lat},${dest.lon}&travelmode=driving")
                                val uri = android.net.Uri.parse(sb.toString())
                                // 1) intenta abrir directamente la app de Google Maps; 2) si no está
                                // (o no es visible), abre la URL con cualquier navegador/app de mapas;
                                // 3) último recurso, un intent geo: turn-by-turn. FLAG_ACTIVITY_NEW_TASK
                                // por si el context no es una Activity.
                                fun launch(i: Intent): Boolean = runCatching {
                                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                                }.getOrDefault(false)
                                val ok = launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) ||
                                    launch(Intent(Intent.ACTION_VIEW, uri)) ||
                                    launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${dest.lat},${dest.lon}&mode=d")))
                                if (!ok) android.widget.Toast.makeText(context, "Google Maps no disponible", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, tint = green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.travel_navigate_gmaps), color = cardText, fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    val finalMs = tMs
                    Button(
                        onClick = {
                            scope.launch {
                                val list = if (manualMode) TravelPlaylist.fillRemaining(finalMs, manualTracks.toList(), likedCache)
                                           else TravelPlaylist.build(finalMs, likedCache)
                                startTrip(list)
                            }
                        },
                        enabled = !loading && (!manualMode || manualTracks.isNotEmpty()) && finalMs > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = green, disabledContainerColor = Color(0xFF2C2C2E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                        else Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.travel_start), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Prompt de ubicación desactivada ──────────────────────────────────────────────────
        if (!locationEnabled) {
            val promptScrim = if (isDarkMap) Color(0xCC000000) else Color(0xCCFFFFFF)
            val promptText = if (isDarkMap) Color.White else Color.Black
            Surface(
                color = promptScrim,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.travel_enable_location), color = promptText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            colors = ButtonDefaults.buttonColors(containerColor = green)
                        ) { Text(stringResource(R.string.travel_enable), color = Color.Black, fontWeight = FontWeight.Bold) }
                        if (!hasPerm) {
                            Button(
                                onClick = { requestLocationPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE))
                            ) { Text(stringResource(R.string.travel_grant_location), color = promptText) }
                        }
                    }
                }
            }
        }

        // ── MiniPlayer sobre el mapa ─────────────────────────────────────────────────────────
        if (currentTrack != null) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
                MiniPlayer(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasPrevious = hasPrev,
                    hasNext = hasNext,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipPrevious = onSkipPrev,
                    onSkipNext = onSkipNext,
                    onClick = onMiniPlayerClick
                )
            }
        }

        // ── Estado (errores) ─────────────────────────────────────────────────────────────────
        status?.let {
            Surface(
                color = Color(0xCCB00020),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            ) {
                Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }

    // ── Hoja de selección manual (favoritas) ──────────────────────────────────────────────────
    if (showPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        LaunchedEffect(Unit) {
            if (likedCache.isEmpty()) {
                likedCache = spotifyRepo.likedTracks.toList().ifEmpty {
                    runCatching { spotifyRepo.getSavedTracks(limit = 100).items }.getOrDefault(emptyList())
                }
            }
        }
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = sheetState, containerColor = Color(0xFF1C1C1E)) {
            ManualPickerContent(
                likedCache = likedCache,
                manualTracks = manualTracks,
                targetMs = currentTargetMs(),
                onToggleAdd = { t ->
                    val idx = manualTracks.indexOfFirst { it.id == t.id }
                    if (idx >= 0) manualTracks.removeAt(idx) else manualTracks.add(t)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    // ── Hoja de preview/edit/save ────────────────────────────────────────────────────────────
    if (showPreview) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Genera la playlist Auto on demand; la manual son las seleccionadas + relleno.
        val previewTracks = remember(manualMode, manualTracks.size, route, bufferMin, likedCache.size, useManualDuration, manualDurationH, manualDurationM) {
            val tMs = if (useManualDuration || route == null) (manualDurationH * 3600L + manualDurationM * 60L) * 1000L
                       else ((route!!.durationSec + bufferMin.toLong() * 60L) * 1000L)
            if (tMs <= 0) emptyList()
            else if (manualMode) TravelPlaylist.fillRemaining(tMs, manualTracks.toList(), likedCache)
            else TravelPlaylist.build(tMs, likedCache)
        }
        val reorderable = remember { mutableStateListOf<FullTrack>().apply { addAll(previewTracks) } }
        // Re-sync cuando cambian las dependencias del preview.
        LaunchedEffect(previewTracks) { reorderable.clear(); reorderable.addAll(previewTracks) }

        ModalBottomSheet(onDismissRequest = { showPreview = false }, sheetState = sheetState, containerColor = Color(0xFF1C1C1E)) {
            PreviewSheetContent(
                tracks = reorderable,
                onPlay = { onPlayTracks(reorderable.toList()); showPreview = false },
                onSaveToSpotify = { name, onResult ->
                    scope.launch {
                        runCatching {
                            val user = spotifyRepo.getMe()
                            val pl = spotifyRepo.createPlaylist(user.id, name)
                            val ids = reorderable.mapNotNull { it.id }.filter { it.isNotBlank() && !it.startsWith("local:") }
                            spotifyRepo.addAllTracksToPlaylist(context, pl.id, ids)
                            pl.id
                        }.onSuccess { onResult(true) }
                         .onFailure { onResult(false) }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ── Hoja manual: muestra tiempo cubierto arriba y lista para tap añadir/quitar ─────────────
@Composable
private fun ManualPickerContent(
    likedCache: List<FullTrack>,
    manualTracks: List<FullTrack>,
    targetMs: Long,
    onToggleAdd: (FullTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val green = Color(0xFF1DB954)
    val covered = TravelPlaylist.totalMs(manualTracks)
    val ratio = if (targetMs > 0) (covered.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Text(stringResource(R.string.travel_pick_tracks), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { ratio },
            color = green, trackColor = Color(0xFF2C2C2E),
            modifier = Modifier.fillMaxWidth().height(6.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.travel_coverage, TravelPlaylist.formatDuration(covered), TravelPlaylist.formatDuration(targetMs)),
            color = Color.White, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(likedCache) { t ->
                val isAdded = manualTracks.any { mt -> mt.id == t.id }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAdd(t) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                        Text(t.artists.joinToString { it.name }, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    val sec = (t.durationMs / 1000)
                    Text("${sec / 60}:${(sec % 60).toString().padStart(2, '0')}", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Surface(color = if (isAdded) green else Color(0xFF2C2C2E), shape = RoundedCornerShape(50)) {
                        Text(
                            if (isAdded) "✓" else "+",
                            color = if (isAdded) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Hoja preview: drag-reorder + remove + save to Spotify ────────────────────────────────────
@Composable
private fun PreviewSheetContent(
    tracks: MutableList<FullTrack>,
    onPlay: () -> Unit,
    onSaveToSpotify: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val green = Color(0xFF1DB954)
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var saveName by remember { mutableStateOf("Travel ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}") }
    var saving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.travel_preview_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.travel_preview_count, tracks.size), color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.travel_preview_total, TravelPlaylist.formatDuration(TravelPlaylist.totalMs(tracks))),
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.travel_drag_hint), color = Color.Gray, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            itemsIndexed(tracks) { index, t ->
                key(t.id ?: index) {
                    val currentIndex by rememberUpdatedState(index)
                    val isDragging = draggingIndex == currentIndex
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .background(if (isDragging) Color(0xFF2A2A2A) else Color.Transparent)
                            .pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingIndex = currentIndex; dragOffset = 0f },
                                    onDragEnd = { draggingIndex = null; dragOffset = 0f },
                                    onDragCancel = { draggingIndex = null; dragOffset = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume(); dragOffset += dragAmount.y
                                        val moved = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                        val targetDelta = (dragOffset / rowHeightPx).toInt()
                                        if (targetDelta != 0) {
                                            val target = (moved + targetDelta).coerceIn(0, tracks.lastIndex)
                                            if (target != moved) {
                                                tracks.add(target, tracks.removeAt(moved))
                                                dragOffset -= (target - moved) * rowHeightPx
                                                draggingIndex = target
                                            }
                                        }
                                    }
                                )
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            Text(t.artists.joinToString { it.name }, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(4.dp))
                        val sec = (t.durationMs / 1000)
                        Text("${sec / 60}:${(sec % 60).toString().padStart(2, '0')}", color = Color.Gray, fontSize = 12.sp)
                        IconButton(onClick = { tracks.removeAt(currentIndex) }) {
                            Icon(Icons.Filled.Clear, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = saveName, onValueChange = { saveName = it },
            label = { Text(stringResource(R.string.travel_save_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF2C2C2E),
                focusedIndicatorColor = green, focusedLabelColor = green, cursorColor = green
            )
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPlay() },
                colors = ButtonDefaults.buttonColors(containerColor = green),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.travel_play), color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    saving = true; saveResult = null
                    onSaveToSpotify(saveName) { ok -> saving = false; saveResult = ok }
                },
                enabled = !saving && tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                modifier = Modifier.weight(1f)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.travel_save_spotify), color = Color.White, fontSize = 13.sp)
            }
        }
        saveResult?.let { ok ->
            Spacer(Modifier.height(6.dp))
            Text(
                if (ok) stringResource(R.string.travel_save_ok) else stringResource(R.string.travel_save_fail),
                color = if (ok) green else Color(0xFFE57373), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ── Map state refs (compartido entre composables del módulo) ───────────────────────────────
private val mapRef = arrayOfNulls<MapLibreMap>(1)
private val userSourceRef = arrayOfNulls<GeoJsonSource>(1)
private val destSourceRef = arrayOfNulls<GeoJsonSource>(1)
private val routeSourceRef = arrayOfNulls<GeoJsonSource>(1)
private val originSourceRef = arrayOfNulls<GeoJsonSource>(1)

private const val EMPTY_POINT_GEO_JSON = """{"type":"FeatureCollection","features":[]}"""
private const val EMPTY_LINE_GEO_JSON = """{"type":"FeatureCollection","features":[]}"""

private fun pointFeatureJson(lat: Double, lon: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}"""

private fun registerLayers(style: Style) {
    runCatching {
        if (style.getSource("user-loc-source") == null) {
            val us = GeoJsonSource("user-loc-source", EMPTY_POINT_GEO_JSON)
            style.addSource(us)
            userSourceRef[0] = us
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
            destSourceRef[0] = ds
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
        // Origen explícito (azul) — solo visible cuando el usuario fija un origen distinto del GPS.
        if (style.getSource("origin-source") == null) {
            val os = GeoJsonSource("origin-source", EMPTY_POINT_GEO_JSON)
            style.addSource(os)
            originSourceRef[0] = os
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
            routeSourceRef[0] = rs
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
            // Oculta logo + (i) de MapLibre: el (i) crashea al abrir el sheet de atribución
            // sobre estilos asset:// sin URLs válidas. La atribución va como Text overlay.
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

private fun hasLocationPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun lastKnownLocation(context: android.content.Context): Location? = try {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
} catch (e: SecurityException) { null } catch (e: Exception) { null }