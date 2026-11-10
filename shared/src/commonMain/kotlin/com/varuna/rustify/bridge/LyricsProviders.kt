// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/LyricsProviders.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.formUrlEncode
import com.varuna.rustify.platform.httpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/**
 * Lyrics providers (inspired by EeveeSpotify's set). The [LyricsChain] tries the ENABLED
 * providers in the user-configured order (Settings) and returns the first non-empty result — so an
 * upper provider that resolves stops the chain. LRCLIB is enabled by default (current behaviour);
 * the rest are opt-in.
 *
 * Common code since 5.x: the same requests and the same parsing on every platform, the HTTP over
 * the platform's Ktor client (`platform/Http.kt`). What each shows as a provider's name is the
 * platform's ([LyricsProvider.id] is the key).
 *
 * ⚠️ The non-LRCLIB providers are **best-effort** (private/undocumented endpoints, scraping, or
 * rotating tokens) and are NOT tested here; on any failure they return null and the chain falls
 * through to the next provider.
 */
interface LyricsProvider {
    val id: String
    suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult?

    /**
     * The same, with the track's id. Every provider matched by artist and title ignores it; the
     * Spotify one is asked for the track itself, which is the whole of its advantage.
     */
    suspend fun fetchFor(trackId: String, artist: String, title: String, durationSec: Int): LyricsResult? =
        fetch(artist, title, durationSec)
}

/**
 * The providers' two requests, as they were made with `HttpURLConnection`: the same User-Agent, ten
 * seconds to connect and ten to read, the body read as UTF-8, and null for anything but a 2xx or
 * any failure. A cancelled caller is not a failure: that goes on up.
 */
internal object LyricsHttp {
    private const val USER_AGENT = "Mozilla/5.0 (Android) Rustify"

    private val client by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
    }

    suspend fun get(urlStr: String, extraHeaders: Map<String, String> = emptyMap()): String? = answer {
        client.get(urlStr) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }
    }

    suspend fun postForm(urlStr: String, form: Map<String, String>): String? = answer {
        val body = form.entries.joinToString("&") { "${it.key}=${formUrlEncode(it.value)}" }
        client.post(urlStr) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(TextContent(body, ContentType.Application.FormUrlEncoded))
        }
    }

    private suspend fun answer(request: suspend () -> HttpResponse): String? = try {
        val response = request()
        if (response.status.value in 200..299) response.body<ByteArray>().decodeToString() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

// The few reads the providers make into answers they do not own: absent or the wrong shape is null.
private fun parse(text: String): JsonElement? = runCatching { RustifyJson.parseToJsonElement(text) }.getOrNull()
private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
private fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

private fun stripHtml(html: String): String =
    html.replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
        .replace("&gt;", ">").replace("&lt;", "<")
        .lines().joinToString("\n") { it.trim() }.trim()

// ── Providers ──────────────────────────────────────────────────────────────────────────

/**
 * Spotify's own lyrics (4.3), asked for by track id — no "artist + title" guess to get wrong. Needs a
 * Spotify session (measured: the anonymous token is refused); without one, or for a track that is not
 * a Spotify one, it answers null and the next provider is asked. See `core_engine/src/spotify/lyrics.rs`.
 */
object SpotifyLyricsProvider : LyricsProvider {
    override val id = "spotify"

    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = null

    override suspend fun fetchFor(trackId: String, artist: String, title: String, durationSec: Int): LyricsResult? {
        val spotifyId = TrackRef.parse(trackId)?.spotifyId ?: return null
        val json = NativeEngine.getSpotifyLyrics(spotifyId)
        val element = parse(json) ?: return null
        if ((element as? JsonObject)?.get("success") != null) {
            // Says why, once per track: "no account", "no lyrics for this one" and "the token was refused"
            // look the same from the screen (the next provider answers), and only the log tells them apart.
            Log.i("Lyrics", "spotify: none for $spotifyId: ${json.take(160)}")
            return null
        }
        val lyrics = runCatching { RustifyJson.decodeFromJsonElement(SpotifyLyrics.serializer(), element) }
            .getOrNull() ?: return null
        val lines = lyrics.lines.filter { it.words.isNotBlank() }
        Log.i("Lyrics", "spotify: ${lines.size} lines, ${lyrics.syncType}, from ${lyrics.provider}")
        return if (lyrics.syncType == "UNSYNCED") {
            LyricsResult(synced = emptyList(), plain = lines.joinToString("\n") { it.words })
        } else {
            LyricsResult(
                synced = lines.map { LyricLine(it.startMs, it.words) },
                plain = lines.joinToString("\n") { it.words }
            )
        }
    }
}

/** `spotify::lyrics::SpotifyLyrics`, pinned by the `SpotifyLyrics` wire fixture. */
@kotlinx.serialization.Serializable
data class SpotifyLyrics(
    val syncType: String = "",
    val lines: List<SpotifyLyricLine> = emptyList(),
    val provider: String = ""
)

@kotlinx.serialization.Serializable
data class SpotifyLyricLine(val startMs: Long = 0, val words: String = "")

object LrcLibProvider : LyricsProvider {
    override val id = "lrclib"
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        val a = formUrlEncode(artist); val t = formUrlEncode(title)
        fun isIt(j: JsonElement) = LyricsMatch.accepts(
            artist, title, durationSec,
            foundTitle = j.str("trackName"),
            foundArtists = listOfNotNull(j.str("artistName")),
            foundDurationSec = j.str("duration")?.toDoubleOrNull()?.toInt() ?: 0,
        )
        suspend fun byGet(url: String) = LyricsHttp.get(url)?.let { parse(it) }?.takeIf { isIt(it) }?.let { answer(it) }
        (if (durationSec > 0) byGet("https://lrclib.net/api/get?artist_name=$a&track_name=$t&duration=$durationSec") else null)
            ?: byGet("https://lrclib.net/api/get?artist_name=$a&track_name=$t")
            ?: run {
                // A search answers whatever is closest, so the first hit is not the song: the first
                // hit that IS the song is, and none is better than another song's lyrics.
                val body = LyricsHttp.get("https://lrclib.net/api/search?q=" + formUrlEncode("$artist $title")) ?: return@run null
                (parse(body) as? JsonArray)?.firstOrNull { isIt(it) }?.let { answer(it) }
            }
    }
    private fun answer(j: JsonElement): LyricsResult? {
        // LRCLIB says when a song has no words; an instrumental is not a song whose lyrics are missing.
        if (j.str("instrumental") == "true") return null
        val synced = j.str("syncedLyrics").orEmpty().let { if (it.isNotBlank()) parseLrcText(it) else emptyList() }
        val plain = j.str("plainLyrics")?.takeIf { it.isNotBlank() }
        return if (synced.isEmpty() && plain == null) null else LyricsResult(synced, plain)
    }
}

