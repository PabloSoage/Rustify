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

// SettingsStorageSections.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Lo que la app guarda en el dispositivo: la cache de canciones y el export/import de tus datos.
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
 * The stream cache: songs kept on the device after they have been played once.
 *
 * Placed with the backends because it is what makes them cheap — a track already here needs no
 * backend at all. The size and the clear button are here because a cache the user cannot see or
 * empty is just disk that went missing.
 */
@Composable
internal fun StreamCacheSection(context: Context) {
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(com.varuna.rustify.audio.StreamRouting.isEnabled(context))
    }
    var bytes by remember { mutableStateOf(0L) }

    suspend fun refreshSize() {
        bytes = com.varuna.rustify.audio.StreamRouting.size(context)
    }

    LaunchedEffect(Unit) { refreshSize() }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.settings_stream_cache),
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_stream_cache_enabled),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        com.varuna.rustify.audio.StreamRouting.setEnabled(context, on)
                    },
                    colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_stream_cache_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_stream_cache_using, formatBytes(bytes)),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        com.varuna.rustify.audio.StreamRouting.clear(context)
                        refreshSize()
                    }
                }) {
                    Text(stringResource(R.string.settings_stream_cache_clear), color = Color(0xFF1DB954))
                }
            }
        }
    }
}

/**
 * Casting to something else on the network — E16.
 *
 * Here, and off by default, because turning it on is the moment this app stops being a thing that
 * only talks to itself. The description says what it opens and for how long, in words, because a
 * switch that opens a port on your network and does not say so is not consent.
 *
 * Discovery deliberately opens nothing: it is a question sent to the network and answers coming
 * back. The port only exists once you pick a device and press play on it.
 */
