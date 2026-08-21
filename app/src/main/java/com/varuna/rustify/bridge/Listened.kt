package com.varuna.rustify.bridge

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * "Already listened", as the screens see it — point I.
 *
 * The marks themselves are a bitfield in the core (`core_engine/src/listened/`), which is what makes
 * a 200-track playlist twenty-five bytes rather than four kilobytes. This is the thin part: read the
 * marks for a list, and give a screen one boolean per row.
 *
 * A bitfield is indexed by **position**, and a playlist's positions move. That is handled in the
 * core, and it is why the current order is handed over on every read rather than stored once —
 * see the module note there for what survives an edit and what deliberately does not.
 */
object Listened {

    private const val TAG = "Listened"

    /**
     * One boolean per id in [trackIds], in the same order.
     *
     * Never throws: a tick on a list is not worth an error path, and the answer when anything is
     * wrong is "nothing is marked", which is the state the screen was in before this existed.
     */
    suspend fun stateFor(contextId: String, trackIds: List<String>): List<Boolean> {
        if (contextId.isBlank() || trackIds.isEmpty()) return List(trackIds.size) { false }
        val queueJson = JSONArray().also { array -> trackIds.forEach { array.put(it) } }.toString()
        return runCatching {
            val answer = NativeEngine.listenedState(contextId, queueJson)
            val array = JSONArray(answer)
            List(trackIds.size) { i -> array.optBoolean(i, false) }
        }.getOrElse {
            Log.w(TAG, "could not read the marks for $contextId", it)
            List(trackIds.size) { false }
        }
    }

    /** Drops one context's marks, or every one when [contextId] is empty. */
    suspend fun forget(contextId: String = "") {
        runCatching { NativeEngine.forgetListened(contextId) }
            .onFailure { Log.w(TAG, "could not forget $contextId", it) }
    }
}

/**
 * The marks for a list, as Compose state.
 *
 * Re-read whenever the list changes **or** [refreshKey] does. The second one exists because nothing
 * else tells a screen that a track finished while it was open — the mark is written by the player
 * service, not by the screen showing it.
 */
@Composable
fun rememberListened(
    contextId: String,
    trackIds: List<String>,
    refreshKey: Any? = null
): List<Boolean> {
    var marks by remember(contextId) { mutableStateOf(emptyList<Boolean>()) }
    LaunchedEffect(contextId, trackIds, refreshKey) {
        marks = Listened.stateFor(contextId, trackIds)
    }
    // Until the read comes back, nothing is marked: the list must draw immediately rather than wait
    // on a tick that is decoration.
    return if (marks.size == trackIds.size) marks else List(trackIds.size) { false }
}
