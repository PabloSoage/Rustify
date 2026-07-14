package com.varuna.rustify.dj

import android.content.Context

/**
 * E90 — Persistencia de la configuración del DJ en `rustify_settings` (SharedPreferences),
 * en paridad con el resto de ajustes de la app (sin Room / sin deps nuevas).
 */
object DjSettings {
    const val PREFS = "rustify_settings"

    const val KEY_MODE = "dj_mode"                 // "heuristic" | "api" | "local"
    const val KEY_API_BASE_URL = "dj_api_base_url"
    const val KEY_API_MODEL = "dj_api_model"
    const val KEY_API_KEY = "dj_api_key"           // opcional; en blanco = sin key

    /**
     * Endpoint público OpenAI-compatible por defecto: Pollinations AI, gratuito y **sin API key**.
     * Es best-effort (rate limits / términos pueden cambiar) y totalmente configurable en Ajustes.
     * NO se embebe ninguna key privada de terceros.
     */
    const val DEFAULT_API_BASE_URL = "https://text.pollinations.ai/openai"
    const val DEFAULT_API_MODEL = "openai"

    fun mode(context: Context): DjMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_MODE, "heuristic")) {
            "api" -> DjMode.API
            "local" -> DjMode.LOCAL
            else -> DjMode.HEURISTIC
        }
    }

    fun apiBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL)?.ifBlank { DEFAULT_API_BASE_URL }
            ?: DEFAULT_API_BASE_URL
    }

    fun apiModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_MODEL, DEFAULT_API_MODEL)?.ifBlank { DEFAULT_API_MODEL }
            ?: DEFAULT_API_MODEL
    }

    /** API key opcional; cadena vacía => sin autenticación (endpoint keyless). */
    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    // ── Voz (DJ hablado, estilo "DJ Livi") ──────────────────────────────────────────────
    const val KEY_VOICE_ENABLED = "dj_voice_enabled"       // el DJ habla las intros/transiciones
    const val KEY_VOICE_LANG = "dj_voice_lang"             // idioma de la VOZ, independiente del de la app ("" = idioma de la app)
    const val KEY_VOICE_CLOUD_URL = "dj_voice_cloud_url"   // endpoint TTS en la nube opcional (OpenAI-compatible /audio/speech); "" = TTS nativo
    const val KEY_VOICE_CLOUD_KEY = "dj_voice_cloud_key"
    const val KEY_VOICE_CLOUD_VOICE = "dj_voice_cloud_voice"

    // ── Modo autónomo (automix por moods, tú solo le das al botón) ───────────────────────
    const val KEY_AUTO_SOURCE = "dj_auto_source"           // "favorites" | "balanced" | "discover"

    fun voiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_ENABLED, true)

    /** Código de idioma de la voz (ej. "es", "en"); en blanco ⇒ usar el idioma de la app / sistema. */
    fun voiceLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_LANG, "") ?: ""

    fun voiceCloudUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_URL, "") ?: ""

    fun voiceCloudKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_KEY, "") ?: ""

    fun voiceCloudVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_VOICE, "alloy") ?: "alloy"

    /** favorites = solo favoritas · balanced = favoritas + alguna sugerencia · discover = mayormente sugerencias. */
    fun autoSource(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTO_SOURCE, "balanced") ?: "balanced"
}
