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

