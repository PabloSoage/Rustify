// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsLocalMusicSections.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.ArtistSeparators
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.folderLabel
import com.varuna.rustify.ui.rememberPickFolder
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.enable_ytm_music
import com.varuna.rustify.ui.resources.enable_ytm_music_desc
import com.varuna.rustify.ui.resources.settings_add_folder
import com.varuna.rustify.ui.resources.settings_added_folders
import com.varuna.rustify.ui.resources.settings_artist_separators
import com.varuna.rustify.ui.resources.settings_artist_separators_desc
import com.varuna.rustify.ui.resources.settings_download_dir
import com.varuna.rustify.ui.resources.settings_enable_local_music
import com.varuna.rustify.ui.resources.settings_enable_local_music_desc
import com.varuna.rustify.ui.resources.settings_excluded_folders
import com.varuna.rustify.ui.resources.settings_local_covers_fullres
import com.varuna.rustify.ui.resources.settings_local_covers_fullres_desc
import com.varuna.rustify.ui.resources.settings_local_music
import com.varuna.rustify.ui.resources.settings_match_local_first
import com.varuna.rustify.ui.resources.settings_match_local_first_desc
import com.varuna.rustify.ui.resources.settings_min_duration
import com.varuna.rustify.ui.resources.settings_min_duration_desc
import com.varuna.rustify.ui.resources.settings_min_duration_off
import com.varuna.rustify.ui.resources.settings_no_dir_configured
import com.varuna.rustify.ui.resources.settings_no_folder_configured
import com.varuna.rustify.ui.resources.settings_remove
import com.varuna.rustify.ui.resources.settings_restore_folder
import com.varuna.rustify.ui.resources.settings_select_folder
import com.varuna.rustify.ui.resources.settings_ytm_scraper
import com.varuna.rustify.ui.resources.settings_ytm_scraper_off
import com.varuna.rustify.ui.resources.settings_ytm_scraper_on
import org.jetbrains.compose.resources.stringResource

