package com.varuna.rustify.bridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Lyrics from the user's own file (`17` §4, after Gramophone), asked before any online provider
 * for a `local:` track: first a `.lrc` with the same name in the same folder, then the lyrics tag
 * inside the file (read by the core, `audio::embedded_lyrics`). Both go through [parseLrcText];
 * text without timestamps is shown as plain lyrics.
 */
object LocalLyrics {
    /** The document id of the file next to [docId] with extension [ext]: `vol:Dir/a.mp3` → `vol:Dir/a.lrc`. */
    fun siblingDocId(docId: String, ext: String): String? {
        val slash = docId.lastIndexOf('/')
        val colon = docId.indexOf(':')
        val nameStart = maxOf(slash, colon) + 1
        val name = docId.substring(nameStart)
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        return docId.substring(0, nameStart) + name.substring(0, dot) + "." + ext
    }

    /** Synced or plain lyrics for a `local:` track, or null when its file has none. Blocking. */
    fun read(context: Context, trackId: String): LyricsResult? {
        val uri = Uri.parse(trackId.removePrefix("local:"))
        val text = sidecar(context, uri) ?: embedded(context, uri) ?: return null
        val synced = parseLrcText(text)
        return if (synced.isNotEmpty()) LyricsResult(synced, null)
        else text.trim().takeIf { it.isNotEmpty() }?.let { LyricsResult(emptyList(), it) }
    }

    private fun sidecar(context: Context, uri: Uri): String? = runCatching {
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val lrc = siblingDocId(DocumentsContract.getDocumentId(uri), "lrc") ?: return null
        val lrcUri = DocumentsContract.buildDocumentUriUsingTree(tree, lrc)
        context.contentResolver.openInputStream(lrcUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()?.removePrefix("﻿")?.takeIf { it.isNotBlank() }

    private fun embedded(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val json = NativeEngine.readEmbeddedLyrics("/proc/self/fd/${pfd.fd}")
            RustifyJson.decodeFromString(String.serializer().nullable, json)
        }
    }.getOrNull()
}
