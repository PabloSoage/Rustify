// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsCacheSections.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.varuna.rustify.bridge.LocalPlaylist
import com.varuna.rustify.bridge.MatchStore
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.AppServices
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.LocalAppServices
import com.varuna.rustify.ui.byteSize
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.isFolder
import com.varuna.rustify.ui.listChildren
import com.varuna.rustify.ui.readAllText
import com.varuna.rustify.ui.rememberOpenFile
import com.varuna.rustify.ui.rememberSaveFile
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.settings_advanced_data
import com.varuna.rustify.ui.resources.settings_cache_audio_cleared
import com.varuna.rustify.ui.resources.settings_cache_images_cleared
import com.varuna.rustify.ui.resources.settings_cache_max
import com.varuna.rustify.ui.resources.settings_cache_max_hint
import com.varuna.rustify.ui.resources.settings_cache_storage
import com.varuna.rustify.ui.resources.settings_cleanup
import com.varuna.rustify.ui.resources.settings_edit_matches
import com.varuna.rustify.ui.resources.settings_cleanup_audio
import com.varuna.rustify.ui.resources.settings_cleanup_images
import com.varuna.rustify.ui.resources.settings_export
import com.varuna.rustify.ui.resources.settings_export_error
import com.varuna.rustify.ui.resources.settings_export_local_empty
import com.varuna.rustify.ui.resources.settings_export_success
import com.varuna.rustify.ui.resources.settings_import
import com.varuna.rustify.ui.resources.settings_import_error
import com.varuna.rustify.ui.resources.settings_import_success
import com.varuna.rustify.ui.resources.settings_local_data
import com.varuna.rustify.ui.resources.settings_no_mappings_export
import com.varuna.rustify.ui.resources.settings_youtube_mappings
import com.varuna.rustify.ui.toast
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.optStringOrNull
import com.varuna.rustify.util.parseArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource

fun AppFile.treeSize(): Long {
    if (!fileExists()) return 0L
    if (isFolder()) {
        return listChildren().sumOf { it.treeSize() }
    }
    return byteSize()
}

fun AppFile.deleteTree(): Boolean {
    if (!fileExists()) return true
    if (isFolder()) {
        listChildren().forEach { it.deleteTree() }
    }
    return deleteFile()
}

object LocalDataBackup {
    const val SCHEMA = "rustify-local-user-data"
    const val VERSION = 1

    /** Produces the JSON container string for export, or null if no local data exists. */
    fun exportLocalUserData(filesDir: AppFile): String? {
        val playlistsFile = filesDir.child("local_playlists.json")
        val favoritesFile = filesDir.child("local_favorites.json")
        if (!playlistsFile.fileExists() && !favoritesFile.fileExists()) return null

        val playlistsJson = if (playlistsFile.fileExists()) {
            runCatching { Json.parseToJsonElement(playlistsFile.readAllText()).jsonArray }.getOrDefault(JsonArray(emptyList()))
        } else JsonArray(emptyList())

        val favoritesJson = if (favoritesFile.fileExists()) {
            runCatching { Json.parseToJsonElement(favoritesFile.readAllText()).jsonArray }.getOrDefault(JsonArray(emptyList()))
        } else JsonArray(emptyList())

        return buildJsonObject {
            put("schema", SCHEMA)
            put("version", VERSION)
            put("exportedAt", currentTimeMillis())
            put("playlists", playlistsJson)
            put("favorites", favoritesJson)
        }.toString()
    }

