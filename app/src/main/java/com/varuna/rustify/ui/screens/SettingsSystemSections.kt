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

// SettingsSystemSections.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Secciones de sistema: bateria, reproductor web, actualizaciones, musica local, carpeta de descargas y registro.
//
// SettingsScreen.kt eran 3 796 lineas, de las que 1 875 estaban dentro de una sola funcion. Este
// corte se lleva los ayudantes de nivel superior, que son la mitad mecanica: cada uno es una
// seccion independiente de la pantalla y ninguno comparte estado con los demas. Lo que queda en
// SettingsScreen.kt es el composable grande, que es otra conversacion.
//
// Las funciones movidas pasaron de private a internal: en Kotlin un private de nivel superior
// es privado *del fichero*, asi que moverlas sin mas las habria hecho invisibles para la pantalla
// que las llama.

// Doze exemption. Not a preference — a system state the user has to grant, shown here because when
// it is missing the symptom looks like an app bug: playback stops between songs with the screen off.
// Re-checked on every visit and after the prompt, since reinstalling the app clears it.
@Composable
internal fun BatteryOptimizationSection(context: Context) {
    val green = Color(0xFF1DB954)
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { exempt = BatteryOptimization.isExempt(context) }

    // The prompt does not report the outcome, and the user may also change it from system settings.
    LifecycleResumeEffect(Unit) {
        exempt = BatteryOptimization.isExempt(context)
        onPauseOrDispose { }
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.settings_battery_title),
        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (exempt) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (exempt) green else Color(0xFFE57373),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (exempt) R.string.settings_battery_exempt
                        else R.string.settings_battery_restricted
                    ),
                    color = Color.White, fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_battery_desc),
                color = Color.Gray, fontSize = 12.sp
            )
            if (!exempt) {
                // On these makes the status above can stay red even when the user has already set the
                // manufacturer's own control correctly, because that control is a different switch
                // from Android's and only Android's is readable. Say so instead of looking broken.
                if (BatteryOptimization.hasVendorBatteryLayer()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_battery_vendor_hint),
                        color = Color(0xFFB0A070), fontSize = 12.sp
                    )
                }
                TextButton(onClick = {
                    // First one that opens wins: resolveActivity lies often enough here that trying
                    // is the only reliable check.
                    BatteryOptimization.requestIntents(context).firstOrNull { intent ->
                        runCatching { launcher.launch(intent) }.isSuccess
                    }
                }) { Text(stringResource(R.string.settings_battery_allow), color = green) }
            }
        }
    }
}

