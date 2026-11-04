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
import androidx.compose.runtime.collectAsState
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
import com.varuna.rustify.ui.ShellHost
import com.varuna.rustify.ui.SettingsSlot
import com.varuna.rustify.Screen
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

/**
 * The Settings sections only Android has, at the shared screen's slots (docs serie-4.x/27, phase E):
 * what SettingsScreen drew inline before its frame, the menu and the portable sections (language,
 * Rustify link, session, version) moved to :sharedUi.
 */
@Composable
fun AndroidSettingsSections(slot: SettingsSlot, host: ShellHost) {
    val context = LocalContext.current
    when (slot) {
        SettingsSlot.GENERAL -> GeneralCategory(
            spotifyRepository = host.spotifyRepo,
            ytmRepository = host.ytmRepo,
            onNavigateMatchEditor = { host.navigate(Screen.MatchEditor) },
        )
        SettingsSlot.GENERAL_DJ -> DjSettingsSection()
        SettingsSlot.AUDIO -> {
            // First: without the Doze exemption nothing below matters, because playback stops
            // between songs with the screen off no matter which backend resolves the stream.
            BatteryOptimizationSection(context)
        }
        // After the lyrics providers, which the shared screen draws between the two.
        SettingsSlot.AUDIO_SOURCES -> {
            // Order: local music, then the audio backends.
            LocalMusicSection(context)
            AudioBackendsSection(context)
            // Right after the chain: it is the same decision ("what plays my music"), even
            // though it cannot live inside the chain itself.
            WebPlayerSection(context)
            InvidiousBackendSection(context)
            DeezerBackendSection(context)
            SubsonicBackendSection(context)
        }
        SettingsSlot.DOWNLOADS -> DownloadsCategory(context) { host.navigate(Screen.CustomDownload) }
        SettingsSlot.INTEGRATIONS -> {
            AndroidAutoPreviewSection(context)
            TravelMapSection(context)
        }
        SettingsSlot.ADVANCED -> {
            UpdatesSection(context)
            LoggingSection(context) { host.navigate(Screen.LogViewer) }
            SpotifyHashInspectorSection()
        }
    }
}

