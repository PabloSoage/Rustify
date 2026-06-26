@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SpotifyRepository
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
    onLocaleChanged: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var ytDlpVersion by remember { mutableStateOf(YoutubeDL.getInstance().version(context) ?: "Unknown") }
    var isNightly by remember { mutableStateOf(prefs.getString("ytdlp_channel", "NIGHTLY") == "NIGHTLY") }
    
    var downloadUriString by remember { mutableStateOf(prefs.getString("download_directory", null)) }
    
    var enableLocalMusic by remember { mutableStateOf(prefs.getBoolean("enable_local_music", true)) }
    var matchLocalFirst by remember { mutableStateOf(prefs.getBoolean("settings_match_local_first", false)) }
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

    var audioCacheSize by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var imageCacheSize by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

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

    androidx.compose.runtime.LaunchedEffect(Unit) {
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

            Spacer(modifier = Modifier.height(24.dp))

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
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { exportLauncher.launch("youtube_mappings.json") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_export), color = Color.White)
                        }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_import), color = Color.White)
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
                    val currentConfig = androidx.compose.ui.platform.LocalConfiguration.current
                    
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
                                    @android.annotation.SuppressLint("AppBundleLocaleChanges")
                                    fun applyLanguage(code: String) {
                                        prefs.edit { putString("app_language", code) }
                                        
                                        val langCode = if (code == "system") java.util.Locale.getDefault().language else code
                                        com.varuna.rustify.bridge.NativeEngine.setLanguageNative(langCode)
                                        
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                                            if (code == "system") {
                                                localeManager.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
                                            } else {
                                                localeManager.applicationLocales = android.os.LocaleList.forLanguageTags(code)
                                            }
                                        } else {
                                            val locale = if (code == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(code)
                                            java.util.Locale.setDefault(locale)
                                            val config = android.content.res.Configuration(currentConfig)
                                            config.setLocale(locale)
                                            @Suppress("DEPRECATION")
                                            (context as? android.app.Activity)?.resources?.let { res ->
                                                res.updateConfiguration(config, res.displayMetrics)
                                            }
                                            if (context is android.app.Activity) {
                                                context.recreate()
                                            }
                                        }
                                    }
                                    applyLanguage(code)
                                    onLocaleChanged?.invoke()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedLang == code,
                                onClick = null,
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                            )
                            Spacer(modifier = Modifier.padding(start = 12.dp))
                            Text(name, color = Color.White, fontSize = 16.sp)
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
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
