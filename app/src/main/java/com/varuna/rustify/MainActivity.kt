// app/src/main/java/com/varuna/rustify/MainActivity.kt

package com.varuna.rustify

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.TrackFavorites
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.ArtistAllSongsScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.DjScreen
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.LogViewerScreen
import com.varuna.rustify.ui.screens.MetricsScreen
import com.varuna.rustify.ui.screens.NewReleasesScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.RadioScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.SettingsScreen
import com.varuna.rustify.ui.screens.TrackScreen
import com.varuna.rustify.ui.screens.TravelScreen
import com.varuna.rustify.ui.screens.YtMusicAlbumScreen
import com.varuna.rustify.ui.screens.YtMusicArtistScreen
import com.varuna.rustify.ui.screens.YtMusicLocalPlaylistScreen
import com.varuna.rustify.ui.screens.YtMusicPlaylistScreen
import com.varuna.rustify.ui.screens.YtMusicSearchScreen
import com.varuna.rustify.ui.theme.RustifyTheme
import com.varuna.rustify.util.LinkTarget
import com.varuna.rustify.util.canonicalUrl
import com.varuna.rustify.util.deepLinkToken
import com.varuna.rustify.util.bouncingMarquee
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch


sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    object NewReleases : Screen()
    data class PlaylistDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class AlbumDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class ArtistDetail(val id: String) : Screen()
    data class ArtistAllSongs(val id: String, val name: String) : Screen()
    data class TrackDetail(val id: String, val openQueue: Boolean = false) : Screen()
    data class RadioDetail(val trackId: String, val trackName: String) : Screen()
    // YouTube Music first-class destinations (no more embedded tab screen).
    object YtmSearch : Screen()
    data class YtmAlbumDetail(val browseId: String, val title: String) : Screen()
    data class YtmArtistDetail(val channelId: String, val name: String) : Screen()
    data class YtmPlaylistDetail(val playlistId: String, val title: String) : Screen()
    data class YtmLocalPlaylistDetail(val localId: String) : Screen()
    object Settings : Screen()
    object WebPlayer : Screen()
    object Downloads : Screen()
    // Custom downloads (paste a URL, choose video/audio quality, separate folder).
    object CustomDownload : Screen()
    object LogViewer : Screen()
    // Settings tools (4.7.1): a chat with Gemini Nano, and the audio analyser.
    object NanoChat : Screen()
    object AudioLab : Screen()
    object Metrics : Screen()
    // YouTube match editor (list with names; edit/preview/delete, add manually).
    object MatchEditor : Screen()
    // AI DJ (automix / natural-language requests).
    object Dj : Screen()
    object Travel : Screen()
    /**
     * Travel with a preloaded initial destination (when opened from a shared Google Maps link or a
     * geo: link). [lat]/[lon] are reverse-geocoded to a label via Nominatim/Google.
     */
    data class TravelWithDestination(val lat: Double, val lon: Double, val label: String? = null) : Screen()
}

/**
 * Route a deep-link token ("track:ID" / "album:ID" / "playlist:ID" / "artist:ID" /
 * "NOW_PLAYING", or a legacy bare track id) onto the navigation stack.
 */
