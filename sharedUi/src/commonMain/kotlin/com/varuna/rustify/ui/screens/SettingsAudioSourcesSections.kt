// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsAudioSourcesSections.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.audio.AddonAudioSource
import com.varuna.rustify.audio.AudioBackendSettings
import com.varuna.rustify.audio.AudioSourceCapabilities
import com.varuna.rustify.audio.AudioSourceEnvironment
import com.varuna.rustify.audio.AudioSourceRegistry
import com.varuna.rustify.audio.DeezerArl
import com.varuna.rustify.audio.DeezerAudioSource
import com.varuna.rustify.audio.DeezerClient
import com.varuna.rustify.audio.DeezerSettings
import com.varuna.rustify.audio.InnertubeAudioSource
import com.varuna.rustify.audio.InvidiousAudioSource
import com.varuna.rustify.audio.InvidiousInstances
import com.varuna.rustify.audio.InvidiousSettings
import com.varuna.rustify.audio.SubsonicAudioSource
import com.varuna.rustify.audio.SubsonicSettings
import com.varuna.rustify.bridge.AddonRepository
import com.varuna.rustify.bridge.InstalledAddon
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.player.LocalPlayer
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.relativeTimeSpan
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.backend_addon
import com.varuna.rustify.ui.resources.backend_deezer
import com.varuna.rustify.ui.resources.backend_download
import com.varuna.rustify.ui.resources.backend_innertube
import com.varuna.rustify.ui.resources.backend_invidious
import com.varuna.rustify.ui.resources.backend_stream
import com.varuna.rustify.ui.resources.backend_subsonic
import com.varuna.rustify.ui.resources.backend_ytdlp
import com.varuna.rustify.ui.resources.deezer_arl_legend
import com.varuna.rustify.ui.resources.dz_arl_invalid
import com.varuna.rustify.ui.resources.dz_arl_label
import com.varuna.rustify.ui.resources.dz_arl_valid
import com.varuna.rustify.ui.resources.dz_fetch_arls
import com.varuna.rustify.ui.resources.dz_fetched_fmt
import com.varuna.rustify.ui.resources.dz_footer
import com.varuna.rustify.ui.resources.dz_found_fmt
import com.varuna.rustify.ui.resources.dz_hint
import com.varuna.rustify.ui.resources.dz_in_use
import com.varuna.rustify.ui.resources.dz_mode_single
import com.varuna.rustify.ui.resources.dz_mode_source
import com.varuna.rustify.ui.resources.dz_no_arls
import com.varuna.rustify.ui.resources.dz_quality
import com.varuna.rustify.ui.resources.dz_source_hint
import com.varuna.rustify.ui.resources.dz_source_label
import com.varuna.rustify.ui.resources.dz_test
import com.varuna.rustify.ui.resources.dz_test_arl
import com.varuna.rustify.ui.resources.dz_test_playback
import com.varuna.rustify.ui.resources.dz_test_playback_hint
import com.varuna.rustify.ui.resources.dz_tp_fail_fmt
import com.varuna.rustify.ui.resources.dz_tp_no_track
import com.varuna.rustify.ui.resources.dz_tp_ok_fmt
import com.varuna.rustify.ui.resources.dz_updated_fmt
import com.varuna.rustify.ui.resources.dz_use
import com.varuna.rustify.ui.resources.inv_add
import com.varuna.rustify.ui.resources.inv_add_custom_label
import com.varuna.rustify.ui.resources.inv_allow_anon
import com.varuna.rustify.ui.resources.inv_allow_anon_desc
import com.varuna.rustify.ui.resources.inv_anon_suffix
import com.varuna.rustify.ui.resources.inv_fixed_url_label
import com.varuna.rustify.ui.resources.inv_health_fmt
import com.varuna.rustify.ui.resources.inv_health_unknown
import com.varuna.rustify.ui.resources.inv_hint_enable
import com.varuna.rustify.ui.resources.inv_instances
import com.varuna.rustify.ui.resources.inv_mode_auto
import com.varuna.rustify.ui.resources.inv_mode_fixed
import com.varuna.rustify.ui.resources.inv_refresh
import com.varuna.rustify.ui.resources.inv_remove
import com.varuna.rustify.ui.resources.inv_test
import com.varuna.rustify.ui.resources.inv_title
import com.varuna.rustify.ui.resources.inv_tor_route
import com.varuna.rustify.ui.resources.settings_addons
import com.varuna.rustify.ui.resources.settings_addons_desc
import com.varuna.rustify.ui.resources.settings_addons_empty
import com.varuna.rustify.ui.resources.settings_addons_install
import com.varuna.rustify.ui.resources.settings_addons_privacy
import com.varuna.rustify.ui.resources.settings_addons_url_label
import com.varuna.rustify.ui.resources.settings_audio_backends
import com.varuna.rustify.ui.resources.settings_backends_download_order
import com.varuna.rustify.ui.resources.settings_backends_drag_hint
import com.varuna.rustify.ui.resources.settings_backends_stream_order
import com.varuna.rustify.ui.resources.settings_subsonic
import com.varuna.rustify.ui.resources.settings_subsonic_desc
import com.varuna.rustify.ui.resources.settings_subsonic_failed
import com.varuna.rustify.ui.resources.settings_subsonic_ok
import com.varuna.rustify.ui.resources.settings_subsonic_password
import com.varuna.rustify.ui.resources.settings_subsonic_server
import com.varuna.rustify.ui.resources.settings_subsonic_test
import com.varuna.rustify.ui.resources.settings_subsonic_user
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Settings' audio sources, moved from the app's SettingsBackendSections.kt and
// SettingsAddonSections.kt (docs serie-4.x/27 §2.5, phase E): the stream and download order, the
// add-ons, Invidious, Deezer and Subsonic. The sources themselves are :shared's, so the phone and
// the desktop configure the same chain. What only Android's player has (offload, bit-perfect, the
// headset button…) stays in the app.
//
// Everything these call that touches the network or the core is suspending and moves itself off
// the calling thread, so the coroutines here run on the interface's.

