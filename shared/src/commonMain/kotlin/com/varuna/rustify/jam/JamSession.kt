// shared/src/commonMain/kotlin/com/varuna/rustify/jam/JamSession.kt
package com.varuna.rustify.jam

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.random.Random

/**
 * The Jam calls the desktop and iOS make (`core_engine/src/jam/`), JSON in and out. One per way of
 * reaching the core: `JvmJamCore` (JNI) and `IosJamCore` (C ABI).
 */
interface JamCore {
    /** A fresh room code, as a JSON string. Memory only. */
    fun newCode(): String
    suspend fun join(configJson: String): String
    /** Every event within [timeoutMs], as a JSON array. */
    suspend fun poll(timeoutMs: Int): String
    suspend fun publishState(stateJson: String): String
    suspend fun send(commandJson: String): String
    suspend fun kick(id: String): String
    suspend fun leave(): String
}

/**
 * Jam, listening together, on the platforms that play through [QueuePlayer] — the same room as the
 * phone's (`player/Jam.kt` on Android), over the same broker and encryption, which are the core's.
 *
 * - **Host:** publishes the queue, the current song, play/pause and the position whenever they
 *   change, and the position every 10 s while playing; does what guests ask.
 * - **Guest:** plays the host's queue in step, within [DRIFT_MS] of the same place. Next and previous
 *   are asked of the host, so the room moves together. Pausing stays local and is never sent: a
 *   guest stepping away must not stop the room.
 *
 * Runs on the [scope]'s thread, which must be the player's.
 */
