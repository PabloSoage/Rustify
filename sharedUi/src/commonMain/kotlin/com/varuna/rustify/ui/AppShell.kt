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

    /**
     * The Settings sections only this platform has, drawn at [slot] of their category, so the shared
     * screen keeps the phone's order (docs serie-4.x/27, phase E).
     */
    @Composable
    fun SettingsSections(slot: SettingsSlot, host: ShellHost)

    /**
     * The page the Settings easter egg ends on: Android shows it over the app, in a full-screen
     * WebView with a close button; the desktop opens it in the browser and closes at once.
     */
    @Composable
    fun EasterEggPage(url: String, onClose: () -> Unit)

    /** The Settings menu's top-bar actions (Android: check for an update). */
    @Composable
    fun SettingsActions(host: ShellHost)

    /**
     * Puts the interface (and what the core answers in, Spotify's titles) in [code]'s language, or
     * the system's for `"system"`. Settings has already stored the choice.
     */
    fun applyLanguage(code: String)
}

/** Where in Settings a platform's own sections go; see [AppShell.SettingsSections]. */
enum class SettingsSlot {
    /** General, before the language: the yt-dlp engine, caches, local data, Drive, storage. */
    GENERAL,

    /** General, between the Rustify link and the session: the DJ. */
    GENERAL_DJ,

    /** Audio, before the lyrics providers: Android's battery exemption. */
    AUDIO,

    /** Audio, after the lyrics providers: local music and the audio sources. */
    AUDIO_SOURCES,
    DOWNLOADS,
    INTEGRATIONS,

    /** Advanced, before the links to the tools. */
    ADVANCED,
}

/**
 * What a platform screen can reach in the root: the repositories, the player and the navigation.
 *
 * The root keeps one for its whole life, and a screen's content captures it once (the root wraps each
 * screen in `remember(screen) { movableContentOf { … } }`), so what changes is read through a function
 * every time: a plain `Boolean` here stayed what it was when the screen opened, and Settings' menu
 * never opened a category.
 */
class ShellHost(
    val spotifyRepo: SpotifyRepository,
    val ytmRepo: YtMusicRepository,
    val player: PlayerController,
    val navigate: (Screen) -> Unit,
    val back: () -> Unit,
    private val isSignedInNow: () -> Boolean,
    val signIn: () -> Unit,
    /** The account was signed out from Settings: Home forgets its sections. */
    val signedOut: () -> Unit,
    private val settingsCategoryNow: () -> String?,
    val onSettingsCategory: (String?) -> Unit,
    /** The app's language changed in Settings: the root re-reads what Spotify answered in the old one. */
    val onLanguageChanged: (String) -> Unit,
) {
    val isSignedIn: Boolean get() = isSignedInNow()

    /** Settings' open category, kept by the root so a sub-screen and back returns to it. */
    val settingsCategory: String? get() = settingsCategoryNow()
}
