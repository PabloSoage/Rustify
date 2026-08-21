@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("UseKtx")

package com.varuna.rustify.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.sync.DriveSyncManager
import com.varuna.rustify.sync.DriveSyncPrefs
import com.varuna.rustify.sync.GoogleDriveSync
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.varuna.rustify.util.AppLinksHosts
import com.varuna.rustify.util.BatteryOptimization
import com.varuna.rustify.util.LogCapture
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// SettingsExtras.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Android Auto, el inspector de hashes de Spotify y el mapa de viajes.
//
// SettingsScreen.kt eran 3 796 lineas, de las que 1 875 estaban dentro de una sola funcion. Este
// corte se lleva los ayudantes de nivel superior, que son la mitad mecanica: cada uno es una
// seccion independiente de la pantalla y ninguno comparte estado con los demas. Lo que queda en
// SettingsScreen.kt es el composable grande, que es otra conversacion.
//
// Las funciones movidas pasaron de private a internal: en Kotlin un private de nivel superior
// es privado *del fichero*, asi que moverlas sin mas las habria hecho invisibles para la pantalla
// que las llama.

@Composable
internal fun AndroidAutoPreviewSection(context: Context) {
    val prefs = remember { context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("debug_auto_preview", false)) }
    val ytmRepo = remember { YtMusicRepository(context.applicationContext) }
    var path by remember { mutableStateOf(listOf("root")) }

    Spacer(modifier = Modifier.height(24.dp))
    Text(stringResource(R.string.settings_debug), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_auto_preview), color = Color.White, fontSize = 14.sp)
                    Text(stringResource(R.string.settings_auto_preview_hint), color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.edit { putBoolean("debug_auto_preview", it) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954)))
            }
            if (enabled) {
                Spacer(Modifier.height(12.dp))
                // Async load (as in the car): also resolves Spotify playlists and albums.
                var nodes by remember { mutableStateOf<List<com.varuna.rustify.player.AndroidAutoBrowse.Node>>(emptyList()) }
                var loadingNodes by remember { mutableStateOf(false) }
                LaunchedEffect(path) {
                    loadingNodes = true
                    nodes = com.varuna.rustify.player.AndroidAutoBrowse.childrenAsync(context, path.last(), ytmRepo)
                    loadingNodes = false
                }
                if (path.size > 1) {
                    Text("← " + stringResource(R.string.settings_back), color = Color(0xFF1DB954), fontSize = 13.sp,
                        modifier = Modifier.clickable { path = path.dropLast(1) }.padding(vertical = 6.dp))
                }
                if (loadingNodes && nodes.isEmpty()) {
                    CircularProgressIndicator(color = Color(0xFF1DB954), strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(vertical = 4.dp))
                } else if (nodes.isEmpty()) {
                    Text(stringResource(R.string.settings_auto_preview_empty), color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else nodes.take(60).forEach { node ->
                    val art = node.imageUrl ?: node.track?.album?.images?.firstOrNull()?.url
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .let { m -> if (node.browsable) m.clickable { path = path + node.id } else m }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cover art (as it would appear in the car); falls back to a ▸/♪ icon.
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!art.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = art, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(if (node.browsable) "▸" else "♪", color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(node.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            if (node.subtitle.isNotBlank()) Text(node.subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                        if (node.browsable) Text("›", color = Color.Gray, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpotifyHashInspectorSection() {
    val coroutineScope = rememberCoroutineScope()

    // Map of operationName -> sha256 hash (empty = not cached)
    var hashes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastRefreshed by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    fun loadHashes() {
        try {
            val json = org.json.JSONObject(com.varuna.rustify.bridge.NativeEngine.getSpotifyHashesNative())
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.getString(key) }
            hashes = map.toSortedMap()
        } catch (_: Exception) {
            hashes = emptyMap()
        }
    }

    LaunchedEffect(Unit) { loadHashes() }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Spotify GQL Hashes",
        color = Color(0xFF1DB954),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val cachedCount = hashes.count { it.value.isNotBlank() }
                    Text(
                        "$cachedCount / ${hashes.size} hashes cached",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (lastRefreshed.isNotEmpty()) {
                        Text(
                            "Last refresh: $lastRefreshed",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Refresh button
                    IconButton(
                        onClick = {
                            if (!isRefreshing) {
                                isRefreshing = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    com.varuna.rustify.bridge.NativeEngine.warmupSpotifyHashesNative()
                                    // Wait for warmup to propagate (it's async in Rust)
                                    kotlinx.coroutines.delay(3500)
                                    loadHashes()
                                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    withContext(Dispatchers.Main) {
                                        lastRefreshed = sdf.format(java.util.Date())
                                        isRefreshing = false
                                    }
                                }
                            }
                        }
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF1DB954),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh hashes",
                                tint = Color(0xFF1DB954)
                            )
                        }
                    }
                    // Expand/collapse toggle
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "Hide" else "Show",
                            color = Color(0xFF1DB954),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (expanded) {
                if (hashes.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No hashes cached. Tap refresh to scrape them from Spotify.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.HorizontalDivider(color = Color(0xFF333333))
                    Spacer(Modifier.height(8.dp))
                    hashes.entries.forEach { (operation, hash) ->
                        val isPresent = hash.isNotBlank()
                        val isLong = hash.length >= 40
                        // Color: green = good long hash, yellow = short/suspicious, red = missing
                        val dotColor = when {
                            !isPresent -> Color(0xFFEF5350)   // red
                            !isLong    -> Color(0xFFFFB300)   // amber
                            else       -> Color(0xFF66BB6A)   // green
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Color dot indicator
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(dotColor)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    operation,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    if (isPresent) hash.take(16) + "…" + hash.takeLast(8)
                                    else "— not cached —",
                                    color = if (isPresent) Color(0xFF888888) else Color(0xFFEF5350),
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TravelMapSection(context: Context) {
    val prefs = remember { context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE) }
    var mapTilerKey by remember {
        mutableStateOf(
            prefs.getString(com.varuna.rustify.travel.TravelSettings.KEY_MAPTILER_KEY, "") ?: ""
        )
    }
    val green = Color(0xFF1DB954)

    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.travel_title),
        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.travel_maptiler_key),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.travel_maptiler_key_desc),
                color = Color.Gray, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mapTilerKey,
                onValueChange = {
                    mapTilerKey = it.trim()
                    prefs.edit { putString(com.varuna.rustify.travel.TravelSettings.KEY_MAPTILER_KEY, mapTilerKey) }
                },
                singleLine = true,
                placeholder = { Text("abcDEF123456", color = Color(0xFF555555), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF2C2C2E),
                    focusedIndicatorColor = green, focusedLabelColor = green, cursorColor = green
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (mapTilerKey.isBlank())
                    "Using bundled keyless basemap (CARTO Voyager / OpenStreetMap)."
                else
                    "Using MapTiler Cloud vector tiles with your key.",
                color = Color.Gray, fontSize = 11.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.travel_google_key),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.travel_google_key_desc),
                color = Color.Gray, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            var googleKey by remember {
                mutableStateOf(
                    prefs.getString(com.varuna.rustify.travel.TravelSettings.KEY_GEOCODING_API_KEY, "") ?: ""
                )
            }
            OutlinedTextField(
                value = googleKey,
                onValueChange = {
                    googleKey = it.trim()
                    prefs.edit { putString(com.varuna.rustify.travel.TravelSettings.KEY_GEOCODING_API_KEY, googleKey) }
                },
                singleLine = true,
                placeholder = { Text("AIzaSy...", color = Color(0xFF555555), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF2C2C2E),
                    focusedIndicatorColor = green, focusedLabelColor = green, cursorColor = green
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (googleKey.isBlank())
                    stringResource(R.string.travel_google_key_off)
                else
                    stringResource(R.string.travel_google_key_on),
                color = Color.Gray, fontSize = 11.sp
            )
        }
    }
}

