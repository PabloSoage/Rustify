@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("UseKtx")

package com.varuna.rustify.ui.screens

import com.varuna.rustify.ui.screens.CacheStorageSection
import com.varuna.rustify.ui.screens.AdvancedDataSection

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
            LocalMusicSection()
            AudioBackendsSection(context)
            // Right after the chain: it is the same decision ("what plays my music"), even
            // though it cannot live inside the chain itself.
            WebPlayerSection(context)
            // Shared with the desktop (SettingsAudioSourcesSections.kt).
            InvidiousBackendSection()
            DeezerBackendSection()
            SubsonicBackendSection()
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



                CacheStorageSection(onReloadLocalData = { spotifyRepository.reloadLocalUserData() })

                AdvancedDataSection(onNavigateMatchEditor = onNavigateMatchEditor)

                Spacer(modifier = Modifier.height(24.dp))

                // Google Drive sync (shared with the desktop and iOS since 5.x).
                com.varuna.rustify.ui.screens.DriveSyncSection(spotifyRepository, ytmRepository)

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
