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

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")  // standle.net easter egg: trusted URL, JavaScript required for the game
@Composable
fun SettingsScreen(
    spotifyRepository: SpotifyRepository,
    onBack: () -> Unit,
    category: String? = null,
    onCategoryChange: (String?) -> Unit = {},
    onNavigateLogViewer: () -> Unit = {},
    onLocaleChanged: ((String) -> Unit)? = null,
    onNavigateMetrics: () -> Unit = {},
    onNavigateMatchEditor: () -> Unit = {},
    onNavigateCustomDownload: () -> Unit = {},
    ytmRepository: YtMusicRepository? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    // Category-based settings (sub-screens): null = category menu; otherwise the open category.
    // The state is hoisted in MainActivity so it survives opening and returning from sub-screens.
    val settingsCategory = category
    androidx.activity.compose.BackHandler(enabled = settingsCategory != null) { onCategoryChange(null) }

    // State for the update checker (refresh icon in the top bar).
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<com.varuna.rustify.update.AppUpdate.UpdateInfo?>(null) }
    val updateUpToDateMsg = stringResource(R.string.update_up_to_date)
    val updateCheckFailedMsg = stringResource(R.string.update_check_failed)

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var ytDlpVersion by remember { mutableStateOf(YoutubeDL.getInstance().version(context) ?: "Unknown") }
    var isNightly by remember { mutableStateOf(prefs.getString("ytdlp_channel", "NIGHTLY") == "NIGHTLY") }

    // The downloads folder, local music, and log capture moved to their own categories
    // (Downloads / Audio and backends / Advanced), each with its own state in the extracted section.

    // Link wrapper host.
    // null pref (never set) → preselect default. Explicit "" (user cleared it) → blank/fallback.
    var wrapperHost by remember {
        mutableStateOf(prefs.getString("rustify_wrapper_host", null) ?: AppLinksHosts.DEFAULT_HOST)
    }
    // "Custom…" mode: user typing a free host not in verifiedHosts.
    var wrapperHostCustom by remember {
        mutableStateOf(wrapperHost.isNotBlank() && wrapperHost !in AppLinksHosts.verifiedHosts)
    }
    var wrapperMenuExpanded by remember { mutableStateOf(false) }
    // First run (pref never set): persist the preselected default so behavior matches the UI.
    LaunchedEffect(Unit) {
        if (!prefs.contains("rustify_wrapper_host")) {
            prefs.edit { putString("rustify_wrapper_host", AppLinksHosts.DEFAULT_HOST) }
        }
    }
    var shareAsRustify by remember { mutableStateOf(prefs.getBoolean("share_as_rustify_link", false)) }

    // DJ AI: mode (heuristic / API / local) plus configuration for the OpenAI-compatible endpoint.
    var djMode by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_MODE, "heuristic") ?: "heuristic") }
    var djApiBaseUrl by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_BASE_URL, com.varuna.rustify.dj.DjSettings.DEFAULT_API_BASE_URL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_BASE_URL) }
    var djApiModel by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_MODEL, com.varuna.rustify.dj.DjSettings.DEFAULT_API_MODEL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_MODEL) }
    var djApiKey by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_KEY, "") ?: "") }


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
    manualUpdate?.let { info ->
        com.varuna.rustify.update.UpdateAvailableDialog(info = info, onDismiss = { manualUpdate = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (settingsCategory != null) onCategoryChange(null) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    // Check for updates (only in the root settings menu).
                    if (settingsCategory == null) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
          when (settingsCategory) {
            null -> SettingsCategoryMenu { onCategoryChange(it) }
            "audio" -> {
                Spacer(modifier = Modifier.height(8.dp))
                // First: without the Doze exemption nothing below matters, because playback stops
                // between songs with the screen off no matter which backend resolves the stream.
                BatteryOptimizationSection(context)
                // Order: lyrics first, then local music, then the audio backends.
                LyricsProvidersSection(context)
                LocalMusicSection(context)
                AudioBackendsSection(context)
                // Right after the chain: it is the same decision ("what plays my music"), even
                // though it cannot live inside the chain itself.
                WebPlayerSection(context)
                InvidiousBackendSection(context)
                DeezerBackendSection(context)
            }
            "downloads" -> DownloadsCategory(context, onNavigateCustomDownload)
            "integrations" -> {
                Spacer(modifier = Modifier.height(8.dp))
                AndroidAutoPreviewSection(context)
                TravelMapSection(context)
            }
            "advanced" -> {
                Spacer(modifier = Modifier.height(8.dp))
                UpdatesSection(context)
                LoggingSection(context, onNavigateLogViewer)
                SpotifyHashInspectorSection()
                AdvancedLinks(onNavigateMetrics, onNavigateMatchEditor, onNavigateLogViewer)
            }
            else -> {
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
                            Text("Máxima Calidad de Vídeo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Actívalo para intentar cargar vídeos en 1080p o superior. Si va lento, desactívalo.", color = Color.Gray, fontSize = 12.sp)
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
                    // Calculate storage sizes


                    val filesDir = context.filesDir
                    val cacheDir = context.cacheDir
                    val noBackupDir = context.noBackupFilesDir

                    fun File.dirSize(): Long {
                        if (!exists()) return 0L
                        return if (isDirectory) {
                            listFiles()?.sumOf { it.dirSize() } ?: 0L
                        } else {
                            length()
                        }
                    }

                    // Yt-dlp and FFmpeg binaries
                    val ytdlpDir = File(noBackupDir, "youtubedl-android")
                    val ytdlpBinaryBytes = ytdlpDir.dirSize()
                    
                    var ffmpegBinaryBytes = 0L
                    var ffprobeBinaryBytes = 0L
                    try {
                        val apkFile = java.util.zip.ZipFile(context.applicationInfo.sourceDir)
                        for (entry in apkFile.entries()) {
                            if (entry.name.endsWith("libffmpeg.zip.so")) ffmpegBinaryBytes = entry.size
                            if (entry.name.endsWith("libffprobe.zip.so")) ffprobeBinaryBytes = entry.size
                        }
                        apkFile.close()
                    } catch (e: Exception) {}

                    // Local Music Cache (covers + local JSON)
                    val localCoversBytes = File(filesDir, "covers").dirSize()
                    val localJsonBytes = File(filesDir, "local_music_cache.json").length()
                    val localMusicCacheBytes = localCoversBytes + localJsonBytes

                    // Spotify Cache (JSONs)
                    val spotifyLikedBytes = File(filesDir, "spotify_liked_tracks_cache.json").length()
                    val spotifyPlaylistsBytes = File(filesDir, "spotify_saved_playlists_cache.json").length()
                    val spotifyAlbumsBytes = File(filesDir, "spotify_saved_albums_cache.json").length()
                    val spotifyArtistsBytes = File(filesDir, "spotify_followed_artists_cache.json").length()
                    val spotifyCacheBytes = spotifyLikedBytes + spotifyPlaylistsBytes + spotifyAlbumsBytes + spotifyArtistsBytes

                    // Rest of User Data
                    val totalFilesDirBytes = filesDir.dirSize()
                    val otherUserDataBytes = totalFilesDirBytes - (localMusicCacheBytes + spotifyCacheBytes)
                    val userDataBytes = kotlin.math.max(0L, otherUserDataBytes)

                    val audioCacheDir = File(cacheDir, "audio_cache")
                    val imageCacheDir = File(cacheDir, "image_cache")
                    val audioCacheBytes = audioCacheDir.dirSize()
                    val imageCacheBytes = imageCacheDir.dirSize()
                    
                    val totalBytes = totalFilesDirBytes + audioCacheBytes + imageCacheBytes + ytdlpBinaryBytes + ffmpegBinaryBytes

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
                                Text("Total", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Clear buttons
                    val audioClearedMsg = stringResource(R.string.settings_cache_audio_cleared)
                    val imageClearedMsg = stringResource(R.string.settings_cache_images_cleared)
                    val errorMsg = stringResource(R.string.general_error)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    if (audioCacheDir.exists()) audioCacheDir.deleteRecursively()
                                    Toast.makeText(context, audioClearedMsg, Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_clear_audio_cache), color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                try {
                                    if (imageCacheDir.exists()) imageCacheDir.deleteRecursively()
                                    Toast.makeText(context, imageClearedMsg, Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_clear_image_cache), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_language),
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
                    val currentLang = prefs.getString("app_language", "system") ?: "system"
                    var selectedLang by remember { mutableStateOf(currentLang) }

                    
                    val languages = listOf(
                        "system" to stringResource(R.string.settings_lang_system), 
                        "en" to stringResource(R.string.settings_lang_en), 
                        "es" to stringResource(R.string.settings_lang_es),
                        "ja" to stringResource(R.string.settings_lang_ja)
                    )
                    
                    languages.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLang = code
                                    @SuppressLint("AppBundleLocaleChanges")
                                    fun applyLanguage(code: String) {
                                        prefs.edit { putString("app_language", code) }
                                        
                                        val langCode = if (code == "system") java.util.Locale.getDefault().language else code
                                        com.varuna.rustify.bridge.NativeEngine.setLanguageNative(langCode)
                                        
                                        if (code == "system") {
                                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
                                        } else {
                                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(code))
                                        }
                                    }
                                    applyLanguage(code)
                                    onLocaleChanged?.invoke(code)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLang == code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                            )
                            Spacer(modifier = Modifier.padding(start = 12.dp))
                            Text(name, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Diagnostics / logs moved to the "Advanced" category (LoggingSection).

            // Rustify link (wrapper).
            Text(
                text = stringResource(R.string.settings_rustify_link),
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
                    // Persist helper: blank → remove pref (fallback rustify://); else store trimmed host.
                    val persistWrapperHost: (String) -> Unit = { value ->
                        wrapperHost = value
                        prefs.edit {
                            if (value.isBlank()) putString("rustify_wrapper_host", "")
                            else putString("rustify_wrapper_host", value.trim())
                        }
                    }
                    val customOption = stringResource(R.string.settings_wrapper_custom)
                    val blankOption = stringResource(R.string.settings_wrapper_blank)
                    val fieldColors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF121212),
                        unfocusedContainerColor = Color(0xFF121212)
                    )
                    val selectionLabel = when {
                        wrapperHostCustom -> customOption
                        wrapperHost.isBlank() -> blankOption
                        else -> wrapperHost
                    }

                    ExposedDropdownMenuBox(
                        expanded = wrapperMenuExpanded,
                        onExpandedChange = { wrapperMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectionLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.settings_wrapper_host)) },
                            singleLine = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = wrapperMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            colors = fieldColors
                        )
                        DropdownMenu(
                            expanded = wrapperMenuExpanded,
                            onDismissRequest = { wrapperMenuExpanded = false }
                        ) {
                            AppLinksHosts.verifiedHosts.forEach { host ->
                                DropdownMenuItem(
                                    text = { Text(host) },
                                    onClick = {
                                        wrapperHostCustom = false
                                        wrapperMenuExpanded = false
                                        persistWrapperHost(host)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(blankOption) },
                                onClick = {
                                    wrapperHostCustom = false
                                    wrapperMenuExpanded = false
                                    persistWrapperHost("")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(customOption) },
                                onClick = {
                                    wrapperHostCustom = true
                                    wrapperMenuExpanded = false
                                    // Keep current value; user edits it in the free-text field below.
                                }
                            )
                        }
                    }

                    if (wrapperHostCustom) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wrapperHost,
                            onValueChange = { persistWrapperHost(it) },
                            label = { Text(customOption) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_wrapper_host_desc), color = Color.Gray, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(R.string.settings_share_as_rustify), color = Color.White, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_share_as_rustify_desc), color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = shareAsRustify,
                            onCheckedChange = { checked ->
                                shareAsRustify = checked
                                prefs.edit { putBoolean("share_as_rustify_link", checked) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1DB954)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // DJ AI section.
            Text(
                text = stringResource(R.string.settings_dj),
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
                    Text(stringResource(R.string.settings_dj_mode), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_dj_mode_desc), color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    val djModes = listOf(
                        "heuristic" to stringResource(R.string.dj_mode_heuristic),
                        "api" to stringResource(R.string.dj_mode_api),
                        "local" to stringResource(R.string.dj_mode_local)
                    )
                    djModes.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    djMode = code
                                    prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_MODE, code) }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = djMode == code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                            )
                            Spacer(modifier = Modifier.padding(start = 12.dp))
                            Text(name, color = Color.White, fontSize = 15.sp)
                        }
                    }

                    if (djMode == "api") {
                        Spacer(modifier = Modifier.height(12.dp))

                        // AI provider: free/keyless list plus a latency indicator and add/remove controls.
                        Text(stringResource(R.string.settings_dj_provider), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        var providersVersion by remember { mutableIntStateOf(0) }
                        val djProviders = remember(providersVersion) { com.varuna.rustify.dj.DjProviders.visibleProviders(context) }
                        val djSelectedId = remember(providersVersion) { com.varuna.rustify.dj.DjProviders.selectedId(context) }
                        val djLatencies = remember { androidx.compose.runtime.mutableStateMapOf<String, com.varuna.rustify.dj.DjProviders.Latency>() }
                        androidx.compose.runtime.LaunchedEffect(providersVersion) {
                            djProviders.forEach { p ->
                                val ms = com.varuna.rustify.dj.DjProviders.measureLatency(p.baseUrl)
                                djLatencies[p.id] = com.varuna.rustify.dj.DjProviders.classify(ms)
                            }
                        }
                        djProviders.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        com.varuna.rustify.dj.DjProviders.select(context, p)
                                        djApiBaseUrl = p.baseUrl; djApiModel = p.model; djApiKey = p.apiKey
                                        providersVersion++
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = djSelectedId == p.id,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                )
                                Spacer(modifier = Modifier.padding(start = 8.dp))
                                val dotColor = when (djLatencies[p.id] ?: com.varuna.rustify.dj.DjProviders.Latency.UNKNOWN) {
                                    com.varuna.rustify.dj.DjProviders.Latency.FAST -> Color(0xFF1DB954)
                                    com.varuna.rustify.dj.DjProviders.Latency.OK -> Color(0xFFFFC107)
                                    com.varuna.rustify.dj.DjProviders.Latency.SLOW -> Color(0xFFFF7043)
                                    com.varuna.rustify.dj.DjProviders.Latency.DOWN -> Color(0xFFE53935)
                                    else -> Color.Gray
                                }
                                Text("●", color = dotColor, fontSize = 12.sp)
                                Spacer(modifier = Modifier.padding(start = 8.dp))
                                Text(p.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    com.varuna.rustify.dj.DjProviders.removeProvider(context, p)
                                    providersVersion++
                                }) {
                                    Text(stringResource(R.string.settings_dj_remove), color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                        var showAddProvider by remember { mutableStateOf(false) }
                        TextButton(onClick = { showAddProvider = true }) {
                            Text(stringResource(R.string.settings_dj_add_provider), color = Color(0xFF1DB954))
                        }
                        if (showAddProvider) {
                            var npLabel by remember { mutableStateOf("") }
                            var npUrl by remember { mutableStateOf("") }
                            var npModel by remember { mutableStateOf("") }
                            var npKey by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showAddProvider = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        if (npUrl.isNotBlank() && npModel.isNotBlank()) {
                                            com.varuna.rustify.dj.DjProviders.addCustom(context, npLabel, npUrl, npModel, npKey)
                                            showAddProvider = false; providersVersion++
                                        }
                                    }) { Text(stringResource(R.string.settings_dj_add_provider), color = Color(0xFF1DB954)) }
                                },
                                dismissButton = { TextButton(onClick = { showAddProvider = false }) { Text("Cancel") } },
                                title = { Text(stringResource(R.string.settings_dj_add_provider)) },
                                text = {
                                    Column {
                                        OutlinedTextField(value = npLabel, onValueChange = { npLabel = it }, label = { Text("Name") }, singleLine = true)
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(value = npUrl, onValueChange = { npUrl = it }, label = { Text("Base URL") }, singleLine = true)
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(value = npModel, onValueChange = { npModel = it }, label = { Text("Model") }, singleLine = true)
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(value = npKey, onValueChange = { npKey = it }, label = { Text("API key (optional)") }, singleLine = true)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        val djFieldColors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF121212),
                            unfocusedContainerColor = Color(0xFF121212)
                        )
                        OutlinedTextField(
                            value = djApiBaseUrl,
                            onValueChange = { djApiBaseUrl = it; prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_API_BASE_URL, it) } },
                            label = { Text(stringResource(R.string.settings_dj_base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = djFieldColors
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = djApiModel,
                            onValueChange = { djApiModel = it; prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_API_MODEL, it) } },
                            label = { Text(stringResource(R.string.settings_dj_model)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = djFieldColors
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = djApiKey,
                            onValueChange = { djApiKey = it; prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_API_KEY, it) } },
                            label = { Text(stringResource(R.string.settings_dj_api_key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = djFieldColors
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.settings_dj_api_desc), color = Color.Gray, fontSize = 12.sp)
                    }

                    if (djMode == "local") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_dj_local_note), color = Color.Gray, fontSize = 12.sp)
                    }

                    // DJ voice (hybrid: native TTS plus an optional cloud endpoint).
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.settings_dj_voice), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val djVoiceFieldColors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF121212),
                        unfocusedContainerColor = Color(0xFF121212)
                    )
                    var djVoiceEnabled by remember { mutableStateOf(prefs.getBoolean(com.varuna.rustify.dj.DjSettings.KEY_VOICE_ENABLED, true)) }
                    var djVoiceLang by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_LANG, "") ?: "") }
                    var djVoiceCloudUrl by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_URL, "") ?: "") }
                    var djVoiceCloudKey by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_KEY, "") ?: "") }
                    var djTtsEngine by remember { mutableStateOf(com.varuna.rustify.dj.DjSettings.ttsEngine(context)) }
                    var djVoiceNativeName by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_NATIVE_NAME, "") ?: "") }
                    var djCloudVoice by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE, "alloy") ?: "alloy") }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_dj_voice_enabled), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = djVoiceEnabled,
                            onCheckedChange = {
                                djVoiceEnabled = it
                                prefs.edit { putBoolean(com.varuna.rustify.dj.DjSettings.KEY_VOICE_ENABLED, it) }
                            }
                        )
                    }
                    var voiceLangExpanded by remember { mutableStateOf(false) }
                    LaunchedEffect(djMode) { voiceLangExpanded = false }
                    val systemDefaultLabel = stringResource(R.string.settings_dj_voice_lang_system)
                    val allVoiceLangs = listOf("en", "es", "ja", "fr", "de", "pt", "it", "ko", "zh")
                    val voiceLangOptions = remember(djMode, systemDefaultLabel) {
                        val appLang = prefs.getString("app_language", "system") ?: "system"
                        val displayLocale = if (appLang == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(appLang)
                        val filteredLangs = if (djMode == "api") allVoiceLangs else listOf("en", "es")
                        listOf("" to systemDefaultLabel) + filteredLangs.map { code ->
                            code to java.util.Locale.forLanguageTag(code).getDisplayName(displayLocale)
                        }
                    }
                    val voiceLangLabel = voiceLangOptions.firstOrNull { it.first == djVoiceLang }?.second ?: djVoiceLang
                    ExposedDropdownMenuBox(
                        expanded = voiceLangExpanded,
                        onExpandedChange = { voiceLangExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = voiceLangLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.settings_dj_voice_lang)) },
                            singleLine = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceLangExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            colors = djVoiceFieldColors
                        )
                        DropdownMenu(
                            expanded = voiceLangExpanded,
                            onDismissRequest = { voiceLangExpanded = false }
                        ) {
                            voiceLangOptions.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        djVoiceLang = code
                                        prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_LANG, code) }
                                        voiceLangExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Voice engine (native Android / keyless Pollinations / own OpenAI endpoint).
                    Spacer(modifier = Modifier.height(8.dp))
                    var ttsEngineExpanded by remember { mutableStateOf(false) }
                    val ttsEngineOptions = listOf(
                        "native" to stringResource(R.string.settings_dj_tts_native),
                        "edge" to stringResource(R.string.settings_dj_tts_edge),
                        "gtranslate" to stringResource(R.string.settings_dj_tts_gtranslate),
                        "pollinations" to stringResource(R.string.settings_dj_tts_pollinations),
                        "openai" to stringResource(R.string.settings_dj_tts_openai)
                    )
                    val ttsEngineLabel = ttsEngineOptions.firstOrNull { it.first == djTtsEngine }?.second ?: djTtsEngine
                    ExposedDropdownMenuBox(expanded = ttsEngineExpanded, onExpandedChange = { ttsEngineExpanded = it }) {
                        OutlinedTextField(
                            value = ttsEngineLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.settings_dj_tts_engine)) },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsEngineExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = djVoiceFieldColors
                        )
                        DropdownMenu(expanded = ttsEngineExpanded, onDismissRequest = { ttsEngineExpanded = false }) {
                            ttsEngineOptions.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        djTtsEngine = code
                                        prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_TTS_ENGINE, code) }
                                        ttsEngineExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    when (djTtsEngine) {
                        // Specific native voice, filtered by the chosen voice language.
                        "native" -> {
                            var nativeVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                            LaunchedEffect(djVoiceLang) {
                                com.varuna.rustify.dj.DjVoice.queryVoices(context, djVoiceLang) { nativeVoices = it }
                            }
                            var nativeVoiceExpanded by remember { mutableStateOf(false) }
                            val systemVoiceLabel = stringResource(R.string.settings_dj_voice_native_system)
                            val nativeOptions = remember(nativeVoices, systemVoiceLabel) {
                                listOf("" to systemVoiceLabel) + nativeVoices
                            }
                            val nativeVoiceLabel = nativeOptions.firstOrNull { it.first == djVoiceNativeName }?.second
                                ?: (djVoiceNativeName.ifBlank { systemVoiceLabel })
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(expanded = nativeVoiceExpanded, onExpandedChange = { nativeVoiceExpanded = it }) {
                                OutlinedTextField(
                                    value = nativeVoiceLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.settings_dj_voice_native)) },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nativeVoiceExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    colors = djVoiceFieldColors
                                )
                                DropdownMenu(expanded = nativeVoiceExpanded, onDismissRequest = { nativeVoiceExpanded = false }) {
                                    nativeOptions.forEach { (id, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                djVoiceNativeName = id
                                                prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_NATIVE_NAME, id) }
                                                com.varuna.rustify.dj.DjVoice.refreshConfig(context)
                                                nativeVoiceExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        // Microsoft Edge: free neural voices with no token. Voice selector plus health check.
                        "edge" -> {
                            var edgeExpanded by remember { mutableStateOf(false) }
                            val edgeHealth = remember { mutableStateOf<com.varuna.rustify.dj.DjProviders.Latency?>(null) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                edgeHealth.value = com.varuna.rustify.dj.DjProviders.classify(com.varuna.rustify.dj.DjVoice.probeEdge())
                            }
                            val edgeVoices = com.varuna.rustify.dj.DjSettings.EDGE_VOICES
                            val selVoice = if (djCloudVoice.contains("Neural")) djCloudVoice else com.varuna.rustify.dj.DjSettings.EDGE_DEFAULT_VOICE
                            val selLabel = edgeVoices.firstOrNull { it.first == selVoice }?.second ?: selVoice
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(expanded = edgeExpanded, onExpandedChange = { edgeExpanded = it }) {
                                OutlinedTextField(
                                    value = selLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.settings_dj_voice_pick)) },
                                    singleLine = true,
                                    leadingIcon = { Text("●", color = voiceDotColor(edgeHealth.value), fontSize = 12.sp) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = edgeExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    colors = djVoiceFieldColors
                                )
                                DropdownMenu(expanded = edgeExpanded, onDismissRequest = { edgeExpanded = false }) {
                                    edgeVoices.forEach { (id, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                djCloudVoice = id
                                                prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE, id) }
                                                edgeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(stringResource(R.string.settings_dj_tts_edge_note), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        // Google Translate: voice per language (no voice selection), free and tokenless.
                        "gtranslate" -> {
                            val gtHealth = remember { mutableStateOf<com.varuna.rustify.dj.DjProviders.Latency?>(null) }
                            androidx.compose.runtime.LaunchedEffect(djVoiceLang) {
                                gtHealth.value = com.varuna.rustify.dj.DjProviders.classify(com.varuna.rustify.dj.DjVoice.probeGoogleTranslate(context))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = voiceDotColor(gtHealth.value), fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_dj_tts_gtranslate_note), color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        // OpenAI voices (keyless Pollinations or own OpenAI) plus the endpoint fields.
                        else -> {
                            var cloudVoiceExpanded by remember { mutableStateOf(false) }
                            // Health of the Pollinations voices (same ● indicator as the DJ model):
                            // a short real ping per voice. Pollinations' free audio API was deprecated
                            // (openai-audio → 404), so these usually show RED; the indicator makes that
                            // explicit so the user picks the native voice or their own OpenAI-compatible
                            // endpoint instead of waiting for a fallback.
                            val voiceHealth = remember { androidx.compose.runtime.mutableStateMapOf<String, com.varuna.rustify.dj.DjProviders.Latency>() }
                            if (djTtsEngine == "pollinations") {
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    com.varuna.rustify.dj.DjSettings.OPENAI_VOICES.forEach { v ->
                                        launch {
                                            val ms = com.varuna.rustify.dj.DjVoice.probeVoice(context, v)
                                            voiceHealth[v] = com.varuna.rustify.dj.DjProviders.classify(ms)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(expanded = cloudVoiceExpanded, onExpandedChange = { cloudVoiceExpanded = it }) {
                                OutlinedTextField(
                                    value = djCloudVoice,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.settings_dj_voice_pick)) },
                                    singleLine = true,
                                    leadingIcon = if (djTtsEngine == "pollinations") {
                                        { Text("●", color = voiceDotColor(voiceHealth[djCloudVoice]), fontSize = 12.sp) }
                                    } else null,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cloudVoiceExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    colors = djVoiceFieldColors
                                )
                                DropdownMenu(expanded = cloudVoiceExpanded, onDismissRequest = { cloudVoiceExpanded = false }) {
                                    com.varuna.rustify.dj.DjSettings.OPENAI_VOICES.forEach { v ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (djTtsEngine == "pollinations") {
                                                        Text("●", color = voiceDotColor(voiceHealth[v]), fontSize = 12.sp)
                                                        Spacer(Modifier.width(8.dp))
                                                    }
                                                    Text(v.replaceFirstChar { it.uppercase() })
                                                }
                                            },
                                            onClick = {
                                                djCloudVoice = v
                                                prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE, v) }
                                                cloudVoiceExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            if (djTtsEngine == "pollinations") {
                                Text(
                                    stringResource(R.string.settings_dj_tts_pollinations_note),
                                    color = Color.Gray, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = djVoiceCloudUrl,
                                    onValueChange = { djVoiceCloudUrl = it; prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_URL, it) } },
                                    label = { Text(stringResource(R.string.settings_dj_voice_cloud_url)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = djVoiceFieldColors
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = djVoiceCloudKey,
                                    onValueChange = { djVoiceCloudKey = it; prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_KEY, it) } },
                                    label = { Text(stringResource(R.string.settings_dj_voice_cloud_key)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = djVoiceFieldColors
                                )
                            }
                        }
                    }

                    // Previews the selected voice with a test phrase (ignores the "DJ enabled" toggle,
                    // so it can be heard while choosing a voice from Settings).
                    var previewLoading by remember { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            previewLoading = true
                            com.varuna.rustify.dj.DjVoice.init(context)
                            com.varuna.rustify.dj.DjVoice.refreshConfig(context)
                            com.varuna.rustify.dj.DjVoice.preview(context) { previewLoading = false }
                        },
                        enabled = !previewLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (previewLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading...", color = Color.Black, fontSize = 14.sp)
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_dj_voice_preview), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Automatic DJ source (favorites / balanced / discover).
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.settings_dj_auto_source), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    var djAutoSource by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_AUTO_SOURCE, "balanced") ?: "balanced") }
                    val djAutoSources = listOf(
                        "favorites" to stringResource(R.string.dj_auto_source_favorites),
                        "balanced" to stringResource(R.string.dj_auto_source_balanced),
                        "discover" to stringResource(R.string.dj_auto_source_discover)
                    )
                    djAutoSources.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    djAutoSource = code
                                    prefs.edit { putString(com.varuna.rustify.dj.DjSettings.KEY_AUTO_SOURCE, code) }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = djAutoSource == code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                            )
                            Spacer(modifier = Modifier.padding(start = 12.dp))
                            Text(name, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_session),
                color = Color(0xFF1DB954),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        spotifyRepository.logout()
                        onBack()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.settings_logout), color = Color.White, fontWeight = FontWeight.Bold)
            }

            // Easter egg (level 1): tap the version 7 times to reveal an animated crab. Tap it 9 more
            // times to trigger the nested egg (level 2), which opens standle.net in the app's language.
            // Self-contained, localized, and requires no new permissions.
            var eggTaps by remember { mutableIntStateOf(0) }
            var showEgg by remember { mutableStateOf(false) }
            var showJojo by remember { mutableStateOf(false) }
            var showWeb by remember { mutableStateOf(false) }
            var oraCount by remember { mutableIntStateOf(0) }
            val appVersion = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            }
            // Easter egg language: always resolved from the language chosen in the app (the app_language
            // pref), building a localized Context by hand. This keeps the texts and standle.net in the
            // app's language even when the dialog (a sub-composition) does not inherit the locale and even
            // when the system is set to a different language.
            val eggLang = remember {
                val appLang = prefs.getString("app_language", "system") ?: "system"
                if (appLang == "system") java.util.Locale.getDefault().language else appLang
            }
            val webLang = remember(eggLang) {
                when { eggLang.startsWith("es") -> "es"; eggLang.startsWith("ja") -> "ja"; else -> "en" }
            }
            val eggCtx = remember(eggLang) {
                val cfg = android.content.res.Configuration(context.resources.configuration)
                cfg.setLocale(java.util.Locale.forLanguageTag(eggLang))
                context.createConfigurationContext(cfg)
            }
            val eggTitle = remember(eggCtx) { eggCtx.getString(R.string.egg_found_title) }
            val eggMsg = remember(eggCtx) { eggCtx.getString(R.string.egg_found_msg) }
            val eggJojoMsg = remember(eggCtx) { eggCtx.getString(R.string.egg_jojo_msg) }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Rustify $appVersion 🦀",
                color = Color(0xFF555555), fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    // No ripple: the default highlight looked poor over the text.
                    .noRippleClickable { eggTaps++; if (eggTaps >= 7) { eggTaps = 0; oraCount = 0; showEgg = true } }
                    .padding(vertical = 10.dp)
            )

            if (showEgg) {
                JojoOraOra(
                    title = eggTitle,
                    msg = eggMsg,
                    oraCount = oraCount,
                    onOra = { oraCount++; if (oraCount >= 9) { showEgg = false; showJojo = true } },
                    onClose = { showEgg = false }
                )
            }

            if (showJojo) {
                JojoTimeStop(
                    message = eggJojoMsg,
                    onContinue = { showJojo = false; showWeb = true },
                    onDismiss = { showJojo = false }
                )
            }

            if (showWeb) {
                Dialog(onDismissRequest = { showWeb = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = android.webkit.WebViewClient()
                                    loadUrl("https://standle.net/$webLang")
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        TextButton(onClick = { showWeb = false }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 8.dp)) {
                            Text("✕", color = Color.White, fontSize = 20.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            }
          }
        }
    }
}

