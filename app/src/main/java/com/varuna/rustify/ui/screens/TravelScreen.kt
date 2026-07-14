package com.varuna.rustify.ui.screens

import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.travel.TravelPlaylist
import com.varuna.rustify.travel.TravelRouting
import kotlinx.coroutines.launch

/**
 * E99 — "Travel playlist": pick a destination, compute the driving ETA (OSRM), and build a playlist
 * that fills that ETA (+ a traffic buffer) from your liked songs, then play it. Map via MapLibre.
 *
 * ⚠️ NOT compiled/tested here. The MapLibre GL API (MapLibre.getInstance / MapView lifecycle /
 * setStyle / moveCamera) is version-specific and written blind — verify against the resolved
 * `org.maplibre.gl:android-sdk` version. Routing/geocoding use the public OSRM/Nominatim demo servers
 * (rate-limited; self-host for real use).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelScreen(
    spotifyRepo: SpotifyRepository,
    onPlayTracks: (List<FullTrack>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    var hasPerm by remember { mutableStateOf(hasLocationPermission(context)) }
    var current by remember { mutableStateOf<Location?>(null) }
    var destination by remember { mutableStateOf("") }
    var destGeo by remember { mutableStateOf<TravelRouting.Geo?>(null) }
    var route by remember { mutableStateOf<TravelRouting.Route?>(null) }
    var bufferMin by remember { mutableStateOf(10f) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val noLocationMsg = stringResource(R.string.travel_no_location)
    val notFoundMsg = stringResource(R.string.travel_dest_not_found)
    val routeFailedMsg = stringResource(R.string.travel_route_failed)
    val noTracksMsg = stringResource(R.string.travel_no_tracks)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
        if (granted) current = lastKnownLocation(context)
    }

    LaunchedEffect(hasPerm) { if (hasPerm && current == null) current = lastKnownLocation(context) }

    fun computeRoute() {
        val loc = current
        if (loc == null) { status = noLocationMsg; return }
        if (destination.isBlank()) return
        loading = true; status = null; route = null
        scope.launch {
            val geo = TravelRouting.geocode(destination)
            if (geo == null) { status = notFoundMsg; loading = false; return@launch }
            destGeo = geo
            val r = TravelRouting.route(loc.latitude, loc.longitude, geo.lat, geo.lon)
            route = r
            if (r == null) status = routeFailedMsg
            loading = false
        }
    }

    fun startTrip() {
        val r = route ?: return
        scope.launch {
            val pool = spotifyRepo.likedTracks.toList().ifEmpty {
                runCatching { spotifyRepo.getSavedTracks(limit = 50).items }.getOrDefault(emptyList())
            }
            val targetMs = (r.durationSec + (bufferMin.toLong() * 60L)) * 1000L
            val list = TravelPlaylist.build(targetMs, pool)
            if (list.isNotEmpty()) onPlayTracks(list) else status = noTracksMsg
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.travel_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (hasPerm) {
                MapLibreView(Modifier.fillMaxWidth().height(260.dp), current)
            } else {
                Box(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF1E1E1E)), Alignment.Center) {
                    Button(onClick = { permLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) },
                        colors = ButtonDefaults.buttonColors(containerColor = green)) {
                        Text(stringResource(R.string.travel_grant_location), color = Color.Black)
                    }
                }
            }

            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.travel_subtitle), color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                val fieldColors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedLabelColor = green, focusedIndicatorColor = green
                )
                OutlinedTextField(
                    value = destination, onValueChange = { destination = it },
                    label = { Text(stringResource(R.string.travel_destination)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { computeRoute() }, enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.travel_calc_route), color = Color.Black, fontWeight = FontWeight.Bold)
                }

                if (loading) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(color = green, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                route?.let { r ->
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.travel_eta, r.durationSec / 60, (r.distanceM / 1000).toInt()),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    destGeo?.let { Text(it.label, color = Color.Gray, fontSize = 12.sp, maxLines = 2) }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.travel_buffer, bufferMin.toInt()), color = Color.Gray, fontSize = 13.sp)
                    Slider(value = bufferMin, onValueChange = { bufferMin = it }, valueRange = 0f..30f, steps = 29)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { startTrip() }, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.travel_start), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFE57373), fontSize = 14.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun lastKnownLocation(context: android.content.Context): Location? = try {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
} catch (e: SecurityException) { null } catch (e: Exception) { null }

/**
 * MapLibre GL map. ⚠️ blind against the resolved SDK version — verify the API when you compile.
 */
@Composable
private fun MapLibreView(modifier: Modifier, center: Location?) {
    val context = LocalContext.current
    val mapRef = remember { arrayOfNulls<org.maplibre.android.maps.MapLibreMap>(1) }
    val mapView = remember {
        org.maplibre.android.MapLibre.getInstance(context)
        org.maplibre.android.maps.MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    LaunchedEffect(center) {
        val loc = center ?: return@LaunchedEffect
        mapRef[0]?.moveCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude), 11.0
            )
        )
    }
    AndroidView(modifier = modifier, factory = {
        mapView.getMapAsync { map ->
            mapRef[0] = map
            map.setStyle(org.maplibre.android.maps.Style.Builder().fromUri("https://demotiles.maplibre.org/style.json"))
            center?.let { loc ->
                map.moveCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude), 11.0
                    )
                )
            }
        }
        mapView
    })
}
