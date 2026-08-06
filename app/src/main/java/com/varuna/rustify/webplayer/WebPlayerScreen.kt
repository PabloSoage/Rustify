package com.varuna.rustify.webplayer

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.R

/**
 * Screen hosting Spotify's web player.
 *
 * The WebView itself lives in [WebPlayerController], not here: this screen only attaches and detaches
 * it, so navigating away does not stop playback. Everything that makes the embed work — granting the
 * Widevine permission and filtering requests with uBlock Origin's lists — is set up by the controller.
 */
@Composable
fun WebPlayerScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEnter: () -> Unit = {}
) {
    val context = LocalContext.current
    var filtersReady by remember { mutableStateOf(false) }
    // Desktop by default: Spotify serves phone user agents a "get the app" page instead of a usable
    // library. The toggle is there because the desktop layout is dense on a phone.
    var desktopSite by remember { mutableStateOf(WebPlayerController.isDesktopMode(context)) }

    LaunchedEffect(Unit) { onEnter() }

    // Compile the filter lists before the page loads so the first requests are already covered.
    LaunchedEffect(Unit) {
        filtersReady = runCatching { AdblockFilters.ensureLoaded(context) }.getOrDefault(false)
        WebPlayerController.getOrCreate(context)
        WebPlayerController.loadHomeIfNeeded()
    }

    // Detach (never destroy) so audio survives leaving the screen.
    DisposableEffect(Unit) { onDispose { WebPlayerController.detachFromParent() } }

    BackHandler { onBackClick() }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF121212))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = Color.White
                )
            }
            Text(
                text = stringResource(R.string.web_player_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                desktopSite = !desktopSite
                WebPlayerController.setDesktopMode(context, desktopSite)
            }) {
                Icon(
                    if (desktopSite) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                    contentDescription = stringResource(R.string.web_player_desktop_site),
                    tint = Color.White
                )
            }
            IconButton(onClick = { WebPlayerController.getOrCreate(context).reload() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.web_player_reload),
                    tint = Color.White
                )
            }
        }

        if (!filtersReady) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1DB954),
                trackColor = Color(0xFF333333)
            )
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebPlayerController.getOrCreate(ctx).also { wv ->
                        // Re-entering the screen reuses the same instance, which may still be
                        // attached to the previous (now discarded) host.
                        (wv.parent as? ViewGroup)?.removeView(wv)
                    }
                }
            )
        }
    }
}
