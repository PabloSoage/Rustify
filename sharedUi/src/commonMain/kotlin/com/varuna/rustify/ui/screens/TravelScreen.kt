package com.varuna.rustify.ui.screens

import com.varuna.rustify.bridge.TrackRef
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.LocalAppServices
import com.varuna.rustify.ui.MiniPlayer
import com.varuna.rustify.ui.resources.*
import com.varuna.rustify.ui.todayIso
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.travel.TravelCamera
import com.varuna.rustify.travel.TravelMarks
import com.varuna.rustify.travel.TravelPlaylist
import com.varuna.rustify.travel.TravelRouting
import com.varuna.rustify.travel.TravelSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "Travel playlist": full-screen interactive map (MapLibre over OpenFreeMap on Android, OSM raster
 * tiles drawn in Compose elsewhere; see [com.varuna.rustify.travel.TravelPlatform]), with destination autocomplete (Photon + Nominatim,
 * keyless), live location drawn on the map, an OSRM route drawn on the map, a prompt to enable GPS,
 * and two playlist modes:
 *  - **Auto**: filled from liked tracks.
 *  - **Manual**: starts empty; you add songs and a bar shows how much of the ETA you cover.
 * In both modes you can preview, reorder (drag), add/remove, and save as a Spotify playlist
 * (via GraphQL `createSpotifyPlaylist` + `addTracksToPlaylist`).
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
    val platform = LocalAppPlatform.current
    val prefs = platform.prefs()
    val travel = LocalAppServices.current.travel
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    val location = travel.rememberLocation()
    val current = location.current
    val camera = remember { TravelCamera() }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<TravelRouting.Geo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var pickedDestination by remember { mutableStateOf<TravelRouting.Geo?>(null) }
    // Optional explicit origin: if null, the current GPS location is used as the origin.
    var originOverride by remember { mutableStateOf<TravelRouting.Geo?>(null) }
    // What the search / long-press sets: 0 = destination, 1 = origin. Where there is no location the
    // origin comes first, since nothing else would give one.
    var pickMode by remember { mutableIntStateOf(if (location.supported) 0 else 1) }

    var route by remember { mutableStateOf<TravelRouting.Route?>(null) }
    var bufferMin by remember { mutableFloatStateOf(10f) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var manualMode by remember { mutableStateOf(false) }
    val manualTracks = remember { mutableStateListOf<FullTrack>() }
    var showPicker by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var likedCache by remember { mutableStateOf<List<FullTrack>>(emptyList()) }

    var useManualDuration by remember { mutableStateOf(false) }
    var manualDurationH by remember { mutableIntStateOf(1) }
    var manualDurationM by remember { mutableIntStateOf(0) }
    var showManualCard by remember { mutableStateOf(false) }

    fun currentTargetMs(): Long {
        val s = route?.durationSec
        if (s != null && !useManualDuration) return (s + bufferMin.toLong() * 60L) * 1000L
        return (manualDurationH * 3600L + manualDurationM * 60L) * 1000L
    }

    var followUser by remember { mutableStateOf(true) }

    var mapStyleIndex by remember { mutableIntStateOf(TravelSettings.mapStyleIndex(prefs)) }
    var showStyleMenu by remember { mutableStateOf(false) }
    val isDarkMap = mapStyleIndex == TravelSettings.STYLE_DARK_INDEX
    val titleColor = if (isDarkMap) Color.White else Color.Black
    val onMapTextColor = if (isDarkMap) Color.White else Color.Black
    val onMapIconColor = if (isDarkMap) Color.White else Color.Black
    // Nearly opaque: at 80 % the map showed through the suggestions and the route card (4.4).
    val scrimColor = if (isDarkMap) Color(0xF2121212) else Color(0xF7FFFFFF)
    // Explicit segment colours: the default selected segment was dark with black text, and the
    // other was transparent over the map (4.7).
    val segColors = SegmentedButtonDefaults.colors(
        activeContainerColor = green, activeContentColor = Color.Black,
        inactiveContainerColor = scrimColor, inactiveContentColor = onMapTextColor
    )

    val noLocationMsg = stringResource(
        if (location.supported) Res.string.travel_no_location else Res.string.travel_pick_origin_first
    )
    val routeFailedMsg = stringResource(Res.string.travel_route_failed)
    val noTracksMsg = stringResource(Res.string.travel_no_tracks)

    fun ensureCache() {
        scope.launch {
            if (likedCache.isEmpty()) {
                likedCache = spotifyRepo.likedTracks.toList().ifEmpty {
                    runCatching { spotifyRepo.getSavedTracks(limit = 100).items }.getOrDefault(emptyList())
                }
            }
        }
    }

    // Preload liked tracks on entry so "Start trip" in Auto mode works without first opening the
    // Preview sheet (the only thing that used to load them).
    LaunchedEffect(Unit) { ensureCache() }
    // Preload the destination if it came from a shared link (geo: or Google Maps).
    LaunchedEffect(initialDestination) {
        val dest = initialDestination ?: return@LaunchedEffect
        pickedDestination = dest
        query = dest.label
        ensureCache()
        // The route is computed once there is a location (below); until then the destination is marked.
        camera.animateTo(dest.lat, dest.lon, 11.0)
    }
    // The map follows each new location while following is on; the first one centres it closer in.
    var centredOnUser by remember { mutableStateOf(initialDestination != null) }
    LaunchedEffect(current) {
        val loc = current ?: return@LaunchedEffect
        if (!followUser) return@LaunchedEffect
        if (centredOnUser) camera.animateTo(loc.lat, loc.lon)
        else {
            camera.moveTo(loc.lat, loc.lon, 13.0)
            centredOnUser = true
        }
    }

    var debounceJob by remember { mutableStateOf<Job?>(null) }
    // Set when the text changes because a suggestion was picked, not typed: searching for the label
    // just chosen reopened the list on top of the route card.
    var queryFromPick by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) { showSuggestions = false; suggestions = emptyList(); return@LaunchedEffect }
        if (queryFromPick) { queryFromPick = false; return@LaunchedEffect }
        searching = true
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(400)
            val sugg = TravelRouting.geocodeSuggestions(
                query, limit = 12,
                biasLat = current?.lat,
                biasLon = current?.lon,
                googleKey = TravelSettings.geocodingApiKey(prefs),
                country = Locale.current.region,
                lang = Locale.current.language.ifBlank { "en" }
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
    }

    // Effective origin: the manually fixed point if any, otherwise the current GPS location.
    fun originLatLon(): Pair<Double, Double>? {
        originOverride?.let { return it.lat to it.lon }
        current?.let { return it.lat to it.lon }
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
                val centerLat = (oLat + dest.lat) / 2.0
                val centerLon = (oLon + dest.lon) / 2.0
                val approxDistanceKm = TravelRouting.distanceM(oLat, oLon, dest.lat, dest.lon) / 1000.0
                val zoom = when {
                    approxDistanceKm < 5 -> 15.0
                    approxDistanceKm < 20 -> 12.0
                    approxDistanceKm < 80 -> 9.5
                    approxDistanceKm < 400 -> 6.5
                    else -> 4.0
                }
                camera.animateTo(centerLat, centerLon, zoom)
            }
        }
    }

    // If the destination arrived from a shared link before a GPS location was available, the marker
    // is already drawn (onMapReady), but the route is not computed until the screen is reopened. As
    // soon as a location is available, compute it automatically so a Maps link shows both the
    // location and the route, not an empty map.
    LaunchedEffect(current, pickedDestination) {
        val d = pickedDestination
        if (d != null && current != null && route == null && !loading) computeRoute(d)
    }

    // Set (or clear with null) the explicit origin, draw its blue marker, and recompute the route.
    fun setOrigin(g: TravelRouting.Geo?) {
        originOverride = g
        pickedDestination?.let { computeRoute(it) }
    }

    fun startTrip(map: List<FullTrack>) {
        if (map.isNotEmpty()) onPlayTracks(map) else status = noTracksMsg
    }

    // Full screen: root Box, map fills everything, overlays painted on top.
    Box(Modifier.fillMaxSize()) {

        travel.Map(
            Modifier.fillMaxSize(),
            styleIndex = mapStyleIndex,
            marks = TravelMarks(current, originOverride, pickedDestination, route),
            camera = camera,
            // When the user drags the map manually, unset followUser.
            onUserMove = { followUser = false },
            // Long-press on the map: mark the destination and compute the route to that point.
            onLongPress = { lat, lon ->
                val coordLabel = TravelRouting.coordinatesLabel(lat, lon)
                if (pickMode == 1) {
                    // Long-press in Origin mode: fix the origin at that point.
                    val g = TravelRouting.Geo(lat, lon, coordLabel)
                    camera.animateTo(lat, lon, 11.0)
                    ensureCache()
                    setOrigin(g)
                    scope.launch {
                        val label = TravelRouting.reverseGeocode(lat, lon, TravelSettings.geocodingApiKey(prefs))
                        if (originOverride?.lat == g.lat && originOverride?.lon == g.lon) {
                            originOverride = originOverride?.copy(label = label)
                        }
                    }
                } else {
                    pickedDestination = TravelRouting.Geo(lat, lon, coordLabel)
                    query = ""
                    suggestions = emptyList()
                    showSuggestions = false
                    route = null
                    camera.animateTo(lat, lon, 11.0)
                    ensureCache()
                    computeRoute(pickedDestination!!)
                    // Reverse geocode in the background to replace "lat, lon" with the label.
                    scope.launch {
                        val label = TravelRouting.reverseGeocode(lat, lon, TravelSettings.geocodingApiKey(prefs))
                        pickedDestination = pickedDestination?.copy(label = label)
                        query = label
                    }
                }
            }
        )

        // A fade from the panels' colour behind the bar: transparent, the title and icons sat straight on
        // the map and vanished into its labels (seen on the emulator in 4.4).
        Box(
            Modifier.fillMaxWidth().height(170.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(scrimColor, scrimColor.copy(alpha = 0f)))
            )
        )
        // Transparent TopAppBar over the map (manual status bar padding).
        TopAppBar(
            title = {
                Text(
                    text = stringResource(Res.string.travel_title),
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                    // One line: four action icons share the bar, and on a narrow phone the title wrapped.
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            },
            actions = {
                // Manual mode (time without a destination) — opens the card.
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
                // Follow-me (my location).
                IconButton(onClick = {
                    followUser = true
                    current?.let { loc ->
                        // Zoom in when centering: if the current zoom is far out (world/country view),
                        // zoom to 14. If already closer, respect the user's zoom.
                        camera.animateTo(loc.lat, loc.lon, camera.zoom.coerceAtLeast(14.0))
                    }
                }) {
                    Icon(
                        if (followUser) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                        contentDescription = null,
                        tint = if (followUser) green else onMapIconColor
                    )
                }
                // Custom compass (always visible, rotates with the bearing, click = north up).
                IconButton(onClick = { camera.north() }) {
                    Icon(
                        Icons.Filled.Explore,
                        contentDescription = null,
                        tint = onMapIconColor,
                        modifier = Modifier.rotate(camera.bearing.toFloat())
                    )
                }
                // Map style selector (layers). Opens a dropdown with the available styles.
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
                                    TravelSettings.setMapStyleIndex(prefs, idx)
                                    showStyleMenu = false
                                }
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.settings_back), tint = onMapIconColor)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.statusBarsPadding()
        )

        // Search overlay (below the top bar, over the map).
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp).padding(horizontal = 10.dp)) {
            // Selects what the search/long-press sets: Destination (red) or Origin (blue).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = pickMode == 0, onClick = { pickMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = segColors
                ) { Text(stringResource(Res.string.travel_pick_destination), color = if (pickMode == 0) Color.Black else onMapTextColor) }
                SegmentedButton(
                    selected = pickMode == 1, onClick = { pickMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = segColors
                ) { Text(stringResource(Res.string.travel_pick_origin), color = if (pickMode == 1) Color.Black else onMapTextColor) }
            }
            Spacer(Modifier.height(4.dp))
            // Chip for the fixed origin (if any), with a button to return to the current location.
            originOverride?.let { o ->
                Surface(color = scrimColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(Res.string.travel_origin_prefix, o.label),
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
                    placeholder = { Text(stringResource(Res.string.travel_search_hint), color = onMapTextColor.copy(alpha = 0.6f)) },
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
                    // Height cap: with many suggestions the list grew unbounded and covered the map and
                    // the bottom card. It is now a bounded, scrollable list.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
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
                                        camera.animateTo(s.lat, s.lon, 10.0)
                                        ensureCache()
                                        if (pickMode == 1) {
                                            query = ""
                                            suggestions = emptyList()
                                            setOrigin(s)
                                        } else {
                                            pickedDestination = s
                                            queryFromPick = true
                                            query = s.label
                                            route = null
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

        // Minimal attribution (over the map, instead of MapLibre's logo/(i) which crashes).
        Text(
            travel.attribution,
            color = onMapTextColor.copy(alpha = 0.65f),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 6.dp, bottom = 4.dp)
        )

        // Bottom card: destination/time + buffer + mode + actions.
        // Shown whenever a destination is chosen (even if the OSRM route fails or there is no GPS),
        // so the "Navigate in Google Maps" button and manual mode stay available.
        if (route != null || showManualCard || pickedDestination != null) {
            val cardScrim = scrimColor
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
                    // Toggle Route / Manual (time).
                    if (route != null) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !useManualDuration, onClick = { useManualDuration = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                colors = segColors
                            ) { Text(stringResource(Res.string.travel_route_mode), color = if (!useManualDuration) Color.Black else cardText) }
                            SegmentedButton(
                                selected = useManualDuration, onClick = { useManualDuration = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                colors = segColors
                            ) { Text(stringResource(Res.string.travel_time_mode), color = if (useManualDuration) Color.Black else cardText) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (useManualDuration || route == null) {
                        // Hours / minutes selector.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.travel_duration), color = cardText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                            stringResource(Res.string.travel_duration_total, TravelPlaylist.formatDuration(currentTargetMs())),
                            color = cardSub, fontSize = 11.sp
                        )
                        if (route != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.travel_route_available), color = cardSub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Button(onClick = { clearDestination() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = green)) {
                                    Text(stringResource(Res.string.travel_clear), fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        // Computed route (normal mode).
                        route?.let { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(Res.string.travel_eta, r.durationSec / 60, (r.distanceM / 1000).toInt()),
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
                            Text(stringResource(Res.string.travel_buffer, bufferMin.toInt()), color = cardSub, fontSize = 12.sp)
                            Slider(value = bufferMin, onValueChange = { bufferMin = it }, valueRange = 0f..30f, steps = 29)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.travel_playlist_mode), color = cardSub, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !manualMode, onClick = { manualMode = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            colors = segColors
                        ) { Text(stringResource(Res.string.travel_mode_auto), color = if (!manualMode) Color.Black else cardText) }
                        SegmentedButton(
                            selected = manualMode, onClick = { manualMode = true; ensureCache() },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            colors = segColors
                        ) { Text(stringResource(Res.string.travel_mode_manual), color = if (manualMode) Color.Black else cardText) }
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
                            stringResource(Res.string.travel_coverage, TravelPlaylist.formatDuration(covered), TravelPlaylist.formatDuration(tMs)),
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
                                Text(stringResource(Res.string.travel_add_tracks), color = cardText, fontSize = 13.sp)
                            }
                            if (manualTracks.isNotEmpty()) {
                                Button(
                                    onClick = { showPreview = true; ensureCache() },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = cardText, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.travel_preview), color = cardText, fontSize = 13.sp)
                                }
                            }
                        }
                        if (manualTracks.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.travel_manual_count, manualTracks.size),
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
                            Text(stringResource(Res.string.travel_preview_auto), color = cardText, fontSize = 13.sp)
                        }
                    }

                    // Navigate in Google Maps: opens the route (respecting the fixed origin) to drive
                    // while Rustify plays the playlist. No API key or SDK — via an intent to the map app.
                    pickedDestination?.let { dest ->
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
                                originOverride?.let { sb.append("&origin=${it.lat},${it.lon}") }
                                sb.append("&destination=${dest.lat},${dest.lon}&travelmode=driving")
                                travel.navigate(sb.toString(), dest.lat, dest.lon, platform)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, tint = green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.travel_navigate_gmaps), color = cardText, fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val list = if (manualMode) TravelPlaylist.fillRemaining(tMs, manualTracks.toList(), likedCache)
                                           else TravelPlaylist.build(tMs, likedCache)
                                startTrip(list)
                            }
                        },
                        enabled = !loading && (!manualMode || manualTracks.isNotEmpty()) && tMs > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = green, disabledContainerColor = Color(0xFF2C2C2E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                        else Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.travel_start), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Prompt shown when location is disabled.
        if (location.supported && !location.enabled) {
            val promptScrim = scrimColor
            val promptText = if (isDarkMap) Color.White else Color.Black
            Surface(
                color = promptScrim,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(Res.string.travel_enable_location), color = promptText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { location.openSettings() },
                            colors = ButtonDefaults.buttonColors(containerColor = green)
                        ) { Text(stringResource(Res.string.travel_enable), color = Color.Black, fontWeight = FontWeight.Bold) }
                        if (!location.hasPermission) {
                            Button(
                                onClick = { location.requestPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMap) Color(0xFF2C2C2E) else Color(0xFFEEEEEE))
                            ) { Text(stringResource(Res.string.travel_grant_location), color = promptText) }
                        }
                    }
                }
            }
        }

        // MiniPlayer over the map.
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

        // Status (errors).
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

    // Manual selection sheet (liked tracks).
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

    // Preview / edit / save sheet.
    if (showPreview) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Generate the Auto playlist on demand; the manual one is the selected tracks plus filler.
        val previewTracks = remember(manualMode, manualTracks.size, route, bufferMin, likedCache.size, useManualDuration, manualDurationH, manualDurationM) {
            val tMs = if (useManualDuration || route == null) (manualDurationH * 3600L + manualDurationM * 60L) * 1000L
                       else ((route!!.durationSec + bufferMin.toLong() * 60L) * 1000L)
            if (tMs <= 0) emptyList()
            else if (manualMode) TravelPlaylist.fillRemaining(tMs, manualTracks.toList(), likedCache)
            else TravelPlaylist.build(tMs, likedCache)
        }
        val reorderable = remember { mutableStateListOf<FullTrack>().apply { addAll(previewTracks) } }
        // Re-sync when the preview dependencies change.
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
                            val ids = reorderable.mapNotNull { it.id }.filter { it.isNotBlank() && !TrackRef.isLocal(it) }
                            spotifyRepo.addAllTracksToPlaylist(pl.id, ids)
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

