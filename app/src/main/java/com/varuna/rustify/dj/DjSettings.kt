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
    const val KEY_VOICE_NATIVE_NAME = "dj_voice_native_name" // voz nativa concreta (TextToSpeech.Voice.name); "" = por defecto del idioma
    const val KEY_VOICE_CLOUD_URL = "dj_voice_cloud_url"   // endpoint TTS OpenAI-compatible (/audio/speech) para el motor "openai"; "" = sin configurar
    const val KEY_VOICE_CLOUD_KEY = "dj_voice_cloud_key"
    const val KEY_VOICE_CLOUD_VOICE = "dj_voice_cloud_voice"

    /**
     * Motor de voz: "native" (TextToSpeech de Android, offline), "pollinations" (voces OpenAI
     * gratuitas y **sin token**, mucho más naturales que el TTS nativo) u "openai" (endpoint
     * OpenAI-compatible propio). Ver [ttsEngine].
     */
    const val KEY_TTS_ENGINE = "dj_tts_engine"

    /** Base keyless de Pollinations TTS (GET /{texto}?model=openai-audio&voice=...). */
    const val POLLINATIONS_TTS_BASE = "https://text.pollinations.ai"

    /** Voces OpenAI compartidas por Pollinations y por endpoints OpenAI-compatibles. */
    val OPENAI_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    /**
     * Voces neurales de Microsoft Edge (motor "edge", gratis y sin token). Las *Multilingual* hablan
     * cualquier idioma automáticamente (buenas por defecto); el resto son por idioma/región. `(id, etiqueta)`.
     */
    val EDGE_VOICES = listOf(
        "en-US-EmmaMultilingualNeural" to "Emma · multilingüe",
        "en-US-AvaMultilingualNeural" to "Ava · multilingüe",
        "en-US-AndrewMultilingualNeural" to "Andrew · multilingüe",
        "en-US-BrianMultilingualNeural" to "Brian · multilingüe",
        "es-ES-ElviraNeural" to "Elvira · ES-ES",
        "es-ES-AlvaroNeural" to "Álvaro · ES-ES",
        "es-MX-DaliaNeural" to "Dalia · ES-MX",
        "en-US-AriaNeural" to "Aria · EN-US",
        "en-US-GuyNeural" to "Guy · EN-US",
        "en-GB-SoniaNeural" to "Sonia · EN-GB",
        "ja-JP-NanamiNeural" to "Nanami · JA",
        "ja-JP-KeitaNeural" to "Keita · JA",
        "fr-FR-DeniseNeural" to "Denise · FR",
        "de-DE-KatjaNeural" to "Katja · DE",
        "it-IT-ElsaNeural" to "Elsa · IT",
        "pt-BR-FranciscaNeural" to "Francisca · PT-BR"
    )
    const val EDGE_DEFAULT_VOICE = "en-US-EmmaMultilingualNeural"

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

    /** Voz nativa concreta (Voice.name); en blanco ⇒ la que Android elija para el idioma. */
    fun voiceNativeName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_NATIVE_NAME, "") ?: ""

    /**
     * Motor de voz efectivo. Si el usuario nunca lo eligió explícitamente, migramos el
     * comportamiento previo: si había un endpoint nube configurado ⇒ "openai", si no ⇒ "native".
     */
    fun ttsEngine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val explicit = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        if (explicit.isNotBlank()) return explicit
        return if ((prefs.getString(KEY_VOICE_CLOUD_URL, "") ?: "").isNotBlank()) "openai" else "native"
    }

    /** favorites = solo favoritas · balanced = favoritas + alguna sugerencia · discover = mayormente sugerencias. */
    fun autoSource(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTO_SOURCE, "balanced") ?: "balanced"
}
