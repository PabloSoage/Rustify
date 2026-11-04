// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/bridge/DesktopLocalFiles.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.ui.appFileOfUri
import com.varuna.rustify.ui.fileUri
import java.io.File

/**
 * A desktop local track is a file on this computer, and its id is `local:` plus the file's `file://`
 * URI (`file:///home/me/Music/a%20b.mp3`, `file:///C:/Music/a.mp3`): percent-encoded, three slashes,
 * the form the engine opens, Coil loads and [M3u.pathOf] reads a path out of. Built and read here only.
 */
object DesktopLocalFiles {
    /** The `file://` URI of [file]. */
    fun uriOf(file: File): String = file.fileUri()

    /** The track id of [file]. */
    fun trackIdOf(file: File): String = TrackRef.Local(uriOf(file)).raw

    /** The file a `file://` URI names, or null when it names none (a phone's `content://` track). */
    fun fileOfUri(uri: String): File? = appFileOfUri(uri)

    /** The file a `local:` track is, or null when it is no file on this computer. */
    fun fileOf(trackId: String?): File? = TrackRef.localUriOf(trackId)?.let { fileOfUri(it) }
}
