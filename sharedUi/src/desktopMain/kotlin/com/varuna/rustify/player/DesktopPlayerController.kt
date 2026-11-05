// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/player/DesktopPlayerController.kt
package com.varuna.rustify.player

import com.varuna.rustify.audio.DesktopAudioSources
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.CoroutineScope

/** The player on the desktop: the phone's sources and yt-dlp, and the output the engine reports. */
fun DesktopPlayerController(player: QueuePlayer, scope: CoroutineScope, platform: AppPlatform) = EnginePlayerController(
    player,
    scope,
    platform,
    sources = DesktopAudioSources.install(platform.prefs()),
    output = DesktopOutput.name,
    refreshOutput = DesktopOutput::refresh,
)
