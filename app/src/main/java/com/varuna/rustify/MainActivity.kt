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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.movableContentOf
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
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.util.SpotifyLink
import com.varuna.rustify.util.SpotifyLinkParser
import com.varuna.rustify.util.bouncingMarquee
import com.varuna.rustify.ui.screens.LogViewerScreen
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.ArtistAllSongsScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.MetricsScreen
import com.varuna.rustify.ui.screens.NewReleasesScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.RadioScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.SettingsScreen
import com.varuna.rustify.ui.screens.DjScreen
import com.varuna.rustify.ui.screens.TravelScreen
import com.varuna.rustify.ui.screens.TrackScreen
import com.varuna.rustify.ui.screens.YtMusicSearchScreen
import com.varuna.rustify.ui.screens.YtMusicAlbumScreen
import com.varuna.rustify.ui.screens.YtMusicArtistScreen
import com.varuna.rustify.ui.screens.YtMusicPlaylistScreen
import com.varuna.rustify.ui.screens.YtMusicLocalPlaylistScreen
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.ui.theme.RustifyTheme
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
    data class TrackDetail(val id: String) : Screen()
    data class RadioDetail(val trackId: String, val trackName: String) : Screen()
    // E40 — YouTube Music first-class destinations (no more embedded tab screen).
    object YtmSearch : Screen()
    data class YtmAlbumDetail(val browseId: String, val title: String) : Screen()
    data class YtmArtistDetail(val channelId: String, val name: String) : Screen()
    data class YtmPlaylistDetail(val playlistId: String, val title: String) : Screen()
    data class YtmLocalPlaylistDetail(val localId: String) : Screen()
    object Settings : Screen()
    object Downloads : Screen()
    // E103 — Descargas personalizadas (pega una URL, elige calidad de vídeo/audio, carpeta aparte).
    object CustomDownload : Screen()
    object LogViewer : Screen()
    object Metrics : Screen()
    // Editor de matches YouTube (lista con nombres, editar/preview/borrar, añadir manual).
    object MatchEditor : Screen()
    // E90 — DJ IA (automix / peticiones en lenguaje natural).
    object Dj : Screen()
    object Travel : Screen()
    /**
     * E99+ — Travel con destino inicial pre-cargado (cuando se abre desde un link compartido
     * de Google Maps o un link geo:). [lat]/[lon] se revierten a etiqueta via Nominatim/Google.
     */
    data class TravelWithDestination(val lat: Double, val lon: Double, val label: String? = null) : Screen()
}

/**
 * Route a deep-link token ("track:ID" / "album:ID" / "playlist:ID" / "artist:ID" /
 * "NOW_PLAYING", or a legacy bare track id) onto the navigation stack (E20).
 */
private fun navigateDeepLink(
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
        // E99+ — Google Maps / geo: link compartido.
        "travel" -> {
            val inner = parts[1].split(",", limit = 3)
            if (inner.size >= 2) {
                val lat = inner[0].toDoubleOrNull() ?: return
                val lon = inner[1].toDoubleOrNull() ?: return
                val label = inner.getOrNull(2)?.replace("+", " ")
                navigationStack.add(Screen.TravelWithDestination(lat, lon, label))
            }
        }
        // Short link / place de Google Maps: resolvemos el redirect en IO (no en el hilo principal).
        "travelresolve" -> {
            val url = parts[1]
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val geo = resolveMapsRedirect(url)
                if (geo != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    navigationStack.add(Screen.TravelWithDestination(geo.lat, geo.lon, geo.label.ifBlank { null }))
                }
            }
        }
        // E40 - YouTube Music deep links.
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
 * Extrae un token "travel:lat,lng,label" desde enlaces de Google Maps o coordenadas geo:.
 * Soporta: geo:lat,lng?q=label, maps.google.com/place/...@lat,lng, maps.app.goo.gl (short links).
 * Devuelve null si no se reconoce un enlace de mapa.
 */