class JamSession(
    private val scope: CoroutineScope,
    private val core: JamCore,
    private val player: QueuePlayer,
    private val settings: KeyValueStore,
    private val deviceName: String,
) {
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

    private val jobs = mutableListOf<Job>()
    private var revision = 0L
    private var lastApplied = -1L
    private var applying = false

    val broker: String get() = settings.getString(KEY_BROKER)?.ifBlank { null } ?: DEFAULT_BROKER
    val port: Int get() = settings.getLong(KEY_PORT, DEFAULT_PORT.toLong()).toInt()
    val name: String get() = settings.getString(KEY_NAME)?.takeIf { it.isNotBlank() } ?: deviceName

    fun setBroker(host: String, port: Int) {
        settings.putString(KEY_BROKER, host.trim())
        settings.putLong(KEY_PORT, port.toLong())
    }

    fun setName(name: String) = settings.putString(KEY_NAME, name.trim())

    private fun participantId(): String = settings.getString(KEY_ID) ?: buildString {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }.also { settings.putString(KEY_ID, it) }

    /** Opens a room; answers null, or why it could not. */
    suspend fun host(): String? {
        val code = runCatching { RustifyJson.parseToJsonElement(core.newCode()).jsonPrimitive.content }.getOrNull()
            ?: return "no code"
        return start(code, host = true)
    }

    suspend fun join(code: String): String? = start(code, host = false)

    private suspend fun start(code: String, host: Boolean): String? {
        leave()
        val config = buildJsonObject {
            put("brokerHost", broker)
            put("brokerPort", port)
            put("tls", port == 8883)
            put("code", code)
            put("id", participantId())
            put("name", name)
            put("host", host)
        }
        val answer = runCatching { RustifyJson.parseToJsonElement(core.join(config.toString())) as? JsonObject }.getOrNull()
        if (answer?.get("success")?.jsonPrimitive?.booleanOrNull != true) {
            val why = answer?.get("error")?.jsonPrimitive?.contentOrNull ?: "could not join"
            _ui.value = Ui(error = why)
            return why
        }
        revision = 0
        lastApplied = -1
        _ui.value = Ui(role = if (host) Role.HOST else Role.GUEST, code = pretty(code), connected = true)
        if (!host) player.intercept = ::forward
        jobs += scope.launch { pollLoop() }
        if (host) jobs += scope.launch { publishLoop() }
        return null
    }

    /** "h2bt7xc9p3xa" → "H2BT-7XC9-P3XA", for showing; the core reads either. */
    fun pretty(code: String): String = code.filter { it.isLetterOrDigit() }.uppercase().chunked(4).joinToString("-")

    fun leave() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        player.intercept = null
        if (_ui.value.role != Role.NONE) scope.launch { runCatching { core.leave() } }
        _ui.value = Ui()
    }

    /**
     * [leave] for a process about to end: waits until the room has been told (presence cleared, and
     * the state too when hosting), which [leave] only launches.
     */
    suspend fun leaveNow() {
        val inRoom = _ui.value.role != Role.NONE
        jobs.forEach { it.cancel() }
        jobs.clear()
        player.intercept = null
        _ui.value = Ui()
        if (inRoom) runCatching { core.leave() }
    }

    fun kick(id: String) {
        scope.launch { runCatching { core.kick(id) } }
    }

    /** Guest: next and previous go to the host. */
    private fun forward(what: String): Boolean {
        if (_ui.value.role != Role.GUEST || applying) return false
        scope.launch { runCatching { core.send("""{"type":"$what"}""") } }
        return true
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val raw = runCatching { core.poll(2_000) }.getOrNull() ?: continue
            val events = runCatching { RustifyJson.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: continue
            events.forEach { (it as? JsonObject)?.let(::handle) }
        }
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun handle(e: JsonObject) {
        when (e.text("type")) {
            "connected" -> _ui.value = _ui.value.copy(connected = true, error = null)
            "disconnected" -> _ui.value = _ui.value.copy(connected = false, error = e.text("reason"))
            "presence" -> _ui.value = _ui.value.copy(
                participants = (e["participants"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                    Participant(it.text("id"), it.text("name"), it["host"]?.jsonPrimitive?.booleanOrNull == true)
                },
            )
            "kicked" -> {
                leave()
                _ui.value = Ui(kicked = true)
            }
            "state" -> (e["state"] as? JsonObject)?.let(::applyState)
            "command" -> ((e["command"] as? JsonObject)?.get("command") as? JsonObject)?.let(::obey)
        }
    }

    /** Host: what a guest asked for. */
    private fun obey(c: JsonObject) {
        when (c.text("type")) {
            "next" -> player.next()
            "previous" -> player.previous()
            "play" -> player.play()
            "pause" -> player.pause()
            "jumpTo" -> c["index"]?.jsonPrimitive?.intOrNull?.let { player.jumpTo(it) }
            // Appending to the queue is not something the shared player does yet (docs 25).
            "suggest" -> Log.i(TAG, "a guest suggested songs; not supported on this platform yet")
        }
    }

    /** Where the host is now, from what it published and when. */
    private fun expectedPosition(positionMs: Long, atMs: Long, playing: Boolean, nowMs: Long): Long {
        val inFlight = nowMs - atMs
        // Clocks of two devices agree to about a second; anything wild is a clock that does not.
        return if (playing && inFlight in 0..60_000) positionMs + inFlight else positionMs
    }

    /** Guest: follows the host. */
    private fun applyState(s: JsonObject) {
        val rev = s["revision"]?.jsonPrimitive?.longOrNull ?: return
        if (rev <= lastApplied) return
        lastApplied = rev
        val tracks = (s["tracks"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { RustifyJson.decodeFromJsonElement(FullTrack.serializer(), it) }.getOrNull()
        }
        if (tracks.isEmpty()) return
        val index = (s["index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, tracks.lastIndex)
        val playing = s["playing"]?.jsonPrimitive?.booleanOrNull == true
        val expected = expectedPosition(
            s["positionMs"]?.jsonPrimitive?.longOrNull ?: 0,
            s["atMs"]?.jsonPrimitive?.longOrNull ?: 0,
            playing,
            currentTimeMillis(),
        )
        val ui = player.ui.value
        applying = true
        try {
            when {
                ui.queue.map { it.id } != tracks.map { it.id } -> player.playAll(tracks, index, expected)
                ui.index != index -> player.jumpTo(index, expected)
                abs(ui.positionMs - expected) > DRIFT_MS -> player.seek(expected)
            }
            if (playing) player.play() else player.pause()
        } finally {
            applying = false
        }
    }

    private fun stateJson(): String? {
        val ui = player.ui.value
        if (ui.queue.isEmpty()) return null
        return buildJsonObject {
            put("tracks", JsonArray(ui.queue.map { RustifyJson.encodeToJsonElement(FullTrack.serializer(), it) }))
            put("index", ui.index)
            put("playing", ui.playing)
            put("positionMs", ui.positionMs)
            put("atMs", currentTimeMillis())
            put("revision", ++revision)
        }.toString()
    }

    /** Host: publishes on every change of song, queue or play state, and the position every 10 s. */
    private suspend fun publishLoop() {
        val changes = scope.launch {
            player.ui.map { Triple(it.queue.map { t -> t.id }, it.index, it.playing) }
                .distinctUntilChanged()
                .collect { publishNow() }
        }
        try {
            while (scope.isActive) {
                delay(POSITION_EVERY_MS)
                if (player.ui.value.playing) publishNow()
            }
        } finally {
            changes.cancel()
        }
    }

    private suspend fun publishNow() {
        val json = stateJson() ?: return
        runCatching { core.publishState(json) }.onFailure { Log.w(TAG, "could not publish", it) }
    }

    companion object {
        const val DEFAULT_BROKER = "broker.hivemq.com"
        const val DEFAULT_PORT = 8883
        private const val DRIFT_MS = 4_000L
        private const val POSITION_EVERY_MS = 10_000L
        private const val TAG = "JamSession"
        // The keys Android's Jam keeps in rustify_settings.
        private const val KEY_BROKER = "jam_broker"
        private const val KEY_PORT = "jam_port"
        private const val KEY_NAME = "jam_name"
        private const val KEY_ID = "jam_id"
    }
}
