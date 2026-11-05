// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/bridge/DesktopDownloads.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.engine.JvmPlayerBackend
import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.ui.AppPlatform
import java.io.File

/** [EngineDownloads] on the desktop: the player's sources, and a folder is an absolute path. */
fun DesktopDownloads(platform: AppPlatform): EngineDownloads = EngineDownloads(
    platform,
    StreamResolver(JvmPlayerBackend, com.varuna.rustify.audio.DesktopAudioSources.install(platform.prefs())),
    folderOf = { folder -> File(folder).takeIf { it.isAbsolute } },
    finish = { file -> if (DesktopMp3.wanted(platform.prefs())) DesktopMp3.convert(file) else file },
)
