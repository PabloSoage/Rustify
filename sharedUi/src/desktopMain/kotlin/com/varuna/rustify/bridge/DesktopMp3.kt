// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/bridge/DesktopMp3.kt
package com.varuna.rustify.bridge

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.varuna.rustify.platform.Executables
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.settings_download_mp3
import com.varuna.rustify.ui.resources.settings_download_mp3_desc
import com.varuna.rustify.ui.screens.SettingSwitchRow
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's "Convert to MP3" where the engine plays: `ffmpeg`, found on `PATH`, turning a finished
 * download into a 320 kbps MP3, off by default as on the phone. Without the program the switch is not
 * offered. A conversion that fails leaves the file as it came: a download is not lost over it.
 */
object DesktopMp3 {
    /** The phone's key (`DownloadFormat`). */
    private const val KEY = "download_convert_mp3"
    private const val TAG = "DesktopMp3"
    private const val TIMEOUT_S = 10 * 60L

    fun wanted(prefs: KeyValueStore): Boolean = prefs.getBoolean(KEY, false)

    /** The MP3 made of [file], which is removed, or [file] itself when it is one or none could be made. */
    fun convert(file: File, exe: String? = Executables.onPath("ffmpeg")): File {
        if (exe == null || file.extension.equals("mp3", ignoreCase = true)) return file
        val out = File(file.parentFile, file.nameWithoutExtension + ".mp3").let { wanted ->
            generateSequence(1) { it + 1 }.map { n -> if (n == 1) wanted else File(wanted.parentFile, "${wanted.nameWithoutExtension} ($n).mp3") }
                .first { !it.exists() }
        }
        val answer = Executables.run(
            listOf(exe, "-hide_banner", "-loglevel", "error", "-nostdin", "-i", file.path, "-vn", "-codec:a", "libmp3lame", "-b:a", "320k", out.path),
            timeoutSeconds = TIMEOUT_S,
        )
        if (answer?.first != 0 || !out.isFile || out.length() == 0L) {
            Log.w(TAG, "could not convert ${file.name}: ${answer?.second?.take(200)}")
            out.delete()
            return file
        }
        file.delete()
        return out
    }

    /** The switch under the downloads folder; nothing without the program. */
    @Composable
    fun Switch() {
        if (Executables.onPath("ffmpeg") == null) return
        val prefs = LocalAppPlatform.current.prefs()
        var on by remember { mutableStateOf(wanted(prefs)) }
        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(stringResource(Res.string.settings_download_mp3), stringResource(Res.string.settings_download_mp3_desc), on) {
            on = it
            prefs.putBoolean(KEY, it)
        }
    }
}
