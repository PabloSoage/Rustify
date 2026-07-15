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
 *
 * Robustez (v3.0):
 *  - **Feedback inmediato**: `State.preparing` se activa en cuanto pulsas, así la UI muestra un
 *    spinner y no puedes volver a pulsar (antes no había feedback → doble pulsación → doble voz y
 *    doble encolado).
 *  - **Single-flight**: nunca se solapan dos construcciones de bloque (era lo que metía "un montón"
 *    de temas cuando el observer y una pulsación competían). Se cancela la anterior y gana la última.
 *  - **Variedad real**: el mood inicial y el orden de rotación se barajan (ya no empieza siempre en
 *    "chill"), las selecciones se barajan y se evitan los temas recientes (ya no repite lo mismo).
 */
object DjAutoController {

    /** [preparing] = construyendo un bloque (mostrar spinner, no permitir otra acción). */
    data class State(
        val moodId: String,
        val moodLabel: String,
        val segment: Int,
        val preparing: Boolean = false
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var moodIndex = 0
    private var moodOrder: List<Int> = DjMoods.MOODS.indices.toList()
    private var favoritesProvider: (suspend () -> List<FullTrack>)? = null
    private var repoRef: SpotifyRepository? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var segmentJob: kotlinx.coroutines.Job? = null
    private var advanceGuardId: String? = null
    /** Ids servidos recientemente para no repetirlos entre bloques (ventana rodante). */
    private val recentTrackIds = ArrayDeque<String>()

    val isActive: Boolean get() = _state.value != null

    fun start(context: Context, repo: SpotifyRepository, favoritesProvider: suspend () -> List<FullTrack>) {
        // Ya activo → ignora la re-pulsación (evita reiniciar la sesión y una segunda voz).
        if (isActive) return
        this.repoRef = repo
        this.favoritesProvider = favoritesProvider
        // Baraja mood inicial + orden de rotación: no empieza siempre en "chill".
        this.moodOrder = DjMoods.MOODS.indices.shuffled()
        this.moodIndex = 0
        this.recentTrackIds.clear()
        DjVoice.init(context)
        runSegment(context.applicationContext, first = true)
        startObserver(context.applicationContext)
    }

    /**
     * Auto-avance tipo Livi: cuando el último tema del bloque está a punto de terminar, encola el
     * siguiente mood automáticamente (sin que el usuario tenga que pulsar nada). No avanza mientras
     * se está preparando/construyendo un bloque (evita solapes).
     */
    private fun startObserver(context: Context) {
        observerJob?.cancel()
        advanceGuardId = null
        observerJob = scope.launch {
            val svc = AudioPlayerService.getInstance(context)
            svc.state.collect { st ->
                val cs = _state.value ?: return@collect
                if (cs.preparing || segmentJob?.isActive == true) return@collect
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
        segmentJob?.cancel(); segmentJob = null
        observerJob?.cancel(); observerJob = null
        advanceGuardId = null
        _state.value = null
        DjVoice.stop()
    }

    private fun runSegment(context: Context, first: Boolean) {
        val repo = repoRef ?: return
        // Single-flight: cancela cualquier construcción en curso; solo vale la última petición.
        segmentJob?.cancel()
        // Feedback inmediato (síncrono): la UI ve `preparing` antes de que empiece el trabajo async.
        _state.value = (_state.value ?: State(moodId = "", moodLabel = "", segment = 0)).copy(preparing = true)
        // Rebaraja el orden al completar una vuelta completa, para más variedad a largo plazo.
        if (moodIndex > 0 && moodIndex % moodOrder.size == 0) moodOrder = DjMoods.MOODS.indices.shuffled()
        val mood = DjMoods.MOODS[moodOrder[moodIndex % moodOrder.size]]
        segmentJob = scope.launch {
            val tracks = buildSegment(context, repo, mood)
            if (tracks.isEmpty()) {
                // Sin nada que reproducir: si era el arranque, libera la sesión (el botón vuelve a
                // "Iniciar"); si era un avance, solo quita el spinner y deja seguir el bloque actual.
                if (first) stop() else _state.value = _state.value?.copy(preparing = false)
                return@launch
            }
            val svc = AudioPlayerService.getInstance(context)
            if (first) svc?.loadPlaylist(tracks, 0) else svc?.enqueueAll(tracks)
            recentTrackIds.addAll(tracks.mapNotNull { it.id })
            while (recentTrackIds.size > 60) recentTrackIds.removeFirst()
            _state.value = State(mood.id, mood.label(context), moodIndex + 1, preparing = false)
            // Spoken text uses the VOICE language (separate from app language), from DjPhrases.
            val phrase = DjPhrases.announce(DjSettings.voiceLanguage(context), mood.id, first)
            DjVoice.speak(context, phrase)
        }
    }

    private suspend fun buildSegment(context: Context, repo: SpotifyRepository, mood: DjMood): List<FullTrack> {
        val size = 5
        val source = DjSettings.autoSource(context)
        val favs = runCatching { favoritesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList())
        // Baraja las sugerencias para no servir siempre los primeros resultados de Spotify.
        val suggestions = runCatching { repo.searchTracks(mood.query, limit = 20).items }
            .getOrDefault(emptyList()).shuffled()

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
        // del mood (centroide de sus sugerencias) y baraja la franja más relevante para variar.
        val moodCentroid = DjVectors.centroid(suggestions)

        fun pick(avoidRecent: Boolean): List<FullTrack> {
            fun keep(list: List<FullTrack>) =
                list.filter { it.id != null && (!avoidRecent || it.id !in recentTrackIds) }
            fun pickFavs(pool: List<FullTrack>, n: Int): List<FullTrack> {
                val kept = keep(pool)
                if (kept.isEmpty()) return emptyList()
                return DjVectors.rankBySimilarity(kept, moodCentroid).take(maxOf(n * 4, n)).shuffled().take(n)
            }
            return when (source) {
                // Solo favoritas: las que casan con el mood (rankeadas); si hay muy pocas, cae a todas.
                "favorites" -> pickFavs(if (favForMood.size >= 2) favForMood else favs, size)
                "discover" -> keep(suggestions).take(size)
                else /* balanced */ -> {
                    val f = pickFavs(if (favForMood.isNotEmpty()) favForMood else favs, 3)
                    val s = keep(suggestions).filter { sg -> f.none { it.id == sg.id } }.take(size - f.size)
                    interleave(f, s)
                }
            }.filter { it.id != null }.distinctBy { it.id }.take(size)
        }

        // Primero evitando repeticiones; si la biblioteca es pequeña y se vacía, reintenta sin evitar.
        return pick(avoidRecent = true).ifEmpty { pick(avoidRecent = false) }
    }

    private fun norm(s: String): String = s.trim().lowercase()

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
