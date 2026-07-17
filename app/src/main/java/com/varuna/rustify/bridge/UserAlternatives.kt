package com.varuna.rustify.bridge

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Conjunto de trackIds de Spotify para los que el usuario **eligió explícitamente** una alternativa
 * de YouTube (desde el diálogo de alternativas).
 *
 * Existe para **distinguir un match confirmado por el usuario de uno auto-persistido**: el resolver
 * antiguo (pre-2.9.10) guardaba en `youtube_mappings.json` CADA hint auto-matcheado como si fuera un
 * mapping del usuario. Eso hacía que `playTrack` tratara cualquier mapping como "alternativa del
 * usuario" y **se saltara el match local** aunque el usuario nunca hubiera elegido nada. Con este
 * conjunto, el match local gana sobre un auto-mapping viejo pero **no** sobre una elección real.
 *
 * Persistido como array JSON en `filesDir/user_alternatives.json`.
 */
object UserAlternatives {
    private const val FILE = "user_alternatives.json"
    private val ids = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var loaded = false

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = File(context.filesDir, FILE)
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) ids.add(arr.optString(i))
                }
            }
            loaded = true
        }
    }

    fun isUserSet(context: Context, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        ensure(context)
        return ids.contains(trackId)
    }

    fun add(context: Context, trackId: String) {
        if (trackId.isBlank()) return
        ensure(context)
        if (ids.add(trackId)) persist(context)
    }

    fun remove(context: Context, trackId: String) {
        ensure(context)
        if (ids.remove(trackId)) persist(context)
    }

    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            synchronized(ids) { ids.forEach { arr.put(it) } }
            File(context.filesDir, FILE).writeText(arr.toString())
        }
    }
}