private fun extractTravelToken(text: String): String? {
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

    // Coordenadas directas en la URL, sin red: @lat,lng (centro), !3d<lat>!4d<lon> (lugar/destino),
    // destination=/q=/ll= lat,lon, etc.
    mapsCoords(t)?.let { return "travel:${it.first},${it.second}," }

    // Cualquier enlace de mapa (corto o largo, DIRECCIONES o LUGAR): resolvemos el redirect y, si no hay
    // coordenadas, geocodificamos el nombre del lugar — por RED, en una corrutina IO (aquí estamos en el
    // hilo principal → NetworkOnMainThread). Antes solo se difería goo.gl/google.com/maps y solo se sacaba
    // @lat,lng, así que los links de /dir/… o de lugar sin @ abrían el mapa vacío (ni ubicación ni ruta).
    if (t.contains("maps.app.goo.gl", ignoreCase = true) ||
        t.contains("goo.gl/maps", ignoreCase = true) ||
        t.contains("google.com/maps", ignoreCase = true) ||
        t.contains("maps.google", ignoreCase = true) ||
        t.contains("g.co/kgs", ignoreCase = true)) {
        return "travelresolve:$t"
    }

    return null
}

/** Extrae coordenadas de un texto/URL de Google Maps probando varios formatos (sin red). */
private fun mapsCoords(text: String): Pair<Double, Double>? {
    // Coords del lugar/destino embebidas en el parámetro data: !3d<lat>!4d<lon>.
    Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(text)?.let {
        return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
    }
    // destino / consulta como lat,lon (coma literal o %2C).
    Regex("""[?&](?:destination|daddr|q|ll|sll|center)=(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)""", RegexOption.IGNORE_CASE)
        .find(text)?.let { return it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    // Centro del mapa @lat,lng (último recurso).
    Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let {
        return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
    }
    return null
}

/** Extrae un NOMBRE de lugar/destino de una URL de Maps para geocodificar si no hay coordenadas. */
private fun mapsPlaceName(text: String): String? {
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
 * Resuelve un enlace corto/place de Google Maps siguiendo redirects y buscando `@lat,lng` en la
 * cadena de URLs y en el cuerpo final. Bloqueante — llamar en Dispatchers.IO.
 */
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
    // Sin coordenadas en la URL resuelta: geocodifica el nombre del lugar/destino como último recurso.
    val name = mapsPlaceName(finalUrl) ?: return null
    return runCatching { com.varuna.rustify.travel.TravelRouting.geocode(name) }.getOrNull()
}

class MainActivity : ComponentActivity() {
    private var initialDeepLink: String? = null

    /**
     * Extract a Spotify link from a shared URL — unified parser (E20).
     * Returns a tagged string like "track:ID", "album:ID", "playlist:ID", "artist:ID",
     * or null if no Spotify link is found.
     */
    private fun extractSpotifyLink(text: String): String? {
        return when (val link = SpotifyLinkParser.parse(text)) {
            is SpotifyLink.Track -> "track:${link.id}"
            is SpotifyLink.Album -> "album:${link.id}"
            is SpotifyLink.Playlist -> "playlist:${link.id}"
            is SpotifyLink.Artist -> "artist:${link.id}"
            null -> null
        }
    }

    /**
     * Extract a Spotify OR YouTube Music link from arbitrary text (share / wrapper payload).
     * Spotify is tried first, then YTM (E40). Returns a tagged token or null.
     */
    private fun extractAnyLink(text: String): String? =
        extractSpotifyLink(text) ?: com.varuna.rustify.util.YtMusicLinkParser.toDeepLinkToken(text)

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
        // Si se deniega, la notificación del reproductor no se verá en Android 13+
    }

    private val intentFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    private fun extractDeepLink(intent: android.content.Intent?): String? {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val uri = intent.data
            // E99+: Google Maps / geo: link → travel token.
            if (uri?.scheme == "geo") {
                return extractTravelToken(uri.toString())
            }
            // F1.A: wrapper Rustify autoverificable (host propio del usuario, §4.A.2).
            // El host se guarda en prefs para GENERAR el link; los hosts VERIFICADOS van fijados
            // en el manifest a build-time. Aquí desenvolvemos tanto el host de prefs como los
            // baked-in definidos en AppLinksHosts.verifiedHosts.
            val prefs = getSharedPreferences("rustify_settings", MODE_PRIVATE)
            val wrapperHost = prefs.getString("rustify_wrapper_host", null)
            val knownHosts = listOfNotNull(wrapperHost) + com.varuna.rustify.util.AppLinksHosts.verifiedHosts
            if (uri?.scheme == "https" && uri.host in knownHosts
                && uri.pathSegments.firstOrNull() == "r"
            ) {
                val payload = uri.getQueryParameter("s")                 // formato A: ?s=open.spotify.com/track/ID
                    ?: uri.pathSegments.drop(1).joinToString("/")        // formato B: /r/track/ID
                return extractAnyLink(payload)                           // Spotify o YTM (E40)
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
                    "ytmtrack", "ytmalbum", "ytmartist", "ytmplaylist"   // E40 YTM fallback scheme
                )
            ) {
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    return "${uri.host}:${pathSegments[0]}"
                }
            } else if (uri?.host == "music.youtube.com" || uri?.host == "www.youtube.com" || uri?.host == "youtu.be") {
                // E40: single source of truth — delegate to YtMusicLinkParser.
                com.varuna.rustify.util.YtMusicLinkParser.toDeepLinkToken(uri.toString())?.let { return it }
            }
        } else if (intent?.action == android.content.Intent.ACTION_SEND) {
            if (intent.type == "text/plain") {
                val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    // E99+: si es un enlace de Google Maps / geo, tiene prioridad.
                    val travel = extractTravelToken(sharedText)
                    if (travel != null) {
                        android.util.Log.d("MainActivity", "Extracted travel token: $travel")
                        return travel
                    }
                    val link = extractAnyLink(sharedText)   // Spotify o YTM (E40)
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
        
        // E60: bootstrap de los backends de audio centralizado en AudioSourceRegistry
        // (movido desde el inline yt-dlp init/update que había aquí). yt-dlp sigue siendo
        // el provider por defecto; nuevos backends (Invidious/Deemix) se inicializarán aquí también.
        com.varuna.rustify.audio.AudioSourceRegistry.initialize(application)

        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
        // Configure Coil image loading with a persistent disk cache. E108 — tamaño configurable en
        // Ajustes (pref cache_max_mb, por defecto 500 MB); se aplica al reiniciar la app.
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
                        EngineTester(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineTester(
    modifier: Modifier = Modifier,
    initialDeepLink: String? = null,
    intentFlow: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    onLanguageChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val spotifyRepo = remember { SpotifyRepository(context) }
    // E40 — single shared YTM repository so favorites/playlists stay consistent across
    // the library tab and all first-class YTM screens.
    val ytmRepo = remember { YtMusicRepository(context.applicationContext) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val audioPlayerService = remember { AudioPlayerService.getInstance(context) }
    // E12 D5: don't tear down the player on every Activity dispose (rotation/recomposition
    // previously killed playback mid-song). Persist state synchronously; the foreground
    // service + onTaskRemoved handle actual teardown when the user leaves for real.
    DisposableEffect(audioPlayerService) {
        onDispose {
            audioPlayerService.saveNow()
        }
    }


    var isRunning by remember { mutableStateOf(false) }
    // E107 — Login optimista: si YA hay una sesión guardada (cookie sp_dc), entramos directos a la app
    // (con Home/biblioteca cacheados y música local accesibles) y refrescamos el token EN SEGUNDO PLANO,
    // en vez de bloquear toda la app tras una validación de red que, si Spotify falla, te dejaba atascado
    // en la pantalla de login (heredada de la pre-alpha) sin poder ni abrir la música local.
    var isLoggedIn by remember { mutableStateOf(spotifyRepo.hasSavedSession()) }
    var showWebView by remember { mutableStateOf(false) }
    var browseSections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val navigationStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // E105: Settings category is hoisted here so it survives pushing a sub-screen (View Logs, Metrics,
    // Match editor…) and popping back — otherwise Settings recomposes fresh at the root menu instead of
    // returning to the category you came from.
    var settingsCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // Hoisted state for LibraryScreen to survive rotation and navigation pops
    var librarySelectedTab by rememberSaveable { mutableStateOf(com.varuna.rustify.ui.screens.LibraryTab.PLAYLISTS) }
    var librarySelectedGroup by rememberSaveable { mutableStateOf("Tracks") }

    LaunchedEffect(Unit) {
        if (initialDeepLink != null) {
            navigateDeepLink(initialDeepLink, navigationStack, audioPlayerService)
        }
    }

    LaunchedEffect(intentFlow) {
        intentFlow.collect { deepLink ->
            navigateDeepLink(deepLink, navigationStack, audioPlayerService)
        }
    }

    // E50 — Auto-sync silencioso al abrir la app: solo si la cuenta está vinculada,
    // el toggle "auto-sync" está activo y hay un token disponible SIN pedir consentimiento
    // (refresh en segundo plano). Si requiere UI de consentimiento, se pospone a que el
    // usuario pulse "Sincronizar ahora" en Ajustes. Nunca lanza diálogo aquí.
    LaunchedEffect(Unit) {
        val appCtx = context.applicationContext
        if (com.varuna.rustify.sync.DriveSyncPrefs.isLinked(appCtx) &&
            com.varuna.rustify.sync.DriveSyncPrefs.isAutoSync(appCtx)
        ) {
            val drive = com.varuna.rustify.sync.GoogleDriveSync(appCtx)
            val syncWith = { token: String ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.varuna.rustify.sync.DriveSyncManager(appCtx, drive, spotifyRepo, ytmRepo).syncNow(token)
                    }
                }
                Unit
            }
            if (com.varuna.rustify.sync.DriveSyncPrefs.authMethod(appCtx) == "browser") {
                // B — refresco silencioso con AppAuth; si hace falta consentimiento se pospone a Ajustes.
                com.varuna.rustify.sync.AppAuthDriveAuth(appCtx).getFreshToken(
                    onToken = syncWith,
                    onNone = { /* requiere UI; el usuario sincroniza desde Ajustes */ },
                    onError = { /* silencioso en auto-sync */ }
                )
            } else {
                // A — Play Services (token silencioso si hay consentimiento cacheado).
                drive.authorize(
                    onToken = syncWith,
                    onNeedConsent = { /* posponer: requiere UI; el usuario sincroniza desde Ajustes */ },
                    onError = { /* silencioso en auto-sync */ }
                )
            }
        }
    }

    // Physical / Gesture Back Button handling
    BackHandler(enabled = navigationStack.size > 1) {
        navigationStack.removeAt(navigationStack.lastIndex)
    }

    // E107 — Refresco de sesión en segundo plano (ya estamos DENTRO si había sesión, ver isLoggedIn).
    // No bloquea: Home muestra su propio spinner/caché y las demás pestañas (Búsqueda/Biblioteca/local)
    // son navegables mientras tanto. Un fallo TRANSITORIO de red mantiene al usuario dentro con lo
    // cacheado (restoreSession no borra credenciales en ese caso); solo una sesión REALMENTE muerta
    // (credenciales borradas) manda a la pantalla de login.
    LaunchedEffect(Unit) {
        if (spotifyRepo.hasSavedSession()) {
            isRunning = true
            val result = spotifyRepo.restoreSession()
            if (result?.success == true) {
                try {
                    browseSections = spotifyRepo.getBrowseSections(10)
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            } else if (!spotifyRepo.hasSavedSession()) {
                // Sesión muerta (revocada/expirada de verdad → restoreSession borró las credenciales).
                isLoggedIn = false
                errorMessage = "Saved session expired. Please log in again."
            }
            // Si sigue habiendo sesión guardada = fallo transitorio: nos quedamos dentro con lo cacheado.
            isRunning = false
        }
    }

    if (showWebView) {
        SpotifyLoginWebView(
            onLoginSuccess = { codeOrCookie ->
                showWebView = false
                isRunning = true

                coroutineScope.launch {
                    val result = spotifyRepo.login(codeOrCookie)
                    if (result.success) {
                        isLoggedIn = true
                        try {
                            browseSections = spotifyRepo.getBrowseSections(10)
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    } else {
                        errorMessage = "Authentication failed: ${result.error}"
                        // E107 — Un `sp_dc` obsoleto que persiste en el WebView (por cerrar la app a medio
                        // login) puede auto-disparar este login y fallar una y otra vez. Al fallar, limpiamos
                        // las cookies para que el siguiente intento muestre el formulario limpio (antes había
                        // que "salir de ese menú y volver" para desatascarlo).
                        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                    }
                    isRunning = false
                }
            },
            onCancel = { showWebView = false }
        )
        return
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Text("Spotify Engine Standby", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showWebView = true }, enabled = !isRunning) {
                    Text("Login to Spotify")
                }
                if (isRunning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }

                // Removed Developer Settings UI
            }
        }
        return
    }

    // Render screen based on current navigation stack state
    val currentScreen = navigationStack.lastOrNull() ?: Screen.Home
    val bottomNavScreens = listOf(Screen.Home, Screen.Search, Screen.Library)
    val isBottomNavScreen = bottomNavScreens.contains(currentScreen)
    val isTravelScreen = currentScreen is Screen.Travel

    val playerStateHolder = audioPlayerService.state.collectAsState()
    val playerState by playerStateHolder
    // IMPORTANTE: currentTrack debe ser una lectura VIVA (derivedStateOf), no un snapshot plano.
    // El contenido de pantalla se envuelve en `remember(currentScreen){ movableContentOf { … } }`
    // (más abajo), que captura las variables una sola vez por pantalla. Un `val currentTrack =
    // playerState.currentTrack` se congelaba ahí hasta cambiar de pantalla → el miniplayer y el
    // resaltado verde de "sonando ahora" se quedaban en la canción anterior. Con derivedStateOf,
    // leer `currentTrack` dentro del movableContent recompone al cambiar de pista.
    val currentTrack by remember { derivedStateOf { playerStateHolder.value.currentTrack } }

    LaunchedEffect(currentTrack?.id) {
        val current = currentTrack?.id
        if (current != null && currentScreen is Screen.TrackDetail) {
            if (currentScreen.id != current) {
                navigationStack[navigationStack.lastIndex] = Screen.TrackDetail(current)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    
    DisposableEffect(isLandscape) {
        val window = (view.context as? ComponentActivity)?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        if (insetsController != null) {
            if (isLandscape) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    Scaffold(
        bottomBar = {
            if (isTravelScreen) return@Scaffold // Travel pinta su propio miniplayer sobre el mapa.
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentTrack != null && currentScreen !is Screen.TrackDetail) {
                    val queueIndex = playerState.queue.indexOfFirst { it.id == currentTrack!!.id }
                    val hasPrevious = queueIndex > 0
                    val hasNext = queueIndex != -1 && queueIndex < playerState.queue.lastIndex

                    MiniPlayer(
                        track = currentTrack!!,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrevious = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
                        onClick = {
                            currentTrack!!.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        }
                    )
                }
                if (isBottomNavScreen && !isLandscape) {
                    NavigationBar(
                        containerColor = Color(0xFF121212),
                        contentColor = Color(0xFF1DB954)
                    ) {
                        NavigationBarItem(
                            selected = currentScreen == Screen.Home,
                            onClick = {
                                if (currentScreen != Screen.Home) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Home)
                                }
                            },
                            icon = { 
                                val activeDownloads by com.varuna.rustify.bridge.DownloadManager.activeDownloadCount.collectAsState()
                                if (activeDownloads > 0 && currentScreen != Screen.Home) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            androidx.compose.material3.Badge(containerColor = Color.Red) {
                                                Text(activeDownloads.toString(), color = Color.White)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home))
                                    }
                                } else {
                                    Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home))
                                }
                            },
                            label = { Text(stringResource(R.string.nav_home)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.Search,
                            onClick = {
                                if (currentScreen != Screen.Search) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Search)
                                }
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search)) },
                            label = { Text(stringResource(R.string.nav_search)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.Library,
                            onClick = {
                                if (currentScreen != Screen.Library) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Library)
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                            label = { Text(stringResource(R.string.nav_library)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF121212),
        contentWindowInsets = if (isLandscape || isTravelScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets
    ) { paddingValues ->
        val contentModifier = if (isLandscape) {
            modifier.fillMaxSize()
        } else {
            modifier.fillMaxSize().padding(paddingValues)
        }
        val screenKey = when (currentScreen) {
            is Screen.Home -> "Home"
            is Screen.Search -> "Search"
            is Screen.Library -> "Library"
            is Screen.PlaylistDetail -> "PlaylistDetail_${currentScreen.id}"
            is Screen.AlbumDetail -> "AlbumDetail_${currentScreen.id}"
            is Screen.ArtistDetail -> "ArtistDetail_${currentScreen.id}"
            is Screen.ArtistAllSongs -> "ArtistAllSongs_${currentScreen.id}"
            is Screen.TrackDetail -> "TrackDetail_${currentScreen.id}"
            is Screen.RadioDetail -> "RadioDetail_${currentScreen.trackId}"
            is Screen.YtmSearch -> "YtmSearch"
            is Screen.YtmAlbumDetail -> "YtmAlbumDetail_${currentScreen.browseId}"
            is Screen.YtmArtistDetail -> "YtmArtistDetail_${currentScreen.channelId}"
            is Screen.YtmPlaylistDetail -> "YtmPlaylistDetail_${currentScreen.playlistId}"
            is Screen.YtmLocalPlaylistDetail -> "YtmLocalPlaylistDetail_${currentScreen.localId}"
            is Screen.Settings -> "Settings"
            is Screen.MatchEditor -> "MatchEditor"
            is Screen.Downloads -> "Downloads"
            is Screen.CustomDownload -> "CustomDownload"
            is Screen.LogViewer -> "LogViewer"
            is Screen.Metrics -> "Metrics"
            is Screen.NewReleases -> "NewReleases"
            is Screen.Dj -> "Dj"
            is Screen.Travel -> "Travel"
            is Screen.TravelWithDestination -> "TravelDest_${currentScreen.lat}_${currentScreen.lon}"
        }

        val screenContent = remember(currentScreen) {
            movableContentOf {
                saveableStateHolder.SaveableStateProvider(screenKey) {
                    when (currentScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            browseSections = browseSections,
                            isRunning = isRunning,
                            errorMessage = errorMessage,
                            onRetry = {
                                coroutineScope.launch {
                                    isRunning = true
                                    errorMessage = null
                                    try {
                                        browseSections = spotifyRepo.getBrowseSections(10)
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    }
                                    isRunning = false
                                }
                            },
                            onItemClick = { item ->
                                when (item) {
                                    is BrowseSectionItem.PlaylistItem -> {
                                        navigationStack.add(Screen.PlaylistDetail(item.playlist.id, item.playlist.name, item.playlist.images))
                                    }
                                    is BrowseSectionItem.AlbumItem -> {
                                        navigationStack.add(Screen.AlbumDetail(item.album.id, item.album.name, item.album.images))
                                    }
                                    is BrowseSectionItem.ArtistItem -> {
                                        navigationStack.add(Screen.ArtistDetail(item.artist.id))
                                    }
                                }
                            },
                            onSettingsClick = {
                                navigationStack.add(Screen.Settings)
                            },
                            onDownloadsClick = {
                                navigationStack.add(Screen.Downloads)
                            },
                            onNewReleasesClick = {
                                navigationStack.add(Screen.NewReleases)
                            },
                            onMetricsClick = {
                                navigationStack.add(Screen.Metrics)
                            },
                            onDjClick = {
                                navigationStack.add(Screen.Dj)
                            },
                            onTravelClick = {
                                navigationStack.add(Screen.Travel)
                            }
                        )
                    }
                    is Screen.Search -> {
                        SearchScreen(
                            spotifyRepo = spotifyRepo,
                            onTrackClick = { track -> audioPlayerService.loadPlaylist(listOf(track), 0) },
                            // E20: pasting a track link opens its detail (doesn't fabricate an
                            // empty stub and try to reproduce it).
                            onOpenTrack = { id -> navigationStack.add(Screen.TrackDetail(id)) },
                            onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                            onGoToQueue = {
                                currentTrack?.id?.let { id ->
                                    navigationStack.add(Screen.TrackDetail(id))
                                }
                            },
                            onAlbumClick = { id, name, images ->
                                navigationStack.add(Screen.AlbumDetail(id, name, images))
                            },
                            onPlaylistClick = { id, name, images ->
                                navigationStack.add(Screen.PlaylistDetail(id, name, images))
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                            currentTrackId = currentTrack?.id
                        )
                    }
                    is Screen.Library -> {
                        LibraryScreen(
                            spotifyRepo = spotifyRepo,
                            selectedTab = librarySelectedTab,
                            onTabSelected = { librarySelectedTab = it },
                            selectedGroup = librarySelectedGroup,
                            onGroupSelected = { librarySelectedGroup = it },
                            onPlaylistClick = { id, name, images ->
                                navigationStack.add(Screen.PlaylistDetail(id, name, images))
                            },
                            onAlbumClick = { id, name, images ->
                                navigationStack.add(Screen.AlbumDetail(id, name, images))
                            },
                            onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                            onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                            onGoToQueue = {
                                currentTrack?.id?.let { id ->
                                    navigationStack.add(Screen.TrackDetail(id))
                                }
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                            onOpenSettings = { navigationStack.add(Screen.Settings) },
                            ytmRepo = ytmRepo,
                            onYtmOpenSearch = { navigationStack.add(Screen.YtmSearch) },
                            onYtmOpenAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                            onYtmOpenArtist = { id, name -> navigationStack.add(Screen.YtmArtistDetail(id, name)) },
                            onYtmOpenLocalPlaylist = { id -> navigationStack.add(Screen.YtmLocalPlaylistDetail(id)) },
                            onYtmOpenPlaylist = { id, title -> navigationStack.add(Screen.YtmPlaylistDetail(id, title)) },
                            onYtmPasteLink = {
                                val pasted = try {
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cb?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                } catch (e: Exception) { null }
                                if (pasted.isNullOrBlank()) {
                                    android.widget.Toast.makeText(context, R.string.paste_clipboard_empty, android.widget.Toast.LENGTH_SHORT).show()
                                } else when (val link = com.varuna.rustify.util.YtMusicLinkParser.parse(pasted)) {
                                    is com.varuna.rustify.util.YtmLink.Track -> {
                                        val yt = FullTrack(
                                            id = "ytm:${link.videoId}", name = "YouTube Music",
                                            externalUri = "https://music.youtube.com/watch?v=${link.videoId}",
                                            explicit = false, durationMs = 0, isrc = "",
                                            artists = emptyList(), album = null
                                        )
                                        audioPlayerService.loadPlaylist(listOf(yt), 0)
                                    }
                                    is com.varuna.rustify.util.YtmLink.Album -> navigationStack.add(Screen.YtmAlbumDetail(link.browseId, ""))
                                    is com.varuna.rustify.util.YtmLink.Artist -> navigationStack.add(Screen.YtmArtistDetail(link.channelId, ""))
                                    is com.varuna.rustify.util.YtmLink.Playlist -> navigationStack.add(Screen.YtmPlaylistDetail(link.playlistId, ""))
                                    null -> android.widget.Toast.makeText(context, R.string.paste_no_ytm_link, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            currentTrackId = currentTrack?.id
                        )
                    }
                is Screen.PlaylistDetail -> {
                    PlaylistScreen(
                        playlistId = currentScreen.id,
                        playlistName = currentScreen.name,
                        playlistImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.AlbumDetail -> {
                    AlbumScreen(
                        albumId = currentScreen.id,
                        albumName = currentScreen.name,
                        albumImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) }
                    )
                }
                is Screen.ArtistDetail -> {
                    ArtistScreen(
                        artistId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        onViewAllSongs = { name -> navigationStack.add(Screen.ArtistAllSongs(currentScreen.id, name)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.ArtistAllSongs -> {
                    ArtistAllSongsScreen(
                        artistId = currentScreen.id,
                        artistName = currentScreen.name,
                        spotifyRepo = spotifyRepo,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.TrackDetail -> {
                    TrackScreen(
                        trackId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        ytmRepo = ytmRepo
                    )
                }
                is Screen.RadioDetail -> {
                    RadioScreen(
                        trackId = currentScreen.trackId,
                        trackName = currentScreen.trackName,
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenTrack = { id -> navigationStack.add(Screen.TrackDetail(id)) }
                    )
                }
                is Screen.NewReleases -> {
                    NewReleasesScreen(
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) }
                    )
                }
                is Screen.YtmSearch -> {
                    YtMusicSearchScreen(
                        repo = ytmRepo,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                        onArtist = { id, name -> navigationStack.add(Screen.YtmArtistDetail(id, name)) },
                        onPlaylist = { id, title -> navigationStack.add(Screen.YtmPlaylistDetail(id, title)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmAlbumDetail -> {
                    YtMusicAlbumScreen(
                        repo = ytmRepo,
                        browseId = currentScreen.browseId,
                        title = currentScreen.title,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmArtistDetail -> {
                    YtMusicArtistScreen(
                        repo = ytmRepo,
                        channelId = currentScreen.channelId,
                        name = currentScreen.name,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmPlaylistDetail -> {
                    YtMusicPlaylistScreen(
                        repo = ytmRepo,
                        playlistId = currentScreen.playlistId,
                        title = currentScreen.title,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmLocalPlaylistDetail -> {
                    YtMusicLocalPlaylistScreen(
                        repo = ytmRepo,
                        localId = currentScreen.localId,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.Settings -> {
                    SettingsScreen(
                        spotifyRepository = spotifyRepo,
                        ytmRepository = ytmRepo,
                        category = settingsCategory,
                        onCategoryChange = { settingsCategory = it },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onNavigateLogViewer = { navigationStack.add(Screen.LogViewer) },
                        onNavigateMetrics = { navigationStack.add(Screen.Metrics) },
                        onNavigateMatchEditor = { navigationStack.add(Screen.MatchEditor) },
                        onNavigateCustomDownload = { navigationStack.add(Screen.CustomDownload) },
                        onLocaleChanged = { newCode ->
                            onLanguageChanged(newCode)
                            coroutineScope.launch {
                                try {
                                    browseSections = spotifyRepo.getBrowseSections(10)
                                } catch (e: Exception) {
                                    android.util.Log.w("MainActivity", "Failed to refresh", e)
                                }
                            }
                        }
                    )
                }
                is Screen.MatchEditor -> {
                    com.varuna.rustify.ui.screens.MatchEditorScreen(
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Downloads -> {
                    com.varuna.rustify.ui.screens.DownloadsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenCustom = { navigationStack.add(Screen.CustomDownload) }
                    )
                }
                is Screen.CustomDownload -> {
                    com.varuna.rustify.ui.screens.CustomDownloadScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.LogViewer -> {
                    LogViewerScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Metrics -> {
                    MetricsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Dj -> {
                    DjScreen(
                        spotifyRepo = spotifyRepo,
                        nowPlaying = currentTrack,
                        queue = playerState.queue,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onEnqueueTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.enqueueAll(tracks) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenSettings = { navigationStack.add(Screen.Settings) }
                    )
                }
                is Screen.Travel -> {
                    TravelScreen(
                        spotifyRepo = spotifyRepo,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        currentTrack = currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrev = playerState.queue.indexOfFirst { it.id == currentTrack?.id } > 0,
                        hasNext = playerState.queue.indexOfFirst { it.id == currentTrack?.id }.let { it != -1 && it < playerState.queue.lastIndex },
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrev = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
                        onMiniPlayerClick = {
                            currentTrack?.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) }
                        }
                    )
                }
                is Screen.TravelWithDestination -> {
                    TravelScreen(
                        spotifyRepo = spotifyRepo,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        currentTrack = currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrev = playerState.queue.indexOfFirst { it.id == currentTrack?.id } > 0,
                        hasNext = playerState.queue.indexOfFirst { it.id == currentTrack?.id }.let { it != -1 && it < playerState.queue.lastIndex },
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrev = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
                        onMiniPlayerClick = {
                            currentTrack?.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) }
                        },
                        initialDestination = com.varuna.rustify.travel.TravelRouting.Geo(
                            lat = currentScreen.lat, lon = currentScreen.lon, label = currentScreen.label ?: ""
                        )
                    )
                }
            }
                }
            }
        }

        if (isLandscape && isBottomNavScreen) {
            Row(modifier = contentModifier) {
                NavigationRail(
                    containerColor = Color(0xFF121212),
                    contentColor = Color(0xFF1DB954),
                    modifier = Modifier.fillMaxHeight(),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Home,
                        onClick = {
                            if (currentScreen != Screen.Home) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Home)
                            }
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                        label = { Text(stringResource(R.string.nav_home)) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Search,
                        onClick = {
                            if (currentScreen != Screen.Search) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Search)
                            }
                        },
                        icon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search)) },
                        label = { Text(stringResource(R.string.nav_search)) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Library,
                        onClick = {
                            if (currentScreen != Screen.Library) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Library)
                            }
                        },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                        label = { Text(stringResource(R.string.nav_library)) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    screenContent()
                }
            }
        } else {
            Box(modifier = contentModifier) {
                screenContent()
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginWebView(onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        private var loginAlreadyDone = false

                        private fun checkLoginAndFinish(url: String?) {
                            if (loginAlreadyDone) return

                            if (url != null && url.contains("spotify.com")) {
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null && cookies.contains("sp_dc=")) {
                                    val spDc = cookies.split(";")
                                        .map { it.trim() }
                                        .find { it.startsWith("sp_dc=") }
                                        ?.substringAfter("sp_dc=")

                                    if (!spDc.isNullOrEmpty()) {
                                        loginAlreadyDone = true
                                        onLoginSuccess(spDc)
                                    }
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            checkLoginAndFinish(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            checkLoginAndFinish(url)
                        }
                    }

                    loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MiniPlayer(
    track: FullTrack,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
    val imgUrl = track.effectiveCoverUrl()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A))
            .clickable { onClick() }
    ) {
        // Thin progress indicator at the top
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = spotifyGreen,
            trackColor = Color.Gray.copy(alpha = 0.3f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mini Cover Art
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color.DarkGray
            ) {
                if (!imgUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = imgUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title and Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.bouncingMarquee()
                )
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.bouncingMarquee()
                )
            }

            // Previous Button
            IconButton(
                onClick = onSkipPrevious,
                enabled = hasPrevious
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (hasPrevious) Color.White else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Play/Pause button
            IconButton(onClick = onTogglePlayPause) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = spotifyGreen,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Next Button
            IconButton(
                onClick = onSkipNext,
                enabled = hasNext
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (hasNext) Color.White else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

