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
                    }
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
 * Export and restore everything the engine keeps — point L.
 *
 * Next to the stream cache because both answer "what is this app holding, and can I get it back".
 * The Drive backup that already exists is a *restore* mechanism — it answers "my phone died". This
 * answers "I want to read what this knows about me" and "I am leaving", which a cloud backup does
 * not.
 *
 * **No credential is ever in the file.** Not the Spotify session, not the ARL. An export is a thing
 * that gets mailed to a laptop or handed to someone for help, and the one thing it must never be is
 * a way to hand over an account. That is enforced in the core, with a test.
 */
@Composable
internal fun DataExportSection(context: Context) {
    val scope = rememberCoroutineScope()
    val okMsg = stringResource(R.string.settings_data_export_ok)
    val failMsg = stringResource(R.string.settings_data_export_failed)
    val restoredMsg = stringResource(R.string.settings_data_restore_ok)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val json = com.varuna.rustify.bridge.NativeEngine.exportData("Rustify")
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("could not open the file for writing")
                okMsg
            }.getOrElse { failMsg }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: error("could not read the file")
                val answer = org.json.JSONObject(
                    com.varuna.rustify.bridge.NativeEngine.restoreData(text)
                )
                // The core's own message when it refuses — an export from a newer version, or a file
                // that is not one at all. Shown rather than flattened into "failed", because those
                // two need different things from the user.
                if (answer.optBoolean("success", false)) restoredMsg
                else answer.optString("error").ifBlank { failMsg }
            }.getOrElse { failMsg }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.settings_data_export),
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
                stringResource(R.string.settings_data_export_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { exportLauncher.launch("rustify-data.json") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_data_export_button), color = Color.Black)
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_data_restore_button), color = Color.White)
                }
            }
        }
    }
}

/**
 * Whether a queue that runs out turns into a radio.
 *
 * On by default, so upgrading changes nobody's playback. It is here rather than buried in the player
 * because it is a preference about what the app does when you are not looking, and those belong
 * somewhere you can find them again.
 */
@Composable
internal fun AutoplayRadioSection(context: Context) {
    var enabled by remember {
        mutableStateOf(
            context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                .getBoolean(com.varuna.rustify.player.AudioPlayerService.AUTOPLAY_RADIO_KEY, true)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.settings_autoplay_radio),
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
                    stringResource(R.string.settings_autoplay_radio_enabled),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(
                                com.varuna.rustify.player.AudioPlayerService.AUTOPLAY_RADIO_KEY,
                                on
                            )
                            .apply()
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_autoplay_radio_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
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
@Composable
internal fun CastingSection(context: Context) {
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(com.varuna.rustify.cast.CastSession.isEnabled(context))
    }
    var searching by remember { mutableStateOf(false) }
    var devices by remember {
        mutableStateOf<List<com.varuna.rustify.cast.CastDiscovery.Device>>(emptyList())
    }
    var active by remember { mutableStateOf(com.varuna.rustify.cast.CastSession.device) }
    /** Why the last attempt did not start. Shown as-is: every reason is something the user can act on. */
    var failure by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val noneFound = stringResource(R.string.settings_casting_none)
    val player = remember { com.varuna.rustify.player.AudioPlayerService.getInstance(context) }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.settings_casting),
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
                    stringResource(R.string.settings_casting_enabled),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        com.varuna.rustify.cast.CastSession.setEnabled(context, on)
                        if (!on) {
                            devices = emptyList()
                            active = null
                            failure = null
                        }
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_casting_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )

            if (enabled) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        active?.let { stringResource(R.string.settings_casting_to, it.friendlyName) }
                            ?: stringResource(R.string.settings_casting_idle),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (active != null) {
                        TextButton(onClick = {
                            // Through the player, not straight to the session: it is what owns the
                            // poll that mirrors the device's clock, and stopping the session without
                            // it would leave that loop asking a device nobody is listening to.
                            player.stopCasting()
                            active = null
                            failure = null
                        }) {
                            Text(
                                stringResource(R.string.settings_casting_stop),
                                color = Color(0xFF1DB954)
                            )
                        }
                    } else {
                        TextButton(
                            enabled = !searching,
                            onClick = {
                                scope.launch {
                                    searching = true
                                    devices = com.varuna.rustify.cast.CastDiscovery.search(context)
                                    searching = false
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (searching) R.string.settings_casting_searching
                                    else R.string.settings_casting_search
                                ),
                                color = Color(0xFF1DB954)
                            )
                        }
                    }
                }

                if (active == null && !searching && devices.isEmpty()) {
                    Text(noneFound, color = Color.Gray, fontSize = 12.sp)
                }
                failure?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE57373), fontSize = 12.sp)
                }
                for (found in devices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !starting) {
                                // Picking a device *casts*. Selecting one and calling that casting
                                // is how this section spent a release looking finished: the list
                                // worked, the switch worked, and nothing was ever sent.
                                failure = null
                                starting = true
                                scope.launch {
                                    val why = player.startCastingTo(found)
                                    failure = why
                                    active = if (why == null) found else null
                                    starting = false
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(found.friendlyName, color = Color.White, fontSize = 14.sp)
                            // The address is shown rather than hidden: it is what the server will be
                            // told to answer, and the user should be able to see which box that is.
                            Text(found.address, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Bytes as something a person reads, without pulling in a formatting library for four branches. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