// Clickable without the default indication (ripple/highlight), which looked poor over text and emoji.
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * Level 1 egg: the crab vibrates and each tap fires an "ORA!" with an elastic bounce, while
 * ゴゴゴ symbols rise in the background. After 9 taps it chains into [JojoTimeStop] (level 2).
 * Texts arrive already localized (as resolved Strings) to respect the app's language.
 */
@Composable
private fun JojoOraOra(title: String, msg: String, oraCount: Int, onOra: () -> Unit, onClose: () -> Unit) {
    val purple = Color(0xFF8E24AA)
    val gold = Color(0xFFFFD700)
    val infinite = rememberInfiniteTransition(label = "ora")
    // Very fast crab vibration.
    val shake by infinite.animateFloat(
        initialValue = -7f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(80, easing = LinearEasing), RepeatMode.Reverse), label = "shake"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )
    // Elastic punch of the "ORA!" on each tap.
    val punch = remember { Animatable(1f) }
    LaunchedEffect(oraCount) {
        if (oraCount > 0) { punch.snapTo(1.7f); punch.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = 440f)) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)), contentAlignment = Alignment.Center) {
            // ゴゴゴ symbols rising in the background.
            for (i in 0 until 6) {
                val rise by infinite.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2200, delayMillis = i * 170, easing = LinearEasing), RepeatMode.Restart),
                    label = "go$i"
                )
                Text(
                    "ゴ", color = purple.copy(alpha = (1f - rise).coerceIn(0f, 1f) * 0.35f),
                    fontSize = (16 + (i % 3) * 9).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).offset { IntOffset(x = (28 + i * 58).dp.roundToPx(), y = -(rise * 420).dp.roundToPx()) }
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(title, color = gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text(
                    "🦀", fontSize = 92.sp,
                    modifier = Modifier
                        .graphicsLayer { rotationZ = shake; scaleX = pulse; scaleY = pulse }
                        .noRippleClickable { onOra() }
                )
                Spacer(Modifier.height(18.dp))
                if (oraCount > 0) {
                    Text(
                        "ORA!", color = purple, fontSize = 46.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.graphicsLayer { scaleX = punch.value; scaleY = punch.value; rotationZ = shake * 0.6f }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(("ORA ".repeat(oraCount)).trim(), color = gold.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
                }
                Spacer(Modifier.height(20.dp))
                Text(msg, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                TextButton(onClick = onClose) { Text("😎", color = Color(0xFF1DB954), fontSize = 22.sp) }
            }
        }
    }
}

