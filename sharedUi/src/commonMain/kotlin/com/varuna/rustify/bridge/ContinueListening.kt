package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import com.varuna.rustify.util.optArray
import com.varuna.rustify.util.optBoolean
import com.varuna.rustify.util.optInt
import com.varuna.rustify.util.optLong
import com.varuna.rustify.util.optObject
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.optStringOrNull
import com.varuna.rustify.util.parseArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Continue listening" — the Kotlin face of `core_engine/src/player/session.rs`.
 *
 * Rustify already remembered where you were, but only in one place at a time: start an album,
 * switch to a playlist, and the album was gone. This keeps that per **context**, so Home can offer
 * back the last few things you were in the middle of.
 *
 * Everything a row needs to draw — the label, the subtitle, the image — is stored with the session,
 * so the row appears with no network at all. That is the difference between a row that is there
 * when you open the app and one that arrives a second later.
 */
data class ListeningSession(
    /** `"album:ID"`, `"playlist:ID"`, `"radio:ID"` — stable per context, which is what makes
     *  coming back update the entry you had rather than adding another. */
    val id: String,
    val label: String,
    val subtitle: String,
    val imageUrl: String,
    /**
     * The title of the track that was playing.
     *
     * The row is labelled with the *album*, and the bar under it is progress through the **track**.
     * Without the track's name on the row that bar reads as progress through the album, which is
     * what it looked like the first time this was used on a phone. Empty for sessions stored before
     * this field existed.
     */
    val trackTitle: String,
    /**
     * The artist of that track.
     *
     * With [trackTitle] and [imageUrl] this is enough to rebuild a playable stand-in for the track
     * with **no network at all** — see [stub]. Empty for sessions stored before this field existed.
     */
    val trackArtist: String,
    /**
     * The artwork of that track — not [imageUrl], which is the **context's**. A playlist's cover is
     * not its songs' cover, and a stand-in drawn with it put the playlist's cover in the miniplayer as
     * the song's. Empty for sessions stored before this field existed: see [stub].
     */
    val trackImageUrl: String = "",
    /** Track ids in play order. */
    val queue: List<String>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    /**
     * Shuffle was on. [queue] is then already in the shuffled order (the core's `PlayerState`
     * keeps the shuffle *in* the order), so resuming plays it in that order: see [playOrder].
     * False for sessions stored before the app wrote it, which resume in the list's order.
     */
    val shuffle: Boolean = false
) {
    /** The track to resume on, or null if the stored index no longer makes sense. */
    val currentTrackId: String? get() = queue.getOrNull(index)

    /** 0f..1f through the current track, for a progress line under the row. */
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * What to put under the label: which track, and where it sits in the context.
     *
     * Falls back to the stored subtitle for sessions written before [trackTitle] existed, so an
     * upgrade does not blank the rows already on the home screen.
     */
    val positionLabel: String
        get() = when {
            trackTitle.isBlank() -> subtitle
            queue.size <= 1 -> trackTitle
            else -> "${index + 1}/${queue.size} · $trackTitle"
        }

    /**
     * A playable stand-in for the track this session left off on, built **with no network at all**.
     *
     * This is what makes resuming feel instant. Resuming used to refetch the whole context first —
     * up to twenty round trips for a long playlist — and only then start playing, so a track sitting
     * in the cache or on the device still took seconds to begin. Everything the player needs to
     * start is already stored here: the id to resolve, the title and artist for the notification,
     * and the artwork.
     *
     * It is a *stand-in*: the real list replaces it a moment later, without interrupting playback.
     * Null when the session predates these fields or its index no longer points anywhere, in which
     * case the caller falls back to fetching first.
     */
    fun stub(): FullTrack? {
        val trackId = currentTrackId ?: return null
        if (trackTitle.isBlank()) return null
        // The track's own artwork; failing that, the context's only where it is the same picture —
        // an album. For a playlist or a radio, no artwork beats the wrong one until the real track
        // arrives a moment later.
        val artwork = trackImageUrl.ifBlank {
            if (com.varuna.rustify.player.ContextRef.parse(id) is com.varuna.rustify.player.ContextRef.Album) imageUrl
            else ""
        }
        return FullTrack(
            id = trackId,
            name = trackTitle,
            externalUri = "",
            explicit = false,
            durationMs = durationMs.toInt(),
            isrc = "",
            artists = if (trackArtist.isBlank()) emptyList()
                      else listOf(
                          SimpleArtist(id = "", name = trackArtist, externalUri = "", images = null)
                      ),
            album = if (artwork.isBlank()) null else SimpleAlbum(
                id = "",
                name = label,
                externalUri = "",
                releaseDate = null,
                releaseDatePrecision = null,
                images = listOf(SpotifyImage(url = artwork, height = null, width = null)),
                artists = emptyList(),
                albumType = null
            )
        )
    }

    /**
     * [fetched] — the context's whole list, in its own order — rearranged into the order this
     * session was playing, or null when shuffle was off and the list's order is the one to use.
     *
     * Without it a shuffled session came back in the list's order: the song was right and everything
     * after it was the album from that point on. Each stored id takes the next copy of that track,
     * so a playlist that repeats a song keeps both; a stored id no longer in the list is skipped,
     * and a track added to the list since is played after the rest, in the list's order.
     */
    fun playOrder(fetched: List<FullTrack>): List<FullTrack>? {
        if (!shuffle) return null
        val unused = fetched.indices.groupBy { fetched[it].id }
            .mapValues { (_, at) -> ArrayDeque(at) }
        val taken = BooleanArray(fetched.size)
        val ordered = ArrayList<FullTrack>(fetched.size)
        for (id in queue) {
            val at = unused[id]?.removeFirstOrNull() ?: continue
            taken[at] = true
            ordered += fetched[at]
        }
        fetched.forEachIndexed { at, track -> if (!taken[at]) ordered += track }
        return ordered
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("subtitle", subtitle)
        put("image_url", imageUrl)
        put("track_title", trackTitle)
        put("track_artist", trackArtist)
        put("track_image_url", trackImageUrl)
        put("duration_ms", durationMs)
        put("updated_at_ms", updatedAtMs)
        put("state", buildJsonObject {
            put("queue", JsonArray(queue.map { JsonPrimitive(it) }))
            put("index", index)
            put("position_ms", positionMs)
            put("shuffle", shuffle)
        })
    }

    companion object {
        internal fun fromJson(json: JsonObject): ListeningSession? {
            val state = json["state"].optObject() ?: return null
            val queueJson = state["queue"].optArray() ?: JsonArray(emptyList())
            val queue = queueJson.mapNotNull { it.optStringOrNull() }
            if (queue.isEmpty()) return null
            return ListeningSession(
                id = json["id"].optString(),
                label = json["label"].optString(),
                subtitle = json["subtitle"].optString(),
                imageUrl = json["image_url"].optString(),
                trackTitle = json["track_title"].optString(),
                trackArtist = json["track_artist"].optString(),
                trackImageUrl = json["track_image_url"].optString(),
                queue = queue,
                index = state["index"].optInt(),
                positionMs = state["position_ms"].optLong(),
                durationMs = json["duration_ms"].optLong(),
                updatedAtMs = json["updated_at_ms"].optLong(),
                shuffle = state["shuffle"].optBoolean()
            )
        }
    }
}