internal fun navigateDeepLink(
    deepLink: String,
    navigationStack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>,
    audioPlayerService: AudioPlayerService
) {
    if (deepLink == "NOW_PLAYING") {
        val currentTrackId = audioPlayerService.state.value.currentTrack?.id
        if (currentTrackId != null) {
            val last = navigationStack.lastOrNull()
            if (last !is Screen.TrackDetail || last.id != currentTrackId) {
                navigationStack.add(Screen.TrackDetail(currentTrackId))
            }
        }
        return
    }
    val parts = deepLink.split(":", limit = 2)
    if (parts.size != 2) {
        navigationStack.add(Screen.TrackDetail(deepLink))
        return
    }
    when (parts[0]) {
        "track" -> navigationStack.add(Screen.TrackDetail(parts[1]))
        "album" -> navigationStack.add(Screen.AlbumDetail(parts[1], "", emptyList()))
        "playlist" -> navigationStack.add(Screen.PlaylistDetail(parts[1], "", emptyList()))
"artist" -> navigationStack.add(Screen.ArtistDetail(parts[1]))
        // Shared Google Maps / geo: link.
        "travel" -> {
            val inner = parts[1].split(",", limit = 3)
            if (inner.size >= 2) {
                val lat = inner[0].toDoubleOrNull() ?: return
                val lon = inner[1].toDoubleOrNull() ?: return
                val label = inner.getOrNull(2)?.replace("+", " ")
                navigationStack.add(Screen.TravelWithDestination(lat, lon, label))
            }
        }
        // Google Maps short link / place: resolve the redirect on IO (not on the main thread).
        "travelresolve" -> {
            val url = parts[1]
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val geo = resolveMapsRedirect(url)
                if (geo != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    navigationStack.add(Screen.TravelWithDestination(geo.lat, geo.lon, geo.label.ifBlank { null }))
                }
            }
        }
        // YouTube Music deep links.
        "ytmtrack" -> {
            // Build a minimal ytm: FullTrack and hand it to the existing player pipeline.
            val yt = FullTrack(
                id = "ytm:${parts[1]}",
                name = "YouTube Music",
                externalUri = "https://music.youtube.com/watch?v=${parts[1]}",
                explicit = false, durationMs = 0, isrc = "",
                artists = emptyList(), album = null
            )
            audioPlayerService.loadPlaylist(listOf(yt), 0)
        }
        "ytmalbum" -> navigationStack.add(Screen.YtmAlbumDetail(parts[1], ""))
        "ytmartist" -> navigationStack.add(Screen.YtmArtistDetail(parts[1], ""))
        "ytmplaylist" -> navigationStack.add(Screen.YtmPlaylistDetail(parts[1], ""))
        else -> navigationStack.add(Screen.TrackDetail(deepLink))
    }
}

/**
 * Extracts a "travel:lat,lng,label" token from Google Maps links or geo: coordinates.
 * Supports: geo:lat,lng?q=label, maps.google.com/place/...@lat,lng, maps.app.goo.gl (short links).
 * Returns null if a map link is not recognized.
 */
internal fun extractTravelToken(text: String): String? {
    val t = text.trim()
    // geo: URI scheme (Android standard)
    if (t.startsWith("geo:", ignoreCase = true)) {
        var body = t.substring(4)
        if (body.startsWith("//")) body = body.substring(2)
        val coordPart = body.split("?", limit = 2)
        val coords = coordPart[0].split(",")
        if (coords.size >= 2) {
            val lat = coords[0].toDoubleOrNull() ?: return null
            val lon = coords[1].toDoubleOrNull() ?: return null
            var label = ""
            if (coordPart.size > 1) {
                val q = coordPart[1].split("&").firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: ""
                label = try { java.net.URLDecoder.decode(q, "UTF-8") } catch (_: Exception) { "" }
            }
            return "travel:$lat,$lon,${label.replace(" ", "+")}"
        }
    }

    // Direct coordinates in the URL, no network: @lat,lng (center), !3d<lat>!4d<lon> (place/destination),
    // destination=/q=/ll= lat,lon, etc.
    mapsCoords(t)?.let { return "travel:${it.first},${it.second}," }

    // Any map link (short or long, DIRECTIONS or PLACE): resolve the redirect and, if there are no
    // coordinates, geocode the place name — over the NETWORK, on an IO coroutine (this runs on the
    // main thread → NetworkOnMainThread). Deferring resolution covers /dir/… and place links without
    // an @ that would otherwise open an empty map (no location, no route).
    if (t.contains("maps.app.goo.gl", ignoreCase = true) ||
        t.contains("goo.gl/maps", ignoreCase = true) ||
        t.contains("google.com/maps", ignoreCase = true) ||
        t.contains("maps.google", ignoreCase = true) ||
        t.contains("g.co/kgs", ignoreCase = true)) {
        return "travelresolve:$t"
    }

    return null
}

