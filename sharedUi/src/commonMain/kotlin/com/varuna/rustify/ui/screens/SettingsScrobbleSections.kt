// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsScrobbleSections.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.metrics.LastFm
import com.varuna.rustify.metrics.ListenBrainz
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.settings_lastfm_api_key
import com.varuna.rustify.ui.resources.settings_lastfm_desc
import com.varuna.rustify.ui.resources.settings_lastfm_password
import com.varuna.rustify.ui.resources.settings_lastfm_secret
import com.varuna.rustify.ui.resources.settings_lastfm_user
import com.varuna.rustify.ui.resources.settings_listenbrainz_connect
import com.varuna.rustify.ui.resources.settings_listenbrainz_connected
import com.varuna.rustify.ui.resources.settings_listenbrainz_desc
import com.varuna.rustify.ui.resources.settings_listenbrainz_disconnect
import com.varuna.rustify.ui.resources.settings_listenbrainz_invalid
import com.varuna.rustify.ui.resources.settings_listenbrainz_token
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The two scrobbling accounts, moved from the app's SettingsStorageSections.kt (docs serie-4.x/27,
// phase E). Both platforms scrobble through metrics.Scrobbler, so both offer the accounts.

/**
 * ListenBrainz (`18`): paste the token from the profile's settings page; it is checked against
 * ListenBrainz before it is kept, and the user name shown so a wrong account is obvious.
 */
@Composable
fun ListenBrainzSection() {
    val lb = ListenBrainz
    val prefs = LocalAppPlatform.current.prefs()
    var token by remember { mutableStateOf(prefs.getString(lb.PREF_TOKEN).orEmpty()) }
    var user by remember { mutableStateOf(prefs.getString(lb.PREF_USER)) }
    var checking by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "ListenBrainz",
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
            Text(stringResource(Res.string.settings_listenbrainz_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (user != null) {
                Text(stringResource(Res.string.settings_listenbrainz_connected, user!!), color = Color.White, fontSize = 15.sp)
                TextButton(onClick = {
                    prefs.remove(lb.PREF_TOKEN); prefs.remove(lb.PREF_USER)
                    token = ""; user = null
                }) { Text(stringResource(Res.string.settings_listenbrainz_disconnect), color = Color(0xFFCC2200)) }
            } else {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; invalid = false },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.settings_listenbrainz_token)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (invalid) Text(stringResource(Res.string.settings_listenbrainz_invalid), color = Color(0xFFCC2200), fontSize = 12.sp)
                TextButton(
                    enabled = token.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val name = lb.validate(token)
                            checking = false
                            if (name == null) invalid = true
                            else {
                                prefs.putString(lb.PREF_TOKEN, token.trim()); prefs.putString(lb.PREF_USER, name)
                                user = name
                            }
                        }
                    }
                ) { Text(stringResource(Res.string.settings_listenbrainz_connect), color = Color(0xFF1DB954)) }
            }
        }
    }
}

/** Last.fm scrobbling (`23` §3) with the user's own API key. See [LastFm]. */
@Composable
fun LastFmSection() {
    val lf = LastFm
    val platform = LocalAppPlatform.current
    var user by remember { mutableStateOf(lf.user(platform)) }
    var apiKey by remember { mutableStateOf(lf.apiKey(platform)) }
    var secret by remember { mutableStateOf(lf.secret(platform)) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(24.dp))
    Text("Last.fm", color = Color(0xFF1DB954), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_lastfm_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (user != null) {
                Text(stringResource(Res.string.settings_listenbrainz_connected, user!!), color = Color.White, fontSize = 15.sp)
                TextButton(onClick = { lf.logout(platform); user = null }) {
                    Text(stringResource(Res.string.settings_listenbrainz_disconnect), color = Color(0xFFCC2200))
                }
            } else {
                @Composable
                fun field(value: String, hint: StringResource, secretText: Boolean = false, onChange: (String) -> Unit) =
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onChange(it); error = null },
                        singleLine = true,
                        placeholder = { Text(stringResource(hint)) },
                        visualTransformation = if (secretText) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                    )
                field(apiKey, Res.string.settings_lastfm_api_key) { apiKey = it }
                field(secret, Res.string.settings_lastfm_secret, secretText = true) { secret = it }
                field(name, Res.string.settings_lastfm_user) { name = it }
                field(password, Res.string.settings_lastfm_password, secretText = true) { password = it }
                error?.let { Text(it, color = Color(0xFFCC2200), fontSize = 12.sp) }
                TextButton(
                    enabled = !busy && apiKey.isNotBlank() && secret.isNotBlank() && name.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val why = lf.login(platform, apiKey, secret, name, password)
                            busy = false
                            password = ""
                            if (why == null) user = lf.user(platform) else error = why
                        }
                    }
                ) { Text(stringResource(Res.string.settings_listenbrainz_connect), color = Color(0xFF1DB954)) }
            }
        }
    }
}
