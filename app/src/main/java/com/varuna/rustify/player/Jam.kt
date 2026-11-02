package com.varuna.rustify.player

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jam, listening together (`23` §4): the app side of `core_engine/src/jam/`.
 *
 * - **Host:** publishes the queue, the current song, play/pause and the position whenever they
 *   change (and the position every 10 s while playing); does what guests ask.
 * - **Guest:** plays the host's queue on their own phone, in step: same song, within [DRIFT_MS] of
 *   the same place, playing or paused with the host. Next, previous and choosing a song in the queue
 *   are asked of the host, so everyone moves together. Pausing stays on the guest's own phone (a
 *   call, the headphones coming off) and is never sent: a guest's disconnect must not stop the room.
 *
 * The broker and the encryption are the core's business; this only moves the player.
 */
object Jam {
    const val DEFAULT_BROKER = "broker.hivemq.com"
    const val DEFAULT_PORT = 8883
    private const val DRIFT_MS = 4_000L
    private const val POSITION_EVERY_MS = 10_000L

    enum class Role { NONE, HOST, GUEST }

    data class Participant(val id: String, val name: String, val host: Boolean)

    data class Ui(
        val role: Role = Role.NONE,
        val code: String = "",
        val connected: Boolean = false,
        val participants: List<Participant> = emptyList(),
        val error: String? = null,
        val kicked: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobs = mutableListOf<Job>()
    private var revision = 0L
    private var lastApplied = -1L

    /** True while a guest applies the host's state, so the player does not send it back. */
    @Volatile private var applying = false

    private fun prefs(c: Context) = c.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    fun broker(c: Context): String = prefs(c).getString("jam_broker", DEFAULT_BROKER).orEmpty().ifBlank { DEFAULT_BROKER }
    fun port(c: Context): Int = prefs(c).getInt("jam_port", DEFAULT_PORT)
    fun name(c: Context): String = prefs(c).getString("jam_name", null)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    fun setBroker(c: Context, host: String, port: Int) = prefs(c).edit().putString("jam_broker", host.trim()).putInt("jam_port", port).apply()
    fun setName(c: Context, name: String) = prefs(c).edit().putString("jam_name", name.trim()).apply()

    private fun participantId(c: Context): String =
        prefs(c).getString("jam_id", null) ?: java.util.UUID.randomUUID().toString().take(12).also {
            prefs(c).edit().putString("jam_id", it).apply()
        }

    /**
     * A guest's next/previous/jump goes to the host instead of the local player. Answers whether it
     * was sent (so the caller does nothing more).
     */
    fun forward(command: JSONObject): Boolean {
        if (_ui.value.role != Role.GUEST || applying) return false
        scope.launch { NativeEngine.jamSend(command.toString()) }
        return true
    }

    fun next() = JSONObject().put("type", "next")
    fun previous() = JSONObject().put("type", "previous")
    fun jumpTo(index: Int) = JSONObject().put("type", "jumpTo").put("index", index)

    suspend fun host(context: Context): String? {
        val code = runCatching { org.json.JSONTokener(NativeEngine.jamNewCode()).nextValue() as String }.getOrNull()
            ?: return "no code"
        return start(context, code, host = true)
    }

    suspend fun join(context: Context, code: String): String? = start(context, code, host = false)

    private suspend fun start(context: Context, code: String, host: Boolean): String? {
        leave()
        val cfg = JSONObject()
            .put("brokerHost", broker(context)).put("brokerPort", port(context)).put("tls", port(context) == 8883)
            .put("code", code).put("id", participantId(context)).put("name", name(context)).put("host", host)
        val answer = JSONObject(NativeEngine.jamJoin(cfg.toString()))
        if (!answer.optBoolean("success", false)) {
            val why = answer.optString("error").ifBlank { answer.toString() }
            _ui.value = Ui(error = why)
            return why
        }
        revision = 0; lastApplied = -1
        _ui.value = Ui(role = if (host) Role.HOST else Role.GUEST, code = code, connected = true)
        jobs += scope.launch { pollLoop(context) }
        if (host) jobs += scope.launch { publishLoop(context) }
        return null
    }

    fun leave() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (_ui.value.role != Role.NONE) scope.launch { NativeEngine.jamLeave() }
        _ui.value = Ui()
    }

    fun kick(id: String) {
        scope.launch { NativeEngine.jamKick(id) }
    }

    private suspend fun pollLoop(context: Context) {
        while (scope.isActive) {
            val raw = NativeEngine.jamPoll(2_000)
            val events = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            for (i in 0 until events.length()) handle(context, events.getJSONObject(i))
        }
    }

