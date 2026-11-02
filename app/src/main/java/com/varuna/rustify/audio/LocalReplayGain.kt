package com.varuna.rustify.audio

import android.content.Context
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * ReplayGain for local files (4.10, Musicolet 3.3): the level the user's tagger measured, applied the
 * way [LoudnessStore] applies YouTube's — through the player volume, so only ever down.
 *
 * The core reads the tags (`audio::replaygain`, every format through `lofty`); this decides the
 * volume. ReplayGain aims at -18 LUFS and YouTube at about -14, so a 4 dB pre-amp by default puts a
 * tagged album next to a normalised stream instead of a step quieter; the peak keeps it from
 * clipping where that pre-amp would. Pure but for [read], so tested on the JVM.
 */
object LocalReplayGain {
    const val PREF_MODE = "replaygain_mode"
    const val PREF_PREAMP_DB = "replaygain_preamp_db"
    const val DEFAULT_PREAMP_DB = 4

    enum class Mode { OFF, TRACK, ALBUM }

    @Serializable
    data class Tags(
        val trackGainDb: Double? = null,
        val albumGainDb: Double? = null,
        val trackPeak: Double? = null,
        val albumPeak: Double? = null,
    )

    private val cache = ConcurrentHashMap<String, Tags>()

    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(prefs(context).getString(PREF_MODE, Mode.TRACK.name)!!)
    }.getOrDefault(Mode.TRACK)

    fun preampDb(context: Context): Int = prefs(context).getInt(PREF_PREAMP_DB, DEFAULT_PREAMP_DB)

    /**
     * Player volume for [tags]: the album gain in album mode and the track gain otherwise, each
     * falling back to the other, plus [preampDb]; capped by the peak and at 1, floored so a bad tag
     * cannot make a song inaudible. Untagged files play as they are.
     */
    fun volumeFor(tags: Tags?, mode: Mode, preampDb: Int): Float {
        if (tags == null || mode == Mode.OFF) return 1f
        val album = mode == Mode.ALBUM
        val gain = (if (album) tags.albumGainDb ?: tags.trackGainDb else tags.trackGainDb ?: tags.albumGainDb)
            ?: return 1f
        val peak = if (album) tags.albumPeak ?: tags.trackPeak else tags.trackPeak ?: tags.albumPeak
        var linear = 10.0.pow((gain + preampDb) / 20.0)
        if (peak != null && peak > 0) linear = minOf(linear, 1.0 / peak)
        return linear.toFloat().coerceIn(0.1f, 1f)
    }

    /** The tags of a `local:` track, read once per run. Off the main thread: it opens the file. */
    fun read(context: Context, trackId: String): Tags? {
        cache[trackId]?.let { return it }
        val uri = android.net.Uri.parse(trackId.removePrefix("local:"))
        val tags = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val json = NativeEngine.readReplayGain("/proc/self/fd/${pfd.fd}")
                RustifyJson.decodeFromString(Tags.serializer(), json)
            }
        }.getOrNull() ?: return null
        cache[trackId] = tags
        return tags
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
}
