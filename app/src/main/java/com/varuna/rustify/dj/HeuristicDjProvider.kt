package com.varuna.rustify.dj

/**
 * E90 — Provider heurístico (offline, sin LLM). Interpreta peticiones simples por reglas
 * (es/en) y las mapea a semillas + una frase de intro. NO hace red por sí mismo: solo produce
 * un [DjPlan] con [DjSeed]s que el [DjEngine] resuelve luego (radio/related/search).
 *
 * Reglas cubiertas (best-effort, cubre el ~80% de peticiones frecuentes):
 *  - "más de X" / "more X" / "like X"           → semilla de artista/track = X
 *  - "descubrimiento" / "discovery" / "nuevo"   → prioriza queries de descubrimiento
 *  - "conocido" / "familiar" / "mis favoritos"  → semilla desde top artists/tracks del usuario
 *  - "tranquilo/chill/relax" o "energía/fiesta"  → añade una query de vibe (aprox por texto)
 *  - petición vacía                             → automix desde la semilla (now playing o top)
 */
class HeuristicDjProvider : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        val req = request.trim().lowercase()
        val seeds = mutableListOf<DjSeed>()
        val introParts = mutableListOf<String>()

        // 1) "más de X" / "more X" / "like X" / "de X" — extrae el objetivo tras la preposición.
        extractMoreOf(req)?.let { target ->
            seeds += DjSeed(DjSeed.Type.QUERY, target)
            introParts += "Poniendo más de $target"
        }

        // 2) Vibe aproximada por palabras clave (mood-por-texto; ver §3.2 del diseño: aproximado).
        val vibe = detectVibe(req)
        if (vibe != null) {
            seeds += DjSeed(DjSeed.Type.QUERY, vibe.query)
            introParts += vibe.intro
        }

        // 3) Descubrimiento vs conocido.
        val discovery = req.containsAny("descubr", "discover", "nuevo", "new music", "sorpr", "surprise")
        val familiar = req.containsAny("conocid", "familiar", "favorit", "lo de siempre", "my favor")

        if (discovery && context.topArtists.isNotEmpty()) {
            // Expande desde artistas top pero pidiendo descubrimiento (el engine tira de related).
            context.topArtists.take(3).forEach { seeds += DjSeed(DjSeed.Type.ARTIST, it) }
            introParts += "Buscando cosas nuevas para ti"
        }
        if (familiar) {
            context.topTracks.take(3).forEach { seeds += DjSeed(DjSeed.Type.TRACK, it) }
            introParts += "Volviendo a lo que ya te gusta"
        }

        // 4) Semilla base: now playing, o top del usuario si no hay nada sonando.
        if (seeds.isEmpty()) {
            context.nowPlaying?.let {
                seeds += DjSeed(DjSeed.Type.TRACK, it)
                introParts += "Continuando el hilo de lo que suena"
            }
            if (seeds.isEmpty()) {
                context.topTracks.firstOrNull()?.let { seeds += DjSeed(DjSeed.Type.TRACK, it) }
                context.topArtists.take(2).forEach { seeds += DjSeed(DjSeed.Type.ARTIST, it) }
                if (seeds.isNotEmpty()) introParts += "Montando una sesión con tu estilo"
            }
        }

        val intro = when {
            introParts.isNotEmpty() -> introParts.joinToString(". ") + "."
            else -> "Aquí tu DJ. Vamos con una selección para ti."
        }
        return DjPlan(intro = intro, seeds = seeds)
    }

    private data class Vibe(val query: String, val intro: String)

    private fun detectVibe(req: String): Vibe? = when {
        req.containsAny("tranquil", "chill", "relax", "calma", "suave", "study", "estudiar", "focus", "concentr") ->
            Vibe("chill relax calm", "Bajando el ritmo")
        req.containsAny("energí", "energy", "fiesta", "party", "hype", "workout", "gym", "correr", "run", "baila", "dance") ->
            Vibe("energetic party dance", "Subiendo la energía")
        req.containsAny("triste", "sad", "melanc", "llov", "rain") ->
            Vibe("sad melancholic", "Poniendo algo más melancólico")
        req.containsAny("feliz", "happy", "alegr", "good vibes") ->
            Vibe("happy upbeat feel good", "Poniendo buen rollo")
        else -> null
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
