package com.varuna.rustify.dj

import android.annotation.SuppressLint
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
 * A mood of the autonomous DJ: a vibe with its search query (English keywords, which match best on
 * Spotify/YouTube) and its i18n labels.
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
 * Autonomous DJ. You just press the button: the DJ announces a mood (with voice), queues a block of
 * ~5 songs for that mood (favorites and/or suggestions per [DjSettings.autoSource]), and on "next"
 * (button or the DJ icon on the Track screen) it changes mood.
 *
 * Singleton: keeps the session alive so the player icon can advance without re-creating anything.
 * Enqueues via [AudioPlayerService] (public methods) without modifying it.
 *
 * Robustness:
 *  - Immediate feedback: `State.preparing` is set as soon as you press, so the UI shows a spinner and
 *    you cannot press again (which would otherwise cause a double voice and double enqueue).
 *  - Single-flight: two block builds never overlap (which used to enqueue far too many songs when the
 *    observer and a press competed). The previous build is cancelled and the latest one wins.
 *  - Real variety: the initial mood and rotation order are shuffled (it no longer always starts on
 *    "chill"), selections are shuffled, and recent tracks are avoided (no more repeats).
 */
object DjAutoController {

    /** [preparing] = building a block (show a spinner, disallow another action). */
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
    // SpotifyRepository only retains the applicationContext (see appCtx), not an Activity → no real leak.
    @SuppressLint("StaticFieldLeak")
    private var repoRef: SpotifyRepository? = null
    private var observerJob: kotlinx.coroutines.Job? = null
    private var segmentJob: kotlinx.coroutines.Job? = null
    private var advanceGuardId: String? = null
    /** Recently served ids, to avoid repeating them across blocks (rolling window). */
    private val recentTrackIds = ArrayDeque<String>()

    val isActive: Boolean get() = _state.value != null

    fun start(context: Context, repo: SpotifyRepository, favoritesProvider: suspend () -> List<FullTrack>) {
        // Already active → ignore the re-press (avoids restarting the session and a second voice).
        if (isActive) return
        this.repoRef = repo
        this.favoritesProvider = favoritesProvider
        // Shuffle the initial mood + rotation order: it does not always start on "chill".
        this.moodOrder = DjMoods.MOODS.indices.shuffled()
        this.moodIndex = 0
        this.recentTrackIds.clear()
        DjVoice.init(context)
        runSegment(context.applicationContext, first = true)
        startObserver(context.applicationContext)
    }

    /**
     * Auto-advance: when the last song of the block is about to end, the next mood is queued
     * automatically (without the user pressing anything). It does not advance while a block is being
     * prepared/built (avoids overlaps).
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

    /** Advances to the next mood ("change mood" button / DJ icon on Track). */
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
        // Single-flight: cancel any build in progress; only the latest request counts.
        segmentJob?.cancel()
        // Immediate (synchronous) feedback: the UI sees `preparing` before the async work starts.
        _state.value = (_state.value ?: State(moodId = "", moodLabel = "", segment = 0)).copy(preparing = true)
        // Reshuffle the order after a full lap, for more variety over time.
        if (moodIndex > 0 && moodIndex % moodOrder.size == 0) moodOrder = DjMoods.MOODS.indices.shuffled()
        val mood = DjMoods.MOODS[moodOrder[moodIndex % moodOrder.size]]
        segmentJob = scope.launch {
            val tracks = buildSegment(context, repo, mood)
            if (tracks.isEmpty()) {
                // Nothing to play: if this was the start, release the session (the button returns to
                // "Start"); if it was an advance, just drop the spinner and keep the current block.
                if (first) stop() else _state.value = _state.value?.copy(preparing = false)
                return@launch
            }
            val svc = AudioPlayerService.getInstance(context)
            // `first` starts the session from scratch. On advances (mood change or auto-advance) we
            // replace the pending block after the current track instead of stacking it on top of the
            // previous one; otherwise the old mood's songs would linger in the queue.
            if (first) svc?.loadPlaylist(tracks, 0) else svc?.replaceAutoQueueAfterCurrent(tracks)
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
        // Shuffle the suggestions so we do not always serve Spotify's first results.
        val suggestions = runCatching { repo.searchTracks(mood.query, limit = 20).items }
            .getOrDefault(emptyList()).shuffled()

        // Without Spotify audio features (API unavailable), we classify the cached favorites by artist
        // overlap with the mood: a favorite "belongs to this mood" if it shares an artist with the
        // mood's suggestions (+ a search for the mood's artists). A robust approximation that still
        // draws on the real favorites list.
        val moodArtists = HashSet<String>()
        suggestions.forEach { t -> t.artists.forEach { a -> moodArtists.add(norm(a.name)) } }
        runCatching { repo.searchArtists(mood.query, limit = 5).items }.getOrNull()
            ?.forEach { moodArtists.add(norm(it.name)) }
        val favForMood = favs.filter { fav -> fav.artists.any { norm(it.name) in moodArtists } }

        // Metadata-vector ranking (DjVectors): orders favorites by cosine similarity to the mood's
        // "sound" (the centroid of its suggestions) and shuffles the most relevant band for variety.
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
                // Favorites only: those matching the mood (ranked); if too few, fall back to all.
                "favorites" -> pickFavs(if (favForMood.size >= 2) favForMood else favs, size)
                "discover" -> keep(suggestions).take(size)
                else /* balanced */ -> {
                    val f = pickFavs(if (favForMood.isNotEmpty()) favForMood else favs, 3)
                    val s = keep(suggestions).filter { sg -> f.none { it.id == sg.id } }.take(size - f.size)
                    interleave(f, s)
                }
            }.filter { it.id != null }.distinctBy { it.id }.take(size)
        }

        // First avoiding repeats; if the library is small and runs dry, retry without avoiding them.
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