/** The update check in Settings' top bar (only in the root menu), with the dialog it opens. */
@Composable
fun AndroidSettingsActions() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // State for the update checker (refresh icon in the top bar).
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<com.varuna.rustify.update.AppUpdate.UpdateInfo?>(null) }
    val updateUpToDateMsg = stringResource(R.string.update_up_to_date)
    val updateCheckFailedMsg = stringResource(R.string.update_check_failed)

    manualUpdate?.let { info ->
        com.varuna.rustify.update.UpdateAvailableDialog(info = info, onDismiss = { manualUpdate = null })
    }
    if (isCheckingUpdate) {
        CircularProgressIndicator(
            color = Color(0xFF1DB954), strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp).padding(end = 4.dp)
        )
        Spacer(Modifier.width(8.dp))
    } else {
        IconButton(onClick = {
            isCheckingUpdate = true
            coroutineScope.launch {
                val res = runCatching { com.varuna.rustify.update.AppUpdate.check(context) }
                isCheckingUpdate = false
                res.onSuccess { info ->
                    if (info != null) manualUpdate = info
                    else Toast.makeText(context, updateUpToDateMsg, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, updateCheckFailedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.update_check), tint = Color.White)
        }
    }
}

/**
 * The "General" category's sections only Android has: the yt-dlp engine, storage and caches, data
 * import/export and Drive. It was the `else` branch of SettingsScreen, inline — 1,400 lines plus 280
 * of state declared at the top of a function that used none of it anywhere else (4.7). The language,
 * the Rustify link, the session and the version moved to :sharedUi (docs serie-4.x/27), and the DJ to
 * [DjSettingsSection], which the shared screen draws between them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralCategory(
    spotifyRepository: SpotifyRepository,
    ytmRepository: YtMusicRepository?,
    onNavigateMatchEditor: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var ytDlpVersion by remember { mutableStateOf(YoutubeDL.getInstance().version(context) ?: "Unknown") }
    var isNightly by remember { mutableStateOf(prefs.getString("ytdlp_channel", "NIGHTLY") == "NIGHTLY") }

    // The downloads folder, local music, and log capture moved to their own categories
    // (Downloads / Audio and backends / Advanced), each with its own state in the extracted section.




    val exportSuccessMsg = stringResource(R.string.settings_export_success)
    val exportNoDataMsg = stringResource(R.string.settings_no_mappings_export)

    var audioCacheSize by remember { mutableLongStateOf(0L) }
    var imageCacheSize by remember { mutableLongStateOf(0L) }

    fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    fun updateCacheSizes() {
        coroutineScope.launch(Dispatchers.IO) {
            val audioCache = File(context.cacheDir, "audio_cache")
            val imgCache = File(context.cacheDir, "image_cache")
            val aSize = if (audioCache.exists()) getDirSize(audioCache) else 0L
            val iSize = if (imgCache.exists()) getDirSize(imgCache) else 0L
            withContext(Dispatchers.Main) {
                audioCacheSize = aSize
                imageCacheSize = iSize
            }
        }
    }

    LaunchedEffect(Unit) {
        updateCacheSizes()
    }
    val exportErrorMsg = stringResource(R.string.settings_export_error)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val mappingsFile = File(context.filesDir, "youtube_mappings.json")
                    if (mappingsFile.exists()) {
                        context.contentResolver.openOutputStream(uri)?.use { outStream ->
                            mappingsFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, exportSuccessMsg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, exportNoDataMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, exportErrorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val importSuccessMsg = stringResource(R.string.settings_import_success)
    val importErrorMsg = stringResource(R.string.settings_import_error)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val mappingsFile = File(context.filesDir, "youtube_mappings.json")
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        mappingsFile.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, importSuccessMsg, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, importErrorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Export/import of local user data (versioned container).
    val localExportSuccessMsg = stringResource(R.string.settings_export_success)
    val localExportEmptyMsg = stringResource(R.string.settings_export_local_empty)
    val localExportErrorMsg = stringResource(R.string.settings_export_error)
    val localImportSuccessMsg = stringResource(R.string.settings_import_success)
    val localImportErrorMsg = stringResource(R.string.settings_import_error)
    val exportLocalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val playlistsFile = File(context.filesDir, "local_playlists.json")
                    val favoritesFile = File(context.filesDir, "local_favorites.json")
                    if (!playlistsFile.exists() && !favoritesFile.exists()) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, localExportEmptyMsg, Toast.LENGTH_SHORT).show() }; return@launch
                    }
                    val root = org.json.JSONObject().apply {
                        put("schema", "rustify-local-user-data"); put("version", 1); put("exportedAt", System.currentTimeMillis())
                        put("playlists", if (playlistsFile.exists()) org.json.JSONArray(playlistsFile.readText()) else org.json.JSONArray())
                        put("favorites", if (favoritesFile.exists()) org.json.JSONArray(favoritesFile.readText()) else org.json.JSONArray())
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString().toByteArray()) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, localExportSuccessMsg, Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { e.printStackTrace(); withContext(Dispatchers.Main) { Toast.makeText(context, localExportErrorMsg, Toast.LENGTH_SHORT).show() } }
            }
        }
    }
    val importLocalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { java.io.ByteArrayOutputStream().also { bos -> it.copyTo(bos) }.toString("UTF-8") } ?: ""
                    val root = org.json.JSONObject(raw)
                    if (root.optString("schema") != "rustify-local-user-data") { withContext(Dispatchers.Main) { Toast.makeText(context, localImportErrorMsg, Toast.LENGTH_SHORT).show() }; return@launch }
                    File(context.filesDir, "local_playlists.json").writeText((root.optJSONArray("playlists") ?: org.json.JSONArray()).toString())
                    File(context.filesDir, "local_favorites.json").writeText((root.optJSONArray("favorites") ?: org.json.JSONArray()).toString())
                    spotifyRepository.reloadLocalUserData()
                    withContext(Dispatchers.Main) { Toast.makeText(context, localImportSuccessMsg, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) { e.printStackTrace(); withContext(Dispatchers.Main) { Toast.makeText(context, localImportErrorMsg, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    // Google Drive sync.
    val drive = remember { GoogleDriveSync(context.applicationContext) }
    // AppAuth backend (browser/PKCE), coexisting with the Play Services backend.
    val appAuth = remember { com.varuna.rustify.sync.AppAuthDriveAuth(context.applicationContext) }
    var driveAuthMethod by remember { mutableStateOf(DriveSyncPrefs.authMethod(context)) }
    val syncManager = remember {
        DriveSyncManager(context.applicationContext, drive, spotifyRepository, ytmRepository)
    }
    // Web client id: if blank, the app is not configured (see GoogleDriveSync.kt).
    val webClientId = stringResource(R.string.default_web_client_id)
    var driveLinked by remember { mutableStateOf(DriveSyncPrefs.isLinked(context)) }
    var driveAutoSync by remember { mutableStateOf(DriveSyncPrefs.isAutoSync(context)) }
    var driveLastSync by remember { mutableLongStateOf(DriveSyncPrefs.lastSyncMs(context)) }
    var drivePulledFrom by remember { mutableStateOf(DriveSyncPrefs.pulledFrom(context)) }
    var driveSyncing by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf("") }

    val driveNeverSynced = stringResource(R.string.settings_drive_never_synced)
    val driveSyncOkMsg = stringResource(R.string.settings_drive_sync_ok)
    val driveSyncErrTmpl = stringResource(R.string.settings_drive_sync_error)
    val driveNotConfiguredMsg = stringResource(R.string.settings_drive_not_configured)

    // Runs a full sync with an already obtained access token.
    fun runDriveSync(token: String) {
        driveSyncing = true
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching { syncManager.syncNow(token) }
            withContext(Dispatchers.Main) {
                driveSyncing = false
                result.fold(
                    onSuccess = {
                        driveLastSync = DriveSyncPrefs.lastSyncMs(context)
                        drivePulledFrom = DriveSyncPrefs.pulledFrom(context)
                        driveStatus = driveSyncOkMsg
                        Toast.makeText(context, driveSyncOkMsg, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        val msg = String.format(driveSyncErrTmpl, e.message ?: e.javaClass.simpleName)
                        driveStatus = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // Launcher for the OAuth consent flow (IntentSender from AuthorizationClient).
    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        drive.handleAuthorizationResult(
            activityResult.data,
            onToken = { token ->
                DriveSyncPrefs.setLinked(context, true); driveLinked = true
                runDriveSync(token)
            },
            onError = { e ->
                driveStatus = String.format(driveSyncErrTmpl, e.message ?: "auth")
                Toast.makeText(context, driveStatus, Toast.LENGTH_LONG).show()
            }
        )
    }

    // Launcher for the AppAuth Custom Tab (Intent, not IntentSender). On return, it exchanges the
    // authorization code for tokens and syncs.
    val driveBrowserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        appAuth.handleResponse(
            activityResult.data,
            onToken = { token ->
                DriveSyncPrefs.setLinked(context, true); driveLinked = true
                runDriveSync(token)
            },
            onError = { e ->
                driveStatus = String.format(driveSyncErrTmpl, e.message ?: "auth")
                Toast.makeText(context, driveStatus, Toast.LENGTH_LONG).show()
            }
        )
    }

    // Requests a token (launching consent if needed), then runs [onToken].
    fun driveAuthorizeThen(onToken: (String) -> Unit) {
        if (driveAuthMethod == "browser") {
            // AppAuth: fetch a fresh token silently; if there is no authorization, open the browser.
            if (!appAuth.isConfigured()) {
                driveStatus = driveNotConfiguredMsg
                Toast.makeText(context, driveNotConfiguredMsg, Toast.LENGTH_LONG).show()
                return
            }
            val launchInteractive = {
                val intent = appAuth.authRequestIntent()
                if (intent != null) driveBrowserLauncher.launch(intent)
                else { driveStatus = driveNotConfiguredMsg; Toast.makeText(context, driveNotConfiguredMsg, Toast.LENGTH_LONG).show() }
            }
            appAuth.getFreshToken(
                onToken = onToken,
                onNone = { launchInteractive() },
                onError = { launchInteractive() }
            )
            return
        }
        // Play Services AuthorizationClient.
        if (webClientId.isBlank()) {
            driveStatus = driveNotConfiguredMsg
            Toast.makeText(context, driveNotConfiguredMsg, Toast.LENGTH_LONG).show()
            return
        }
        drive.authorize(
            onToken = onToken,
            onNeedConsent = { intentSender ->
                driveConsentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            },
            onError = { e ->
                driveStatus = String.format(driveSyncErrTmpl, e.message ?: "auth")
                Toast.makeText(context, driveStatus, Toast.LENGTH_LONG).show()
            }
        )
    }

    // Update dialog launched from the manual check (refresh icon).
    Column {
                val ytDlpUpdatedMsg = stringResource(R.string.settings_ytdlp_updated)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_extraction_engine),
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("yt-dlp", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.settings_ytdlp_current_version, ytDlpVersion), color = Color.Gray, fontSize = 14.sp)
                            }
                            IconButton(
                                onClick = {
                                    if (isUpdatingYtDlp) return@IconButton
                                    isUpdatingYtDlp = true
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                val channel = if (isNightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
                                                YoutubeDL.getInstance().updateYoutubeDL(context, channel)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                        ytDlpVersion = YoutubeDL.getInstance().version(context) ?: "Unknown"
                                        isUpdatingYtDlp = false
                                        Toast.makeText(context, ytDlpUpdatedMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                if (isUpdatingYtDlp) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color(0xFF1DB954),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_nightly_version), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.settings_nightly_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = isNightly,
                                onCheckedChange = { checked ->
                                    isNightly = checked
                                    prefs.edit { putString("ytdlp_channel", if (checked) "NIGHTLY" else "STABLE") }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }
                    
                        Spacer(modifier = Modifier.height(16.dp))

                        var highQualityVideo by remember { mutableStateOf(prefs.getBoolean("high_quality_video", true)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_max_video_quality), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.settings_max_video_quality_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = highQualityVideo,
                                onCheckedChange = { checked ->
                                    highQualityVideo = checked
                                    prefs.edit { putBoolean("high_quality_video", checked) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        var canvasFitWidth by remember { mutableStateOf(prefs.getBoolean("canvas_fit_width", true)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_canvas_fit_title), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.settings_canvas_fit_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = canvasFitWidth,
                                onCheckedChange = { checked ->
                                    canvasFitWidth = checked
                                    prefs.edit { putBoolean("canvas_fit_width", checked) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.settings_ytdlp_desc),
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }



                Text(
                    text = stringResource(R.string.settings_cache_storage),
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
                        // The downloads folder and local music settings moved to the "Downloads" and
                        // "Audio and backends" categories. Only the local data backup (playlists/favorites)
                        // and the cache cleanup remain here.
                        Text(
                            stringResource(R.string.settings_local_data),
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { exportLocalLauncher.launch("rustify_local_data.json") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.settings_export), color = Color.White, fontSize = 12.sp) }
                            Button(onClick = { importLocalLauncher.launch(arrayOf("application/json", "*/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.settings_import), color = Color.White, fontSize = 12.sp) }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_cleanup), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            val totalSizeMb = (audioCacheSize + imageCacheSize) / (1024 * 1024)
                            Text("$totalSizeMb MB", color = Color.Gray, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Audio: ${audioCacheSize / (1024 * 1024)} MB • Images: ${imageCacheSize / (1024 * 1024)} MB", color = Color.Gray, fontSize = 12.sp)

                        // Configurable maximum cache size (audio + images).
                        Spacer(modifier = Modifier.height(12.dp))
                        var cacheMaxMb by remember { mutableIntStateOf(prefs.getInt("cache_max_mb", 500)) }
                        Text(stringResource(R.string.settings_cache_max, cacheMaxMb), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Slider(
                            value = cacheMaxMb.toFloat(),
                            onValueChange = { cacheMaxMb = ((it / 50).toInt() * 50).coerceIn(100, 4096) },
                            onValueChangeFinished = { prefs.edit { putInt("cache_max_mb", cacheMaxMb) } },
                            valueRange = 100f..4096f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF1DB954), activeTrackColor = Color(0xFF1DB954))
                        )
                        Text(stringResource(R.string.settings_cache_max_hint), color = Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val cacheAudioClearedMsg = stringResource(R.string.settings_cache_audio_cleared)
                        val cacheImagesClearedMsg = stringResource(R.string.settings_cache_images_cleared)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val audioCache = File(context.cacheDir, "audio_cache")
                                        audioCache.deleteRecursively()
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, cacheAudioClearedMsg, Toast.LENGTH_SHORT).show()
                                            updateCacheSizes()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_cleanup_audio), color = Color.White)
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val imageCache = File(context.cacheDir, "image_cache")
                                        imageCache.deleteRecursively()
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, cacheImagesClearedMsg, Toast.LENGTH_SHORT).show()
                                            updateCacheSizes()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_cleanup_images), color = Color.White)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.settings_advanced_data),
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
                            stringResource(R.string.settings_youtube_mappings), 
                            color = Color.White, 
                            fontSize = 16.sp, 
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { exportLauncher.launch("youtube_mappings.json") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_export), color = Color.White, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_import), color = Color.White, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Structured editor: named list with edit/preview/delete and manual add.
                        Button(
                            onClick = onNavigateMatchEditor,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_edit_matches), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // The legacy "View Mappings" button and its raw-text dialog (unreadable JSON) were
                        // removed: the match editor already covers view/search/filter/edit/delete.
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Google Drive sync.
                Text(
                    text = stringResource(R.string.settings_drive_title),
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
                            stringResource(R.string.settings_drive_desc),
                            color = Color.Gray, fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(
                                if (driveLinked) R.string.settings_drive_linked
                                else R.string.settings_drive_not_linked
                            ),
                            color = if (driveLinked) Color(0xFF1DB954) else Color.Gray, fontSize = 12.sp
                        )
                        val lastSyncText = if (driveLastSync <= 0L) driveNeverSynced
                            else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(driveLastSync))
                        Text(
                            stringResource(R.string.settings_drive_last_sync, lastSyncText),
                            color = Color.Gray, fontSize = 12.sp
                        )
                        drivePulledFrom?.let { from ->
                            Text(
                                stringResource(R.string.settings_drive_pulled_from, from),
                                color = Color.Gray, fontSize = 12.sp
                            )
                        }
                        if (driveStatus.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(driveStatus, color = Color.Gray, fontSize = 12.sp)
                        }

                        // Auth method: Play Services (official build) or browser (any build).
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(stringResource(R.string.settings_drive_method), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f).clickable {
                                    driveAuthMethod = "play"; DriveSyncPrefs.setAuthMethod(context, "play")
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = driveAuthMethod == "play", onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                )
                                Text(stringResource(R.string.settings_drive_method_play), color = Color.White, fontSize = 12.sp)
                            }
                            Row(
                                modifier = Modifier.weight(1f).clickable {
                                    driveAuthMethod = "browser"; DriveSyncPrefs.setAuthMethod(context, "browser")
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = driveAuthMethod == "browser", onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                )
                                Text(stringResource(R.string.settings_drive_method_browser), color = Color.White, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (driveLinked) {
                                        drive.unlink()
                                        appAuth.signOut()
                                        DriveSyncPrefs.setLinked(context, false)
                                        driveLinked = false
                                        driveStatus = ""
                                    } else {
                                        driveAuthorizeThen { token ->
                                            DriveSyncPrefs.setLinked(context, true); driveLinked = true
                                            runDriveSync(token)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(
                                        if (driveLinked) R.string.settings_drive_unlink
                                        else R.string.settings_drive_link
                                    ),
                                    color = Color.White, fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = { if (!driveSyncing) driveAuthorizeThen { token -> runDriveSync(token) } },
                                enabled = !driveSyncing,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (driveSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp), color = Color(0xFF1DB954), strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(stringResource(R.string.settings_drive_sync_now), color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_drive_auto), color = Color.White, fontSize = 14.sp)
                                Text(stringResource(R.string.settings_drive_auto_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = driveAutoSync,
                                onCheckedChange = { checked ->
                                    driveAutoSync = checked
                                    DriveSyncPrefs.setAutoSync(context, checked)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954))
                            )
                        }

                        // "What gets synced": shows the per-category count of what would be uploaded now,
                        // and lets the user force a sync from there.
                        Spacer(modifier = Modifier.height(8.dp))
                        var showSyncDetails by remember { mutableStateOf(false) }
                        Button(
                            onClick = { showSyncDetails = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.settings_drive_details), color = Color.White, fontSize = 12.sp) }

                        if (showSyncDetails) {
                            var cats by remember { mutableStateOf<List<com.varuna.rustify.sync.RustifyBackup.Category>>(emptyList()) }
                            LaunchedEffect(Unit) { cats = withContext(Dispatchers.IO) { com.varuna.rustify.sync.RustifyBackup.summarize(context) } }
                            AlertDialog(
                                onDismissRequest = { showSyncDetails = false },
                                containerColor = Color(0xFF1E1E1E),
                                title = { Text(stringResource(R.string.settings_drive_details), color = Color.White) },
                                text = {
                                    Column {
                                        Text(stringResource(R.string.settings_drive_details_desc), color = Color.Gray, fontSize = 12.sp)
                                        Spacer(Modifier.height(8.dp))
                                        cats.forEach { c ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(c.label, color = Color.White, fontSize = 13.sp)
                                                Text(c.count.toString(), color = Color(0xFF1DB954), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = { showSyncDetails = false; if (!driveSyncing) driveAuthorizeThen { token -> runDriveSync(token) } },
                                        enabled = !driveSyncing
                                    ) { Text(stringResource(R.string.settings_drive_sync_now), color = Color(0xFF1DB954)) }
                                },
                                dismissButton = { TextButton(onClick = { showSyncDetails = false }) { Text(stringResource(R.string.settings_close), color = Color.Gray) } }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Storage breakdown section.
                Text(
                    text = stringResource(R.string.settings_storage),
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
                        // Measured off the main thread, once per visit (4.7). It walks every file the app
                        // keeps - the yt-dlp Python install alone is thousands of them - and opens the APK
                        // as a zip. Done inline in composition, as it was, that ran on the main thread on
                        // every recomposition: most of the seconds this screen took to appear.
                        val sizes by androidx.compose.runtime.produceState<StorageSizes?>(null) {
                            value = withContext(Dispatchers.IO) { measureStorage(context) }
                        }
                        val measured = sizes ?: StorageSizes()
                        val ytdlpBinaryBytes = measured.ytdlp
                        val ffmpegBinaryBytes = measured.ffmpeg
                        val ffprobeBinaryBytes = measured.ffprobe
                        val localMusicCacheBytes = measured.localMusic
                        val spotifyCacheBytes = measured.spotify
                        val userDataBytes = measured.userData
                        val audioCacheBytes = measured.audioCache
                        val imageCacheBytes = measured.imageCache
                        val totalBytes = measured.total

                        fun formatBytesLocal(bytes: Long): String = when {
                            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
                            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
                            else -> "$bytes B"
                        }

                        // Storage breakdown rows
                        @Composable
                        fun StorageRow(label: String, bytes: Long, color: Color) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                                    Spacer(modifier = Modifier.padding(start = 8.dp).width(8.dp))
                                    Text(label, color = Color.LightGray, fontSize = 13.sp)
                                }
                                Text(formatBytesLocal(bytes), color = Color.White, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        if (ytdlpBinaryBytes > 0) StorageRow("yt-dlp (Python Env)", ytdlpBinaryBytes, Color(0xFFE91E63))
                        if (ffmpegBinaryBytes > 0) StorageRow("FFmpeg Binary", ffmpegBinaryBytes, Color(0xFF9C27B0))
                        if (ffprobeBinaryBytes > 0) StorageRow("FFprobe Binary", ffprobeBinaryBytes, Color(0xFF673AB7))
                        if (localMusicCacheBytes > 0) StorageRow("Local Music Cache", localMusicCacheBytes, Color(0xFF00BCD4))
                        if (spotifyCacheBytes > 0) StorageRow("Spotify Cache", spotifyCacheBytes, Color(0xFF8BC34A))
                        StorageRow("Other User Data", userDataBytes, Color(0xFF1DB954))
                        StorageRow("Audio Cache", audioCacheBytes, Color(0xFFFF9800))
                        StorageRow("Image Cache", imageCacheBytes, Color(0xFF2196F3))
                        Spacer(modifier = Modifier.height(4.dp))
                        StorageRow("Total", totalBytes, Color.White)

                        Spacer(modifier = Modifier.height(16.dp))
                        // Storage donut chart.
                        val chartColors = listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF00BCD4), Color(0xFF8BC34A), Color(0xFF1DB954), Color(0xFFFF9800), Color(0xFF2196F3))
                        val chartValues = listOf(ytdlpBinaryBytes.toFloat(), ffmpegBinaryBytes.toFloat(), ffprobeBinaryBytes.toFloat(), localMusicCacheBytes.toFloat(), spotifyCacheBytes.toFloat(), userDataBytes.toFloat(), audioCacheBytes.toFloat(), imageCacheBytes.toFloat())
                        val totalForChart = chartValues.sum()
                        if (totalForChart > 0) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.size(140.dp)) {
                                    val strokeWidth = 14.dp.toPx()
                                    val radius = (size.minDimension - strokeWidth) / 2
                                    val topLeft = Offset(
                                        (size.width - radius * 2) / 2,
                                        (size.height - radius * 2) / 2
                                    )
                                    val arcSize = Size(radius * 2, radius * 2)
                                    var startAngle = -90f
                                    for (i in chartValues.indices) {
                                        if (chartValues[i] > 0) {
                                            val sweep = (chartValues[i] / totalForChart) * 360f
                                            drawArc(
                                                color = chartColors[i],
                                                startAngle = startAngle,
                                                sweepAngle = sweep,
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = arcSize,
                                                style = Stroke(width = strokeWidth)
                                            )
                                            startAngle += sweep
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatBytesLocal(totalBytes), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.common_total), color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                    }
                }

    }
}

/** What the storage breakdown in General shows, in bytes. All zero until measured. */
private data class StorageSizes(
    val ytdlp: Long = 0, val ffmpeg: Long = 0, val ffprobe: Long = 0,
    val localMusic: Long = 0, val spotify: Long = 0, val userData: Long = 0,
    val audioCache: Long = 0, val imageCache: Long = 0, val total: Long = 0,
)

/** Walks the app's directories and reads the bundled binaries' sizes. Disk-heavy: call it off the main thread. */
private fun measureStorage(context: Context): StorageSizes {
    fun File.dirSize(): Long {
        if (!exists()) return 0L
        return if (isDirectory) listFiles()?.sumOf { it.dirSize() } ?: 0L else length()
    }
    val filesDir = context.filesDir
    val cacheDir = context.cacheDir
    val ytdlp = File(context.noBackupFilesDir, "youtubedl-android").dirSize()
    var ffmpeg = 0L
    var ffprobe = 0L
    runCatching {
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apk ->
            for (entry in apk.entries()) {
                if (entry.name.endsWith("libffmpeg.zip.so")) ffmpeg = entry.size
                if (entry.name.endsWith("libffprobe.zip.so")) ffprobe = entry.size
            }
        }
    }
    val localMusic = File(filesDir, "covers").dirSize() + File(filesDir, "local_music_cache.json").length()
    val spotify = listOf(
        "spotify_liked_tracks_cache.json", "spotify_saved_playlists_cache.json",
        "spotify_saved_albums_cache.json", "spotify_followed_artists_cache.json"
    ).sumOf { File(filesDir, it).length() }
    val allFiles = filesDir.dirSize()
    val audio = File(cacheDir, "audio_cache").dirSize()
    val images = File(cacheDir, "image_cache").dirSize()
    return StorageSizes(
        ytdlp = ytdlp, ffmpeg = ffmpeg, ffprobe = ffprobe,
        localMusic = localMusic, spotify = spotify,
        userData = maxOf(0L, allFiles - (localMusic + spotify)),
        audioCache = audio, imageCache = images,
        total = allFiles + audio + images + ytdlp + ffmpeg,
    )
}
