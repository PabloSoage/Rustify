package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.Log
import com.varuna.rustify.sync.DriveAuth
import com.varuna.rustify.sync.DriveSync
import com.varuna.rustify.sync.DriveSyncPrefs
import com.varuna.rustify.sync.RustifyBackup
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.LocalAppServices
import com.varuna.rustify.ui.formatDateTime
import com.varuna.rustify.ui.formatTemplate
import com.varuna.rustify.ui.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Google Drive sync in Settings: link, sync now, sync on start, and what gets synced. Moved from the
 * phone's Settings (5.x) with the sign-in behind [DriveAuth], so the desktop and iOS have the same
 * section; where the platform has no [DriveAuth] it is not offered.
 */
@Composable
fun DriveSyncSection(spotifyRepository: SpotifyRepository?, ytmRepository: YtMusicRepository?) {
    val auth = LocalAppServices.current.driveAuth ?: return
    val platform = LocalAppPlatform.current
    val prefs = remember { DriveSyncPrefs.of(platform) }
    val scope = rememberCoroutineScope()
    val syncManager = remember { DriveSync(platform, auth.device, spotifyRepository, ytmRepository) }

    var driveAuthMethod by remember {
        mutableStateOf(prefs.authMethod.takeIf { it in auth.methods } ?: auth.methods.first())
    }
    var driveLinked by remember { mutableStateOf(prefs.isLinked) }
    var driveAutoSync by remember { mutableStateOf(prefs.isAutoSync) }
    var driveLastSync by remember { mutableLongStateOf(prefs.lastSyncMs) }
    var drivePulledFrom by remember { mutableStateOf(prefs.pulledFrom) }
    var driveSyncing by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf("") }

    val driveNeverSynced = stringResource(Res.string.settings_drive_never_synced)
    val driveSyncOkMsg = stringResource(Res.string.settings_drive_sync_ok)
    val driveSyncErrTmpl = stringResource(Res.string.settings_drive_sync_error)
    val driveNotConfiguredMsg = stringResource(Res.string.settings_drive_not_configured)
    val signIn = auth.rememberSignIn(driveAuthMethod)

    fun fail(e: Throwable) {
        driveStatus = formatTemplate(driveSyncErrTmpl, e.message ?: e::class.simpleName ?: "auth")
        platform.toast(driveStatus, long = true)
    }

    // Runs a full sync with an already obtained access token.
    fun runDriveSync(token: String) {
        driveSyncing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { syncManager.syncNow(token) } }
            driveSyncing = false
            result.fold(
                onSuccess = {
                    driveLastSync = prefs.lastSyncMs
                    drivePulledFrom = prefs.pulledFrom
                    driveStatus = driveSyncOkMsg
                    platform.toast(driveSyncOkMsg)
                },
                onFailure = { e -> if (e !is CancellationException) fail(e) }
            )
        }
    }

    // Requests a token (asking the person if needed), then runs [onToken].
    fun driveAuthorizeThen(onToken: (String) -> Unit) {
        if (!auth.isConfigured(driveAuthMethod)) {
            driveStatus = driveNotConfiguredMsg
            platform.toast(driveNotConfiguredMsg, long = true)
            return
        }
        scope.launch {
            val token = runCatching { auth.silentToken(driveAuthMethod) ?: signIn() }
            token.fold(onSuccess = onToken, onFailure = { e -> if (e !is CancellationException) fail(e) })
        }
    }

    Text(
        text = stringResource(Res.string.settings_drive_title),
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
            Text(stringResource(Res.string.settings_drive_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(if (driveLinked) Res.string.settings_drive_linked else Res.string.settings_drive_not_linked),
                color = if (driveLinked) Color(0xFF1DB954) else Color.Gray, fontSize = 12.sp
            )
            val lastSyncText = if (driveLastSync <= 0L) driveNeverSynced else formatDateTime(driveLastSync)
            Text(stringResource(Res.string.settings_drive_last_sync, lastSyncText), color = Color.Gray, fontSize = 12.sp)
            drivePulledFrom?.let { from ->
                Text(stringResource(Res.string.settings_drive_pulled_from, from), color = Color.Gray, fontSize = 12.sp)
            }
            if (driveStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(driveStatus, color = Color.Gray, fontSize = 12.sp)
            }

            // Auth method: Play Services (official build) or browser (any build); a choice only where
            // the platform has both.
            if (auth.methods.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(Res.string.settings_drive_method), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    auth.methods.forEach { method ->
                        Row(
                            modifier = Modifier.weight(1f).clickable { driveAuthMethod = method; prefs.authMethod = method },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = driveAuthMethod == method, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                            )
                            Text(
                                stringResource(if (method == DriveAuth.PLAY) Res.string.settings_drive_method_play else Res.string.settings_drive_method_browser),
                                color = Color.White, fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (driveLinked) {
                            auth.unlink()
                            prefs.isLinked = false
                            driveLinked = false
                            driveStatus = ""
                        } else {
                            driveAuthorizeThen { token ->
                                prefs.isLinked = true; driveLinked = true
                                runDriveSync(token)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(if (driveLinked) Res.string.settings_drive_unlink else Res.string.settings_drive_link),
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
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF1DB954), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(Res.string.settings_drive_sync_now), color = Color.White, fontSize = 12.sp)
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
                    Text(stringResource(Res.string.settings_drive_auto), color = Color.White, fontSize = 14.sp)
                    Text(stringResource(Res.string.settings_drive_auto_desc), color = Color.Gray, fontSize = 12.sp)
                }
                Switch(
                    checked = driveAutoSync,
                    onCheckedChange = { checked -> driveAutoSync = checked; prefs.isAutoSync = checked },
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
            ) { Text(stringResource(Res.string.settings_drive_details), color = Color.White, fontSize = 12.sp) }

            if (showSyncDetails) {
                var cats by remember { mutableStateOf<List<RustifyBackup.Category>>(emptyList()) }
                LaunchedEffect(Unit) { cats = withContext(Dispatchers.IO) { RustifyBackup.summarize(platform, auth.device) } }
                AlertDialog(
                    onDismissRequest = { showSyncDetails = false },
                    containerColor = Color(0xFF1E1E1E),
                    title = { Text(stringResource(Res.string.settings_drive_details), color = Color.White) },
                    text = {
                        Column {
                            Text(stringResource(Res.string.settings_drive_details_desc), color = Color.Gray, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            cats.forEach { c ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(labelOf(c.kind)), color = Color.White, fontSize = 13.sp)
                                    Text(c.count.toString(), color = Color(0xFF1DB954), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { showSyncDetails = false; if (!driveSyncing) driveAuthorizeThen { token -> runDriveSync(token) } },
                            enabled = !driveSyncing
                        ) { Text(stringResource(Res.string.settings_drive_sync_now), color = Color(0xFF1DB954)) }
                    },
                    dismissButton = { TextButton(onClick = { showSyncDetails = false }) { Text(stringResource(Res.string.settings_close), color = Color.Gray) } }
                )
            }
        }
    }
}

private fun labelOf(kind: RustifyBackup.Kind): StringResource = when (kind) {
    RustifyBackup.Kind.MAPPINGS -> Res.string.drive_kind_mappings
    RustifyBackup.Kind.USER_ALTERNATIVES -> Res.string.drive_kind_user_alternatives
    RustifyBackup.Kind.LOCAL_PLAYLISTS -> Res.string.drive_kind_local_playlists
    RustifyBackup.Kind.LOCAL_FAVORITES -> Res.string.drive_kind_local_favorites
    RustifyBackup.Kind.YTM_FAVORITES -> Res.string.drive_kind_ytm_favorites
    RustifyBackup.Kind.YTM_PLAYLISTS -> Res.string.drive_kind_ytm_playlists
    RustifyBackup.Kind.YTM_ALBUMS -> Res.string.drive_kind_ytm_albums
    RustifyBackup.Kind.YTM_ARTISTS -> Res.string.drive_kind_ytm_artists
    RustifyBackup.Kind.METRICS -> Res.string.drive_kind_metrics
    RustifyBackup.Kind.PLACES -> Res.string.drive_kind_places
}

/**
 * Silent sync on app open: only if the account is linked, the "auto-sync" toggle is on, and a token
 * is available WITHOUT asking (a stored refresh, a cached consent). If it needs the person, it waits
 * until they tap "Sync now" in Settings. Never shows anything.
 */
@Composable
fun DriveAutoSync(spotifyRepository: SpotifyRepository?, ytmRepository: YtMusicRepository?) {
    val auth = LocalAppServices.current.driveAuth ?: return
    val platform = LocalAppPlatform.current
    LaunchedEffect(Unit) {
        val prefs = DriveSyncPrefs.of(platform)
        if (!prefs.isLinked || !prefs.isAutoSync) return@LaunchedEffect
        val method = prefs.authMethod.takeIf { it in auth.methods } ?: auth.methods.first()
        if (!auth.isConfigured(method)) return@LaunchedEffect
        runCatching {
            val token = auth.silentToken(method) ?: return@runCatching
            DriveSync(platform, auth.device, spotifyRepository, ytmRepository).syncNow(token)
        }.onFailure { if (it !is CancellationException) Log.w("DriveAutoSync", "auto-sync failed", it) }
    }
}
