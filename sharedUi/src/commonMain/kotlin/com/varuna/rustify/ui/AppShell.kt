// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/AppShell.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import com.varuna.rustify.Screen
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.player.PlayerController

/**
 * What the app's root ([RustifyApp]) takes from the platform: signing in, the start-up chores only
 * one platform has, and — until they move to :sharedUi (docs serie-4.x/27) — the screens still
 * written against Android. A screen leaves [PlatformScreen] the day it moves.
 */
interface AppShell {
    /** The sign-in screen; [onLogin] gets what the login produced (the `sp_dc` cookie). */
    @Composable
    fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit)

    /** A sign-in that failed: Android clears the WebView's cookies so the next one starts clean. */
    fun onLoginFailed()

    /**
     * The platform's own start-up, run once inside the root: Android checks for an update (and
     * shows its dialog over everything), syncs with Drive and restores the web player mode.
     */
    @Composable
    fun StartupEffects(host: ShellHost)

    /** Landscape on the phone is full screen; nothing to do in a desktop window. */
    @Composable
    fun SystemBars(landscape: Boolean)

    /** Follows a Maps short link to the place it names (network; Travel is Android's for now). */
    suspend fun resolveMapsLink(url: String): Screen.TravelWithDestination?

    /** A screen [RustifyApp] does not draw itself yet (see the list in [Screen]'s users). */
    @Composable
    fun PlatformScreen(screen: Screen, host: ShellHost)
}

/** What a platform screen can reach in the root: the repositories, the player and the navigation. */
class ShellHost(
    val spotifyRepo: SpotifyRepository,
    val ytmRepo: YtMusicRepository,
    val player: PlayerController,
    val navigate: (Screen) -> Unit,
    val back: () -> Unit,
    val isSignedIn: Boolean,
    val signIn: () -> Unit,
    /** The account was signed out from Settings: Home forgets its sections. */
    val signedOut: () -> Unit,
    /** Settings' open category, kept by the root so a sub-screen and back returns to it. */
    val settingsCategory: String?,
    val onSettingsCategory: (String?) -> Unit,
    /** The app's language changed in Settings: the root re-reads what Spotify answered in the old one. */
    val onLanguageChanged: (String) -> Unit,
)
