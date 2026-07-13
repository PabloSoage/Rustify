package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository

/**
 * E90 — Motor del DJ. Orquesta el flujo completo (ver docs/90-ai-dj-assistant.md §3):
 *  1. Construye el [DjContext] (métricas + estado del player) vía [DjContextBuilder].
 *  2. Elige el [DjProvider] según [DjSettings.mode] y le pide un [DjPlan].
 *  3. Resuelve las semillas del plan a **tracks reales** vía [SpotifyRepository]
 *     (search de artistas/tracks/queries + radio de la mejor semilla), deduplica y
 *     reparte por artista para transiciones suaves.
 *
 * NO toca AudioPlayerService: devuelve el resultado y el llamador (DjScreen) encola con los
 * métodos públicos `loadPlaylist` / `enqueueAll`.
 */
class DjEngine(
    private val context: Context,
    private val repo: SpotifyRepository
) {
    data class Result(val intro: String, val tracks: List<FullTrack>)

    /**
     * Genera una sesión de DJ. [request] vacío ⇒ automix desde la semilla (now playing / top).
     */
    suspend fun run(
        request: String,
        nowPlaying: FullTrack?,
        queue: List<FullTrack>,
        targetCount: Int = 30
    ): Result {
        val djContext = DjContextBuilder.build(context, nowPlaying, queue)
        val provider = providerFor()
        val plan = provider.plan(djContext, request)

        // Si el provider ya devolvió tracks resueltas, respétalas; si no, resuelve las semillas.
        val resolved = if (plan.tracks.isNotEmpty()) {
            plan.tracks
        } else {
            resolveSeeds(plan.seeds, nowPlaying, targetCount)
        }

        val alreadyQueued = queue.mapNotNull { it.id }.toHashSet()
        val ordered = spreadByArtist(
            resolved.filter { it.id != null && it.id !in alreadyQueued }.distinctBy { it.id }
        ).take(targetCount)

        return Result(intro = plan.intro, tracks = ordered)
    }

    private fun providerFor(): DjProvider = when (DjSettings.mode(context)) {
        DjMode.HEURISTIC -> HeuristicDjProvider()
        DjMode.LOCAL -> LocalDjProvider()
        DjMode.API -> ApiDjProvider(
            baseUrl = DjSettings.apiBaseUrl(context),
            model = DjSettings.apiModel(context),
            apiKey = DjSettings.apiKey(context)
        )
    }

    /**
     * Resuelve semillas blandas a tracks. Estrategia:
     *  - Cada semilla se convierte en tracks buscando (search) o resolviendo un artista → top tracks.
     *  - La "mejor" semilla (la primera track resuelta) alimenta [SpotifyRepository.getTrackRadio],
     *    que es la señal principal de similitud (Spotify calcula la afinidad).
     *  - Si el pool queda corto, se expande con related artists de la semilla.
     */
    private suspend fun resolveSeeds(
        seeds: List<DjSeed>,
        nowPlaying: FullTrack?,
        targetCount: Int
    ): List<FullTrack> {
        val pool = LinkedHashMap<String, FullTrack>()
        fun add(tracks: List<FullTrack>) {
            tracks.forEach { t -> t.id?.let { id -> if (!pool.containsKey(id)) pool[id] = t } }
        }

        var radioSeedTrackId: String? = nowPlaying?.id

        for (seed in seeds) {
            if (pool.size >= targetCount * 2) break
            runCatching {
                when (seed.type) {
                    DjSeed.Type.ARTIST -> {
                        val artist = repo.searchArtists(seed.value, limit = 1).items.firstOrNull()
                        if (artist != null) {
                            val top = repo.getArtistTopTracks(artist.id, limit = 10).items
                            add(top)
                            if (radioSeedTrackId == null) radioSeedTrackId = top.firstOrNull()?.id
                        }
                    }
                    DjSeed.Type.TRACK, DjSeed.Type.QUERY -> {
                        val found = repo.searchTracks(seed.value, limit = 8).items
                        add(found)
                        if (radioSeedTrackId == null) radioSeedTrackId = found.firstOrNull()?.id
                    }
                }
            }
        }

        // Señal principal: radio de la mejor semilla (≈50 tracks afines).
        radioSeedTrackId?.let { id ->
            runCatching { add(repo.getTrackRadio(id)) }
        }

        // Refuerzo si hay pocos: related artists de la primera semilla de artista.
        if (pool.size < targetCount) {
            val artistSeed = seeds.firstOrNull { it.type == DjSeed.Type.ARTIST }?.value
                ?: nowPlaying?.artists?.firstOrNull()?.name
            if (artistSeed != null) {
                runCatching {
                    val artist = repo.searchArtists(artistSeed, limit = 1).items.firstOrNull()
                    if (artist != null) {
                        val related = repo.getRelatedArtists(artist.id, limit = 5).items
                        related.forEach { ra ->
                            if (pool.size >= targetCount * 2) return@forEach
                            runCatching { add(repo.getArtistTopTracks(ra.id, limit = 5).items) }
                        }
                    }
                }
            }
        }

        return pool.values.toList()
    }

    /**
     * Evita dos canciones seguidas del mismo artista (transición suave). Reparte round-robin por
     * artista principal; determinista dada la misma entrada (sin aleatoriedad).
     */
    private fun spreadByArtist(tracks: List<FullTrack>): List<FullTrack> {
        if (tracks.size <= 2) return tracks
        val byArtist = LinkedHashMap<String, ArrayDeque<FullTrack>>()
        tracks.forEach { t ->
            val key = t.artists.firstOrNull()?.id?.ifBlank { null }
                ?: t.artists.firstOrNull()?.name?.lowercase()
                ?: "?"
            byArtist.getOrPut(key) { ArrayDeque() }.add(t)
        }
        val result = ArrayList<FullTrack>(tracks.size)
        var lastKey: String? = null
        while (result.size < tracks.size) {
            var progressed = false
            for ((key, deque) in byArtist) {
                if (deque.isEmpty()) continue
                if (key == lastKey && byArtist.count { it.value.isNotEmpty() } > 1) continue
                result.add(deque.removeFirst())
                lastKey = key
                progressed = true
            }
            // Salvaguarda: si todo lo restante es del mismo artista, vacíalo para no bucle infinito.
            if (!progressed) {
                byArtist.values.forEach { d -> while (d.isNotEmpty()) result.add(d.removeFirst()) }
            }
        }
        return result
    }
}
