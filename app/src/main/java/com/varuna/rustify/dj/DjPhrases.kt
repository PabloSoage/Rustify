package com.varuna.rustify.dj

/**
 * E90 — Frases que el DJ **habla** (voz). Deben ir en el idioma de la VOZ (configurable aparte del
 * idioma de la app), por eso no salen de los recursos Android (que siguen el locale de la app) sino
 * de esta tabla. Idiomas soportados: es / en (fallback en). Fácil de ampliar.
 */
object DjPhrases {

    private val MOOD_NAME: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "chill" to "algo tranquilo",
            "energetic" to "algo con energía",
            "happy" to "buen rollo",
            "focus" to "algo para concentrarte",
            "melancholic" to "algo más melancólico",
        ),
        "en" to mapOf(
            "chill" to "something chill",
            "energetic" to "something energetic",
            "happy" to "some feel-good vibes",
            "focus" to "something to focus",
            "melancholic" to "something more melancholic",
        ),
    )

    private fun lang(voiceLang: String): String {
        val code = voiceLang.ifBlank { java.util.Locale.getDefault().language }.lowercase().take(2)
        return if (MOOD_NAME.containsKey(code)) code else "en"
    }

    /** Frase completa de anuncio del DJ para [moodId]; [first] = arranque de sesión vs transición. */
    fun announce(voiceLang: String, moodId: String, first: Boolean): String {
        val l = lang(voiceLang)
        val mood = MOOD_NAME[l]?.get(moodId) ?: moodId
        return if (first) {
            if (l == "es") "Aquí tu DJ. Empezamos con $mood." else "Here's your DJ. Let's start with $mood."
        } else {
            if (l == "es") "Cambiamos de rollo: ahora $mood." else "Switching it up: now $mood."
        }
    }
}
