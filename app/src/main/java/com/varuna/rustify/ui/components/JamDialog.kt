package com.varuna.rustify.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.player.Jam
import kotlinx.coroutines.launch

/** Start, join or run a Jam (`23` §4). See [Jam]. */
@Composable
fun JamDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ui by Jam.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val green = Color(0xFF1DB954)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text(stringResource(R.string.jam_title), color = Color.White) },
        text = {
            Column {
                when (ui.role) {
                    Jam.Role.NONE -> {
                        Text(stringResource(R.string.jam_desc), color = Color.Gray, fontSize = 13.sp)
                        if (ui.kicked) Text(stringResource(R.string.jam_kicked), color = Color(0xFFFFB74D), fontSize = 13.sp)
                        ui.error?.let { Text(stringResource(R.string.jam_failed, it), color = Color(0xFFCC2200), fontSize = 12.sp) }
                        Spacer(Modifier.height(12.dp))
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { Jam.host(context); busy = false }
                        }) { Text(stringResource(R.string.jam_start), color = green) }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.uppercase() },
                            singleLine = true,
                            placeholder = { Text("XXXX-XXXX-XXXX") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(enabled = !busy && code.count { it.isLetterOrDigit() } == 12, onClick = {
                            busy = true
                            scope.launch { Jam.join(context, code); busy = false }
                        }) { Text(stringResource(R.string.jam_join), color = green) }
                        if (busy) CircularProgressIndicator(color = green, modifier = Modifier.padding(8.dp))
                    }
                    else -> {
                        Text(
                            stringResource(if (ui.role == Jam.Role.HOST) R.string.jam_hosting else R.string.jam_joined),
                            color = Color.Gray, fontSize = 13.sp
                        )
                        Text(ui.code, color = green, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        if (!ui.connected) Text(stringResource(R.string.jam_reconnecting), color = Color(0xFFFFB74D), fontSize = 12.sp)
                        if (ui.role == Jam.Role.HOST) {
                            val invite = stringResource(R.string.jam_invite, ui.code)
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite), null)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text(stringResource(R.string.jam_share), color = green) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.jam_people, ui.participants.size), color = Color.White, fontSize = 14.sp)
                        ui.participants.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    p.name + if (p.host) " · " + stringResource(R.string.jam_host) else "",
                                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)
                                )
                                if (ui.role == Jam.Role.HOST && !p.host) {
                                    TextButton(onClick = { Jam.kick(p.id) }) { Text(stringResource(R.string.jam_remove), color = Color(0xFFCC2200)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (ui.role != Jam.Role.NONE) {
                TextButton(onClick = { Jam.leave() }) { Text(stringResource(R.string.jam_leave), color = Color(0xFFCC2200)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jam_close), color = Color.White) } }
    )
}