/** The sources' environment, as the platform gave it to the registry at start-up. */
@Composable
private fun rememberSourceEnvironment(): AudioSourceEnvironment {
    val platform = LocalAppPlatform.current
    // The registry is initialised before the interface on both platforms; the fallback is the same
    // settings store without a stream cache, so a screen can never draw over nothing.
    return remember { AudioSourceRegistry.environment ?: AudioSourceEnvironment(platform.prefs()) }
}

/** How Settings names a source: by its id, since the sources live where there are no resources. */
private fun sourceName(id: String): StringResource = when {
    id == InnertubeAudioSource.ID -> Res.string.backend_innertube
    id == AudioSourceRegistry.YTDLP_ID -> Res.string.backend_ytdlp
    id == InvidiousAudioSource.ID -> Res.string.backend_invidious
    id == DeezerAudioSource.ID -> Res.string.backend_deezer
    id == SubsonicAudioSource.ID -> Res.string.backend_subsonic
    AddonAudioSource.addonIdOf(id) != null -> Res.string.backend_addon
    else -> Res.string.backend_ytdlp
}

/**
 * The two orders the chain is tried in (stream, download), drag to reorder and a switch each, and
 * the add-ons below them, which are where new entries in those lists come from.
 */
@Composable
fun AudioSourcesOrderSection() {
    val store = rememberSourceEnvironment().settings
    // Bumped by [AddonsSection] whenever the installed add-ons change. Without it these three
    // `remember`s would be computed once and an add-on installed a moment later would be missing
    // from the order lists until the screen was left and reopened — which reads as "installing did
    // nothing".
    var backendsRevision by remember { mutableIntStateOf(0) }
    val enabledElsewhere by AudioBackendSettings.enabledElsewhere.collectAsState()
    LaunchedEffect(enabledElsewhere) { if (enabledElsewhere > 0) backendsRevision++ }

    val knownIds = remember(backendsRevision) { AudioSourceRegistry.knownIds() }
    val catalog = remember(backendsRevision) { AudioSourceRegistry.catalog().associateBy { it.id } }
    var streamOrder by remember(backendsRevision) { mutableStateOf(AudioBackendSettings.loadOrder(store, AudioBackendSettings.KEY_STREAM, knownIds)) }
    var downloadOrder by remember(backendsRevision) { mutableStateOf(AudioBackendSettings.loadOrder(store, AudioBackendSettings.KEY_DOWNLOAD, knownIds)) }

    Spacer(modifier = Modifier.height(24.dp))
    Text(stringResource(Res.string.settings_audio_backends), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_backends_stream_order), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ReorderableBackendList(streamOrder, catalog) { newOrder -> streamOrder = newOrder; AudioBackendSettings.saveOrder(store, AudioBackendSettings.KEY_STREAM, newOrder) }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.settings_backends_download_order), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // Only what can download. InnerTube streams only, and listing it here switched on read as
            // "downloads go through it" when the chain skips it; it stays stored, after the rest.
            val canDownload: (String) -> Boolean = { id -> catalog[id]?.canDownload != false }
            ReorderableBackendList(downloadOrder.filter { canDownload(it.id) }, catalog) { newOrder ->
                val full = newOrder + downloadOrder.filterNot { canDownload(it.id) }
                downloadOrder = full
                AudioBackendSettings.saveOrder(store, AudioBackendSettings.KEY_DOWNLOAD, full)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.settings_backends_drag_hint), color = Color.Gray, fontSize = 12.sp)
        }
    }

    AddonsSection(onAddonsChanged = { backendsRevision++ })
}

