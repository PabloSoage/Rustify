@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("UseKtx")

package com.varuna.rustify.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.activity.result.IntentSenderRequest
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.sync.DriveSyncManager
import com.varuna.rustify.sync.DriveSyncPrefs
import com.varuna.rustify.sync.GoogleDriveSync
import com.varuna.rustify.util.AppLinksHosts
import com.varuna.rustify.util.LogCapture
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    spotifyRepository: SpotifyRepository,
    onBack: () -> Unit,
    onNavigateLogViewer: () -> Unit = {},
    onLocaleChanged: ((String) -> Unit)? = null,
    onNavigateMetrics: () -> Unit = {},
    ytmRepository: YtMusicRepository? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var ytDlpVersion by remember { mutableStateOf(YoutubeDL.getInstance().version(context) ?: "Unknown") }
    var isNightly by remember { mutableStateOf(prefs.getString("ytdlp_channel", "NIGHTLY") == "NIGHTLY") }
    
    var downloadUriString by remember { mutableStateOf(prefs.getString("download_directory", null)) }
    
    // F1.B / F1.A — diagnóstico y wrapper.
    var loggingEnabled by remember { mutableStateOf(prefs.getBoolean("logging_capture_enabled", false)) }
    // null pref (never set) → preselect default. Explicit "" (user cleared it) → blank/fallback.
    var wrapperHost by remember {
        mutableStateOf(prefs.getString("rustify_wrapper_host", null) ?: AppLinksHosts.DEFAULT_HOST)
    }
    // "Personalizado…" mode: user typing a free host not in verifiedHosts.
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

    // E90 — DJ IA: modo (heurístico / API / local) + config del endpoint OpenAI-compatible.
    var djMode by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_MODE, "heuristic") ?: "heuristic") }
    var djApiBaseUrl by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_BASE_URL, com.varuna.rustify.dj.DjSettings.DEFAULT_API_BASE_URL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_BASE_URL) }
    var djApiModel by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_MODEL, com.varuna.rustify.dj.DjSettings.DEFAULT_API_MODEL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_MODEL) }
    var djApiKey by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_KEY, "") ?: "") }

    var enableLocalMusic by remember { mutableStateOf(prefs.getBoolean("enable_local_music", true)) }
    var matchLocalFirst by remember { mutableStateOf(prefs.getBoolean("settings_match_local_first", false)) }
    var enableYtmMusic by remember { mutableStateOf(prefs.getBoolean("enable_ytm_music", true)) }
    var localMusicDirs by remember { mutableStateOf(prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()) }

    val addLocalMusicDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val newSet = localMusicDirs.toMutableSet().apply { add(uri.toString()) }
            localMusicDirs = newSet
            prefs.edit { putStringSet("local_music_directories", newSet) }
        }
    }

    val downloadDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            downloadUriString = uri.toString()
            prefs.edit { putString("download_directory", uri.toString()) }
        }
    }

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

    // E30 - export/import de datos locales (contenedor versionado).
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
                    val raw = context.contentResolver.openInputStream(uri)?.use { java.io.ByteArrayOutputStream().also { bos -> it.copyTo(bos) }.toString(Charsets.UTF_8) } ?: ""
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

    // ── E50 — Google Drive sync ───────────────────────────────────────────────
    val drive = remember { GoogleDriveSync(context.applicationContext) }
    val syncManager = remember {
        DriveSyncManager(context.applicationContext, drive, spotifyRepository, ytmRepository)
    }
    // Web client id: si está vacío, la app no está configurada (ver GoogleDriveSync.kt).
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

    // Ejecuta una sync completa con un access token ya obtenido.
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

    // Launcher para el flujo de consentimiento OAuth (IntentSender de AuthorizationClient).
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

    // Pide token (y lanza consentimiento si hace falta), luego ejecuta [onToken].
    fun driveAuthorizeThen(onToken: (String) -> Unit) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
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
                    Text(
                        stringResource(R.string.settings_ytdlp_desc),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }



            AudioBackendsSection(context)

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
                    Text(
                        stringResource(R.string.settings_download_dir), 
                        color = Color.White, 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (downloadUriString != null) Uri.parse(downloadUriString).lastPathSegment ?: downloadUriString!! 
                        else stringResource(R.string.settings_no_dir_configured),
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { downloadDirLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        Text(stringResource(R.string.settings_select_folder), color = Color.White)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Text(stringResource(R.string.settings_local_music), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_enable_local_music), color = Color.White, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_enable_local_music_desc), color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = enableLocalMusic,
                            onCheckedChange = { checked ->
                                enableLocalMusic = checked
                                prefs.edit { putBoolean("enable_local_music", checked) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1DB954)
                            )
                        )
                    }

                    if (enableLocalMusic) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(stringResource(R.string.settings_match_local_first), color = Color.White, fontSize = 14.sp)
                                Text(stringResource(R.string.settings_match_local_first_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = matchLocalFirst,
                                onCheckedChange = { checked ->
                                    matchLocalFirst = checked
                                    prefs.edit { putBoolean("settings_match_local_first", checked) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.enable_ytm_music), color = Color.White, fontSize = 14.sp)
                                Text(stringResource(R.string.enable_ytm_music_desc), color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = enableYtmMusic,
                                onCheckedChange = { checked ->
                                    enableYtmMusic = checked
                                    prefs.edit { putBoolean("enable_ytm_music", checked) }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954))
                            )
                        }

                        Text(stringResource(R.string.settings_added_folders), color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        
if (localMusicDirs.isEmpty()) {
                            Text(stringResource(R.string.settings_no_folder_configured), color = Color.Gray, fontSize = 14.sp)
                        } else {
                            localMusicDirs.forEach { uriStr ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val name = Uri.parse(uriStr).lastPathSegment ?: uriStr
                                    Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        stringResource(R.string.settings_remove),
                                        color = Color.Red,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            val newSet = localMusicDirs.toMutableSet().apply { remove(uriStr) }
                                            localMusicDirs = newSet
                                            prefs.edit { putStringSet("local_music_directories", newSet) }
                                        }.padding(8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { addLocalMusicDirLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
                        ) {
                            Text(stringResource(R.string.settings_add_folder), color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
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
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_cleanup), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        val totalSizeMb = (audioCacheSize + imageCacheSize) / (1024 * 1024)
                        Text("$totalSizeMb MB", color = Color.Gray, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Audio: ${audioCacheSize / (1024 * 1024)} MB • Images: ${imageCacheSize / (1024 * 1024)} MB", color = Color.Gray, fontSize = 12.sp)
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
                    // View/Edit mappings button (BUG-24)
                    var showMappingsDialog by remember { mutableStateOf(false) }
                    val mappingsContent = remember {
                        val file = File(context.filesDir, "youtube_mappings.json")
                        if (file.exists()) file.readText() else null
                    }
                    Button(
                        onClick = { showMappingsDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "View Mappings (${mappingsContent?.lines()?.size ?: 0} entries)",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    if (showMappingsDialog) {
                        var editableContent by remember { mutableStateOf(mappingsContent ?: "") }
                        AlertDialog(
                            onDismissRequest = { showMappingsDialog = false },
                            title = { Text("YouTube Mappings", color = Color.White) },
                            text = {
                                Column {
                                    if (mappingsContent == null) {
                                        Text("No mappings found.", color = Color.Gray)
                                    } else {
                                        rememberScrollState()
                                        OutlinedTextField(
                                            value = editableContent,
                                            onValueChange = { editableContent = it },
                                            modifier = Modifier.fillMaxWidth().height(300.dp),
                                            colors = TextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedContainerColor = Color(0xFF121212),
                                                unfocusedContainerColor = Color(0xFF121212)
                                            ),
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        val file = File(context.filesDir, "youtube_mappings.json")
                                        try {
                                            file.writeText(editableContent)
                                            Toast.makeText(context, "Mappings saved", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
                                        }
                                        showMappingsDialog = false
                                    }) {
                                        Text("Save", color = Color(0xFF1DB954))
                                    }
                                    TextButton(onClick = { showMappingsDialog = false }) {
                                        Text("Close", color = Color.Gray)
                                    }
                                }
                            },
                            containerColor = Color(0xFF1E1E1E)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── E50 — Google Drive sync ───────────────────────────────────────
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

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (driveLinked) {
                                    drive.unlink()
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
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Storage breakdown section (BUG-12+17)
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
                    // Storage donut chart (BUG-25)
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

            // F1.B — Diagnóstico / Logs
            Text(
                text = stringResource(R.string.settings_diagnostics),
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
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(R.string.settings_capture_logs), color = Color.White, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_capture_logs_desc), color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = loggingEnabled,
                            onCheckedChange = { checked ->
                                loggingEnabled = checked
                                prefs.edit { putBoolean("logging_capture_enabled", checked) }
                                // Vía elegida: logcat del propio proceso (start/stop del stream).
                                if (checked) LogCapture.start() else LogCapture.stop()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1DB954)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onNavigateLogViewer() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_view_logs), color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // F1.A — Enlace Rustify (wrapper)
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
                    val customOption = "Personalizado…"
                    val blankOption = "(En blanco → rustify://)"
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
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            colors = fieldColors
                        )
                        ExposedDropdownMenu(
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

            // E90 — Sección DJ IA.
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
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// E60 - Sección "Audio Backends" (drag&drop + toggles).
// ---------------------------------------------------------------------------

@Composable
private fun AudioBackendsSection(context: android.content.Context) {
    val knownIds = remember { com.varuna.rustify.audio.AudioSourceRegistry.knownIds() }
    val catalog = remember { com.varuna.rustify.audio.AudioSourceRegistry.catalog().associateBy { it.id } }
    var streamOrder by remember { mutableStateOf(com.varuna.rustify.audio.AudioBackendSettings.loadOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_STREAM, knownIds)) }
    var downloadOrder by remember { mutableStateOf(com.varuna.rustify.audio.AudioBackendSettings.loadOrder(context, com.varuna.rustify.audio.AudioBackendSettings.KEY_DOWNLOAD, knownIds)) }

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
    var dragOffset by remember { mutableStateOf(0f) }

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
