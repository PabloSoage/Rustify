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
}