/** Bytes as something a person reads, without pulling in a formatting library for four branches. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}


/** Audio offload to the phone's DSP (4.7.1). See [com.varuna.rustify.player.AudioOffload]. */
@Composable
internal fun AudioOffloadSection(context: Context) {
    var enabled by remember { mutableStateOf(com.varuna.rustify.player.AudioOffload.isEnabled(context)) }
    val blocker = com.varuna.rustify.player.AudioOffload.blocker(
        enabled,
        com.varuna.rustify.player.EqualizerController.isEnabled(context),
        com.varuna.rustify.player.TransitionFade.seconds(context),
        speedChanged = !com.varuna.rustify.player.PlaybackSpeed.isDefault(com.varuna.rustify.ui.AndroidAppPlatform(context).prefs()),
        skipSilence = com.varuna.rustify.player.SkipSilence.enabled(context),
        autoEq = com.varuna.rustify.player.AutoEqController.isActive(),
        bitPerfect = com.varuna.rustify.player.BitPerfect.active(context),
        stereo = com.varuna.rustify.player.StereoTools.load(context).active
    )
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_offload),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        com.varuna.rustify.player.AudioOffload.setEnabled(context, on)
                        com.varuna.rustify.player.AudioPlayerService.instance?.applyAudioOffload()
                    },
                    colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                )
            }
            Text(stringResource(R.string.settings_offload_desc), color = Color.Gray, fontSize = 12.sp)
            when (blocker) {
                com.varuna.rustify.player.AudioOffload.Blocker.EQUALIZER ->
                    Text(stringResource(R.string.settings_offload_blocked_eq), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.FADE ->
                    Text(stringResource(R.string.settings_offload_blocked_fade), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.SPEED ->
                    Text(stringResource(R.string.settings_offload_blocked_speed), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.SKIP_SILENCE ->
                    Text(stringResource(R.string.settings_offload_blocked_silence), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.AUTOEQ ->
                    Text(stringResource(R.string.settings_offload_blocked_autoeq), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.BIT_PERFECT ->
                    Text(stringResource(R.string.settings_offload_blocked_bitperfect), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.STEREO ->
                    Text(stringResource(R.string.settings_offload_blocked_stereo), color = Color(0xFFFFB74D), fontSize = 12.sp)
                com.varuna.rustify.player.AudioOffload.Blocker.POSITION_DRIFT ->
                    Text(stringResource(R.string.settings_offload_blocked_drift), color = Color(0xFFFFB74D), fontSize = 12.sp)
                else -> {}
            }
        }
    }
}

/** Headphones or Bluetooth disconnecting (4.9) — see [com.varuna.rustify.player.DisconnectResume]. */
@Composable
internal fun DisconnectSection(context: Context) {
    val dr = com.varuna.rustify.player.DisconnectResume
    var window by remember { mutableIntStateOf(dr.windowSeconds(context)) }
    var ignorePlay by remember { mutableStateOf(dr.ignoreConnectPlay(context)) }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_disconnect), color = Color.White, fontSize = 15.sp)
            Text(stringResource(R.string.settings_disconnect_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dr.WINDOW_CHOICES.forEach { s ->
                    androidx.compose.material3.FilterChip(
                        selected = window == s,
                        onClick = { window = s; dr.setWindowSeconds(context, s) },
                        label = {
                            Text(
                                when {
                                    s == 0 -> stringResource(R.string.settings_disconnect_never)
                                    s < 60 -> stringResource(R.string.settings_disconnect_seconds, s)
                                    else -> stringResource(R.string.settings_disconnect_minutes, s / 60)
                                },
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_ignore_connect_play),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = ignorePlay,
                    onCheckedChange = { on -> ignorePlay = on; dr.setIgnoreConnectPlay(context, on) },
                    colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                )
            }
            Text(stringResource(R.string.settings_ignore_connect_play_desc), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/** The headset's button (4.9) — see [com.varuna.rustify.player.HeadsetButton]. */
@Composable
internal fun HeadsetButtonSection(context: Context) {
    val hb = com.varuna.rustify.player.HeadsetButton
    var double by remember { mutableStateOf(hb.double(context)) }
    var triple by remember { mutableStateOf(hb.triple(context)) }
    @Composable
    fun label(a: com.varuna.rustify.player.HeadsetButton.Action) = stringResource(
        when (a) {
            com.varuna.rustify.player.HeadsetButton.Action.NEXT -> R.string.settings_headset_next
            com.varuna.rustify.player.HeadsetButton.Action.PREVIOUS -> R.string.settings_headset_previous
            else -> R.string.settings_headset_nothing
        }
    )
    @Composable
    fun choices(
        title: String,
        selected: com.varuna.rustify.player.HeadsetButton.Action,
        onPick: (com.varuna.rustify.player.HeadsetButton.Action) -> Unit
    ) {
        Text(title, color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hb.CHOICES.forEach { a ->
                androidx.compose.material3.FilterChip(
                    selected = selected == a,
                    onClick = { onPick(a) },
                    label = { Text(label(a), fontSize = 12.sp) }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_headset), color = Color.White, fontSize = 15.sp)
            Text(stringResource(R.string.settings_headset_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            choices(stringResource(R.string.settings_headset_double), double) { double = it; hb.setDouble(context, it) }
            choices(stringResource(R.string.settings_headset_triple), triple) { triple = it; hb.setTriple(context, it) }
        }
    }
}

/** Bit-perfect USB output (`22`). See [com.varuna.rustify.player.BitPerfect]. */
@Composable
internal fun BitPerfectSection(context: Context) {
    val bp = com.varuna.rustify.player.BitPerfect
    var enabled by remember { mutableStateOf(bp.isEnabled(context)) }
    var status by remember { mutableStateOf(bp.status) }
    var usb by remember { mutableStateOf(bp.usbOutput(context)?.productName?.toString()) }
    // The status changes on the playback thread at each song; a light poll while the card is shown.
    LaunchedEffect(Unit) {
        while (true) {
            status = bp.status
            usb = bp.usbOutput(context)?.productName?.toString()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val spoilerName = when (status.spoiler) {
        com.varuna.rustify.player.BitPerfect.Spoiler.EQUALIZER -> stringResource(R.string.settings_bitperfect_spoiler_eq)
        com.varuna.rustify.player.BitPerfect.Spoiler.AUTOEQ -> stringResource(R.string.settings_bitperfect_spoiler_autoeq)
        com.varuna.rustify.player.BitPerfect.Spoiler.FADE -> stringResource(R.string.settings_bitperfect_spoiler_fade)
        com.varuna.rustify.player.BitPerfect.Spoiler.SPEED -> stringResource(R.string.settings_bitperfect_spoiler_speed)
        com.varuna.rustify.player.BitPerfect.Spoiler.SKIP_SILENCE -> stringResource(R.string.settings_bitperfect_spoiler_silence)
        com.varuna.rustify.player.BitPerfect.Spoiler.STEREO -> stringResource(R.string.settings_stereo)
        null -> ""
    }
    val pcm = status.pcm
    val format = if (pcm == null) "" else "${pcm.sampleRate} Hz, " +
        (if (pcm.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) "32-bit float" else "16-bit")
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_bitperfect), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    enabled = bp.supported,
                    onCheckedChange = { on ->
                        enabled = on
                        bp.setEnabled(context, on)
                        com.varuna.rustify.player.AudioPlayerService.instance?.applyAudioOffload()
                    },
                    colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                )
            }
            Text(stringResource(R.string.settings_bitperfect_desc), color = Color.Gray, fontSize = 12.sp)
            val warn = Color(0xFFFFB74D)
            val good = Color(0xFF1DB954)
            when {
                !bp.supported -> Text(stringResource(R.string.settings_bitperfect_old_android), color = warn, fontSize = 12.sp)
                !enabled -> {}
                usb == null -> Text(stringResource(R.string.settings_bitperfect_no_usb), color = warn, fontSize = 12.sp)
                status.state == com.varuna.rustify.player.BitPerfect.State.ACTIVE ->
                    Text(stringResource(R.string.settings_bitperfect_active, status.device ?: usb.orEmpty(), format), color = good, fontSize = 13.sp)
                status.state == com.varuna.rustify.player.BitPerfect.State.SPOILED ->
                    Text(stringResource(R.string.settings_bitperfect_spoiled, spoilerName), color = warn, fontSize = 12.sp)
                status.state == com.varuna.rustify.player.BitPerfect.State.NO_MATCH ->
                    Text(stringResource(R.string.settings_bitperfect_no_match, status.device ?: usb.orEmpty(), format), color = warn, fontSize = 12.sp)
                else -> Text(stringResource(R.string.settings_bitperfect_next_song), color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

/** Jam (`23` §4): the broker it goes through and the name others see. See [com.varuna.rustify.player.Jam]. */
@Composable
internal fun JamSection(context: Context) {
    val jam = com.varuna.rustify.player.Jam
    var broker by remember { mutableStateOf(jam.broker(context)) }
    var port by remember { mutableStateOf(jam.port(context).toString()) }
    var name by remember { mutableStateOf(jam.name(context)) }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.jam_title), color = Color.White, fontSize = 15.sp)
            Text(stringResource(R.string.settings_jam_desc), color = Color.Gray, fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it; jam.setName(context, it) }, singleLine = true,
                label = { Text(stringResource(R.string.settings_jam_name)) }, modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.OutlinedTextField(
                value = broker, onValueChange = { broker = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_jam_broker)) }, modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                label = { Text(stringResource(R.string.settings_jam_port)) }, modifier = Modifier.fillMaxWidth()
            )
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    jam.setBroker(context, broker, port.toIntOrNull() ?: jam.DEFAULT_PORT)
                }) { Text(stringResource(R.string.settings_jam_save), color = Color(0xFF1DB954)) }
                androidx.compose.material3.TextButton(onClick = {
                    broker = jam.DEFAULT_BROKER; port = jam.DEFAULT_PORT.toString()
                    jam.setBroker(context, broker, jam.DEFAULT_PORT)
                }) { Text(stringResource(R.string.settings_jam_default), color = Color.Gray) }
            }
        }
    }
}
