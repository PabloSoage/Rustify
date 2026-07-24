package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit

/**
 * Preferences for the Deezer backend (a source distinct from YouTube: Deezer HiFi/FLAC using the
 * user's own ARL). On/off and order live in [AudioBackendSettings] (id "deezer"). Here we store: the
 * ARL mode (a single own ARL vs a site that publishes ARLs), the ARL/URL, the working ARL (cache),
 * and the quality.
 *
 * ARLs are never embedded or provided: the user enters their own, or picks a public site and the app
 * only automates testing the ones that site lists.
 */
object DeezerSettings {
    private const val PREFS = "rustify_settings"
    private const val K_ARL_MODE = "dz_arl_mode"       // "single" | "source"
    private const val K_ARL = "dz_arl"                 // own ARL
    private const val K_SOURCE = "dz_arl_source_url"    // URL of the site that publishes ARLs
    private const val K_WORKING = "dz_arl_working"      // last ARL that worked (cache)
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

    /** Formats to request from get_url, in preference order (fallback within the same request). */
    fun formatChain(ctx: Context): List<String> = when (quality(ctx)) {
        "mp3_128" -> listOf("MP3_128")
        "mp3_320" -> listOf("MP3_320", "MP3_128")
        else -> listOf("FLAC", "MP3_320", "MP3_128")
    }
}
