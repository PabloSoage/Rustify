// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopLocalDjPanel.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.varuna.rustify.dj.LocalLlmDetector
import com.varuna.rustify.dj.LocalLlmServer
import com.varuna.rustify.dj.LocalLlmSettings
import com.varuna.rustify.dj.LocalModelCatalog
import com.varuna.rustify.dj.LocalModelManager
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.settings_dj_local_delete
import com.varuna.rustify.ui.resources.settings_dj_local_downloading_what
import com.varuna.rustify.ui.resources.settings_dj_local_failed
import com.varuna.rustify.ui.resources.settings_dj_local_get
import com.varuna.rustify.ui.resources.settings_dj_local_installed
import com.varuna.rustify.ui.resources.settings_dj_local_managed
import com.varuna.rustify.ui.resources.settings_dj_local_managed_desc
import com.varuna.rustify.ui.resources.settings_dj_local_model_size
import com.varuna.rustify.ui.resources.settings_dj_local_on_disk
import com.varuna.rustify.ui.resources.settings_dj_local_running
import com.varuna.rustify.ui.resources.settings_dj_local_start
import com.varuna.rustify.ui.resources.settings_dj_local_starting
import com.varuna.rustify.ui.resources.settings_dj_local_stop
import com.varuna.rustify.ui.resources.settings_dj_local_unpacking
import com.varuna.rustify.ui.resources.settings_dj_local_at
import com.varuna.rustify.ui.resources.settings_dj_local_auto
import com.varuna.rustify.ui.resources.settings_dj_local_detecting
import com.varuna.rustify.ui.resources.settings_dj_local_found
import com.varuna.rustify.ui.resources.settings_dj_local_model
import com.varuna.rustify.ui.resources.settings_dj_local_no_models
import com.varuna.rustify.ui.resources.settings_dj_local_none
import com.varuna.rustify.ui.resources.settings_dj_local_not_running
import com.varuna.rustify.ui.resources.settings_dj_local_rescan
import com.varuna.rustify.ui.resources.settings_dj_local_source
import org.jetbrains.compose.resources.stringResource

private val Green = Color(0xFF1DB954)

/**
 * The desktop's panel under the DJ's "local" mode: which model servers are running on this
 * computer, which one to ask and with which of its models, and Rustify's own ([manager]) with its
 * downloads and its state. The phone shows Gemini Nano's here.
 */