/** Extracts coordinates from a Google Maps text/URL trying several formats (no network). */
internal fun mapsCoords(text: String): Pair<Double, Double>? {
    // Place/destination coords embedded in the data parameter: !3d<lat>!4d<lon>.
    Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(text)?.let {
        return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
    }
    // destination / query as lat,lon (literal comma or %2C).
    Regex("""[?&](?:destination|daddr|q|ll|sll|center)=(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)""", RegexOption.IGNORE_CASE)
        .find(text)?.let { return it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    // Map center @lat,lng (last resort).
    Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let {
        return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
    }
    return null
}

/** Extracts a place/destination NAME from a Maps URL to geocode when there are no coordinates. */
internal fun mapsPlaceName(text: String): String? {
    Regex("""/place/([^/@?]+)""").find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        val name = runCatching { java.net.URLDecoder.decode(raw.replace('+', ' '), "UTF-8") }.getOrDefault(raw).trim()
        if (name.isNotBlank()) return name
    }
    Regex("""[?&](?:destination|daddr|q)=([^&]+)""").find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        val name = runCatching { java.net.URLDecoder.decode(raw.replace('+', ' '), "UTF-8") }.getOrDefault(raw).trim()
        if (name.isNotBlank() && !Regex("""^-?\d+\.\d+\s*,\s*-?\d+\.\d+$""").matches(name)) return name
    }
    return null
}

/**
 * Resolves a Google Maps short link/place by following redirects and searching for `@lat,lng` in the
 * URL chain and the final body. Blocking — call on Dispatchers.IO.
 */
// Always invoked within Dispatchers.IO (see the "travelresolve" branch); the thread analysis does not
// detect this interprocedurally, so the blocking call here is safe.
@Suppress("BlockingMethodInNonBlockingContext")
private suspend fun resolveMapsRedirect(startUrl: String): com.varuna.rustify.travel.TravelRouting.Geo? {
    fun geo(p: Pair<Double, Double>, srcUrl: String) =
        com.varuna.rustify.travel.TravelRouting.Geo(p.first, p.second, mapsPlaceName(srcUrl) ?: "")
    var url = startUrl
    mapsCoords(url)?.let { return geo(it, url) }
    var finalUrl = url
    try {
        for (i in 0 until 6) {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Rustify")
                instanceFollowRedirects = false
                connectTimeout = 6000; readTimeout = 6000
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location"); conn.disconnect()
                if (loc.isNullOrBlank()) break
                url = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(url), loc).toString()
                finalUrl = url
                mapsCoords(url)?.let { return geo(it, url) }
            } else {
                val body = runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                conn.disconnect()
                finalUrl = url
                mapsCoords(url)?.let { return geo(it, url) }
                mapsCoords(body)?.let { return geo(it, url) }
                break
            }
        }
    } catch (_: Exception) { return null }
    // No coordinates in the resolved URL: geocode the place/destination name as a last resort.
    val name = mapsPlaceName(finalUrl) ?: return null
    return runCatching { com.varuna.rustify.travel.TravelRouting.geocode(name) }.getOrNull()
}

class MainActivity : ComponentActivity() {
    private var initialDeepLink: String? = null

    /**
     * Extract a Spotify link from a shared URL — unified parser.
     * Returns a tagged string like "track:ID", "album:ID", "playlist:ID", "artist:ID",
     * or null if no Spotify link is found.
     */
    private fun extractSpotifyLink(text: String): String? {
        val link = LinkTarget.parse(text) ?: return null
        return when (link) {
            is LinkTarget.SpotifyTrack,
            is LinkTarget.SpotifyAlbum,
            is LinkTarget.SpotifyPlaylist,
            is LinkTarget.SpotifyArtist -> link.deepLinkToken
            // A YouTube Music link is a link, just not a Spotify one — [extractAnyLink] takes it.
            else -> null
        }
    }

