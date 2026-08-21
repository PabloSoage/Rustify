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

// SettingsBackendSections.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Los backends de audio y su configuracion: Invidious, Deezer y el orden de la cadena.
//
// SettingsScreen.kt eran 3 796 lineas, de las que 1 875 estaban dentro de una sola funcion. Este
// corte se lleva los ayudantes de nivel superior, que son la mitad mecanica: cada uno es una
// seccion independiente de la pantalla y ninguno comparte estado con los demas. Lo que queda en
// SettingsScreen.kt es el composable grande, que es otra conversacion.
//
// Las funciones movidas pasaron de private a internal: en Kotlin un private de nivel superior
// es privado *del fichero*, asi que moverlas sin mas las habria hecho invisibles para la pantalla
// que las llama.

// Invidious instance configuration (the on/off and ordering live in Audio Backends).
@Composable
internal fun InvidiousBackendSection(context: Context) {
    val green = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()
    val inv = com.varuna.rustify.audio.InvidiousSettings
    var mode by remember { mutableStateOf(inv.mode(context)) }
    var fixed by remember { mutableStateOf(inv.fixedInstance(context)) }
    var torOn by remember { mutableStateOf(inv.torEnabled(context)) }
    var anonOn by remember { mutableStateOf(inv.allowAnonNetworks(context)) }
    var custom by remember { mutableStateOf("") }
    var customList by remember { mutableStateOf(inv.customInstances(context)) }
    var instances by remember { mutableStateOf<List<com.varuna.rustify.audio.InvidiousInstances.Instance>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pings by remember { mutableStateOf(mapOf<String, Boolean?>()) }
    // End-to-end playback tester for the current track via Invidious (same as the Deezer one).
    var invTpBusy by remember { mutableStateOf(false) }
    var invTpStatus by remember { mutableStateOf("") }

    fun refresh(force: Boolean) {
        loading = true
        scope.launch(Dispatchers.IO) {
            val list = com.varuna.rustify.audio.InvidiousInstances.list(context, force).take(15)
            withContext(Dispatchers.Main) { instances = list; loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh(false) }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.inv_title), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.inv_hint_enable), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("auto" to stringResource(R.string.inv_mode_auto), "fixed" to stringResource(R.string.inv_mode_fixed)).forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { mode = code; inv.setMode(context, code) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = green))
                        Text(label, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (mode == "fixed") {
                OutlinedTextField(value = fixed, onValueChange = { fixed = it; inv.setFixedInstance(context, it) },
                    label = { Text(stringResource(R.string.inv_fixed_url_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.inv_instances), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = green)
                    TextButton(onClick = { refresh(true) }) { Text(stringResource(R.string.inv_refresh), color = green, fontSize = 12.sp) }
                }
                instances.forEach { inst ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        val badge = when (inst.type) { "onion" -> "🧅"; "i2p" -> "📨"; "ygg" -> "🕸️"; else -> "🌐" }
                        Text(badge, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                        val h = inst.health
                        val healthText = if (h != null) stringResource(R.string.inv_health_fmt, h.toInt()) else stringResource(R.string.inv_health_unknown)
                        val anonText = if (inst.isAnon) stringResource(R.string.inv_anon_suffix) else ""
                        Column(Modifier.weight(1f)) {
                            Text(inst.baseUrl.removePrefix("https://"), color = Color.White, fontSize = 12.sp, maxLines = 1)
                            Text(healthText + anonText, color = Color.Gray, fontSize = 10.sp)
                        }
                        Text(when { pings.containsKey(inst.baseUrl) && pings[inst.baseUrl] == null -> "⏳"; pings[inst.baseUrl] == true -> "✅"; pings[inst.baseUrl] == false -> "❌"; else -> "" }, fontSize = 12.sp)
                        TextButton(onClick = { scope.launch { pings = pings + (inst.baseUrl to null); val ok = com.varuna.rustify.audio.InvidiousInstances.probe(context, inst); pings = pings + (inst.baseUrl to ok) } }) {
                            Text(stringResource(R.string.inv_test), color = green, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text(stringResource(R.string.inv_add_custom_label)) }, singleLine = true, modifier = Modifier.weight(1f), colors = backendFieldColors())
                TextButton(onClick = { if (custom.isNotBlank()) { inv.addCustomInstance(context, custom); customList = inv.customInstances(context); custom = "" } }) { Text(stringResource(R.string.inv_add), color = green) }
            }
            customList.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { inv.removeCustomInstance(context, c); customList = inv.customInstances(context) }) { Text(stringResource(R.string.inv_remove), color = Color(0xFFCC3333), fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.inv_allow_anon), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.inv_allow_anon_desc), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(checked = anonOn, onCheckedChange = { anonOn = it; inv.setAllowAnonNetworks(context, it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.inv_tor_route), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = torOn, onCheckedChange = { torOn = it; inv.setTorEnabled(context, it) })
            }

            // Test playback of the current track via Invidious (resolves like the real backend).
            Spacer(Modifier.height(12.dp))
            val invNoTrack = stringResource(R.string.dz_tp_no_track)
            val invOkFmt = stringResource(R.string.dz_tp_ok_fmt)
            val invFailFmt = stringResource(R.string.dz_tp_fail_fmt)
            TextButton(onClick = {
                invTpBusy = true; invTpStatus = ""
                scope.launch(Dispatchers.IO) {
                    val track = com.varuna.rustify.player.AudioPlayerService.instance?.state?.value?.currentTrack
                    if (track == null) { withContext(Dispatchers.Main) { invTpStatus = invNoTrack; invTpBusy = false }; return@launch }
                    val r = com.varuna.rustify.audio.InvidiousAudioSource(context).resolveStreamUrl(track, null)
                    withContext(Dispatchers.Main) {
                        invTpStatus = r.fold(onSuccess = { invOkFmt.format("stream ✓") }, onFailure = { invFailFmt.format(it.message ?: "error") })
                        invTpBusy = false
                    }
                }
            }, enabled = !invTpBusy) { Text(stringResource(R.string.dz_test_playback), color = green) }
            if (invTpBusy) { Spacer(Modifier.height(6.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green) }
            if (invTpStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(invTpStatus, color = Color.White, fontSize = 12.sp) }
        }
    }
}

// Deezer backend configuration (own ARL or ARL source) plus ARL and playback testers.
@Composable
internal fun DeezerBackendSection(context: Context) {
    val green = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()
    val dz = com.varuna.rustify.audio.DeezerSettings
    var arlMode by remember { mutableStateOf(dz.arlMode(context)) }
    var arl by remember { mutableStateOf(dz.arl(context)) }
    var source by remember { mutableStateOf(dz.sourceUrl(context)) }
    var quality by remember { mutableStateOf(dz.quality(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // ARL source tester: lists the ARLs from the web, tests each one (✅/❌), and lets the user pick which to use.
    var entries by remember { mutableStateOf<List<com.varuna.rustify.audio.DeezerArl.ArlEntry>>(emptyList()) }
    // arl -> ArlCheck (tested) or null (testing). Absent key = not yet tested.
    var entryStatus by remember { mutableStateOf(mapOf<String, com.varuna.rustify.audio.DeezerClient.ArlCheck?>()) }
    var fetchedAt by remember { mutableLongStateOf(0L) }
    var workingArl by remember { mutableStateOf(dz.workingArl(context)) }
    // End-to-end playback tester (auth + match + get_url) without changing the backend order.
    var tpStatus by remember { mutableStateOf("") }
    var tpBusy by remember { mutableStateOf(false) }

    val validMsg = stringResource(R.string.dz_arl_valid)
    val invalidMsg = stringResource(R.string.dz_arl_invalid)
    val noArlsMsg = stringResource(R.string.dz_no_arls)
    val tpNoTrack = stringResource(R.string.dz_tp_no_track)
    val tpOkFmt = stringResource(R.string.dz_tp_ok_fmt)
    val tpFailFmt = stringResource(R.string.dz_tp_fail_fmt)

    fun mask(a: String) = if (a.length > 12) a.take(6) + "…" + a.takeLast(4) else a

    // Auto-load: if a source URL is already saved and we are in "source" mode, list the ARLs when the
    // section opens, without requiring a button press.
    LaunchedEffect(arlMode) {
        if (arlMode == "source" && source.isNotBlank() && entries.isEmpty() && !busy) {
            busy = true; status = ""
            val res = com.varuna.rustify.audio.DeezerArl.fetchDetailed(context, source.trim())
            entries = res.entries; fetchedAt = System.currentTimeMillis(); busy = false
            status = if (res.entries.isEmpty()) (res.error?.let { "$noArlsMsg ($it)" } ?: noArlsMsg) else ""
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.backend_deezer), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dz_hint), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("single" to stringResource(R.string.dz_mode_single), "source" to stringResource(R.string.dz_mode_source)).forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { arlMode = code; dz.setArlMode(context, code) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = arlMode == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = green))
                        Text(label, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (arlMode == "single") {
                OutlinedTextField(value = arl, onValueChange = { arl = it; dz.setArl(context, it) }, label = { Text(stringResource(R.string.dz_arl_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
                TextButton(onClick = {
                    busy = true; status = ""
                    scope.launch(Dispatchers.IO) {
                        val ok = com.varuna.rustify.audio.DeezerClient().testArl(arl.trim())
                        if (ok) { dz.setWorkingArl(context, arl.trim()) }
                        withContext(Dispatchers.Main) { status = if (ok) validMsg else invalidMsg; busy = false; workingArl = dz.workingArl(context) }
                    }
                }, enabled = !busy) { Text(stringResource(R.string.dz_test_arl), color = green) }
            } else {
                OutlinedTextField(value = source, onValueChange = { source = it; dz.setSourceUrl(context, it) }, label = { Text(stringResource(R.string.dz_source_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = backendFieldColors())
                Spacer(Modifier.height(8.dp))
                // A filled button so it clearly reads as a button and users recognize it as tappable.
                Button(
                    onClick = {
                        busy = true; status = ""; entryStatus = emptyMap()
                        scope.launch(Dispatchers.IO) {
                            val res = com.varuna.rustify.audio.DeezerArl.fetchDetailed(context, source.trim())
                            withContext(Dispatchers.Main) {
                                entries = res.entries; fetchedAt = System.currentTimeMillis(); busy = false
                                // Shows the actual failure reason (HTTP 403 / timeout / 0 ARLs, etc.)
                                // instead of a generic, uninformative "no ARLs" message.
                                status = if (res.entries.isEmpty()) (res.error?.let { "$noArlsMsg ($it)" } ?: noArlsMsg) else ""
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dz_fetch_arls), fontWeight = FontWeight.SemiBold) }
                Text(stringResource(R.string.dz_source_hint), color = Color.Gray, fontSize = 11.sp)

                if (entries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.dz_found_fmt, entries.size), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (fetchedAt > 0L) {
                        val rel = android.text.format.DateUtils.getRelativeTimeSpanString(fetchedAt).toString()
                        Text(stringResource(R.string.dz_fetched_fmt, rel), color = Color.Gray, fontSize = 10.sp)
                    }
                    // Legend: ✅ plays (premium) · ⚠️ authenticates but does not play (free account) · ❌ invalid.
                    Text("✅ premium · ⚠️ free (no stream) · ❌ inválido", color = Color(0xFF888888), fontSize = 10.sp)
                    entries.take(30).forEach { e ->
                        val tested = entryStatus.containsKey(e.arl)
                        val chk = entryStatus[e.arl]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((e.country.ifBlank { "—" }) + "  " + mask(e.arl), color = Color.White, fontSize = 11.sp, maxLines = 1)
                                if (e.updated.isNotBlank()) Text(stringResource(R.string.dz_updated_fmt, e.updated), color = Color.Gray, fontSize = 9.sp)
                                if (chk != null) Text(chk.detail, color = if (chk.canStream) green else Color(0xFFFFB74D), fontSize = 9.sp, maxLines = 1)
                            }
                            Text(
                                when { tested && chk == null -> "⏳"; chk == null -> ""; chk.canStream -> "✅"; chk.auth -> "⚠️"; else -> "❌" },
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TextButton(onClick = {
                                entryStatus = entryStatus + (e.arl to null)
                                scope.launch(Dispatchers.IO) {
                                    // checkArl: auth + get_url on a canary track to distinguish premium/free/invalid.
                                    val res = com.varuna.rustify.audio.DeezerClient().checkArl(e.arl)
                                    withContext(Dispatchers.Main) {
                                        entryStatus = entryStatus + (e.arl to res)
                                        if (res.canStream) { dz.setWorkingArl(context, e.arl); workingArl = e.arl }
                                    }
                                }
                            }) { Text(stringResource(R.string.dz_test), color = green, fontSize = 11.sp) }
                            if (workingArl == e.arl) {
                                Text(stringResource(R.string.dz_in_use), color = green, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                            } else {
                                TextButton(onClick = { dz.setWorkingArl(context, e.arl); workingArl = e.arl }) { Text(stringResource(R.string.dz_use), color = Color.White, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dz_quality), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth()) {
                listOf("flac" to "FLAC", "mp3_320" to "MP3 320", "mp3_128" to "MP3 128").forEach { (code, label) ->
                    Row(Modifier.weight(1f).clickable { quality = code; dz.setQuality(context, code) }, verticalAlignment = Alignment.CenterVertically) {
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
                scope.launch(Dispatchers.IO) {
                    val track = com.varuna.rustify.player.AudioPlayerService.instance?.state?.value?.currentTrack
                    if (track == null) {
                        withContext(Dispatchers.Main) { tpStatus = tpNoTrack; tpBusy = false }
                        return@launch
                    }
                    val result = runCatching {
                        val a = com.varuna.rustify.audio.DeezerArl.ensureArl(context) ?: error("no working ARL (testea un ARL primero)")
                        val client = com.varuna.rustify.audio.DeezerClient()
                        // Step by step to report what fails: auth vs not-found vs no stream rights.
                        val session = client.auth(a) ?: error("ARL auth failed (inválido/caducado)")
                        val id = client.deezerTrackId(track) ?: error("track not found on Deezer (ISRC/búsqueda)")
                        val media = client.media(session, id, dz.formatChain(context))
                            ?: error("get_url vacío (ARL sin premium/HiFi o región) — prueba un ARL con ✅")
                        media.format
                    }
                    withContext(Dispatchers.Main) {
                        tpStatus = result.fold(
                            onSuccess = { tpOkFmt.format(it) },
                            onFailure = { tpFailFmt.format(it.message ?: "error") }
                        )
                        tpBusy = false
                    }
                }
            }, enabled = !tpBusy) { Text(stringResource(R.string.dz_test_playback), color = green) }
            Text(stringResource(R.string.dz_test_playback_hint), color = Color.Gray, fontSize = 11.sp)
            if (tpBusy) { Spacer(Modifier.height(6.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green) }
            if (tpStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(tpStatus, color = Color.White, fontSize = 12.sp) }

            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dz_footer), color = Color(0xFF888888), fontSize = 11.sp)
        }
    }
}

@Composable
internal fun AudioBackendsSection(context: Context) {
    // Bumped by [AddonsSection] whenever the installed add-ons change. Without it these three
    // `remember`s would be computed once and an add-on installed a moment later would be missing
    // from the order lists until the screen was left and reopened — which reads as "installing did
    // nothing".
    var backendsRevision by remember { mutableIntStateOf(0) }

    val knownIds = remember(backendsRevision) { com.varuna.rustify.audio.AudioSourceRegistry.knownIds() }
    val catalog = remember(backendsRevision) { com.varuna.rustify.audio.AudioSourceRegistry.catalog().associateBy { it.id } }
    var streamOrder by remember(backendsRevision) { mutableStateOf(com.varuna.rustify.audio.AudioBackendSettings.loadOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_STREAM, knownIds)) }
    var downloadOrder by remember(backendsRevision) { mutableStateOf(com.varuna.rustify.audio.AudioBackendSettings.loadOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_DOWNLOAD, knownIds)) }

    Spacer(modifier = Modifier.height(24.dp))
    Text(stringResource(R.string.settings_audio_backends), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_backends_stream_order), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ReorderableBackendList(streamOrder, catalog) { newOrder -> streamOrder = newOrder; com.varuna.rustify.audio.AudioBackendSettings.saveOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_STREAM, newOrder) }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_backends_download_order), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ReorderableBackendList(downloadOrder, catalog) { newOrder -> downloadOrder = newOrder; com.varuna.rustify.audio.AudioBackendSettings.saveOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_DOWNLOAD, newOrder) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_backends_drag_hint), color = Color.Gray, fontSize = 12.sp)
        }
    }

    AddonsSection(context, onAddonsChanged = { backendsRevision++ })
    AutoplayRadioSection(context)
    StreamCacheSection(context)
    DataExportSection(context)
    CastingSection(context)
}

