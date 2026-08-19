package com.varuna.rustify.bridge

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
    /** Track ids in play order. */
    val queue: List<String>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
) {
    /** The track to resume on, or null if the stored index no longer makes sense. */
    val currentTrackId: String? get() = queue.getOrNull(index)

    /** 0f..1f through the current track, for a progress line under the row. */
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("subtitle", subtitle)
        put("image_url", imageUrl)
        put("duration_ms", durationMs)
        put("updated_at_ms", updatedAtMs)
        put("state", JSONObject().apply {
            put("queue", JSONArray().also { array -> queue.forEach { array.put(it) } })
            put("index", index)
            put("position_ms", positionMs)
        })
    }

    companion object {
        internal fun fromJson(json: JSONObject): ListeningSession? {
            val state = json.optJSONObject("state") ?: return null
            val queueJson = state.optJSONArray("queue") ?: JSONArray()
            val queue = (0 until queueJson.length()).mapNotNull { queueJson.optString(it, null) }
            if (queue.isEmpty()) return null
            return ListeningSession(
                id = json.optString("id"),
                label = json.optString("label"),
                subtitle = json.optString("subtitle"),
                imageUrl = json.optString("image_url"),
                queue = queue,
                index = state.optInt("index"),
                positionMs = state.optLong("position_ms"),
                durationMs = json.optLong("duration_ms"),
                updatedAtMs = json.optLong("updated_at_ms")
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
        val array = JSONArray(NativeEngine.listContinueListening())
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { ListeningSession.fromJson(it) }
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