    /**
     * Extract a Spotify OR YouTube Music link from arbitrary text (share / wrapper payload).
     *
     * One parser for both since 3.3: there is no "try Spotify, then try YTM" any more, because
     * there is no longer one parser per service to try. Returns a tagged token or null.
     */
    private fun extractAnyLink(text: String): String? = LinkTarget.parse(text)?.deepLinkToken

    @SuppressLint("AppBundleLocaleChanges")
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("rustify_settings", MODE_PRIVATE)
        val appLang = prefs.getString("app_language", "system") ?: "system"
        if (appLang != "system" && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val locale = java.util.Locale.forLanguageTag(appLang)
            java.util.Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            val newContext = newBase.createConfigurationContext(config)
            super.attachBaseContext(newContext)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        // If denied, the player notification will not be shown on Android 13+.
    }

    private val intentFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    private fun extractDeepLink(intent: android.content.Intent?): String? {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val uri = intent.data
            // Google Maps / geo: link → travel token.
            if (uri?.scheme == "geo") {
                return extractTravelToken(uri.toString())
            }
            // Self-verifiable Rustify wrapper (the user's own host).
            // The host is stored in prefs to GENERATE the link; the VERIFIED hosts are pinned in the
            // manifest at build time. Here we unwrap both the prefs host and the baked-in ones
            // defined in AppLinksHosts.verifiedHosts.
            val prefs = getSharedPreferences("rustify_settings", MODE_PRIVATE)
            val wrapperHost = prefs.getString("rustify_wrapper_host", null)
            val knownHosts = listOfNotNull(wrapperHost) + com.varuna.rustify.util.AppLinksHosts.verifiedHosts
            if (uri?.scheme == "https") {
                // Unwrapping lives in the core alongside the wrapping, so the two cannot drift —
                // it understands both `?s=<url>` and the older `/r/track/ID` path form.
                LinkTarget.unwrap(uri.toString(), knownHosts)?.let { payload ->
                    return extractAnyLink(payload)                       // Spotify or YTM
                }
            }
            if (uri?.host == "open.spotify.com") {
                // pathSegments ignores the intl-xx prefix automatically: look for the entity type.
                val segs = uri.pathSegments
                val i = segs.indexOfFirst { it in setOf("track", "album", "playlist", "artist") }
                if (i != -1 && i + 1 < segs.size) {
                    return "${segs[i]}:${segs[i + 1]}"
                }
            } else if (uri?.scheme == "rustify" &&
                uri.host in setOf(
                    "track", "album", "playlist", "artist",
                    "ytmtrack", "ytmalbum", "ytmartist", "ytmplaylist"   // YTM fallback scheme
                )
            ) {
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    return "${uri.host}:${pathSegments[0]}"
                }
            } else if (uri?.host == "music.youtube.com" || uri?.host == "www.youtube.com" || uri?.host == "youtu.be") {
                // Single source of truth — the parser in the core.
                LinkTarget.parse(uri.toString())?.let { return it.deepLinkToken }
            }
        } else if (intent?.action == android.content.Intent.ACTION_SEND) {
            if (intent.type == "text/plain") {
                val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    // A Google Maps / geo link takes priority.
                    val travel = extractTravelToken(sharedText)
                    if (travel != null) {
                        android.util.Log.d("MainActivity", "Extracted travel token: $travel")
                        return travel
                    }
                    val link = extractAnyLink(sharedText)   // Spotify or YTM
                    if (link != null) {
                        android.util.Log.d("MainActivity", "Extracted link from shared text: $link")
                        return link
                    }
                }
            }
        } else if (intent?.action == "com.varuna.rustify.action.VIEW_NOW_PLAYING") {
            return "NOW_PLAYING"
        }
        return null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)?.let { intentFlow.tryEmit(it) }
    }

    /**
     * Turns on `StrictMode`'s thread policy in debug builds (point N4).
     *
     * The whole of point N was one class of mistake — network or disk work on the thread that draws
     * the screen — and the fix so far has been to make it hard to write. This is the other half: it
     * makes the next one announce itself in the log the moment it runs, instead of being discovered
     * as "the app freezes on a bad connection" some releases later.
     *
     * Deliberately `penaltyLog` and not `penaltyDeath`: a crash here would be a debug-only crash on
     * code paths that are merely slow, and a tool that cries wolf gets turned off.
     *
     * Debuggability is read from the manifest flag rather than `BuildConfig.DEBUG`, which this
     * module does not generate.
     */
    private fun enableStrictModeOnDebugBuilds() {
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableStrictModeOnDebugBuilds()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        initialDeepLink = extractDeepLink(intent)

        
        val prefs = getSharedPreferences("rustify_settings", MODE_PRIVATE)
        val appLang = prefs.getString("app_language", "system") ?: "system"
        val langCode = if (appLang == "system") java.util.Locale.getDefault().language else appLang
        com.varuna.rustify.bridge.NativeEngine.setLanguageNative(langCode)

        // LogCapture: init file path + auto-resume if toggle was on (survives crash).
        com.varuna.rustify.util.LogCapture.init(this)
        if (prefs.getBoolean("logging_capture_enabled", false)) {
            com.varuna.rustify.util.LogCapture.start(clearFirst = false)
        }
        
        // Audio backends bootstrap centralized in AudioSourceRegistry. yt-dlp remains the default
        // provider; new backends (Invidious/Deemix) are initialized here as well.
        com.varuna.rustify.audio.AudioSourceRegistry.initialize(application)

        // The audio disk cache opens its database and scans its directory when first built, and
        // the first composable to ask for the player would build it on the main thread. Started
        // here, off it, so that by the time the UI asks it is usually already there.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.varuna.rustify.player.AudioPlayerService.getCache(application) }
        }

        // And the installed add-ons (3.0), which are backends too. Without this they would only join
        // the chain once the Settings screen had been opened, so a track played straight after
        // launch would silently skip every add-on the user installed.
        //
        // Off the main thread: it crosses JNI to read the installed list.
        lifecycleScope.launch {
            runCatching { com.varuna.rustify.audio.AudioSourceRegistry.refreshAddons(application) }
                .onFailure { android.util.Log.e("MainActivity", "could not load add-ons", it) }
        }

        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
        // Configure Coil image loading with a persistent disk cache. Size is configurable in Settings
        // (pref cache_max_mb, default 500 MB); it takes effect on app restart.
        val cacheMaxMb = prefs.getInt("cache_max_mb", 500).coerceIn(100, 8192).toLong()
        val imageLoader = coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(cacheMaxMb * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
        coil.Coil.setImageLoader(imageLoader)

        enableEdgeToEdge()
        setContent {
            var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "system") ?: "system") }
            val context = LocalContext.current
            val currentConfig = LocalConfiguration.current
            val localizedContext = remember(context, appLanguage, currentConfig) {
                val newConfig = Configuration(currentConfig)
                val locale = if (appLanguage == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(appLanguage)
                newConfig.setLocale(locale)
                context.createConfigurationContext(newConfig)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContext provides localizedContext,
                // NOT overriding LocalConfiguration — let it flow from the framework
                // so orientation changes are tracked correctly by Compose.
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                RustifyTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF121212)
                    ) {
                        com.varuna.rustify.ui.RustifyApp(
                            initialDeepLink = initialDeepLink,
                            intentFlow = intentFlow,
                            onLanguageChanged = { newLang -> appLanguage = newLang }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Puts a "continue listening" session back on, from the track and position it stored.
 *
 * The session keeps track **ids**, not tracks — ids are the only thing every platform agrees on —
 * so the queue is refetched from the context it came from. That is one request, because the context
 * id says which: `album:X` is one album fetch, `playlist:X` is one playlist fetch. Refetching also
 * means a playlist that changed since gets its current contents rather than a stale copy.
 *
 * If the track it left off on is no longer there, playback starts at the top rather than refusing:
 * the user asked for this album, and giving them the album is closer to right than giving them
 * nothing.
 */
internal suspend fun resumeListening(
    session: com.varuna.rustify.bridge.ListeningSession,
    spotifyRepo: SpotifyRepository,
    audioPlayerService: AudioPlayerService,
    /**
     * Called the moment something is actually playing — which is now well before this function
     * returns, because the rest of the context is fetched afterwards. The spinner on the row keys on
     * this rather than on completion; leaving it spinning while music plays would say the opposite
     * of what is happening.
     */
    onPlaying: () -> Unit = {}
) {
    val (kind, id) = session.id.split(':', limit = 2).let {
        if (it.size == 2) it[0] to it[1] else return
    }
    val context = com.varuna.rustify.player.PlaybackContext(
        id = session.id,
        label = session.label,
        subtitle = session.subtitle,
        imageUrl = session.imageUrl
    )
    val target = session.currentTrackId

    // Play FIRST, fetch the list after.
    //
    // This used to be the other way round, and it is why resuming took several seconds even for a
    // track already sitting in the cache: the whole context was refetched before anything played,
    // which since T8 means up to twenty round trips for a long playlist. Nothing about that was
    // needed to start — the session already knows which track, at which second, with which title and
    // artwork, and `StreamRouting.cachedUrlFor` is asked before any backend is chosen, so a cached or
    // on-device track needs no network at all.
    //
    // The stand-in queue holds one track. The real one replaces it below, without interrupting it.
    val stub = session.stub()
    if (stub != null) {
        audioPlayerService.loadPlaylist(
            listOf(stub),
            0,
            context = context,
            startPositionMs = session.positionMs.coerceAtLeast(0L)
        )
        onPlaying()
    }

    val tracks = runCatching {
        when (kind) {
            // Every page, not just the first: a session left off at track 120 used to resume at
            // track 1 because the track it wanted was never fetched. That paging now lives in the
            // repository, because "the tracks it has scrolled to" being read as "the whole album"
            // turned out to be a bug three screens had as well.
            "album" -> spotifyRepo.getAllAlbumTracks(id)
            "playlist" -> spotifyRepo.getAllPlaylistTracks(id)
            // A radio is a generated list, so there is nothing to re-fetch by id: it is regenerated
            // from the track it was seeded on. Resuming it means the same seed, which gives a list
            // that starts where you were even when its tail differs.
            "radio" -> spotifyRepo.getTrackRadio(id)
            else -> emptyList()
        }
    }.getOrElse { emptyList() }

    if (tracks.isEmpty()) {
        // No network, or the context is gone. With a stand-in playing, that is a single track rather
        // than nothing — which is the whole point of starting before fetching.
        return
    }

    if (stub != null) {
        audioPlayerService.replaceQueuePreservingCurrent(tracks, target)
        return
    }

    // No stand-in: a session stored before the title and artist were kept. The old path, which is
    // slower but still correct.
    val index = tracks.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: 0
    // Only start mid-track when we landed on the track that was actually left off on -- dropping two
    // minutes into a different song is worse than starting it. The position goes IN, rather than a
    // seekTo() after the call: resolving a track is asynchronous, so by the time this returns the
    // player is not holding it yet and the start position has already been read as zero. That is why
    // resuming always began at the top of the song.
    val startAt = if (tracks[index].id == target) session.positionMs.coerceAtLeast(0L) else 0L
    audioPlayerService.loadPlaylist(tracks, index, context = context, startPositionMs = startAt)
    onPlaying()
}

/** What Home says when its sections did not load: the engine's words, unless there is no network. */
internal fun homeError(context: android.content.Context, engineMessage: String?): String =
    if (!com.varuna.rustify.util.Connectivity.isOnline(context)) context.getString(R.string.home_offline)
    else engineMessage ?: context.getString(R.string.home_error_loading)
