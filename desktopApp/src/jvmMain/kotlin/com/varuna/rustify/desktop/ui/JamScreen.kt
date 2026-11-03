// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/JamScreen.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.varuna.rustify.jam.JamSession
import kotlinx.coroutines.launch

/**
 * Jam: one device hosts the queue, the others join with its code and play it in step — phones and
 * computers in the same room, since the broker and the encryption are the core's on both.
 */
@Composable
fun JamSection(jam: JamSession) {
    val ui by jam.ui.collectAsState()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(action: suspend () -> String?) {
        busy = true
        error = null
        scope.launch {
            error = action()
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Jam", style = MaterialTheme.typography.titleLarge)
        when (ui.role) {
            JamSession.Role.NONE -> {
                Text(
                    "Listen together: host the queue, or join someone's with their code. End-to-end " +
                        "encrypted; the broker (${jam.broker}) sees only a hash of the code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.kicked) Text("The host removed you from the Jam.", color = MaterialTheme.colorScheme.error)
                Button(enabled = !busy, onClick = { run { jam.host() } }) { Text("Host a Jam") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        label = { Text("Code") },
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = !busy && code.isNotBlank(), onClick = { run { jam.join(code.trim()) } }) {
                        Text("Join")
                    }
                }
            }
            JamSession.Role.HOST, JamSession.Role.GUEST -> {
                Text(
                    if (ui.role == JamSession.Role.HOST) "Hosting" else "Joined",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(ui.code, style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (ui.connected) "Connected" else "Reconnecting…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.participants.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name + if (p.host) " (host)" else "", modifier = Modifier.weight(1f))
                        if (ui.role == JamSession.Role.HOST && !p.host) {
                            TextButton(onClick = { jam.kick(p.id) }) { Text("Remove") }
                        }
                    }
                }
                Button(onClick = { jam.leave() }) { Text("Leave") }
            }
        }
        (error ?: ui.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
