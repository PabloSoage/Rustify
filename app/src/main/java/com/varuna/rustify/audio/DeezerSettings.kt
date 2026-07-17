package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit

/**
 * E62 — Preferencias del backend Deezer (fuente **distinta** de YouTube: Deezer HiFi/FLAC con el
 * **ARL del propio usuario**). On/off y orden en [AudioBackendSettings] (id "deezer"). Aquí va: modo
 * de ARL (uno propio vs una web que publica ARLs), el ARL/URL, el ARL que funciona (cache) y la calidad.
 *
 * **Nunca embebemos ARLs ni los proveemos**: el usuario mete el suyo, o elige una web pública y la app
 * solo automatiza *probar* los que esa web lista.
 */
object DeezerSettings {
    private const val PREFS = "rustify_settings"
    private const val K_ARL_MODE = "dz_arl_mode"       // "single" | "source"
    private const val K_ARL = "dz_arl"                 // ARL propio
    private const val K_SOURCE = "dz_arl_source_url"    // URL de la web que publica ARLs
    private const val K_WORKING = "dz_arl_working"      // último ARL que funcionó (cache)
    private const val K_QUALITY = "dz_quality"          // "flac" | "mp3_320" | "mp3_128"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arlMode(ctx: Context): String = p(ctx).getString(K_ARL_MODE, "single") ?: "single"
    fun setArlMode(ctx: Context, v: String) = p(ctx).edit { putString(K_ARL_MODE, v) }

    fun arl(ctx: Context): String = p(ctx).getString(K_ARL, "")?.trim() ?: ""
    fun setArl(ctx: Context, v: String) = p(ctx).edit { putString(K_ARL, v.trim()) }

    fun sourceUrl(ctx: Context): String = p(ctx).getString(K_SOURCE, "")?.trim() ?: ""
    fun setSourceUrl(ctx: Context, v: String) = p(ctx).edit { putString(K_SOURCE, v.trim()) }

    fun workingArl(ctx: Context): String = p(ctx).getString(K_WORKING, "")?.trim() ?: ""
    fun setWorkingArl(ctx: Context, v: String) = p(ctx).edit { putString(K_WORKING, v.trim()) }

    fun quality(ctx: Context): String = p(ctx).getString(K_QUALITY, "flac") ?: "flac"
    fun setQuality(ctx: Context, v: String) = p(ctx).edit { putString(K_QUALITY, v) }

    /** El ARL a usar ahora: el que funcionó (cache) si existe, si no el propio. En modo "source" el
     *  cache lo rellena [DeezerArl] tras probar la web. */
    fun activeArl(ctx: Context): String {
        val cached = workingArl(ctx)
        if (cached.isNotBlank()) return cached
        return if (arlMode(ctx) == "single") arl(ctx) else ""
    }

    /** Formatos a pedir a get_url, en orden de preferencia (fallback dentro de la misma petición). */
    fun formatChain(ctx: Context): List<String> = when (quality(ctx)) {
        "mp3_128" -> listOf("MP3_128")
        "mp3_320" -> listOf("MP3_320", "MP3_128")
        else -> listOf("FLAC", "MP3_320", "MP3_128")
    }
}
