package com.varuna.rustify.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Acceso desde Kotlin al fichero de matches YouTube (`filesDir/youtube_mappings.json`,
 * `{spotifyTrackId: youtubeVideoId}`), que hasta ahora solo se editaba como texto crudo.
 *
 * IMPORTANTE: tras escribir hay que **recargar el mapa en Rust** con [NativeEngine.initCacheDirNative]
 * (que hace `*lock = load_from_disk`, es decir, REEMPLAZA el mapa en memoria). El editor de texto
 * antiguo no lo hacía → por eso "borrabas algo, dabas a Guardar y no se aplicaba".
 */
object MatchStore {
    private fun file(context: Context) = File(context.filesDir, "youtube_mappings.json")

    /** Todos los matches (trackId → youtubeId). */
    fun readAll(context: Context): Map<String, String> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        val obj = JSONObject(f.readText())
        buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
    }.getOrDefault(emptyMap())

    private fun writeAll(context: Context, map: Map<String, String>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file(context).writeText(obj.toString())
        }
        reload(context)
    }

    /** Crea/actualiza un match y lo marca como elección del usuario. */
    fun put(context: Context, trackId: String, youtubeId: String) {
        if (trackId.isBlank() || youtubeId.isBlank()) return
        val m = readAll(context).toMutableMap()
        m[trackId] = youtubeId
        writeAll(context, m)
        UserAlternatives.add(context, trackId)
        // refleja también en el mapa vivo de Rust de inmediato
        runCatching { NativeEngine.setAlternativeTrackNative(trackId, youtubeId) }
    }

    /** Elimina un match (y su marca de usuario) — vuelve a auto-match / local. */
    fun remove(context: Context, trackId: String) {
        val m = readAll(context).toMutableMap()
        if (m.remove(trackId) != null) writeAll(context, m)
        UserAlternatives.remove(context, trackId)
    }

    /** Sobrescribe todo el mapa desde texto JSON (para el import / editor avanzado). */
    fun replaceFromJson(context: Context, json: String): Boolean = runCatching {
        val obj = JSONObject(json) // valida
        file(context).writeText(obj.toString())
        reload(context)
        true
    }.getOrDefault(false)

    private fun reload(context: Context) {
        runCatching { NativeEngine.initCacheDirNative(context.filesDir.absolutePath) }
    }
}
