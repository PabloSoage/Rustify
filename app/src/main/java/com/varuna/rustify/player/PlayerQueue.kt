package com.varuna.rustify.player

import android.util.Log
import com.varuna.rustify.bridge.NativeEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The queue decisions, taken in the Rust core — point H.
 *
 * ## What moved and what did not
 *
 * `AudioPlayerService` is 2 200 lines of ExoPlayer, a foreground service, a notification, a
 * pre-buffer and observable Compose state. None of that moved and none of it should: it is Android,
 * and a core that reimplemented it would be a second Android.
 *
 * What moved is the part that is neither Android nor obvious, and that had **two implementations
 * free to disagree**: what "next" means with repeat on, how that differs from a track simply ending,
 * what "previous" means twenty seconds in, and where the index lands in each case. That lived in
 * `skipToNext`/`skipToPrevious`/`STATE_ENDED` here, and in `core_engine/src/player/mod.rs` with
 * nineteen tests — and the two did not agree. The core did not know the queue could be extended by a
 * radio; this side did not know about wrapping at all.
 *
 * ## Why the index and not the track id
 *
 * A playlist may legitimately contain the same song twice, and a `local:` track may have no id at
 * all. Mapping a returned id back to a row is therefore ambiguous in exactly the cases that already
 * cost this project a bug. So the answer read from the core is [Decision.index] — a position in the
 * list the caller already holds — and the ids sent across are only ever used to keep "the same song"
 * pinned across a shuffle.
 *
 * ## Cost
 *
 * One JNI call per button press, into a pure function: no storage, no network, no `block_on` that
 * waits on anything. It is safe from the main thread and cheap enough not to think about — see the
 * note on `playerReduceNative`.
 */
object PlayerQueue {

    private const val TAG = "PlayerQueue"

    /** What the core decided, in the terms this side acts on. */
    data class Decision(
        /** Where the queue index ends up. Always within the queue, or -1 for an empty one. */
        val index: Int,
        /** Where the track should start. */
        val positionMs: Long,
        /** Load and play [index]. */
        val play: Boolean,
        /** Seek inside what is already loaded — no reload. Null when there is nothing to seek. */
        val seekTo: Long?,
        /** Nothing more to play. */
        val stop: Boolean,
        /**
         * The queue ran forwards off its end and repeat did not save it.
         *
         * Deliberately distinct from [stop]: on Android this is not a stop at all — it is where the
         * autoplay radio comes in. Collapsing the two would mean either a queue that dead-ends where
         * it used to keep playing, or a `Clear` that starts a radio.
         */
        val exhausted: Boolean,
        /** Worth writing to `playback_state.json`. */
        val persist: Boolean
    )

    /** The reducer's `PlayerState`, built from what the service holds. */
    private fun stateJson(
        ids: List<String>,
        index: Int,
        positionMs: Long,
        repeatOne: Boolean,
        shuffle: Boolean
    ): String = JSONObject().apply {
        put("queue", JSONArray(ids))
        // The core indexes from zero and has no "nothing selected"; -1 becomes 0, and an empty
        // queue makes the index irrelevant anyway.
        put("index", index.coerceAtLeast(0))
        put("position_ms", positionMs.coerceAtLeast(0L))
        // Rustify's playback modes are off / shuffle / repeat-one — there is no repeat-all in the
        // UI, so `all` is never sent. The core supports it for the ports that will want it.
        put("repeat", if (repeatOne) "one" else "off")
        put("shuffle", shuffle)
    }.toString()

    /**
     * Asks the core what to do.
     *
     * [ids] must be one entry per row of the caller's queue, in the same order. A row with no id
     * gets a positional stand-in so the lists stay the same length — the core only ever compares
     * these to each other.
     *
     * Only the three navigation actions go through here. **Shuffle deliberately does not**: the
     * core's `SetShuffle` takes the order as an argument precisely because shuffling needs a random
     * source it refuses to hold, and the order Rustify wants is not a plain shuffle — the tracks
     * queued by hand keep their block, immediately after the current song and in the order they
     * were added. That rule is about the user's queue, which is this side's, so it stays here.
     */
    fun decide(
        ids: List<String>,
        index: Int,
        positionMs: Long,
        repeatOne: Boolean,
        shuffle: Boolean,
        action: JSONObject
    ): Decision {
        val fallback = Decision(
            index = index, positionMs = positionMs, play = false, seekTo = null,
            stop = false, exhausted = false, persist = false
        )
        if (ids.isEmpty()) return fallback.copy(index = -1)

        val answer = runCatching {
            NativeEngine.playerReduceNative(
                stateJson(ids, index, positionMs, repeatOne, shuffle),
                action.toString()
            )
        }.getOrElse {
            // A queue that stops responding to its own next button is worse than one that ignores a
            // core that is not there, so this degrades to "nothing happened" rather than throwing.
            Log.e(TAG, "the core could not answer $action", it)
            return fallback
        }

        return runCatching {
            val json = JSONObject(answer)
            val state = json.optJSONObject("state") ?: return fallback
            val effects = json.optJSONArray("effects") ?: JSONArray()

            var play = false
            var seekTo: Long? = null
            var stop = false
            var exhausted = false
            var persist = false
            var startAt = state.optLong("position_ms", 0L)

            for (i in 0 until effects.length()) {
                val effect = effects.optJSONObject(i) ?: continue
                when (effect.optString("type")) {
                    "play" -> {
                        play = true
                        startAt = effect.optLong("positionMs", 0L)
                    }
                    "seek" -> seekTo = effect.optLong("positionMs", 0L)
                    "stop" -> stop = true
                    "queue_exhausted" -> exhausted = true
                    "persist" -> persist = true
                }
            }

            Decision(
                index = state.optInt("index", index).coerceIn(0, ids.lastIndex),
                positionMs = startAt,
                play = play,
                seekTo = seekTo,
                stop = stop,
                exhausted = exhausted,
                persist = persist
            )
        }.getOrElse {
            Log.e(TAG, "could not read the core's answer: $answer", it)
            fallback
        }
    }

    /** Ids for a queue whose rows may not all have one. Stand-ins are unique by position. */
    fun idsFor(rows: List<String?>): List<String> =
        rows.mapIndexed { position, id -> if (id.isNullOrBlank()) "#$position" else id }

    val NEXT: JSONObject get() = JSONObject().put("type", "next")
    val PREVIOUS: JSONObject get() = JSONObject().put("type", "previous")
    val TRACK_ENDED: JSONObject get() = JSONObject().put("type", "track_ended")
}