object MusixmatchProvider : LyricsProvider {
    override val id = "musixmatch"
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val base = "https://apic-desktop.musixmatch.com/ws/1.1"
            val common = "app_id=web-desktop-app-v1.0&format=json"
            val cookie = mapOf("Cookie" to "x-mxm-token-guid=")
            val tokenJson = LyricsHttp.get("$base/token.get?$common", cookie) ?: return@runCatching null
            val userToken = parse(tokenJson).obj("message").obj("body").str("user_token")
                ?.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") } ?: return@runCatching null
            val q = "q_artist=${formUrlEncode(artist)}&q_track=${formUrlEncode(title)}" +
                (if (durationSec > 0) "&q_duration=$durationSec" else "")
            val body = LyricsHttp.get("$base/macro.subtitles.get?$common&$q&usertoken=$userToken&subtitle_format=lrc", cookie) ?: return@runCatching null
            val macro = parse(body).obj("message").obj("body").obj("macro_calls")
            // macro.subtitles.get always answers its best match for the artist and title, even when
            // it does not have the song: that is how an instrumental came back with another song's
            // lyrics in another language. The matched track is in the same answer; it has to be this
            // one, and an instrumental has no lyrics to show.
            val matched = macro.obj("matcher.track.get").obj("message").obj("body").obj("track")
                ?: return@runCatching null
            if (matched.str("instrumental") == "1") return@runCatching null
            if (!LyricsMatch.accepts(
                    artist, title, durationSec,
                    foundTitle = matched.str("track_name"),
                    foundArtists = listOfNotNull(matched.str("artist_name")),
                    foundDurationSec = matched.str("track_length")?.toIntOrNull() ?: 0,
                )
            ) {
                Log.i("Lyrics", "musixmatch: best match is another song (${matched.str("track_name")} by ${matched.str("artist_name")})")
                return@runCatching null
            }
            val subtitle = macro.obj("track.subtitles.get").obj("message").obj("body")
                .arr("subtitle_list")?.firstOrNull().obj("subtitle").str("subtitle_body")
            if (!subtitle.isNullOrBlank()) LyricsResult(parseLrcText(subtitle), null)
            else macro.obj("track.lyrics.get").obj("message").obj("body")
                .obj("lyrics").str("lyrics_body")?.takeIf { it.isNotBlank() }
                ?.let { LyricsResult(emptyList(), it) }
        }.getOrNull()
    }
}

