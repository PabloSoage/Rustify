package com.varuna.rustify.dj

/**
 * Phrases the DJ speaks (voice). They must be in the voice language (configurable separately from the
 * app language), which is why they come from this table rather than from Android resources (which
 * follow the app locale). Supported languages: es / en (fallback en). Easy to extend.
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

    /** Full DJ announcement phrase for [moodId]; [first] = session start vs transition. */
    fun announce(voiceLang: String, moodId: String, first: Boolean): String {
        val l = lang(voiceLang)
        val mood = MOOD_NAME[l]?.get(moodId) ?: moodId
        return if (first) {
            if (l == "es") "Aquí tu DJ. Empezamos con $mood." else "Here's your DJ. Let's start with $mood."
        } else {
            if (l == "es") "Cambiamos de rollo: ahora $mood." else "Switching it up: now $mood."
        }
    }

    /** Short test phrase for previewing a voice (in the voice language). */
    fun previewPhrase(voiceLang: String): String {
        val l = lang(voiceLang)
        return when (l) {
            "es" -> "Hola, esta es una prueba de mi voz. ¿Cómo sueno?"
            "ja" -> "こんにちは、これは声のテストです。どう聞こえますか？"
            else -> "Hi, this is a test of my voice. How do I sound?"
        }
    }
}