/**
 * Nested egg (level 2): a full-screen "time stop" effect. Rotating golden rays, a double
 * "time stopped" flash, the 「ザ・ワールド」 title entering with an elastic bounce, and rising ゴ
 * symbols. "To Be Continued ➜" opens standle.net; やれやれだぜ closes it.
 * Built entirely with Compose animations, with no new resources or dependencies.
 */
@Composable
private fun JojoTimeStop(message: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val gold = Color(0xFFFFD700)
    val infinite = rememberInfiniteTransition(label = "jojo")
    val rayAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "rays"
    )
    // Title: appears with an elastic bounce. Flash: a double white "time stopped" flash.
    val titleScale = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        flash.animateTo(1f, tween(80)); flash.animateTo(0f, tween(200))
        flash.animateTo(0.75f, tween(70)); flash.animateTo(0f, tween(280))
        titleScale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 300f))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF060606))) {
            val w = maxWidth
            val h = maxHeight

            // Rotating radial golden rays (aura effect).
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = kotlin.math.hypot(size.width, size.height)
                val rays = 24
                val step = 360.0 / rays
                for (i in 0 until rays) {
                    if (i % 2 != 0) continue
                    val a0 = Math.toRadians(rayAngle + i * step)
                    val a1 = Math.toRadians(rayAngle + i * step + step)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy)
                        lineTo(cx + r * kotlin.math.cos(a0).toFloat(), cy + r * kotlin.math.sin(a0).toFloat())
                        lineTo(cx + r * kotlin.math.cos(a1).toFloat(), cy + r * kotlin.math.sin(a1).toFloat())
                        close()
                    }
                    drawPath(path, color = gold.copy(alpha = 0.07f))
                }
            }

            // ゴ symbols rising and fading out, staggered.
            val goCount = 8
            for (i in 0 until goCount) {
                val rise by infinite.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2400, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Restart),
                    label = "go$i"
                )
                Text(
                    "ゴ",
                    color = gold.copy(alpha = (1f - rise).coerceIn(0f, 1f) * 0.55f),
                    fontSize = (18 + (i % 3) * 10).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(x = (w * ((i + 0.5f) / goCount)).roundToPx(), y = (-(h * 0.12f) - h * 0.72f * rise).roundToPx()) }
                )
            }

            // Central content.
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "「ザ・ワールド」",
                    color = gold, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        scaleX = titleScale.value; scaleY = titleScale.value
                        rotationZ = (1f - titleScale.value) * -12f
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text("ゴ ゴ ゴ ゴ", color = gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = onContinue) {
                    Text("To Be Continued  ➜", color = gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(onClick = onDismiss) { Text("やれやれだぜ", color = Color.Gray) }
            }

            // Flash overlay (time stopped).
            if (flash.value > 0.001f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// "Audio Backends" section (drag and drop + toggles).
// ---------------------------------------------------------------------------

@Composable
private fun backendFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedContainerColor = Color(0xFF121212), unfocusedContainerColor = Color(0xFF121212),
    focusedLabelColor = Color(0xFF1DB954), focusedIndicatorColor = Color(0xFF1DB954)
)

// Health dot color (same scheme as the DJ model latency indicator).
private fun voiceDotColor(l: com.varuna.rustify.dj.DjProviders.Latency?): Color =
    when (l ?: com.varuna.rustify.dj.DjProviders.Latency.UNKNOWN) {
        com.varuna.rustify.dj.DjProviders.Latency.FAST -> Color(0xFF1DB954)
        com.varuna.rustify.dj.DjProviders.Latency.OK -> Color(0xFFFFC107)
        com.varuna.rustify.dj.DjProviders.Latency.SLOW -> Color(0xFFFF7043)
        com.varuna.rustify.dj.DjProviders.Latency.DOWN -> Color(0xFFE53935)
        else -> Color.Gray
    }

// Settings category menu (sub-screens). Each row opens its category.
@Composable
private fun SettingsCategoryMenu(onPick: (String) -> Unit) {
    val cats = listOf(
        "general" to stringResource(R.string.settings_cat_general),
        "audio" to stringResource(R.string.settings_cat_audio),
        "downloads" to stringResource(R.string.settings_cat_downloads),
        "integrations" to stringResource(R.string.settings_cat_integrations),
        "advanced" to stringResource(R.string.settings_cat_advanced)
    )
    Spacer(Modifier.height(12.dp))
    cats.forEach { (key, label) ->
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPick(key) }
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = Color.Gray, fontSize = 20.sp)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

// "Downloads" category: custom downloads folder plus access to the download screen.
@Composable
private fun DownloadsCategory(context: Context, onOpenCustom: () -> Unit) {
    val green = Color(0xFF1DB954)
    // Standard downloads folder (in addition to the custom one).
    DownloadFolderSection(context)
    var folder by remember { mutableStateOf(com.varuna.rustify.audio.CustomDownload.folder(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            com.varuna.rustify.audio.CustomDownload.setFolder(context, uri.toString())
            folder = uri.toString()
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_custom_download), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val folderLabel = if (folder.isBlank()) stringResource(R.string.cd_no_folder)
                else Uri.decode(folder.substringAfterLast("%3A").substringAfterLast("/")).ifBlank { folder }
            Text("📁 $folderLabel", color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.cd_pick_folder), color = green) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenCustom, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.cd_title), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// "Advanced" category: hash inspector plus access to metrics, the match editor, and logs.
@Composable
private fun AdvancedLinks(onMetrics: () -> Unit, onMatchEditor: () -> Unit, onLogs: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            stringResource(R.string.home_metrics) to onMetrics,
            stringResource(R.string.settings_edit_matches) to onMatchEditor
        ).forEach { (label, action) ->
            Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(label, color = Color.White, modifier = Modifier.weight(1f))
                Text("›", color = Color.Gray)
            }
        }
    }
}