object GeniusProvider : LyricsProvider {
    override val id = "genius"
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val q = formUrlEncode("$title $artist")
            val search = LyricsHttp.get("https://genius.com/api/search/song?q=$q") ?: return@runCatching null
            val secs = parse(search).obj("response").arr("sections") ?: return@runCatching null
            // A search's first hit is whatever is closest; the hit used has to be this song.
            val url = secs.firstNotNullOfOrNull { section ->
                section.arr("hits")?.firstNotNullOfOrNull { hit ->
                    val result = hit.obj("result")
                    result.str("url")?.takeIf {
                        it.isNotBlank() && LyricsMatch.accepts(
                            artist, title, durationSec,
                            foundTitle = result.str("title"),
                            foundArtists = listOfNotNull(result.obj("primary_artist").str("name"), result.str("artist_names")),
                        )
                    }
                }
            } ?: return@runCatching null
            val html = LyricsHttp.get(url) ?: return@runCatching null
            // [\s\S] is any character, line breaks included: DOT_MATCHES_ALL, which only the JVM has.
            val blocks = Regex("<div[^>]*data-lyrics-container=\"true\"[^>]*>([\\s\\S]*?)</div>")
                .findAll(html).joinToString("\n") { it.groupValues[1] }
            stripHtml(blocks).takeIf { it.isNotBlank() }?.let { LyricsResult(emptyList(), it) }
        }.getOrNull()
    }
}

object PetitLyricsProvider : LyricsProvider {
    override val id = "petitlyrics"
    override suspend fun fetch(artist: String, title: String, durationSec: Int): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val xml = LyricsHttp.postForm("https://petitlyrics.com/api/GetPetitLyricsData/", mapOf(
                "key_title" to title, "key_artist" to artist, "lyricsType" to "1", "clientAppId" to "p1110417", "terminalType" to "10"
            )) ?: return@runCatching null
            val b64 = Regex("<lyricsData>([\\s\\S]*?)</lyricsData>").find(xml)?.groupValues?.get(1)?.trim()
            // The MIME decoder, like Android's Base64.DEFAULT, skips the line breaks the answer wraps it in;
            // padding optional, as java.util.Base64's decoders take it. UTF-8, the JVMs' default charset.
            val decoded = b64?.takeIf { it.isNotBlank() }?.let {
                runCatching { mime.decode(it).decodeToString() }.getOrNull()
            } ?: return@runCatching null
            if (decoded.isBlank()) return@runCatching null
            val synced = parseLrcText(decoded)
            if (synced.isNotEmpty()) LyricsResult(synced, null) else LyricsResult(emptyList(), decoded)
        }.getOrNull()
    }
}

private val mime = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

object LyricsProviders {
    val ALL: List<LyricsProvider> = listOf(SpotifyLyricsProvider, LrcLibProvider, MusixmatchProvider, GeniusProvider, PetitLyricsProvider)
    fun byId(id: String): LyricsProvider? = ALL.firstOrNull { it.id == id }
}

/** Persisted provider order + enabled flags (mirrors AudioBackendSettings). */
object LyricsSettings {
    private const val KEY = "lyrics_provider_order"
    data class ProviderEntry(val id: String, val enabled: Boolean)

    private val DEFAULT = listOf(
        ProviderEntry("spotify", true),
        ProviderEntry("lrclib", true),
        ProviderEntry("musixmatch", false),
        ProviderEntry("genius", false),
        ProviderEntry("petitlyrics", false),
    )

    fun load(store: KeyValueStore): List<ProviderEntry> {
        val raw = store.getString(KEY) ?: return DEFAULT
        val parsed = raw.split(",").mapNotNull {
            val p = it.split(":")
            if (p.size == 2 && LyricsProviders.byId(p[0]) != null) ProviderEntry(p[0], p[1] == "1") else null
        }
        val known = parsed.map { it.id }.toSet()
        // Spotify's lyrics arrived in 4.3 and go first on upgrade too: they are matched by id, so
        // when they exist they are the right ones; without a session they answer nothing at once.
        val front = DEFAULT.filter { it.id == SpotifyLyricsProvider.id && it.id !in known }
        return (front + parsed + DEFAULT.filter { it.id !in known && it.id != SpotifyLyricsProvider.id })
            .ifEmpty { DEFAULT }
    }

    fun save(store: KeyValueStore, entries: List<ProviderEntry>) {
        store.putString(KEY, entries.joinToString(",") { "${it.id}:${if (it.enabled) 1 else 0}" })
    }
}

/**
 * The provider chain over one settings store: the enabled providers in the user's order, the one the
 * user picked for a song first, and an answer cached per provider so swiping between them does not
 * refetch. [readLocal] reads a `local:` track's own lyrics (a `.lrc` beside the file, or its tag),
 * which only the platform can open.
 */
