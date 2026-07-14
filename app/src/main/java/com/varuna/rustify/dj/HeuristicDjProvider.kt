package com.varuna.rustify.dj

/**
 * E90 — Provider heurístico (offline, sin LLM). Interpreta peticiones simples por reglas (bilingüe
 * es/en) y las mapea a semillas + una frase de intro. NO hace red: solo produce un [DjPlan] con
 * [DjSeed]s que el [DjEngine] resuelve luego (radio/related/search).
 *
 * La intro se genera en el idioma de la app/sistema ([DjContext.language]); el matching de palabras
 * clave es bilingüe. (El DJ autónomo hablado usa [DjPhrases], que respeta el idioma de la voz.)
 */
class HeuristicDjProvider : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        val req = request.trim().lowercase()
        val es = context.language.startsWith("es")
        fun l(esText: String, enText: String) = if (es) esText else enText

        val seeds = mutableListOf<DjSeed>()
        val introParts = mutableListOf<String>()

        // 1) "más de X" / "more X" / "like X".
        extractMoreOf(req)?.let { target ->
            seeds += DjSeed(DjSeed.Type.QUERY, target)
            introParts += l("Poniendo más de $target", "Playing more $target")
        }

        // 2) Vibe aproximada por palabras clave (mood-por-texto).
        val vibe = detectVibe(req, es)
        if (vibe != null) {
            seeds += DjSeed(DjSeed.Type.QUERY, vibe.query)
            introParts += vibe.intro
        }

        // 3) Descubrimiento vs conocido.
        val discovery = req.containsAny("descubr", "discover", "nuevo", "new music", "sorpr", "surprise")
        val familiar = req.containsAny("conocid", "familiar", "favorit", "lo de siempre", "my favor")

        if (discovery && context.topArtists.isNotEmpty()) {
            context.topArtists.take(3).forEach { seeds += DjSeed(DjSeed.Type.ARTIST, it) }
            introParts += l("Buscando cosas nuevas para ti", "Digging up something new for you")
        }
        if (familiar) {
            context.topTracks.take(3).forEach { seeds += DjSeed(DjSeed.Type.TRACK, it) }
            introParts += l("Volviendo a lo que ya te gusta", "Back to what you already love")
        }

        // 4) Semilla base: now playing, o top del usuario si no hay nada sonando.
        if (seeds.isEmpty()) {
            context.nowPlaying?.let {
                seeds += DjSeed(DjSeed.Type.TRACK, it)
                introParts += l("Continuando el hilo de lo que suena", "Keeping the vibe of what's playing")
            }
            if (seeds.isEmpty()) {
                context.topTracks.firstOrNull()?.let { seeds += DjSeed(DjSeed.Type.TRACK, it) }
                context.topArtists.take(2).forEach { seeds += DjSeed(DjSeed.Type.ARTIST, it) }
                if (seeds.isNotEmpty()) introParts += l("Montando una sesión con tu estilo", "Putting together a set in your style")
            }
        }

        val intro = when {
            introParts.isNotEmpty() -> introParts.joinToString(". ") + "."
            else -> l("Aquí tu DJ. Vamos con una selección para ti.", "Here's your DJ. Let's spin a selection for you.")
        }
        return DjPlan(intro = intro, seeds = seeds)
    }

    private data class Vibe(val query: String, val intro: String)

    private fun detectVibe(req: String, es: Boolean): Vibe? {
        fun l(esText: String, enText: String) = if (es) esText else enText
        return when {
            req.containsAny("tranquil", "chill", "relax", "calma", "suave", "study", "estudiar", "focus", "concentr") ->
                Vibe("chill relax calm", l("Bajando el ritmo", "Slowing things down"))
            req.containsAny("energí", "energy", "fiesta", "party", "hype", "workout", "gym", "correr", "run", "baila", "dance") ->
                Vibe("energetic party dance", l("Subiendo la energía", "Turning up the energy"))
            req.containsAny("triste", "sad", "melanc", "llov", "rain") ->
                Vibe("sad melancholic", l("Poniendo algo más melancólico", "Going a bit more melancholic"))
            req.containsAny("feliz", "happy", "alegr", "good vibes") ->
                Vibe("happy upbeat feel good", l("Poniendo buen rollo", "Bringing the good vibes"))
            else -> null
        }
    }

    /**
     * Extrae el objetivo de una petición del tipo "más de X" / "more X" / "like X".
     * Devuelve el texto tras la preposición, o null si no aplica.
     */
    private fun extractMoreOf(req: String): String? {
        val markers = listOf("más de ", "mas de ", "more of ", "more ", "like ", "similar a ", "parecido a ")
        for (m in markers) {
            val i = req.indexOf(m)
            if (i >= 0) {
                val rest = req.substring(i + m.length).trim()
                if (rest.isNotBlank() && rest.length in 2..60) return rest
            }
        }
        return null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