    /**
     * Validates [rawJson] using real models and writes playlists & favorites atomically.
     * Returns true on success. If [rawJson] is invalid, does NOT modify existing files and returns false.
     */
    fun importLocalUserData(filesDir: AppFile, rawJson: String, onReload: () -> Unit = {}): Boolean {
        return try {
            val root = Json.parseToJsonElement(rawJson).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.content
            if (schema != SCHEMA) return false
            val playlistsElem = root["playlists"] as? JsonArray ?: return false
            val favoritesElem = root["favorites"] as? JsonArray ?: return false

            // Validate playlists against real model: decodeWire<List<LocalPlaylist>>
            // If any item is not a valid LocalPlaylist (e.g. [1, 2, 3]), decodeWire throws.
            decodeWire<List<LocalPlaylist>>(playlistsElem.toString())

            // Validate favorites: each item must be a string ID (SpotifyRepository.kt:309)
            for (elem in favoritesElem) {
                val prim = elem as? JsonPrimitive ?: error("not a string ID: $elem")
                if (!prim.isString) error("not a string ID: $elem")
            }
            decodeWire<List<String>>(favoritesElem.toString())

            // Write verified payloads atomically
            filesDir.child("local_playlists.json").writeTextAtomically(playlistsElem.toString())
            filesDir.child("local_favorites.json").writeTextAtomically(favoritesElem.toString())
            onReload()
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** The picker's answer as a toast: nothing when it was cancelled ([ok] is null only then). */
private fun AppPlatform.toastImport(ok: Boolean?) {
    when (ok) {
        null -> Unit
        true -> toast(Res.string.settings_import_success, long = true)
        false -> toast(Res.string.settings_import_error)
    }
}

private fun AppPlatform.toastExport(result: Result<Unit>?) {
    if (result != null) toast(if (result.isSuccess) Res.string.settings_export_success else Res.string.settings_export_error)
}

/**
 * The local data backup and what the caches hold. The audio cache is ExoPlayer's, so it only exists
 * where [AppServices.Feature.AUDIO_CACHE] says; the images are Coil's on every platform
 * ([newRustifyImageLoader]), and that is what the size limit applies to there.
 */
@Composable
fun CacheStorageSection(onReloadLocalData: () -> Unit = { SpotifyRepository.instance?.reloadLocalUserData() }) {
    val platform = LocalAppPlatform.current
    val hasAudioCache = LocalAppServices.current.can(AppServices.Feature.AUDIO_CACHE)
    val platformContext = LocalPlatformContext.current
    val coroutineScope = rememberCoroutineScope()

    var audioCacheSize by remember { mutableLongStateOf(0L) }
    var imageCacheSize by remember { mutableLongStateOf(0L) }

    fun updateCacheSizes() {
        coroutineScope.launch(Dispatchers.IO) {
            val aSize = if (hasAudioCache) platform.cacheDir.child("audio_cache").treeSize() else 0L
            val iSize = SingletonImageLoader.get(platformContext).diskCache?.size ?: 0L
            withContext(Dispatchers.Main) {
                audioCacheSize = aSize
                imageCacheSize = iSize
            }
        }
    }

    LaunchedEffect(Unit) {
        updateCacheSizes()
    }

    val exportLocalLauncher = rememberSaveFile("application/json") { platform.toastExport(it) }
    val importLocalLauncher = rememberOpenFile(listOf("application/json", "*/*"), read = { _, bytes ->
        LocalDataBackup.importLocalUserData(platform.filesDir, bytes.decodeToString(), onReload = onReloadLocalData)
    }) { result ->
        platform.toastImport(result?.let { it.getOrNull() == true })
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(Res.string.settings_cache_storage),
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
                stringResource(Res.string.settings_local_data),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val exported = LocalDataBackup.exportLocalUserData(platform.filesDir)
                            withContext(Dispatchers.Main) {
                                if (exported == null) {
                                    platform.toast(Res.string.settings_export_local_empty)
                                } else {
                                    exportLocalLauncher("rustify_local_data.json") { it.write(exported.encodeToByteArray()) }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(Res.string.settings_export), color = Color.White, fontSize = 12.sp) }
                Button(
                    onClick = importLocalLauncher,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(Res.string.settings_import), color = Color.White, fontSize = 12.sp) }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(Res.string.settings_cleanup), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val totalSizeMb = (audioCacheSize + imageCacheSize) / (1024 * 1024)
                Text("$totalSizeMb MB", color = Color.Gray, fontSize = 14.sp)
            }
            if (hasAudioCache) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Audio: ${audioCacheSize / (1024 * 1024)} MB • Images: ${imageCacheSize / (1024 * 1024)} MB", color = Color.Gray, fontSize = 12.sp)
            }

            // Configurable maximum cache size.
            Spacer(modifier = Modifier.height(12.dp))
            var cacheMaxMb by remember { mutableIntStateOf(platform.prefs().getInt("cache_max_mb", 500)) }
            Text(stringResource(Res.string.settings_cache_max, cacheMaxMb), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Slider(
                value = cacheMaxMb.toFloat(),
                onValueChange = { cacheMaxMb = ((it / 50).toInt() * 50).coerceIn(100, 4096) },
                onValueChangeFinished = { platform.prefs().putInt("cache_max_mb", cacheMaxMb) },
                valueRange = 100f..4096f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF1DB954), activeTrackColor = Color(0xFF1DB954))
            )
            Text(stringResource(Res.string.settings_cache_max_hint), color = Color.Gray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hasAudioCache) {
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                platform.cacheDir.child("audio_cache").deleteTree()
                                withContext(Dispatchers.Main) {
                                    platform.toast(Res.string.settings_cache_audio_cleared)
                                    updateCacheSizes()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.settings_cleanup_audio), color = Color.White)
                    }
                }
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            // Through the cache, which is open: its folder pulled from under it is what it would not expect.
                            SingletonImageLoader.get(platformContext).diskCache?.clear()
                            withContext(Dispatchers.Main) {
                                platform.toast(Res.string.settings_cache_images_cleared)
                                updateCacheSizes()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.settings_cleanup_images), color = Color.White)
                }
            }
        }
    }
}

/** The matches: export, import and, where Settings has no other way there, the editor's button. */
@Composable
fun AdvancedDataSection(onNavigateMatchEditor: (() -> Unit)? = null) {
    val platform = LocalAppPlatform.current
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberSaveFile("application/json") { platform.toastExport(it) }
    val importLauncher = rememberOpenFile(listOf("application/json", "*/*"), read = { _, bytes ->
        MatchStore.importAll(platform.filesDir, bytes.decodeToString())
    }) { result ->
        platform.toastImport(result?.let { it.getOrNull() == true })
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(Res.string.settings_advanced_data),
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
                stringResource(Res.string.settings_youtube_mappings),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val map = MatchStore.readAll(platform.filesDir)
                            withContext(Dispatchers.Main) {
                                if (map.isEmpty()) {
                                    platform.toast(Res.string.settings_no_mappings_export)
                                } else {
                                    val json = Json.encodeToString(map)
                                    exportLauncher("youtube_mappings.json") { it.write(json.encodeToByteArray()) }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.settings_export), color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = importLauncher,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.settings_import), color = Color.White, fontSize = 12.sp)
                }
            }
            if (onNavigateMatchEditor != null) {
                Spacer(modifier = Modifier.height(8.dp))
                // Structured editor: named list with edit/preview/delete and manual add.
                Button(
                    onClick = onNavigateMatchEditor,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.settings_edit_matches), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