// Manual sheet: shows covered time at the top and a list to tap to add/remove.
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
        Text(stringResource(Res.string.travel_pick_tracks), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { ratio },
            color = green, trackColor = Color(0xFF2C2C2E),
            modifier = Modifier.fillMaxWidth().height(6.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.travel_coverage, TravelPlaylist.formatDuration(covered), TravelPlaylist.formatDuration(targetMs)),
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
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var saveName by remember { mutableStateOf("Travel ${todayIso()}") }
    var saving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.travel_preview_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.travel_preview_count, tracks.size), color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(Res.string.travel_preview_total, TravelPlaylist.formatDuration(TravelPlaylist.totalMs(tracks))),
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.travel_drag_hint), color = Color.Gray, fontSize = 11.sp)
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
            label = { Text(stringResource(Res.string.travel_save_name)) },
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
                Text(stringResource(Res.string.travel_play), color = Color.Black, fontWeight = FontWeight.Bold)
            }
            // Saving needs an account; without one the button only led to a failure message.
            val signedIn = remember { SpotifyRepository.instance?.hasSavedSession() == true }
            if (signedIn) Button(
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
                Text(stringResource(Res.string.travel_save_spotify), color = Color.White, fontSize = 13.sp)
            }
        }
        saveResult?.let { ok ->
            Spacer(Modifier.height(6.dp))
            Text(
                if (ok) stringResource(Res.string.travel_save_ok) else stringResource(Res.string.travel_save_fail),
                color = if (ok) green else Color(0xFFE57373), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
