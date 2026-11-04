package com.varuna.rustify.audio

import com.varuna.rustify.platform.KeyValueStore

/**
 * Preferences for the Deezer backend (a source distinct from YouTube: Deezer HiFi/FLAC using the
 * user's own ARL). On/off and order live in [AudioBackendSettings] (id "deezer"). Here we store: the
 * ARL mode (a single own ARL vs a site that publishes ARLs), the ARL/URL, the working ARL (cache),
 * and the quality.
 *
 * ARLs are never embedded or provided: the user enters their own, or picks a public site and the app
 * only automates testing the ones that site lists.
 *
 * [p] is the sources' settings store ([AudioSourceEnvironment.settings]): on Android the
 * `rustify_settings` preferences file, as before.
 */
object DeezerSettings {
    private const val K_ARL_MODE = "dz_arl_mode"       // "single" | "source"
    private const val K_ARL = "dz_arl"                 // own ARL
    private const val K_SOURCE = "dz_arl_source_url"    // URL of the site that publishes ARLs
    private const val K_WORKING = "dz_arl_working"      // last ARL that worked (cache)
    private const val K_QUALITY = "dz_quality"          // "flac" | "mp3_320" | "mp3_128"

    fun arlMode(p: KeyValueStore): String = p.getString(K_ARL_MODE) ?: "single"
    fun setArlMode(p: KeyValueStore, v: String) = p.putString(K_ARL_MODE, v)

    fun arl(p: KeyValueStore): String = p.getString(K_ARL)?.trim() ?: ""
    fun setArl(p: KeyValueStore, v: String) = p.putString(K_ARL, v.trim())

    fun sourceUrl(p: KeyValueStore): String = p.getString(K_SOURCE)?.trim() ?: ""
    fun setSourceUrl(p: KeyValueStore, v: String) = p.putString(K_SOURCE, v.trim())

    fun workingArl(p: KeyValueStore): String = p.getString(K_WORKING)?.trim() ?: ""
    fun setWorkingArl(p: KeyValueStore, v: String) = p.putString(K_WORKING, v.trim())

    fun quality(p: KeyValueStore): String = p.getString(K_QUALITY) ?: "flac"

    /**
     * Changing this changes the bytes a track is made of, so anything already stored for it is the
     * wrong quality now. The generation bump is what makes the cache stop answering with it — see
     * Android's `StreamRouting`. Guarded on an actual change, because a needless bump costs a
     * re-download of everything the user had.
     */
    fun setQuality(p: KeyValueStore, v: String) {
        val changed = quality(p) != v
        p.putString(K_QUALITY, v)
        if (changed) AudioSourceRegistry.streamCache.bumpGeneration()
    }

    /** Formats to request from get_url, in preference order (fallback within the same request). */
    fun formatChain(p: KeyValueStore): List<String> = when (quality(p)) {
        "mp3_128" -> listOf("MP3_128")
        "mp3_320" -> listOf("MP3_320", "MP3_128")
        else -> listOf("FLAC", "MP3_320", "MP3_128")
    }
}
