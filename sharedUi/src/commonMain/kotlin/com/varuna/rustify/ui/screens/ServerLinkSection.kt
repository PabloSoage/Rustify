package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.sync.ServerLink
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "Link my Rustify server": gives the person's own rustify-server this app's Spotify session, so the
 * web interface it serves opens with the account (docs serie-4.x/28 §5 bis). The address is kept;
 * the server's access token is not — it is asked each time, and only the server keeps it.
 */
@Composable
fun ServerLinkSection(spotifyRepository: SpotifyRepository?) {
    val platform = LocalAppPlatform.current
    val prefs = remember { platform.prefs() }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(prefs.getString(KEY_ADDRESS).orEmpty()) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ServerLink.Outcome?>(null) }
    val cookie = spotifyRepository?.linkableCookie()

    Text(
        stringResource(Res.string.server_link_title),
        color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.server_link_desc), color = Color.Gray, fontSize = 12.sp)
            if (cookie == null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.server_link_no_session), color = Color.Gray, fontSize = 12.sp)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text(stringResource(Res.string.server_link_address)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(stringResource(Res.string.server_link_token)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    busy = true
                    outcome = null
                    prefs.putString(KEY_ADDRESS, address.trim())
                    scope.launch {
                        outcome = ServerLink.link(address, token, cookie)
                        if (outcome == ServerLink.Outcome.Linked) token = ""
                        busy = false
                    }
                },
                enabled = !busy && address.isNotBlank() && token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF1DB954), strokeWidth = 2.dp)
                else Text(stringResource(Res.string.server_link_button), color = Color.White, fontSize = 12.sp)
            }
            outcome?.let { result ->
                Spacer(Modifier.height(6.dp))
                val text = when (result) {
                    ServerLink.Outcome.Linked -> stringResource(Res.string.server_link_ok)
                    ServerLink.Outcome.Insecure -> stringResource(Res.string.server_link_insecure)
                    ServerLink.Outcome.BadAddress -> stringResource(Res.string.server_link_bad_address)
                    ServerLink.Outcome.WrongToken -> stringResource(Res.string.server_link_wrong_token)
                    is ServerLink.Outcome.Refused -> stringResource(Res.string.server_link_refused, result.reason)
                    is ServerLink.Outcome.Failed -> stringResource(Res.string.server_link_failed, result.reason)
                }
                Text(text, color = if (result == ServerLink.Outcome.Linked) Color(0xFF1DB954) else Color(0xFFE57373), fontSize = 12.sp)
            }
        }
    }
}

private const val KEY_ADDRESS = "server_link_address"