// Standard row of title + description + Switch (avoids repeating the same boilerplate for every setting).
@Composable
private fun SettingSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            if (desc.isNotBlank()) Text(desc, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954))
        )
    }
}

// Doze exemption. Not a preference — a system state the user has to grant, shown here because when
// it is missing the symptom looks like an app bug: playback stops between songs with the screen off.
// Re-checked on every visit and after the prompt, since reinstalling the app clears it.
@Composable
private fun BatteryOptimizationSection(context: Context) {
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
private fun WebPlayerSection(context: Context) {
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
private fun UpdatesSection(context: Context) {
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
private fun LocalMusicSection(context: Context) {
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
private fun DownloadFolderSection(context: Context) {
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
private fun LoggingSection(context: Context, onViewLogs: () -> Unit) {
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

// Invidious instance configuration (the on/off and ordering live in Audio Backends).
@Composable
private fun InvidiousBackendSection(context: Context) {
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
private fun DeezerBackendSection(context: Context) {
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
private fun AudioBackendsSection(context: Context) {
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
    StreamCacheSection(context)
}

/**
 * The stream cache: songs kept on the device after they have been played once.
 *
 * Placed with the backends because it is what makes them cheap — a track already here needs no
 * backend at all. The size and the clear button are here because a cache the user cannot see or
 * empty is just disk that went missing.
 */
@Composable
private fun StreamCacheSection(context: Context) {
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

/** Bytes as something a person reads, without pulling in a formatting library for four branches. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Installing audio backends by URL.
 *
 * Kept beneath the backend order on purpose: an installed addon becomes one more entry in the lists
 * above, so this is where new entries come from rather than a separate world — and
 * [onAddonsChanged] is what makes those lists show it without leaving the screen.
 */
@Composable
private fun AddonsSection(context: Context, onAddonsChanged: () -> Unit) {
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
private fun ReorderableBackendList(
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
private fun LyricsProvidersSection(context: Context) {
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
private fun ReorderableLyricsList(
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

@Composable
private fun AndroidAutoPreviewSection(context: Context) {
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
private fun SpotifyHashInspectorSection() {
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
private fun TravelMapSection(context: Context) {
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

