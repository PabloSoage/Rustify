package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.player.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * E90 — Un "mood" del DJ autónomo: una vibe con su query de búsqueda (keywords en inglés, que es lo
 * que mejor matchea en Spotify/YouTube) y sus etiquetas i18n.
 */
data class DjMood(
    val id: String,
    val query: String,
    val labelRes: Int
) {
    fun label(context: Context): String = context.getString(labelRes)
}

object DjMoods {
    val MOODS: List<DjMood> = listOf(
        DjMood("chill", "chill relax calm lofi", R.string.dj_mood_chill),
        DjMood("energetic", "energetic upbeat dance workout", R.string.dj_mood_energetic),
        DjMood("happy", "happy feel good upbeat", R.string.dj_mood_happy),
        DjMood("focus", "focus study instrumental concentration", R.string.dj_mood_focus),
        DjMood("melancholic", "melancholic sad emotional", R.string.dj_mood_melancholic),
    )
}

/**
 * E90 — DJ autónomo tipo "DJ Livi". Tú solo le das al botón: el DJ **anuncia un mood** (con voz),
 * mete un bloque de ~5 canciones de ese mood (favoritas y/o sugerencias según [DjSettings.autoSource])
 * y al pedirle "siguiente" (botón o icono de DJ en la pantalla de Track) **cambia de mood**.
 *
 * Singleton: mantiene la sesión viva para que el icono del reproductor pueda avanzar sin re-crear
 * nada. Encola vía [AudioPlayerService] (métodos públicos), no lo modifica.
 */
object DjAutoController {

    data class State(val moodId: String, val moodLabel: String, val segment: Int)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var moodIndex = 0
    private var favoritesProvider: (suspend () -> List<FullTrack>)? = null
    private var repoRef: SpotifyRepository? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var advanceGuardId: String? = null

    val isActive: Boolean get() = _state.value != null

    fun start(context: Context, repo: SpotifyRepository, favoritesProvider: suspend () -> List<FullTrack>) {
        this.repoRef = repo
        this.favoritesProvider = favoritesProvider
        this.moodIndex = 0
        DjVoice.init(context)
        runSegment(context.applicationContext, first = true)
        startObserver(context.applicationContext)
    }

    /**
     * Auto-avance tipo Livi: cuando el último tema del bloque está a punto de terminar, encola el
     * siguiente mood automáticamente (sin que el usuario tenga que pulsar nada).
     */
    private fun startObserver(context: Context) {
        observerJob?.cancel()
        advanceGuardId = null
        observerJob = scope.launch {
            val svc = AudioPlayerService.getInstance(context)
            svc.state.collect { st ->
                if (_state.value == null) return@collect
                val cur = st.currentTrack ?: return@collect
                val q = st.queue
                val isLast = q.isNotEmpty() && q.last().id == cur.id
                val nearEnd = st.durationMs > 0 && st.positionMs >= st.durationMs - 10_000
                if (isLast && nearEnd && cur.id != advanceGuardId) {
                    advanceGuardId = cur.id
                    moodIndex++
                    runSegment(context, first = false)
                }
            }
        }
    }

    /** Avanza al siguiente mood (botón "cambiar mood" / icono de DJ en Track). */
    fun next(context: Context) {
        if (!isActive) return
        moodIndex++
        runSegment(context.applicationContext, first = false)
    }

    fun stop() {
        observerJob?.cancel(); observerJob = null
        advanceGuardId = null
        _state.value = null
        DjVoice.stop()
    }

    private fun runSegment(context: Context, first: Boolean) {
        val repo = repoRef ?: return
        val mood = DjMoods.MOODS[moodIndex % DjMoods.MOODS.size]
        scope.launch {
            val tracks = buildSegment(context, repo, mood)
            if (tracks.isEmpty()) return@launch
            val svc = AudioPlayerService.getInstance(context)
            if (first) svc?.loadPlaylist(tracks, 0) else svc?.enqueueAll(tracks)
            _state.value = State(mood.id, mood.label(context), moodIndex + 1)
            // Spoken text uses the VOICE language (separate from app language), from DjPhrases.
            val phrase = DjPhrases.announce(DjSettings.voiceLanguage(context), mood.id, first)
            DjVoice.speak(context, phrase)
        }
    }

    private suspend fun buildSegment(context: Context, repo: SpotifyRepository, mood: DjMood): List<FullTrack> {
        val size = 5
        val source = DjSettings.autoSource(context)
        val favs = runCatching { favoritesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList())
        val suggestions = runCatching { repo.searchTracks(mood.query, limit = 12).items }.getOrDefault(emptyList())

        // Sin audio-features de Spotify (API bloqueada), clasificamos las favoritas CACHEADAS por
        // SOLAPAMIENTO DE ARTISTA con el mood: una favorita "es de este mood" si comparte artista con
        // las sugerencias del mood (+ una búsqueda de artistas del mood). Es una aproximación robusta
        // que sí consulta tu lista de favoritas real.
        val moodArtists = HashSet<String>()
        suggestions.forEach { t -> t.artists.forEach { a -> moodArtists.add(norm(a.name)) } }
        runCatching { repo.searchArtists(mood.query, limit = 5).items }.getOrNull()
            ?.forEach { moodArtists.add(norm(it.name)) }
        val favForMood = favs.filter { fav -> fav.artists.any { norm(it.name) in moodArtists } }

        // Metadata-vector ranking (DjVectors): ordena las favoritas por similitud coseno al "sonido"
        // del mood (centroide de sus sugerencias) y luego rota dentro del pool más relevante para variar.
        val moodCentroid = DjVectors.centroid(suggestions)
        fun pickFavs(pool: List<FullTrack>, n: Int): List<FullTrack> {
            if (pool.isEmpty()) return emptyList()
            val top = DjVectors.rankBySimilarity(pool, moodCentroid).take(maxOf(n * 3, n))
            return rotating(top, n)
        }

        val picked: List<FullTrack> = when (source) {
            // Solo favoritas: las que casan con el mood (rankeadas); si hay muy pocas, cae a todas.
            "favorites" -> pickFavs(if (favForMood.size >= 2) favForMood else favs, size)
            "discover" -> suggestions.take(size)
            else /* balanced */ -> {
                val f = pickFavs(if (favForMood.isNotEmpty()) favForMood else favs, 3)
                val s = suggestions.filter { sg -> f.none { it.id == sg.id } }.take(size - f.size)
                interleave(f, s)
            }
        }
        return picked.filter { it.id != null }.distinctBy { it.id }.take(size)
    }

    private fun norm(s: String): String = s.trim().lowercase()

    /** Ventana rotatoria determinista sobre la lista (varía por segmento sin repetir siempre lo mismo). */
    private fun rotating(list: List<FullTrack>, n: Int): List<FullTrack> {
        if (list.isEmpty()) return emptyList()
        val start = (moodIndex * n) % list.size
        return (0 until minOf(n, list.size)).map { list[(start + it) % list.size] }
    }

    private fun interleave(a: List<FullTrack>, b: List<FullTrack>): List<FullTrack> {
        val out = ArrayList<FullTrack>(a.size + b.size)
        val ia = a.iterator(); val ib = b.iterator()
        while (ia.hasNext() || ib.hasNext()) {
            if (ia.hasNext()) out.add(ia.next())
            if (ib.hasNext()) out.add(ib.next())
        }
        return out
    }
}
