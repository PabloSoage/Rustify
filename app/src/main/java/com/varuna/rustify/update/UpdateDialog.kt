package com.varuna.rustify.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.varuna.rustify.R
import kotlinx.coroutines.launch
import java.io.File

private sealed class DlState {
    object Idle : DlState()
    data class Downloading(val progress: Float) : DlState()
    data class Ready(val file: File) : DlState()
    // Downloaded, and more than one installer is available → let the user pick (system installer vs.
    // Install With Options / SAI / other Shizuku-adb installer).
    data class ChooseInstaller(val file: File, val installers: List<AppUpdate.Installer>) : DlState()
    data class Failed(val message: String) : DlState()
}

/**
 * E109 — "Update available" sheet: shows the GitHub release changelog and offers a one-tap
 * download+install (ABI-matched APK) with a browser fallback. Reused by the Settings manual check and
 * the app-start auto check.
 */
@Composable
fun UpdateAvailableDialog(info: AppUpdate.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DlState>(DlState.Idle) }

    val downloading = state is DlState.Downloading

    // After a successful download: if more than one installer is available (e.g. the system installer
    // AND "Install With Options"/SAI), let the user pick in-app; otherwise fire the single one / chooser.
    val proceedInstall: (File) -> Unit = { file ->
        val installers = AppUpdate.listInstallers(context, file)
        if (installers.size >= 2) {
            state = DlState.ChooseInstaller(file, installers)
        } else {
            state = DlState.Ready(file)
            installOrPrompt(context, file)
        }
    }

    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    color = Color(0xFF1DB954),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = info.title.ifBlank { info.tag },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))

                // Changelog (scrollable). GitHub markdown shown as lightly-cleaned plain text.
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = cleanMarkdown(info.body).ifBlank { info.tag },
                        color = Color(0xFFDDDDDD),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }

                Spacer(Modifier.height(16.dp))

                when (val s = state) {
                    is DlState.Downloading -> {
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF1DB954),
                                trackColor = Color(0xFF333333)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${(s.progress * 100).toInt()}%",
                                color = Color.Gray, fontSize = 12.sp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = Color(0xFF1DB954), strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.update_downloading), color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                    is DlState.ChooseInstaller -> {
                        Text(
                            stringResource(R.string.update_choose_installer),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        s.installers.forEach { inst ->
                            Button(
                                onClick = { AppUpdate.installWith(context, s.file, inst) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(inst.label, color = Color.White, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        // Fallback: hand off to the full system chooser.
                        TextButton(onClick = { AppUpdate.install(context, s.file) }) {
                            Text(stringResource(R.string.update_other_installer), color = Color(0xFF1DB954))
                        }
                    }
                    is DlState.Failed -> {
                        Text(s.message, color = Color(0xFFE57373), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    else -> {}
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !downloading) {
                        Text(stringResource(R.string.update_cancel), color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))

                    // While picking an installer, the list above is the action — hide the primary button.
                    if (info.apkUrl != null && state !is DlState.ChooseInstaller) {
                        Button(
                            onClick = {
                                val ready = state as? DlState.Ready
                                if (ready != null) {
                                    proceedInstall(ready.file)
                                    return@Button
                                }
                                if (downloading) return@Button
                                scope.launch {
                                    state = DlState.Downloading(-1f)
                                    try {
                                        val file = AppUpdate.download(context, info) { p ->
                                            state = DlState.Downloading(p)
                                        }
                                        proceedInstall(file)
                                    } catch (e: Exception) {
                                        state = DlState.Failed(e.message ?: "Download failed")
                                    }
                                }
                            },
                            enabled = !downloading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            val label = when (state) {
                                is DlState.Ready -> stringResource(R.string.update_install)
                                is DlState.Failed -> stringResource(R.string.update_retry)
                                else -> stringResource(R.string.update_download)
                            }
                            Text(label, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else if (info.apkUrl == null) {
                        // No ABI-matched APK asset: offer the release page instead.
                        Button(
                            onClick = { AppUpdate.openReleasePage(context, info) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(stringResource(R.string.update_open_github), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/** Launch the installer, first routing the user to grant "install unknown apps" if needed. */
private fun installOrPrompt(context: android.content.Context, file: File) {
    if (AppUpdate.canInstall(context)) {
        AppUpdate.install(context, file)
    } else {
        AppUpdate.requestInstallPermission(context)
    }
}

/** Strip HTML comments and collapse markdown heading/emphasis noise for a compact changelog view. */
private fun cleanMarkdown(md: String): String {
    if (md.isBlank()) return md
    return md
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { line ->
            line.trimEnd()
                .replace(Regex("^#{1,6}\\s*"), "")   // headings → plain
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1") // bold
                .replace(Regex("`([^`]+)`"), "$1")   // inline code
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
