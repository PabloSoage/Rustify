// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebAppShell.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.desktop_sign_in_cookie
import com.varuna.rustify.ui.resources.desktop_sign_in_help
import com.varuna.rustify.ui.resources.feature_not_on_platform
import com.varuna.rustify.ui.resources.spotify_sign_in
import org.jetbrains.compose.resources.stringResource

private val Background = Color(0xFF121212)
private val Accent = Color(0xFF1DB954)

/**
 * [AppShell] in a browser. The Spotify cookie cannot be read from a page of another site (docs
 * serie-4.x/28 §5 bis), so signing in is pasting it; the screens no browser can have say so.
 */
class WebAppShell(private val onLanguage: (String) -> Unit) : AppShell {
    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        var cookie by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().background(Background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel), color = Accent) }
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
        Column(
            Modifier.fillMaxSize().background(Background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.feature_not_on_platform), color = Color.LightGray)
            TextButton(onClick = host.back) { Text(stringResource(Res.string.cancel), color = Accent) }
        }
    }

    // What a page can act on: effects run in the player, which here is the browser's.
    @Composable
    override fun SettingsSections(slot: SettingsSlot, host: ShellHost) {
        when (slot) {
            SettingsSlot.GENERAL -> {
                com.varuna.rustify.ui.screens.CacheStorageSection()
                com.varuna.rustify.ui.screens.AdvancedDataSection()
            }
            SettingsSlot.AUDIO -> Unit
            SettingsSlot.GENERAL_DJ -> com.varuna.rustify.ui.screens.DjSettingsSection()
            SettingsSlot.AUDIO_SOURCES -> {
                com.varuna.rustify.ui.screens.AudioSourcesOrderSection()
                com.varuna.rustify.ui.screens.AutoplayRadioSection()
                com.varuna.rustify.ui.screens.BlocklistSection()
                com.varuna.rustify.ui.screens.PreviousButtonSection()
                com.varuna.rustify.ui.screens.ShuffleSection()
                com.varuna.rustify.ui.screens.DataExportSection()
                com.varuna.rustify.ui.screens.ListenBrainzSection()
                com.varuna.rustify.ui.screens.LastFmSection()
                com.varuna.rustify.ui.screens.InvidiousBackendSection()
                com.varuna.rustify.ui.screens.DeezerBackendSection()
                com.varuna.rustify.ui.screens.SubsonicBackendSection()
            }
            SettingsSlot.DOWNLOADS, SettingsSlot.INTEGRATIONS ->
                Text(stringResource(Res.string.feature_not_on_platform), color = Color.Gray)
            SettingsSlot.ADVANCED -> {
                com.varuna.rustify.ui.screens.DiagnosticsSection(onViewLogs = { host.navigate(Screen.LogViewer) })
                com.varuna.rustify.ui.screens.SpotifyHashInspectorSection()
            }
        }
    }

    @Composable
    override fun EasterEggPage(url: String, onClose: () -> Unit) {
        val platform = LocalAppPlatform.current
        LaunchedEffect(url) {
            platform.openUrl(url)
            onClose()
        }
    }

    @Composable
    override fun SettingsActions(host: ShellHost) = Unit

    override fun applyLanguage(code: String) = onLanguage(code)
}