// Playback engine (experimental): prefer Spotify's own web player over Rustify's engine.
//
// It sits with the audio backends because that is what the user is choosing between, but it is NOT
// an entry in the provider chain: every backend there answers "give me a URL ExoPlayer can play",
// and the web player never yields one — the audio is produced, DRM-protected, inside the page. So
// it is a separate switch, with per-track fallback to the chain when the page cannot serve a track.
@Composable
internal fun WebPlayerSection(context: Context) {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)
    var enabled by remember { mutableStateOf(prefs.getBoolean("web_player_backend", false)) }
    var desktopPlayback by remember {
        mutableStateOf(com.varuna.rustify.webplayer.WebPlayerController.playbackUsesDesktop(context))
    }
    var adblock by remember {
        mutableStateOf(com.varuna.rustify.webplayer.WebPlayerController.adblockEnabled(context))
    }
    var testing by remember { mutableStateOf(false) }
    var steps by remember {
        mutableStateOf<List<com.varuna.rustify.webplayer.WebPlayerDiagnostics.Step>>(emptyList())
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.settings_playback_engine),
        color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingSwitchRow(
                title = stringResource(R.string.web_player_backend),
                desc = stringResource(R.string.web_player_backend_desc),
                checked = enabled,
                onChange = {
                    enabled = it
                    prefs.edit { putBoolean("web_player_backend", it) }
                    com.varuna.rustify.player.AudioPlayerService.instance?.setWebPlayerMode(it)
                }
            )

            Spacer(Modifier.height(8.dp))
            SettingSwitchRow(
                title = stringResource(R.string.web_player_desktop_playback),
                desc = stringResource(R.string.web_player_desktop_playback_desc),
                checked = desktopPlayback,
                onChange = {
                    desktopPlayback = it
                    com.varuna.rustify.webplayer.WebPlayerController.setPlaybackUsesDesktop(context, it)
                }
            )

            Spacer(Modifier.height(8.dp))
            SettingSwitchRow(
                title = stringResource(R.string.web_player_adblock),
                desc = stringResource(R.string.web_player_adblock_desc),
                checked = adblock,
                onChange = {
                    adblock = it
                    com.varuna.rustify.webplayer.WebPlayerController.setAdblockEnabled(context, it)
                }
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    testing = true
                    steps = emptyList()
                    scope.launch {
                        val trackId = com.varuna.rustify.player.AudioPlayerService.instance
                            ?.state?.value?.currentTrack?.id
                        steps = runCatching {
                            com.varuna.rustify.webplayer.WebPlayerDiagnostics.run(context, trackId)
                        }.getOrDefault(emptyList())
                        testing = false
                    }
                },
                enabled = !testing
            ) { Text(stringResource(R.string.wp_test_run), color = green) }
            Text(stringResource(R.string.wp_test_hint), color = Color.Gray, fontSize = 11.sp)

            if (testing) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green)
            }
            steps.forEach { step ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (step.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (step.ok) green else Color(0xFFE57373),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(step.labelRes), color = Color.White, fontSize = 13.sp)
                        if (step.detail.isNotBlank()) {
                            Text(step.detail, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// Updates: "check on startup" toggle plus a button to check now (GitHub releases).
@Composable
internal fun UpdatesSection(context: Context) {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var checkOnStart by remember { mutableStateOf(prefs.getBoolean("check_updates_on_start", true)) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<com.varuna.rustify.update.AppUpdate.UpdateInfo?>(null) }

    update?.let { info ->
        com.varuna.rustify.update.UpdateAvailableDialog(info = info, onDismiss = { update = null })
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.update_check),
        color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    SettingSwitchRow(
        title = stringResource(R.string.update_check_on_start),
        desc = stringResource(R.string.update_check_on_start_desc),
        checked = checkOnStart,
        onChange = {
            checkOnStart = it
            prefs.edit { putBoolean("check_updates_on_start", it) }
        }
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            if (checking) return@Button
            checking = true
            scope.launch {
                val res = runCatching { com.varuna.rustify.update.AppUpdate.check(context) }
                checking = false
                res.onSuccess { info ->
                    if (info != null) update = info
                    else Toast.makeText(context, context.getString(R.string.update_up_to_date), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                }
            }
        },
        enabled = !checking,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
        shape = RoundedCornerShape(10.dp)
    ) {
        if (checking) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.update_checking), color = Color.Black, fontWeight = FontWeight.Bold)
        } else {
            Text(stringResource(R.string.update_check), color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// Local music (moved to "Audio and backends"): enable, match local first, high-res cover art,
// YouTube Music, use scraper, and added folders. Self-contained (its own state and picker).
@Composable
internal fun LocalMusicSection(context: Context) {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    val green = Color(0xFF1DB954)
    var enableLocalMusic by remember { mutableStateOf(prefs.getBoolean("enable_local_music", true)) }
    var matchLocalFirst by remember { mutableStateOf(prefs.getBoolean("settings_match_local_first", false)) }
    var coversFullRes by remember { mutableStateOf(prefs.getBoolean("settings_local_covers_full_res", true)) }
    var enableYtmMusic by remember { mutableStateOf(prefs.getBoolean("enable_ytm_music", true)) }
    var ytmScraper by remember { mutableStateOf(prefs.getString("ytm_search_mode", "api") == "scraper") }
    var localMusicDirs by remember { mutableStateOf(prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()) }
    val addDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val newSet = localMusicDirs.toMutableSet().apply { add(uri.toString()) }
            localMusicDirs = newSet
            prefs.edit { putStringSet("local_music_directories", newSet) }
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.settings_local_music), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingSwitchRow(stringResource(R.string.settings_enable_local_music), stringResource(R.string.settings_enable_local_music_desc), enableLocalMusic) {
                enableLocalMusic = it; prefs.edit { putBoolean("enable_local_music", it) }
            }
            if (enableLocalMusic) {
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(R.string.settings_match_local_first), stringResource(R.string.settings_match_local_first_desc), matchLocalFirst) {
                    matchLocalFirst = it; prefs.edit { putBoolean("settings_match_local_first", it) }
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(R.string.settings_local_covers_fullres), stringResource(R.string.settings_local_covers_fullres_desc), coversFullRes) {
                    coversFullRes = it; prefs.edit { putBoolean("settings_local_covers_full_res", it) }
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(R.string.enable_ytm_music), stringResource(R.string.enable_ytm_music_desc), enableYtmMusic) {
                    enableYtmMusic = it; prefs.edit { putBoolean("enable_ytm_music", it) }
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(
                    stringResource(R.string.settings_ytm_scraper),
                    if (ytmScraper) stringResource(R.string.settings_ytm_scraper_on) else stringResource(R.string.settings_ytm_scraper_off),
                    ytmScraper
                ) {
                    ytmScraper = it; prefs.edit { putString("ytm_search_mode", if (it) "scraper" else "api") }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_added_folders), color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                if (localMusicDirs.isEmpty()) {
                    Text(stringResource(R.string.settings_no_folder_configured), color = Color.Gray, fontSize = 14.sp)
                } else {
                    localMusicDirs.forEach { uriStr ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val name = Uri.parse(uriStr).lastPathSegment ?: uriStr
                            Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.settings_remove), color = Color.Red, fontSize = 12.sp, modifier = Modifier.clickable {
                                val newSet = localMusicDirs.toMutableSet().apply { remove(uriStr) }
                                localMusicDirs = newSet
                                prefs.edit { putStringSet("local_music_directories", newSet) }
                            }.padding(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { addDirLauncher.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                    Text(stringResource(R.string.settings_add_folder), color = Color.White)
                }
            }
        }
    }
}

// Standard downloads folder (moved to the Downloads category, alongside the custom one).
@Composable
internal fun DownloadFolderSection(context: Context) {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    val green = Color(0xFF1DB954)
    var downloadUriString by remember { mutableStateOf(prefs.getString("download_directory", null)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            downloadUriString = uri.toString()
            prefs.edit { putString("download_directory", uri.toString()) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_download_dir), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (downloadUriString != null) Uri.parse(downloadUriString).lastPathSegment ?: downloadUriString!!
                else stringResource(R.string.settings_no_dir_configured),
                color = Color.Gray, fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { picker.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                Text(stringResource(R.string.settings_select_folder), color = Color.White)
            }
        }
    }
}

// Diagnostics (moved to Advanced): enable log capture plus open the viewer.
@Composable
internal fun LoggingSection(context: Context, onViewLogs: () -> Unit) {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    var loggingEnabled by remember { mutableStateOf(prefs.getBoolean("logging_capture_enabled", false)) }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_diagnostics), color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingSwitchRow(stringResource(R.string.settings_capture_logs), stringResource(R.string.settings_capture_logs_desc), loggingEnabled) {
                loggingEnabled = it; prefs.edit { putBoolean("logging_capture_enabled", it) }
                if (it) LogCapture.start() else LogCapture.stop()
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onViewLogs, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_view_logs), color = Color.White)
            }
        }
    }
}

