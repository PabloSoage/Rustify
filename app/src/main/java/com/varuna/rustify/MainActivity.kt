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
import coil3.compose.AsyncImage
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
internal suspend fun resolveMapsRedirect(startUrl: String): com.varuna.rustify.travel.TravelRouting.Geo? {
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
    private companion object {
        /** The one start-up request for local network access has been made (see onCreate). */
        const val KEY_LAN_ASKED_ON_START = "local_network_asked_on_start"
    }

    private var initialDeepLink: String? = null
    private var pendingLocalNetworkRequest: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

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
        } else if (intent?.action == com.varuna.rustify.util.AppShortcuts.ACTION) {
            return intent.getStringExtra(com.varuna.rustify.util.AppShortcuts.EXTRA_TARGET)?.let { "shortcut:$it" }
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
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.varuna.rustify.util.AppShortcuts.publish(application) }

        
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
        com.varuna.rustify.audio.AndroidAudioSources.initialize(application)

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
            runCatching { com.varuna.rustify.audio.AudioSourceRegistry.refreshAddons() }
                .onFailure { android.util.Log.e("MainActivity", "could not load add-ons", it) }
        }

        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
        // Coil's loader (the disk cache, its size from cache_max_mb) is RustifyApplication's: built by
        // the first get() in the process, whoever makes it.

        enableEdgeToEdge()
        setContent {
            val localNetworkLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                pendingLocalNetworkRequest?.complete(granted)
                pendingLocalNetworkRequest = null
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.varuna.rustify.ui.AndroidAppPlatform.localNetworkPermissionRequester = {
                    if (!com.varuna.rustify.platform.LocalNetworkAccess.needed()) {
                        true
                    } else if (com.varuna.rustify.platform.LocalNetworkAccess.granted(applicationContext)) {
                        true
                    } else {
                        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        pendingLocalNetworkRequest = deferred
                        localNetworkLauncher.launch(com.varuna.rustify.platform.LocalNetworkAccess.PERMISSION)
                        deferred.await()
                    }
                }
                onDispose {
                    com.varuna.rustify.ui.AndroidAppPlatform.localNetworkPermissionRequester = null
                }
            }
            // Updating to the target-37 build with casting or a Subsonic server already switched on:
            // nobody flips the switch again, so the request in those sections never runs, and the
            // server just stops answering (a TCP timeout, nothing saying why). Asked here once, and
            // only for those users; everyone else is asked when they switch the feature on.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val store = com.varuna.rustify.platform.SharedPreferencesStore(prefs)
                val usesLan = com.varuna.rustify.cast.CastSession.isEnabled(store) ||
                    com.varuna.rustify.audio.AudioBackendSettings.isEnabled(store, com.varuna.rustify.audio.SubsonicAudioSource.ID)
                if (usesLan && !prefs.getBoolean(KEY_LAN_ASKED_ON_START, false) &&
                    com.varuna.rustify.platform.LocalNetworkAccess.needed() &&
                    !com.varuna.rustify.platform.LocalNetworkAccess.granted(applicationContext)
                ) {
                    prefs.edit().putBoolean(KEY_LAN_ASKED_ON_START, true).apply()
                    com.varuna.rustify.ui.AndroidAppPlatform.localNetworkPermissionRequester?.invoke()
                }
            }

            var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "system") ?: "system") }
            val context = LocalContext.current
            val currentConfig = LocalConfiguration.current
            // "system" is the device's language, read from the system's resources: the JVM's default
            // is overwritten below, so after picking one it no longer says what the system is. Read
            // again on every configuration change, so a language changed in the system's settings
            // while the app runs is followed.
            val locale = remember(appLanguage, currentConfig) {
                if (appLanguage == "system") android.content.res.Resources.getSystem().configuration.locales[0]
                else java.util.Locale.forLanguageTag(appLanguage)
            }
            val localizedContext = remember(context, locale, currentConfig) {
                val newConfig = Configuration(currentConfig)
                newConfig.setLocale(locale)
                context.createConfigurationContext(newConfig)
            }
            // The screens in :sharedUi take their strings from Compose Multiplatform, which reads the
            // language from the JVM's default Locale — not from the Context swapped above, nor from
            // the configuration alone: with only those, every shared screen stayed in the system's
            // language whatever Settings said. So the default is set too, and the configuration is
            // overridden so what reads it recomposes — derived from the framework's current one on
            // every change, so orientation is still tracked.
            val localizedConfig = remember(currentConfig, locale) {
                java.util.Locale.setDefault(locale)
                Configuration(currentConfig).apply { setLocale(locale) }
            }
            val appPlatform = remember { com.varuna.rustify.ui.AndroidAppPlatform(applicationContext) }
            val appServices = remember { AndroidAppServices(applicationContext) }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity,
                com.varuna.rustify.ui.LocalAppPlatform provides appPlatform,
                com.varuna.rustify.ui.LocalAppServices provides appServices,
                com.varuna.rustify.player.LocalPlayer provides com.varuna.rustify.player.AudioPlayerService.getInstance(applicationContext),
            ) {
                com.varuna.rustify.ui.RememberStringEnvironment()
                RustifyTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF121212)
                    ) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val spotifyRepo = androidx.compose.runtime.remember {
                            com.varuna.rustify.bridge.SpotifyRepository(
                                com.varuna.rustify.ui.AndroidAppPlatform(context),
                                com.varuna.rustify.bridge.AndroidLocalMusicScanner(context),
                            )
                        }
                        val shell = androidx.compose.runtime.remember { AndroidAppShell(context) }
                        com.varuna.rustify.ui.RustifyApp(
                            spotifyRepo = spotifyRepo,
                            shell = shell,
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

