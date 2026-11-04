// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopSettingsSections.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.desktop_logs_desc
import com.varuna.rustify.ui.resources.desktop_update_package
import com.varuna.rustify.ui.resources.settings_diagnostics
import com.varuna.rustify.ui.resources.settings_view_logs
import com.varuna.rustify.ui.resources.update_available_title
import com.varuna.rustify.ui.resources.update_cancel
import com.varuna.rustify.ui.resources.update_check
import com.varuna.rustify.ui.resources.update_check_failed
import com.varuna.rustify.ui.resources.update_check_on_start
import com.varuna.rustify.ui.resources.update_check_on_start_desc
import com.varuna.rustify.ui.resources.update_checking
import com.varuna.rustify.ui.resources.update_open_github
import com.varuna.rustify.ui.resources.update_up_to_date
import com.varuna.rustify.ui.screens.SettingSwitchRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val Green = Color(0xFF1DB954)
private val CardColor = Color(0xFF1E1E1E)

/** The desktop's own settings. */
object DesktopSettings {
    /** The phone's key, so the switch means the same on both. */
    const val CHECK_UPDATES_ON_START = "check_updates_on_start"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/**
 * Advanced: the update check, as on the phone (check on launch, check now), ending in the release
 * page instead of an install.
 */
@Composable
internal fun DesktopUpdatesSection() {
    val platform = LocalAppPlatform.current
    val prefs = platform.prefs()
    var checkOnStart by remember { mutableStateOf(prefs.getBoolean(DesktopSettings.CHECK_UPDATES_ON_START, true)) }
    val check = rememberUpdateCheck()
    check.found?.let { info -> DesktopUpdateDialog(info, onDismiss = check::dismiss) }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(Res.string.update_check))
    SectionCard {
        SettingSwitchRow(
            title = stringResource(Res.string.update_check_on_start),
            desc = stringResource(Res.string.update_check_on_start_desc),
            checked = checkOnStart,
            onChange = {
                checkOnStart = it
                prefs.putBoolean(DesktopSettings.CHECK_UPDATES_ON_START, it)
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = check::start,
            enabled = !check.checking,
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (check.checking) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.update_checking), color = Color.Black, fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(Res.string.update_check), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Advanced: the log viewer, the phone's Diagnostics. There is no capture switch: the desktop always
 * keeps what it logs ([com.varuna.rustify.util.DesktopLogBook]), in memory.
 */
@Composable
internal fun DesktopDiagnosticsSection(onViewLogs: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(Res.string.settings_diagnostics))
    SectionCard {
        Text(stringResource(Res.string.desktop_logs_desc), color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onViewLogs,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_view_logs), color = Color.White)
        }
    }
}

/** Settings' top bar: the phone's refresh button, which checks for an update. */
@Composable
internal fun DesktopUpdateAction() {
    val check = rememberUpdateCheck()
    check.found?.let { info -> DesktopUpdateDialog(info, onDismiss = check::dismiss) }
    if (check.checking) {
        CircularProgressIndicator(color = Green, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 4.dp))
        Spacer(Modifier.width(8.dp))
    } else {
        IconButton(onClick = check::start) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.update_check), tint = Color.White)
        }
    }
}

/**
 * The check on launch, silent unless there is something new, as the phone's; not over the sign-in,
 * where it waits until that closes. Not for a run from Gradle, which has no version to compare.
 */
@Composable
internal fun DesktopStartupUpdateCheck(host: ShellHost) {
    val platform = LocalAppPlatform.current
    var pending by remember { mutableStateOf<DesktopUpdate.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        if (platform.appVersion.isBlank()) return@LaunchedEffect
        if (!platform.prefs().getBoolean(DesktopSettings.CHECK_UPDATES_ON_START, true)) return@LaunchedEffect
        pending = runCatching { DesktopUpdate.check(platform.appVersion) }
            .onFailure { Log.w("DesktopUpdate", "the check on launch failed: ${it.message}") }
            .getOrNull()
    }
    pending?.let { info ->
        if (!host.isSigningIn) DesktopUpdateDialog(info, onDismiss = { pending = null })
    }
}

/** One manual check: its progress, what it found, and the toasts for "nothing new" and "failed". */
private class UpdateCheck(private val scope: CoroutineScope, private val platform: AppPlatform) {
    var checking by mutableStateOf(false)
        private set
    var found by mutableStateOf<DesktopUpdate.UpdateInfo?>(null)
        private set

    fun start() {
        if (checking) return
        checking = true
        scope.launch {
            val result = runCatching { DesktopUpdate.check(platform.appVersion) }
            checking = false
            result.onSuccess { info ->
                if (info != null) found = info else platform.toast(Res.string.update_up_to_date)
            }.onFailure {
                Log.w("DesktopUpdate", "the check failed: ${it.message}")
                platform.toast(Res.string.update_check_failed)
            }
        }
    }

    fun dismiss() {
        found = null
    }
}

@Composable
private fun rememberUpdateCheck(): UpdateCheck {
    val platform = LocalAppPlatform.current
    val scope = rememberCoroutineScope()
    return remember(scope, platform) { UpdateCheck(scope, platform) }
}

/** What is new, and the way to it: the release page, in the browser. */
@Composable
internal fun DesktopUpdateDialog(info: DesktopUpdate.UpdateInfo, onDismiss: () -> Unit) {
    val platform = LocalAppPlatform.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = {
            Column {
                Text(stringResource(Res.string.update_available_title), color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(info.title.ifBlank { info.tag }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column {
                Text(
                    text = DesktopUpdate.plainNotes(info.body).ifBlank { info.tag },
                    color = Color(0xFFDDDDDD),
                    fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                )
                info.asset?.let { asset ->
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.desktop_update_package, asset), color = Color.Gray, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    platform.openUrl(info.htmlUrl)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(stringResource(Res.string.update_open_github), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.update_cancel), color = Color.Gray) }
        },
    )
}
