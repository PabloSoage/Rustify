// app/src/main/java/com/varuna/rustify/MainActivity.kt
package com.varuna.rustify

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.theme.RustifyTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.SpotifyImage
import androidx.activity.compose.BackHandler
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.TrackScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LibraryMusic

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    data class PlaylistDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class AlbumDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class ArtistDetail(val id: String) : Screen()
    data class TrackDetail(val id: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RustifyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EngineTester(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineTester(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val spotifyRepo = remember { SpotifyRepository(context) }

    var isRunning by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var browseSections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Dynamic credentials input state
    var showDevSettings by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf(spotifyRepo.getDeveloperClientId() ?: "") }
    var clientSecretInput by remember { mutableStateOf(spotifyRepo.getDeveloperClientSecret() ?: "") }

    val coroutineScope = rememberCoroutineScope()
    val navigationStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // Physical / Gesture Back Button handling
    BackHandler(enabled = navigationStack.size > 1) {
        navigationStack.removeAt(navigationStack.lastIndex)
    }

    // Auto-restore session on first launch
    LaunchedEffect(Unit) {
        if (spotifyRepo.hasSavedSession()) {
            isRunning = true
            val result = spotifyRepo.restoreSession()
            if (result?.success == true) {
                isLoggedIn = true
                try {
                    browseSections = spotifyRepo.getBrowseSections(10)
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            } else {
                errorMessage = "Saved session expired. Please log in again."
            }
            isRunning = false
        }
    }

    if (showWebView) {
        val hasCustomCreds = clientIdInput.trim().isNotEmpty() && clientSecretInput.trim().isNotEmpty()
        SpotifyLoginWebView(
            clientId = if (hasCustomCreds) clientIdInput.trim() else "",
            onLoginSuccess = { codeOrCookie ->
                showWebView = false
                isRunning = true

                coroutineScope.launch {
                    val result = if (hasCustomCreds) {
                        spotifyRepo.loginWithAuthCode(codeOrCookie, "http://localhost:8080/callback")
                    } else {
                        spotifyRepo.login(codeOrCookie)
                    }
                    if (result.success) {
                        isLoggedIn = true
                        try {
                            browseSections = spotifyRepo.getBrowseSections(10)
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    } else {
                        errorMessage = "Authentication failed: ${result.error}"
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

                Spacer(modifier = Modifier.height(32.dp))
                TextButton(onClick = { showDevSettings = !showDevSettings }) {
                    Text(if (showDevSettings) "Hide API Settings" else "Developer API Credentials")
                }
                
                if (showDevSettings) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        label = { Text("Spotify Client ID") },
                        modifier = Modifier.fillMaxWidth(0.85f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clientSecretInput,
                        onValueChange = { clientSecretInput = it },
                        label = { Text("Spotify Client Secret") },
                        modifier = Modifier.fillMaxWidth(0.85f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            spotifyRepo.setDeveloperCredentials(clientIdInput.trim(), clientSecretInput.trim())
                            showDevSettings = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text("Save Credentials", color = Color.Black)
                    }
                }
            }
        }
        return
    }

    // Render screen based on current navigation stack state
    val currentScreen = navigationStack.lastOrNull() ?: Screen.Home
    val bottomNavScreens = listOf(Screen.Home, Screen.Search, Screen.Library)
    val isBottomNavScreen = bottomNavScreens.contains(currentScreen)

    Scaffold(
        bottomBar = {
            if (isBottomNavScreen) {
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
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
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
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        label = { Text("Search") },
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
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                        label = { Text("Library") },
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
    ) { paddingValues ->
        Box(modifier = modifier.fillMaxSize().padding(paddingValues)) {
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
                        }
                    )
                }
                is Screen.Search -> {
                    SearchScreen(
                        spotifyRepo = spotifyRepo,
                        onTrackClick = { it.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) } },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onPlaylistClick = { id, name, images ->
                            navigationStack.add(Screen.PlaylistDetail(id, name, images))
                        }
                    )
                }
                is Screen.Library -> {
                    LibraryScreen(
                        spotifyRepo = spotifyRepo,
                        onPlaylistClick = { id, name, images ->
                            navigationStack.add(Screen.PlaylistDetail(id, name, images))
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onTrackClick = { it.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) } }
                    )
                }
                is Screen.PlaylistDetail -> {
                    PlaylistScreen(
                        playlistId = currentScreen.id,
                        playlistName = currentScreen.name,
                        playlistImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { it.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) } }
                    )
                }
                is Screen.AlbumDetail -> {
                    AlbumScreen(
                        albumId = currentScreen.id,
                        albumName = currentScreen.name,
                        albumImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { it.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) } }
                    )
                }
                is Screen.ArtistDetail -> {
                    ArtistScreen(
                        artistId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { it.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) } },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
                is Screen.TrackDetail -> {
                    TrackScreen(
                        trackId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginWebView(clientId: String, onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
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

                            if (url != null) {
                                if (clientId.isNotEmpty() && url.contains("code=")) {
                                    val code = url.substringAfter("code=").substringBefore("&")
                                    if (code.isNotEmpty()) {
                                        loginAlreadyDone = true
                                        onLoginSuccess(code)
                                    }
                                } else if (clientId.isEmpty() && url.contains("spotify.com")) {
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

                    if (clientId.isNotEmpty()) {
                        val scopes = listOf(
                            "user-library-read",
                            "user-library-modify",
                            "playlist-read-private",
                            "playlist-modify-public",
                            "playlist-modify-private",
                            "user-follow-read",
                            "user-follow-modify",
                            "user-read-private",
                            "user-read-email"
                        ).joinToString(" ")
                        val authUrl = "https://accounts.spotify.com/authorize?client_id=$clientId&response_type=code&redirect_uri=http://localhost:8080/callback&scope=${scopes}"
                        loadUrl(authUrl)
                    } else {
                        loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}