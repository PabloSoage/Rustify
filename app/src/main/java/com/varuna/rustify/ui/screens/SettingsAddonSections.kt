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

// SettingsAddonSections.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Add-ons instalables y proveedores de letras, con sus dos listas reordenables.
//
// SettingsScreen.kt eran 3 796 lineas, de las que 1 875 estaban dentro de una sola funcion. Este
// corte se lleva los ayudantes de nivel superior, que son la mitad mecanica: cada uno es una
// seccion independiente de la pantalla y ninguno comparte estado con los demas. Lo que queda en
// SettingsScreen.kt es el composable grande, que es otra conversacion.
//
// Las funciones movidas pasaron de private a internal: en Kotlin un private de nivel superior
// es privado *del fichero*, asi que moverlas sin mas las habria hecho invisibles para la pantalla
// que las llama.

/**
 * Installing audio backends by URL.
 *
 * Kept beneath the backend order on purpose: an installed addon becomes one more entry in the lists
 * above, so this is where new entries come from rather than a separate world — and
 * [onAddonsChanged] is what makes those lists show it without leaving the screen.
 */
@Composable
internal fun AddonsSection(context: Context, onAddonsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<com.varuna.rustify.bridge.InstalledAddon>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        addons = com.varuna.rustify.bridge.AddonRepository.list()
        com.varuna.rustify.audio.AudioSourceRegistry.refreshAddons(context)
        onAddonsChanged()
    }

    LaunchedEffect(Unit) { reload() }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.settings_addons),
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
            Text(
                stringResource(R.string.settings_addons_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            addons.forEach { addon ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addon.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${addon.id} · ${addon.version}",
                            color = Color(0xFF888888),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = addon.enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                com.varuna.rustify.bridge.AddonRepository.setEnabled(addon.id, on)
                                reload()
                            }
                        }
                    )
                    IconButton(onClick = {
                        scope.launch {
                            com.varuna.rustify.bridge.AddonRepository.uninstall(addon.id)
                            reload()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFAA4444))
                    }
                }
            }

            if (addons.isEmpty()) {
                Text(
                    stringResource(R.string.settings_addons_empty),
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text(stringResource(R.string.settings_addons_url_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFCC5555), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (url.isBlank() || busy) return@Button
                    busy = true
                    error = null
                    scope.launch {
                        com.varuna.rustify.bridge.AddonRepository.install(url)
                            .onSuccess { url = ""; reload() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_addons_install))
            }

            Spacer(Modifier.height(12.dp))
            // Not decoration: an addon is code written by someone else, and this is the one place
            // the user gets told what it will and will not be given.
            Text(
                stringResource(R.string.settings_addons_privacy),
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
internal fun ReorderableBackendList(
    entries: List<com.varuna.rustify.audio.AudioBackendSettings.BackendEntry>,
    catalog: Map<String, com.varuna.rustify.audio.AudioSourceCapabilities>,
    onOrderChanged: (List<com.varuna.rustify.audio.AudioBackendSettings.BackendEntry>) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    var order by remember(entries) { mutableStateOf(entries) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column {
        order.forEachIndexed { index, entry ->
            key(entry.id) {
                val currentIndex by rememberUpdatedState(index)
                val currentOrder by rememberUpdatedState(order)
                val caps = catalog[entry.id]
                val isDragging = draggingIndex == currentIndex
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(if (isDragging) Color(0xFF2A2A2A) else Color.Transparent)
                        .pointerInput(entry.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingIndex = currentIndex; dragOffset = 0f },
                                onDragEnd = { if (draggingIndex != null) onOrderChanged(order); draggingIndex = null; dragOffset = 0f },
                                onDragCancel = { draggingIndex = null; dragOffset = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume(); dragOffset += dragAmount.y
                                    val moved = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                    val targetDelta = (dragOffset / rowHeightPx).toInt()
                                    if (targetDelta != 0) {
                                        val target = (moved + targetDelta).coerceIn(0, currentOrder.lastIndex)
                                        if (target != moved) {
                                            val mutable = currentOrder.toMutableList(); mutable.add(target, mutable.removeAt(moved))
                                            order = mutable; dragOffset -= (target - moved) * rowHeightPx; draggingIndex = target; onOrderChanged(order)
                                        }
                                    }
                                }
                            )
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragHandle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(caps?.displayNameRes ?: R.string.backend_ytdlp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val badges = buildList {
                            if (caps?.canStream == true) add(stringResource(R.string.backend_stream))
                            if (caps?.canDownload == true) add(stringResource(R.string.backend_download))
                        }
                        if (badges.isNotEmpty()) Text(badges.joinToString(" · "), color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = entry.enabled, onCheckedChange = { checked ->
                        val mutable = order.toMutableList(); mutable[currentIndex] = entry.copy(enabled = checked); order = mutable; onOrderChanged(order)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954)))
                }
            }
        }
    }
}

@Composable
internal fun LyricsProvidersSection(context: Context) {
    var entries by remember { mutableStateOf(com.varuna.rustify.bridge.LyricsSettings.load(context)) }
    Spacer(modifier = Modifier.height(24.dp))
    Text(stringResource(R.string.settings_lyrics_providers), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            ReorderableLyricsList(entries) { newOrder ->
                entries = newOrder
                com.varuna.rustify.bridge.LyricsSettings.save(context, newOrder)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_lyrics_providers_hint), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun ReorderableLyricsList(
    entries: List<com.varuna.rustify.bridge.LyricsSettings.ProviderEntry>,
    onOrderChanged: (List<com.varuna.rustify.bridge.LyricsSettings.ProviderEntry>) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    var order by remember(entries) { mutableStateOf(entries) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Column {
        order.forEachIndexed { index, entry ->
            key(entry.id) {
                val currentIndex by rememberUpdatedState(index)
                val currentOrder by rememberUpdatedState(order)
                val isDragging = draggingIndex == currentIndex
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(if (isDragging) Color(0xFF2A2A2A) else Color.Transparent)
                        .pointerInput(entry.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingIndex = currentIndex; dragOffset = 0f },
                                onDragEnd = { if (draggingIndex != null) onOrderChanged(order); draggingIndex = null; dragOffset = 0f },
                                onDragCancel = { draggingIndex = null; dragOffset = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume(); dragOffset += dragAmount.y
                                    val moved = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                    val targetDelta = (dragOffset / rowHeightPx).toInt()
                                    if (targetDelta != 0) {
                                        val target = (moved + targetDelta).coerceIn(0, currentOrder.lastIndex)
                                        if (target != moved) {
                                            val mutable = currentOrder.toMutableList(); mutable.add(target, mutable.removeAt(moved))
                                            order = mutable; dragOffset -= (target - moved) * rowHeightPx; draggingIndex = target; onOrderChanged(order)
                                        }
                                    }
                                }
                            )
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragHandle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    val nameRes = com.varuna.rustify.bridge.LyricsProviders.byId(entry.id)?.displayNameRes
                    Text(
                        if (nameRes != null) stringResource(nameRes) else entry.id,
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                    )
                    Switch(checked = entry.enabled, onCheckedChange = { checked ->
                        val mutable = order.toMutableList(); mutable[currentIndex] = entry.copy(enabled = checked); order = mutable; onOrderChanged(order)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954)))
                }
            }
        }
    }
}