@Composable
internal fun DesktopLocalDjPanel(platform: AppPlatform, manager: LocalModelManager) {
    val prefs = remember { platform.prefs() }
    var source by remember { mutableStateOf(LocalLlmSettings.source(prefs)) }
    var model by remember { mutableStateOf(LocalLlmSettings.model(prefs)) }
    var scan by remember { mutableIntStateOf(0) }
    // null while looking.
    var servers by remember { mutableStateOf<List<LocalLlmServer>?>(null) }
    LaunchedEffect(scan) {
        servers = null
        servers = LocalLlmDetector.detect()
    }

    Column {
        val found = servers
        when {
            found == null -> Text(stringResource(Res.string.settings_dj_local_detecting), color = Color.Gray, fontSize = 12.sp)
            found.isEmpty() -> Text(stringResource(Res.string.settings_dj_local_none), color = Color.Gray, fontSize = 12.sp)
            else -> Text(
                stringResource(Res.string.settings_dj_local_found, found.joinToString(", ") { "${it.kind.label} (${it.address})" }),
                color = Green,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(stringResource(Res.string.settings_dj_local_source), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        // Every kind, running or not, so a choice whose server has stopped still shows as chosen.
        val options = listOf(LocalLlmSettings.AUTO to stringResource(Res.string.settings_dj_local_auto)) +
            LocalLlmDetector.PROBED.map { kind ->
                val running = found?.firstOrNull { it.kind == kind }
                kind.code to if (running != null) {
                    stringResource(Res.string.settings_dj_local_at, kind.label, running.address)
                } else {
                    stringResource(Res.string.settings_dj_local_not_running, kind.label)
                }
            } +
            (LocalLlmServer.Kind.MANAGED.code to stringResource(Res.string.settings_dj_local_managed))
        options.forEach { (code, label) ->
            Choice(selected = source == code, label = label) {
                source = code
                prefs.putString(LocalLlmSettings.KEY_SOURCE, code)
            }
        }

        // The models of the server that would be asked.
        val target = found?.let { LocalLlmSettings.pick(source, model, it) }
        val server = found?.firstOrNull { it.kind == target?.kind } ?: found?.firstOrNull { it.kind.code == source }
        if (server != null) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.settings_dj_local_model), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (server.models.isEmpty()) {
                Text(stringResource(Res.string.settings_dj_local_no_models), color = Color.Gray, fontSize = 12.sp)
            }
            server.models.forEach { m ->
                Choice(selected = m == target?.model, label = m) {
                    model = m
                    prefs.putString(LocalLlmSettings.KEY_MODEL, m)
                }
            }
        }

        TextButton(onClick = { scan++ }, enabled = servers != null) {
            Text(stringResource(Res.string.settings_dj_local_rescan), color = Green)
        }

        ManagedModel(prefs, manager)
    }
}

/** Rustify's own llama-server: the model to run, what is on disk, and the download or the server. */
@Composable
private fun ManagedModel(prefs: com.varuna.rustify.platform.KeyValueStore, manager: LocalModelManager) {
    val status by manager.status.collectAsState()
    var chosen by remember { mutableStateOf(LocalLlmSettings.managedModel(prefs)) }
    // What is on disk changes with the downloads; read again whenever the status does.
    val build = remember(status) { manager.runtime() }
    val downloaded = remember(status) { LocalModelCatalog.MODELS.filter { manager.isDownloaded(it) }.toSet() }

    Spacer(Modifier.height(14.dp))
    Text(stringResource(Res.string.settings_dj_local_managed), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(Res.string.settings_dj_local_managed_desc), color = Color.Gray, fontSize = 12.sp)
    val onDisk = stringResource(Res.string.settings_dj_local_on_disk)
    LocalModelCatalog.MODELS.forEach { m ->
        val label = stringResource(Res.string.settings_dj_local_model_size, m.label, formatBytes(m.sizeBytes)) +
            if (m in downloaded) " · $onDisk" else ""
        Choice(selected = m == chosen, label = label) {
            chosen = m
            prefs.putString(LocalLlmSettings.KEY_MANAGED_MODEL, m.id)
        }
    }

    val busy = status is LocalModelManager.Status.Downloading ||
        status is LocalModelManager.Status.Unpacking ||
        status is LocalModelManager.Status.Starting
    // Read into a local: a smart cast does not cross into another module's class.
    when (val s = status) {
        is LocalModelManager.Status.Downloading -> {
            val progress = if (s.total > 0) " ${formatBytes(s.done)} / ${formatBytes(s.total)}" else " ${formatBytes(s.done)}"
            Text(stringResource(Res.string.settings_dj_local_downloading_what, s.what, progress), color = Color.Gray, fontSize = 12.sp)
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = Green,
                    trackColor = Color.Gray.copy(alpha = 0.3f),
                )
            }
        }
        is LocalModelManager.Status.Unpacking ->
            Text(stringResource(Res.string.settings_dj_local_unpacking, s.what), color = Color.Gray, fontSize = 12.sp)
        is LocalModelManager.Status.Starting ->
            Text(stringResource(Res.string.settings_dj_local_starting, s.what), color = Color.Gray, fontSize = 12.sp)
        is LocalModelManager.Status.Running ->
            Text(stringResource(Res.string.settings_dj_local_running, s.address, "${s.model.label}, ${s.backend}"), color = Green, fontSize = 12.sp)
        is LocalModelManager.Status.Failed ->
            Text(stringResource(Res.string.settings_dj_local_failed, s.message), color = Color(0xFFE57373), fontSize = 12.sp)
        LocalModelManager.Status.Idle -> if (build != null) {
            Text(stringResource(Res.string.settings_dj_local_installed, build.tag, build.backend), color = Color.Gray, fontSize = 12.sp)
        }
    }

    val running = status as? LocalModelManager.Status.Running
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            busy -> TextButton(onClick = { manager.cancel() }) { Text(stringResource(Res.string.cancel), color = Color.White) }
            running != null && running.model == chosen ->
                TextButton(onClick = { manager.stop() }) { Text(stringResource(Res.string.settings_dj_local_stop), color = Color.White) }
            else -> Button(
                onClick = { manager.start(chosen) },
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
            ) {
                Text(
                    stringResource(if (build != null && chosen in downloaded) Res.string.settings_dj_local_start else Res.string.settings_dj_local_get),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (chosen in downloaded && !busy && running?.model != chosen) {
            TextButton(onClick = { manager.deleteModel(chosen) }) {
                Text(stringResource(Res.string.settings_dj_local_delete), color = Color.Gray)
            }
        }
    }
}

/** "1.1 GB", "34 MB": a download's size the way a person reads it. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
    else -> "${bytes / 1_000L} KB"
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Green))
        Spacer(Modifier.padding(start = 8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