    private fun handle(context: Context, e: JSONObject) {
        when (e.optString("type")) {
            "connected" -> _ui.value = _ui.value.copy(connected = true, error = null)
            "disconnected" -> _ui.value = _ui.value.copy(connected = false, error = e.optString("reason"))
            "presence" -> {
                val a = e.optJSONArray("participants") ?: JSONArray()
                _ui.value = _ui.value.copy(participants = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Participant(it.optString("id"), it.optString("name"), it.optBoolean("host"))
                })
            }
            "kicked" -> { leave(); _ui.value = Ui(kicked = true) }
            "state" -> applyState(context, e.getJSONObject("state"))
            "command" -> obey(context, e.getJSONObject("command").getJSONObject("command"))
        }
    }

    /** Host: what a guest asked for. */
    private fun obey(context: Context, c: JSONObject) {
        val p = AudioPlayerService.getInstance(context)
        when (c.optString("type")) {
            "next" -> p.skipToNext()
            "previous" -> p.skipToPrevious()
            "play" -> p.play()
            "pause" -> p.pause()
            "jumpTo" -> p.state.value.queue.getOrNull(c.optInt("index", -1))?.id?.let { id ->
                p.playSpecificTrackInQueue(id, queueIndex = c.optInt("index"))
            }
            "suggest" -> {
                val a = c.optJSONArray("tracks") ?: return
                p.enqueueAll((0 until a.length()).map { FullTrack.fromJson(a.getJSONObject(it)) })
            }
        }
    }

    /** Where the host is now, from what it published and when. */
    fun expectedPosition(positionMs: Long, atMs: Long, playing: Boolean, nowMs: Long): Long {
        val inFlight = nowMs - atMs
        // Clocks of two phones agree to about a second; anything wild is a clock that does not.
        return if (playing && inFlight in 0..60_000) positionMs + inFlight else positionMs
    }

    /** Guest: follows the host. */
    private fun applyState(context: Context, s: JSONObject) {
        val rev = s.optLong("revision")
        if (rev <= lastApplied) return
        lastApplied = rev
        val a = s.optJSONArray("tracks") ?: return
        val tracks = (0 until a.length()).map { FullTrack.fromJson(a.getJSONObject(it)) }
        if (tracks.isEmpty()) return
        val index = s.optInt("index").coerceIn(0, tracks.lastIndex)
        val playing = s.optBoolean("playing")
        val expected = expectedPosition(s.optLong("positionMs"), s.optLong("atMs"), playing, System.currentTimeMillis())
        val p = AudioPlayerService.getInstance(context)
        val st = p.state.value
        applying = true
        try {
            when {
                st.queue.map { it.id } != tracks.map { it.id } -> p.loadPlaylist(tracks, index, startPositionMs = expected)
                p.currentQueuePosition(st) != index -> {
                    p.playSpecificTrackInQueue(tracks[index].id.orEmpty(), queueIndex = index)
                    if (expected > DRIFT_MS) p.seekTo(expected)
                }
                kotlin.math.abs(st.positionMs - expected) > DRIFT_MS -> p.seekTo(expected)
            }
            if (playing && !p.state.value.isPlaying) p.play()
            if (!playing && p.state.value.isPlaying) p.pause()
        } finally {
            applying = false
        }
    }

    private fun stateJson(st: AudioPlayerState, index: Int): String {
        val tracks = JSONArray().apply { st.queue.forEach { put(it.toJson()) } }
        return JSONObject()
            .put("tracks", tracks).put("index", index).put("playing", st.isPlaying)
            .put("positionMs", st.positionMs).put("atMs", System.currentTimeMillis()).put("revision", ++revision)
            .toString()
    }

    /** Host: publishes on every change of song, queue or play state, and the position every 10 s. */
    private suspend fun publishLoop(context: Context) {
        val p = AudioPlayerService.getInstance(context)
        val changes = scope.launch {
            p.state.map { st -> Triple(st.queue.map { it.id }, p.currentQueuePosition(st), st.isPlaying) }
                .distinctUntilChanged()
                .collect { publishNow(p) }
        }
        try {
            while (scope.isActive) {
                delay(POSITION_EVERY_MS)
                if (p.state.value.isPlaying) publishNow(p)
            }
        } finally {
            changes.cancel()
        }
    }

    private suspend fun publishNow(p: AudioPlayerService) {
        val st = p.state.value
        if (st.queue.isEmpty()) return
        val json = withContext(Dispatchers.Default) { stateJson(st, p.currentQueuePosition(st)) }
        NativeEngine.jamPublishState(json)
    }
}