object ContinueListening {

    private const val TAG = "ContinueListening"

    /**
     * How often progress is worth writing down.
     *
     * A tick arrives several times a second and a write per tick would be a disk write per tick.
     * Twenty seconds is fine: the worst case is resuming twenty seconds earlier than you left, and
     * nobody notices that. What people do notice is a phone that never sleeps.
     */
    const val RECORD_INTERVAL_MS = 20_000L

    /** Records progress. Anything not worth resuming removes the entry instead of updating it. */
    suspend fun record(session: ListeningSession) {
        runCatching { NativeEngine.recordListening(session.toJson().toString()) }
            .onFailure { Log.w(TAG, "could not record ${session.id}", it) }
    }

    /** The contexts to offer, newest first. Never throws: a home-screen row is not worth an error. */
    suspend fun list(): List<ListeningSession> = runCatching {
        val array = parseArray(NativeEngine.listContinueListening())
        array.mapNotNull { element ->
            element.optObject()?.let { ListeningSession.fromJson(it) }
        }
    }.onFailure { Log.w(TAG, "could not read the continue-listening list", it) }
        .getOrDefault(emptyList())

    /** Drops one context. */
    suspend fun forget(id: String) {
        runCatching { NativeEngine.forgetListening(id) }
            .onFailure { Log.w(TAG, "could not forget $id", it) }
    }

    /** Drops all of them. */
    suspend fun clear() {
        runCatching { NativeEngine.forgetListening("") }
            .onFailure { Log.w(TAG, "could not clear the continue-listening list", it) }
    }
}