@OptIn(ExperimentalAtomicApi::class)
class LyricsChain(
    private val store: KeyValueStore,
    private val readLocal: (trackId: String) -> LyricsResult? = { null },
) {
    companion object {
        /** The id of the track's own lyrics (a .lrc beside the file, or its tag). */
        const val LOCAL = "local"

        /** trackId → providerId the user picked by swiping, as a JSON object. */
        private const val KEY_CHOICE = "lyrics_provider_choice"
        private const val MAX_CHOICES = 500
    }

    // Both maps are written from the preload on IO and from the interface's thread (invalidating,
    // choosing), so each is an immutable map swapped atomically, as a ConcurrentHashMap's single
    // operations were atomic.
    // trackId -> result. null means "tried and failed" is NOT cached, so it retries next time.
    private val cache = AtomicReference<Map<String, LyricsResult>>(emptyMap())
    // "trackId|providerId" -> one provider's answer, for swiping between them without refetching.
    private val byProvider = AtomicReference<Map<String, LyricsResult>>(emptyMap())

    private fun hasLines(r: LyricsResult?) = r != null && (r.synced.isNotEmpty() || !r.plain.isNullOrBlank())

    /** The providers in the user's order with their switches, as Settings shows them. */
    fun settings(): List<LyricsSettings.ProviderEntry> = LyricsSettings.load(store)

    /** Settings' new order and switches, in this chain's own store (each platform keeps its own). */
    fun saveSettings(entries: List<LyricsSettings.ProviderEntry>) = LyricsSettings.save(store, entries)

    /** The ids to swipe between for [trackId]: the file's own first for a local track, then the enabled providers in order. */
    fun providersFor(trackId: String): List<String> = buildList {
        if (TrackRef.isLocal(trackId)) add(LOCAL)
        LyricsSettings.load(store).filter { it.enabled && LyricsProviders.byId(it.id) != null }.forEach { add(it.id) }
    }

    private fun choices(): Map<String, String> =
        (store.getString(KEY_CHOICE)?.let { parse(it) } as? JsonObject).orEmpty()
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()

    /** The provider the user picked for [trackId], if any. */
    fun chosen(trackId: String): String? = choices()[trackId]?.ifBlank { null }

    /**
     * Remembers [providerId] for [trackId]: the lyrics shown were the wrong song's, and the one that
     * got it right is what this song should open with next time. The newest 500 choices are kept.
     */
    fun choose(trackId: String, providerId: String) {
        val map = LinkedHashMap(choices())
        map.remove(trackId)
        map[trackId] = providerId
        while (map.size > MAX_CHOICES) map.remove(map.keys.first())
        store.putString(KEY_CHOICE, JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString())
        byProvider.load()["$trackId|$providerId"]?.let { r -> cache.update { it + (trackId to r) } }
    }

    /** What [providerId] alone answers for the track; null when it has nothing. Cached per provider. */
    suspend fun fetchFrom(trackId: String, providerId: String, artist: String, title: String, durationSec: Int): LyricsResult? {
        byProvider.load()["$trackId|$providerId"]?.let { return it }
        val res = if (providerId == LOCAL) {
            withContext(Dispatchers.IO) { runCatching { readLocal(trackId) }.getOrNull() }
        } else {
            // Clean the query the same way for every provider (strip feat/parenthetical, first artist).
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
            val cleanArtist = artist.split(",").firstOrNull()?.trim() ?: artist
            val provider = LyricsProviders.byId(providerId) ?: return null
            runCatching { provider.fetchFor(trackId, cleanArtist, cleanTitle, durationSec) }.getOrNull()
        }?.takeIf { hasLines(it) }?.copy(providerId = providerId) ?: return null
        byProvider.update { it + ("$trackId|$providerId" to res) }
        return res
    }

    suspend fun getLyrics(trackId: String, artist: String, title: String, durationSec: Int): LyricsResult? {
        cache.load()[trackId]?.let { return it }
        val order = providersFor(trackId)
        // The one the user picked for this song first, then the usual order (the file's own first).
        val chosen = chosen(trackId)?.takeIf { it in order }
        for (id in listOfNotNull(chosen) + order.filter { it != chosen }) {
            val res = fetchFrom(trackId, id, artist, title, durationSec) ?: continue
            cache.update { it + (trackId to res) }
            return res
        }
        return null
    }

    fun invalidateLyrics(trackId: String) {
        cache.update { it - trackId }
        byProvider.update { m -> m.filterKeys { !it.startsWith("$trackId|") } }
    }
}
