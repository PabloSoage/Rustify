// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppShell.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.desktop_screen_not_here
import com.varuna.rustify.ui.resources.desktop_settings_account
import com.varuna.rustify.ui.resources.desktop_sign_in_cookie
import com.varuna.rustify.ui.resources.desktop_sign_in_help
import com.varuna.rustify.ui.resources.settings_logout
import com.varuna.rustify.ui.resources.settings_title
import com.varuna.rustify.ui.resources.spotify_sign_in
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val Background = Color(0xFF121212)
private val Accent = Color(0xFF1DB954)

/**
 * [AppShell] on the desktop. Signing in takes the `sp_dc` cookie pasted by hand until the embedded
 * browser lands (docs serie-4.x/27, phase H); the screens not moved yet say so, with a way back, and
 * Settings keeps the account until the phone's Settings moves (phase E).
 */
class DesktopAppShell : AppShell {
    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        var cookie by remember { mutableStateOf("") }
        Page(stringResource(Res.string.spotify_sign_in), onBack = onCancel) {
            Text(stringResource(Res.string.desktop_sign_in_help), color = Color.LightGray)
            OutlinedTextField(
                value = cookie,
                onValueChange = { cookie = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.desktop_sign_in_cookie)) },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                enabled = cookie.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                onClick = { onLogin(cookie) },
            ) { Text(stringResource(Res.string.spotify_sign_in)) }
            Button(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
        }
    }

    override fun onLoginFailed() = Unit

    @Composable
    override fun StartupEffects(host: ShellHost) = Unit

    @Composable
    override fun SystemBars(landscape: Boolean) = Unit

    override suspend fun resolveMapsLink(url: String): Screen.TravelWithDestination? = null

    @Composable
    override fun PlatformScreen(screen: Screen, host: ShellHost) {
        if (screen is Screen.Settings) {
            val scope = rememberCoroutineScope()
            Page(stringResource(Res.string.settings_title), onBack = host.back) {
                Text(stringResource(Res.string.desktop_settings_account), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (host.isSignedIn) {
                    Button(onClick = {
                        scope.launch {
                            host.spotifyRepo.logout()
                            host.signedOut()
                        }
                    }) { Text(stringResource(Res.string.settings_logout)) }
                } else {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        onClick = host.signIn,
                    ) { Text(stringResource(Res.string.spotify_sign_in)) }
                }
                Text(stringResource(Res.string.desktop_screen_not_here), color = Color.Gray)
            }
            return
        }
        Page("", onBack = host.back) {
            Text(stringResource(Res.string.desktop_screen_not_here), color = Color.LightGray)
        }
    }

    @Composable
    private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Background).padding(16.dp)) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                if (title.isNotEmpty()) Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}
