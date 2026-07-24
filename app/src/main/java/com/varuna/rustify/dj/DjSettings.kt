package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.dj.DjSettings.ttsEngine

/**
 * Persistence of the DJ configuration in `rustify_settings` (SharedPreferences), in line with the
 * rest of the app's settings (no Room, no new dependencies).
 */
object DjSettings {
    const val PREFS = "rustify_settings"

    const val KEY_MODE = "dj_mode"                 // "heuristic" | "api" | "local"
    const val KEY_API_BASE_URL = "dj_api_base_url"
    const val KEY_API_MODEL = "dj_api_model"
    const val KEY_API_KEY = "dj_api_key"           // optional; blank = no key

    /**
     * Default public OpenAI-compatible endpoint: Pollinations AI, free and without an API key. It is
     * best-effort (rate limits / terms may change) and fully configurable in Settings. No third-party
     * private key is embedded.
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

    /** Optional API key; empty string => no authentication (keyless endpoint). */
    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    // ── Voice (spoken DJ) ─────────────────────────────────────────────────────────────
    const val KEY_VOICE_ENABLED = "dj_voice_enabled"       // the DJ speaks the intros/transitions
    const val KEY_VOICE_LANG = "dj_voice_lang"             // voice language, independent of the app's ("" = app language)
    const val KEY_VOICE_NATIVE_NAME = "dj_voice_native_name" // specific native voice (TextToSpeech.Voice.name); "" = language default
    const val KEY_VOICE_CLOUD_URL = "dj_voice_cloud_url"   // OpenAI-compatible TTS endpoint (/audio/speech) for the "openai" engine; "" = unset
    const val KEY_VOICE_CLOUD_KEY = "dj_voice_cloud_key"
    const val KEY_VOICE_CLOUD_VOICE = "dj_voice_cloud_voice"

    /**
     * Voice engine: "native" (Android TextToSpeech, offline), "pollinations" (free, token-less OpenAI
     * voices, much more natural than native TTS), or "openai" (your own OpenAI-compatible endpoint).
     * See [ttsEngine].
     */
    const val KEY_TTS_ENGINE = "dj_tts_engine"

    /** Keyless Pollinations TTS base (GET /{text}?model=openai-audio&voice=...). */
    const val POLLINATIONS_TTS_BASE = "https://text.pollinations.ai"

    /** OpenAI voices shared by Pollinations and OpenAI-compatible endpoints. */
    val OPENAI_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    /**
     * Microsoft Edge neural voices ("edge" engine, free and token-less). The *Multilingual* ones
     * speak any language automatically (good defaults); the rest are per language/region. `(id, label)`.
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

    // ── Autonomous mode (automix by moods, one-button) ────────────────────────────────────
    const val KEY_AUTO_SOURCE = "dj_auto_source"           // "favorites" | "balanced" | "discover"

    fun voiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_ENABLED, true)

    /** Voice language code (e.g. "es", "en"); blank ⇒ use the app / system language. */
    fun voiceLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_LANG, "") ?: ""

    fun voiceCloudUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_URL, "") ?: ""

    fun voiceCloudKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_KEY, "") ?: ""

    fun voiceCloudVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_CLOUD_VOICE, "alloy") ?: "alloy"

    /** Specific native voice (Voice.name); blank ⇒ whichever Android picks for the language. */
    fun voiceNativeName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE_NATIVE_NAME, "") ?: ""

    /**
     * Effective voice engine. If the user never chose one explicitly, we derive it: if a cloud
     * endpoint is configured ⇒ "openai", otherwise ⇒ "native".
     */
    fun ttsEngine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val explicit = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        if (explicit.isNotBlank()) return explicit
        return if ((prefs.getString(KEY_VOICE_CLOUD_URL, "") ?: "").isNotBlank()) "openai" else "native"
    }

    /** favorites = favorites only · balanced = favorites + some suggestions · discover = mostly suggestions. */
    fun autoSource(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTO_SOURCE, "balanced") ?: "balanced"
}
