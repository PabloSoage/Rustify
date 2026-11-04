// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/LocalFileLyrics.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.appFileOfUri
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fileName
import com.varuna.rustify.ui.fullPath
import com.varuna.rustify.ui.isPlainFile
import com.varuna.rustify.ui.parentDir
import com.varuna.rustify.ui.readAllText
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Lyrics from the user's own file where a local track is a path (the desktop, iOS), as Android's
 * `LocalLyrics`: a `.lrc` with the same name in the same folder first, then the lyrics tag inside the
 * file (read by the core). Blocking; [LyricsChain] calls it off the main thread.
 */
object LocalFileLyrics {
    fun read(trackId: String): LyricsResult? {
        val file = TrackRef.localUriOf(trackId)?.let { appFileOfUri(it) } ?: return null
        val text = sidecar(file) ?: embedded(file) ?: return null
        val synced = parseLrcText(text)
        return if (synced.isNotEmpty()) LyricsResult(synced, null)
        else text.trim().takeIf { it.isNotEmpty() }?.let { LyricsResult(emptyList(), it) }
    }

    private fun sidecar(file: AppFile): String? {
        val dir = file.parentDir ?: return null
        val base = file.fileName.substringBeforeLast('.')
        val lrc = listOf("$base.lrc", "$base.LRC").map { dir.child(it) }.firstOrNull { it.isPlainFile() } ?: return null
        return runCatching { lrc.readAllText() }.getOrNull()?.removePrefix("﻿")?.takeIf { it.isNotBlank() }
    }

    private fun embedded(file: AppFile): String? = runCatching {
        val json = NativeEngine.readEmbeddedLyrics(file.fullPath)
        RustifyJson.decodeFromString(String.serializer().nullable, json)
    }.getOrNull()
}
