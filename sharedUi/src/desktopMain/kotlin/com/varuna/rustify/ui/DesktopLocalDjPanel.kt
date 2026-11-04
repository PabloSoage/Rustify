// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopLocalDjPanel.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.varuna.rustify.ui.resources.Res
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
 * computer, which one to ask and with which of its models. The phone shows Gemini Nano's here.
 */
@Composable
internal fun DesktopLocalDjPanel(platform: AppPlatform) {
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
            LocalLlmServer.Kind.entries.map { kind ->
                val running = found?.firstOrNull { it.kind == kind }
                kind.code to if (running != null) {
                    stringResource(Res.string.settings_dj_local_at, kind.label, running.address)
                } else {
                    stringResource(Res.string.settings_dj_local_not_running, kind.label)
                }
            }
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
    }
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