/**
 * Installing audio backends by URL.
 *
 * Kept beneath the backend order on purpose: an installed addon becomes one more entry in the lists
 * above, so this is where new entries come from rather than a separate world — and
 * [onAddonsChanged] is what makes those lists show it without leaving the screen.
 */
@Composable
private fun AddonsSection(onAddonsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<InstalledAddon>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        addons = AddonRepository.list()
        AudioSourceRegistry.refreshAddons()
        onAddonsChanged()
    }

    LaunchedEffect(Unit) { reload() }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(Res.string.settings_addons),
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
                stringResource(Res.string.settings_addons_desc),
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
                                AddonRepository.setEnabled(addon.id, on)
                                reload()
                            }
                        },
                        colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                    )
                    IconButton(onClick = {
                        scope.launch {
                            AddonRepository.uninstall(addon.id)
                            reload()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFAA4444))
                    }
                }
            }

            if (addons.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_addons_empty),
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text(stringResource(Res.string.settings_addons_url_label)) },
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
                        AddonRepository.install(url)
                            .onSuccess { url = ""; reload() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(Res.string.settings_addons_install))
            }

            Spacer(Modifier.height(12.dp))
            // Not decoration: an addon is code written by someone else, and this is the one place
            // the user gets told what it will and will not be given.
            Text(
                stringResource(Res.string.settings_addons_privacy),
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ReorderableBackendList(
    entries: List<AudioBackendSettings.BackendEntry>,
    catalog: Map<String, AudioSourceCapabilities>,
    onOrderChanged: (List<AudioBackendSettings.BackendEntry>) -> Unit
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
                        Text(stringResource(sourceName(entry.id)), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val badges = buildList {
                            if (caps?.canStream == true) add(stringResource(Res.string.backend_stream))
                            if (caps?.canDownload == true) add(stringResource(Res.string.backend_download))
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

// Invidious instance configuration (the on/off and ordering live in Audio Backends).
@Composable
fun InvidiousBackendSection() {
    val green = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()
    val env = rememberSourceEnvironment()
    val store = env.settings
    val player = LocalPlayer.current
    val inv = InvidiousSettings
    var mode by remember { mutableStateOf(inv.mode(store)) }
    var fixed by remember { mutableStateOf(inv.fixedInstance(store)) }
    var torOn by remember { mutableStateOf(inv.torEnabled(store)) }
    var anonOn by remember { mutableStateOf(inv.allowAnonNetworks(store)) }
    var custom by remember { mutableStateOf("") }
    var customList by remember { mutableStateOf(inv.customInstances(store)) }
    var instances by remember { mutableStateOf<List<InvidiousInstances.Instance>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pings by remember { mutableStateOf(mapOf<String, Boolean?>()) }
    // End-to-end playback tester for the current track via Invidious (same as the Deezer one).
    var invTpBusy by remember { mutableStateOf(false) }
    var invTpStatus by remember { mutableStateOf("") }

    fun refresh(force: Boolean) {
        loading = true
        scope.launch {
            instances = InvidiousInstances.list(env, force).take(15)
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh(false) }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.inv_title), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.inv_hint_enable), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("auto" to stringResource(Res.string.inv_mode_auto), "fixed" to stringResource(Res.string.inv_mode_fixed)).forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { mode = code; inv.setMode(store, code) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = green))
                        Text(label, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (mode == "fixed") {
                OutlinedTextField(value = fixed, onValueChange = { fixed = it; inv.setFixedInstance(store, it) },
                    label = { Text(stringResource(Res.string.inv_fixed_url_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.inv_instances), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = green)
                    TextButton(onClick = { refresh(true) }) { Text(stringResource(Res.string.inv_refresh), color = green, fontSize = 12.sp) }
                }
                instances.forEach { inst ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        val badge = when (inst.type) { "onion" -> "🧅"; "i2p" -> "📨"; "ygg" -> "🕸️"; else -> "🌐" }
                        Text(badge, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                        val h = inst.health
                        val healthText = if (h != null) stringResource(Res.string.inv_health_fmt, h.toInt()) else stringResource(Res.string.inv_health_unknown)
                        val anonText = if (inst.isAnon) stringResource(Res.string.inv_anon_suffix) else ""
                        Column(Modifier.weight(1f)) {
                            Text(inst.baseUrl.removePrefix("https://"), color = Color.White, fontSize = 12.sp, maxLines = 1)
                            Text(healthText + anonText, color = Color.Gray, fontSize = 10.sp)
                        }
                        Text(when { pings.containsKey(inst.baseUrl) && pings[inst.baseUrl] == null -> "⏳"; pings[inst.baseUrl] == true -> "✅"; pings[inst.baseUrl] == false -> "❌"; else -> "" }, fontSize = 12.sp)
                        TextButton(onClick = { scope.launch { pings = pings + (inst.baseUrl to null); val ok = InvidiousInstances.probe(env, inst); pings = pings + (inst.baseUrl to ok) } }) {
                            Text(stringResource(Res.string.inv_test), color = green, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text(stringResource(Res.string.inv_add_custom_label)) }, singleLine = true, modifier = Modifier.weight(1f), colors = backendFieldColors())
                TextButton(onClick = { if (custom.isNotBlank()) { inv.addCustomInstance(store, custom); customList = inv.customInstances(store); custom = "" } }) { Text(stringResource(Res.string.inv_add), color = green) }
            }
            customList.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { inv.removeCustomInstance(store, c); customList = inv.customInstances(store) }) { Text(stringResource(Res.string.inv_remove), color = Color(0xFFCC3333), fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.inv_allow_anon), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(Res.string.inv_allow_anon_desc), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(checked = anonOn, onCheckedChange = { anonOn = it; inv.setAllowAnonNetworks(store, it) }, colors = com.varuna.rustify.ui.theme.RustifySwitch.colors())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.inv_tor_route), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = torOn, onCheckedChange = { torOn = it; inv.setTorEnabled(store, it) }, colors = com.varuna.rustify.ui.theme.RustifySwitch.colors())
            }

            // Test playback of the current track via Invidious (resolves like the real backend).
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                invTpBusy = true; invTpStatus = ""
                scope.launch {
                    val track = player.state.value.currentTrack
                    if (track == null) { invTpStatus = appString(Res.string.dz_tp_no_track); invTpBusy = false; return@launch }
                    val r = InvidiousAudioSource(env).resolveStreamUrl(track, null)
                    invTpStatus = r.fold(
                        onSuccess = { appString(Res.string.dz_tp_ok_fmt, "stream ✓") },
                        onFailure = { appString(Res.string.dz_tp_fail_fmt, it.message ?: "error") }
                    )
                    invTpBusy = false
                }
            }, enabled = !invTpBusy) { Text(stringResource(Res.string.dz_test_playback), color = green) }
            if (invTpBusy) { Spacer(Modifier.height(6.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green) }
            if (invTpStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(invTpStatus, color = Color.White, fontSize = 12.sp) }
        }
    }
}

// Deezer backend configuration (own ARL or ARL source) plus ARL and playback testers.
@Composable
fun DeezerBackendSection() {
    val green = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()
    val store = rememberSourceEnvironment().settings
    val player = LocalPlayer.current
    val dz = DeezerSettings
    var arlMode by remember { mutableStateOf(dz.arlMode(store)) }
    var arl by remember { mutableStateOf(dz.arl(store)) }
    var source by remember { mutableStateOf(dz.sourceUrl(store)) }
    var quality by remember { mutableStateOf(dz.quality(store)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // ARL source tester: lists the ARLs from the web, tests each one (✅/❌), and lets the user pick which to use.
    var entries by remember { mutableStateOf<List<DeezerArl.ArlEntry>>(emptyList()) }
    // arl -> ArlCheck (tested) or null (testing). Absent key = not yet tested.
    var entryStatus by remember { mutableStateOf(mapOf<String, DeezerClient.ArlCheck?>()) }
    var fetchedAt by remember { mutableLongStateOf(0L) }
    var workingArl by remember { mutableStateOf(dz.workingArl(store)) }
    // End-to-end playback tester (auth + match + get_url) without changing the backend order.
    var tpStatus by remember { mutableStateOf("") }
    var tpBusy by remember { mutableStateOf(false) }

    val validMsg = stringResource(Res.string.dz_arl_valid)
    val invalidMsg = stringResource(Res.string.dz_arl_invalid)
    val noArlsMsg = stringResource(Res.string.dz_no_arls)

    fun mask(a: String) = if (a.length > 12) a.take(6) + "…" + a.takeLast(4) else a

    // Auto-load: if a source URL is already saved and we are in "source" mode, list the ARLs when the
    // section opens, without requiring a button press.
    LaunchedEffect(arlMode) {
        if (arlMode == "source" && source.isNotBlank() && entries.isEmpty() && !busy) {
            busy = true; status = ""
            val res = DeezerArl.fetchDetailed(source.trim())
            entries = res.entries; fetchedAt = currentTimeMillis(); busy = false
            status = if (res.entries.isEmpty()) (res.error?.let { "$noArlsMsg ($it)" } ?: noArlsMsg) else ""
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.backend_deezer), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.dz_hint), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("single" to stringResource(Res.string.dz_mode_single), "source" to stringResource(Res.string.dz_mode_source)).forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { arlMode = code; dz.setArlMode(store, code) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = arlMode == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = green))
                        Text(label, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (arlMode == "single") {
                OutlinedTextField(value = arl, onValueChange = { arl = it; dz.setArl(store, it) }, label = { Text(stringResource(Res.string.dz_arl_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
                TextButton(onClick = {
                    busy = true; status = ""
                    scope.launch {
                        val ok = DeezerClient().testArl(arl.trim())
                        if (ok) { dz.setWorkingArl(store, arl.trim()) }
                        status = if (ok) validMsg else invalidMsg; busy = false; workingArl = dz.workingArl(store)
                    }
                }, enabled = !busy) { Text(stringResource(Res.string.dz_test_arl), color = green) }
            } else {
                OutlinedTextField(value = source, onValueChange = { source = it; dz.setSourceUrl(store, it) }, label = { Text(stringResource(Res.string.dz_source_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
                Spacer(Modifier.height(8.dp))
                // A filled button so it clearly reads as a button and users recognize it as tappable.
                Button(
                    onClick = {
                        busy = true; status = ""; entryStatus = emptyMap()
                        scope.launch {
                            val res = DeezerArl.fetchDetailed(source.trim())
                            entries = res.entries; fetchedAt = currentTimeMillis(); busy = false
                            // Shows the actual failure reason (HTTP 403 / timeout / 0 ARLs, etc.)
                            // instead of a generic, uninformative "no ARLs" message.
                            status = if (res.entries.isEmpty()) (res.error?.let { "$noArlsMsg ($it)" } ?: noArlsMsg) else ""
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(Res.string.dz_fetch_arls), fontWeight = FontWeight.SemiBold) }
                Text(stringResource(Res.string.dz_source_hint), color = Color.Gray, fontSize = 11.sp)

                if (entries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.dz_found_fmt, entries.size), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (fetchedAt > 0L) {
                        Text(stringResource(Res.string.dz_fetched_fmt, relativeTimeSpan(fetchedAt)), color = Color.Gray, fontSize = 10.sp)
                    }
                    // Legend: ✅ plays (premium) · ⚠️ authenticates but does not play (free account) · ❌ invalid.
                    Text(stringResource(Res.string.deezer_arl_legend), color = Color(0xFF888888), fontSize = 10.sp)
                    entries.take(30).forEach { e ->
                        val tested = entryStatus.containsKey(e.arl)
                        val chk = entryStatus[e.arl]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((e.country.ifBlank { "—" }) + "  " + mask(e.arl), color = Color.White, fontSize = 11.sp, maxLines = 1)
                                if (e.updated.isNotBlank()) Text(stringResource(Res.string.dz_updated_fmt, e.updated), color = Color.Gray, fontSize = 9.sp)
                                if (chk != null) Text(chk.detail, color = if (chk.canStream) green else Color(0xFFFFB74D), fontSize = 9.sp, maxLines = 1)
                            }
                            Text(
                                when { tested && chk == null -> "⏳"; chk == null -> ""; chk.canStream -> "✅"; chk.auth -> "⚠️"; else -> "❌" },
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TextButton(onClick = {
                                entryStatus = entryStatus + (e.arl to null)
                                scope.launch {
                                    // checkArl: auth + get_url on a canary track to distinguish premium/free/invalid.
                                    val res = DeezerClient().checkArl(e.arl)
                                    entryStatus = entryStatus + (e.arl to res)
                                    if (res.canStream) { dz.setWorkingArl(store, e.arl); workingArl = e.arl }
                                }
                            }) { Text(stringResource(Res.string.dz_test), color = green, fontSize = 11.sp) }
                            if (workingArl == e.arl) {
                                Text(stringResource(Res.string.dz_in_use), color = green, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                            } else {
                                TextButton(onClick = { dz.setWorkingArl(store, e.arl); workingArl = e.arl }) { Text(stringResource(Res.string.dz_use), color = Color.White, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.dz_quality), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth()) {
                listOf("flac" to "FLAC", "mp3_320" to "MP3 320", "mp3_128" to "MP3 128").forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { quality = code; dz.setQuality(store, code) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = quality == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = green))
                        Text(label, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            if (busy) { Spacer(Modifier.height(6.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green) }
            if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, color = Color.White, fontSize = 12.sp) }

            // End-to-end playback tester: resolves the current track via Deezer (auth + ISRC match +
            // get_url) without requiring Deezer to be the only backend. Reports the served format
            // (FLAC/MP3_320, etc.) or the failure reason, for diagnosis without changing the order.
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                tpBusy = true; tpStatus = ""
                scope.launch {
                    val track = player.state.value.currentTrack
                    if (track == null) {
                        tpStatus = appString(Res.string.dz_tp_no_track); tpBusy = false
                        return@launch
                    }
                    val result = runCatching {
                        val a = DeezerArl.ensureArl(store) ?: error("no working ARL (testea un ARL primero)")
                        val client = DeezerClient()
                        // Step by step to report what fails: auth vs not-found vs no stream rights.
                        val session = client.auth(a) ?: error("ARL auth failed (inválido/caducado)")
                        val id = client.deezerTrackId(track) ?: error("track not found on Deezer (ISRC/búsqueda)")
                        val media = client.media(session, id, dz.formatChain(store))
                            ?: error("get_url vacío (ARL sin premium/HiFi o región) — prueba un ARL con ✅")
                        media.format
                    }
                    tpStatus = result.fold(
                        onSuccess = { appString(Res.string.dz_tp_ok_fmt, it) },
                        onFailure = { appString(Res.string.dz_tp_fail_fmt, it.message ?: "error") }
                    )
                    tpBusy = false
                }
            }, enabled = !tpBusy) { Text(stringResource(Res.string.dz_test_playback), color = green) }
            Text(stringResource(Res.string.dz_test_playback_hint), color = Color.Gray, fontSize = 11.sp)
            if (tpBusy) { Spacer(Modifier.height(6.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green) }
            if (tpStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(tpStatus, color = Color.White, fontSize = 12.sp) }

            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.dz_footer), color = Color(0xFF888888), fontSize = 11.sp)
        }
    }
}

/** The user's own Subsonic server (`20`): address, user, password, checked with `ping`. */
@Composable
fun SubsonicBackendSection() {
    val store = rememberSourceEnvironment().settings
    val ss = SubsonicSettings
    val initial = remember { ss.load(store) }
    var server by remember { mutableStateOf(initial.server) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.settings_subsonic), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_subsonic_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { Text(stringResource(Res.string.settings_subsonic_server)) }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true,
                label = { Text(stringResource(Res.string.settings_subsonic_user)) }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(Res.string.settings_subsonic_password)) }, modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                enabled = !busy && server.isNotBlank() && user.isNotBlank(),
                onClick = {
                    val config = SubsonicSettings.Config(server.trim(), user.trim(), password)
                    ss.save(store, config)
                    busy = true
                    scope.launch {
                        val r = ss.ping(config)
                        busy = false
                        if (r.isSuccess) AudioBackendSettings.enable(store, SubsonicAudioSource.ID)
                        status = r.fold(
                            { appString(Res.string.settings_subsonic_ok, it) },
                            { appString(Res.string.settings_subsonic_failed, it.message ?: "") }
                        )
                    }
                }
            ) { Text(stringResource(Res.string.settings_subsonic_test), color = Color(0xFF1DB954)) }
            if (status.isNotEmpty()) Text(status, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}