// Local music (in "Audio and backends"): enable, match local first, high-res cover art, YouTube
// Music, use scraper, and added folders. Self-contained (its own state and picker). The folders are
// tree URIs on Android and paths on the desktop (rememberPickFolder), under the same key.
@Composable
fun LocalMusicSection() {
    val prefs = LocalAppPlatform.current.prefs()
    val green = Color(0xFF1DB954)
    var enableLocalMusic by remember { mutableStateOf(prefs.getBoolean("enable_local_music", true)) }
    var matchLocalFirst by remember { mutableStateOf(prefs.getBoolean("settings_match_local_first", false)) }
    var coversFullRes by remember { mutableStateOf(prefs.getBoolean("settings_local_covers_full_res", true)) }
    var enableYtmMusic by remember { mutableStateOf(prefs.getBoolean("enable_ytm_music", true)) }
    var ytmScraper by remember { mutableStateOf((prefs.getString("ytm_search_mode") ?: "api") == "scraper") }
    var localMusicDirs by remember { mutableStateOf(prefs.getStringSet("local_music_directories", emptySet())) }
    var excludedFolders by remember { mutableStateOf(prefs.getStringSet(SpotifyRepository.LOCAL_EXCLUDED_FOLDERS, emptySet())) }
    var minDurationS by remember { mutableIntStateOf(prefs.getInt(SpotifyRepository.LOCAL_MIN_DURATION_S, 0)) }
    var separators by remember { mutableStateOf(prefs.getString(ArtistSeparators.PREF) ?: ArtistSeparators.DEFAULT) }
    val addDir = rememberPickFolder(write = false) { dir ->
        if (dir != null) {
            val newSet = localMusicDirs.toMutableSet().apply { add(dir) }
            localMusicDirs = newSet
            prefs.putStringSet("local_music_directories", newSet)
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.settings_local_music), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingSwitchRow(stringResource(Res.string.settings_enable_local_music), stringResource(Res.string.settings_enable_local_music_desc), enableLocalMusic) {
                enableLocalMusic = it; prefs.putBoolean("enable_local_music", it)
            }
            if (enableLocalMusic) {
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(Res.string.settings_match_local_first), stringResource(Res.string.settings_match_local_first_desc), matchLocalFirst) {
                    matchLocalFirst = it; prefs.putBoolean("settings_match_local_first", it)
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(Res.string.settings_local_covers_fullres), stringResource(Res.string.settings_local_covers_fullres_desc), coversFullRes) {
                    coversFullRes = it; prefs.putBoolean("settings_local_covers_full_res", it)
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(stringResource(Res.string.enable_ytm_music), stringResource(Res.string.enable_ytm_music_desc), enableYtmMusic) {
                    enableYtmMusic = it; prefs.putBoolean("enable_ytm_music", it)
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(
                    stringResource(Res.string.settings_ytm_scraper),
                    if (ytmScraper) stringResource(Res.string.settings_ytm_scraper_on) else stringResource(Res.string.settings_ytm_scraper_off),
                    ytmScraper
                ) {
                    ytmScraper = it; prefs.putString("ytm_search_mode", if (it) "scraper" else "api")
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.settings_added_folders), color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                if (localMusicDirs.isEmpty()) {
                    Text(stringResource(Res.string.settings_no_folder_configured), color = Color.Gray, fontSize = 14.sp)
                } else {
                    localMusicDirs.forEach { dir ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(folderLabel(dir), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(stringResource(Res.string.settings_remove), color = Color.Red, fontSize = 12.sp, modifier = Modifier.clickable {
                                val newSet = localMusicDirs.toMutableSet().apply { remove(dir) }
                                localMusicDirs = newSet
                                prefs.putStringSet("local_music_directories", newSet)
                            }.padding(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = addDir, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                    Text(stringResource(Res.string.settings_add_folder), color = Color.White)
                }
                if (excludedFolders.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(Res.string.settings_excluded_folders), color = Color.Gray, fontSize = 12.sp)
                    excludedFolders.sorted().forEach { path ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(path.removePrefix("/storage/emulated/0"), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(stringResource(Res.string.settings_restore_folder), color = green, fontSize = 12.sp, modifier = Modifier.clickable {
                                val newSet = excludedFolders - path
                                excludedFolders = newSet
                                prefs.putStringSet(SpotifyRepository.LOCAL_EXCLUDED_FOLDERS, newSet)
                                SpotifyRepository.instance?.scanLocalMusic()
                            }.padding(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.settings_min_duration), color = Color.White, fontSize = 16.sp)
                Text(stringResource(Res.string.settings_min_duration_desc), color = Color.Gray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(0, 10, 30, 60).forEach { s ->
                        FilterChip(
                            selected = minDurationS == s,
                            onClick = {
                                minDurationS = s
                                prefs.putInt(SpotifyRepository.LOCAL_MIN_DURATION_S, s)
                                SpotifyRepository.instance?.scanLocalMusic()
                            },
                            label = { Text(if (s == 0) stringResource(Res.string.settings_min_duration_off) else "$s s") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = green, selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.settings_artist_separators), color = Color.White, fontSize = 16.sp)
                Text(stringResource(Res.string.settings_artist_separators_desc), color = Color.Gray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    ArtistSeparators.CHOICES.forEach { c ->
                        FilterChip(
                            selected = c in separators,
                            onClick = {
                                separators = if (c in separators) separators.replace(c.toString(), "") else separators + c
                                prefs.putString(ArtistSeparators.PREF, separators)
                                SpotifyRepository.instance?.scanLocalMusic()
                            },
                            label = { Text(c.toString()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = green, selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}

// The standard downloads folder (in the Downloads category): a tree URI on Android, a path on the
// desktop, under the same key. [extra] is what a platform adds below the button (Android: MP3).
@Composable
fun DownloadFolderSection(extra: @Composable () -> Unit = {}) {
    val prefs = LocalAppPlatform.current.prefs()
    val green = Color(0xFF1DB954)
    var downloadDir by remember { mutableStateOf(prefs.getString("download_directory")) }
    val picker = rememberPickFolder(write = true) { dir ->
        if (dir != null) {
            downloadDir = dir
            prefs.putString("download_directory", dir)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(Res.string.settings_download_dir), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val dir = downloadDir
            Text(
                if (dir != null) folderLabel(dir) else stringResource(Res.string.settings_no_dir_configured),
                color = Color.Gray, fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = picker, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                Text(stringResource(Res.string.settings_select_folder), color = Color.White)
            }
            extra()
        }
    }
}
