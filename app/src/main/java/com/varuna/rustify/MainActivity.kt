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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.bridge.SpotifyEngineException
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.theme.RustifyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.SpotifyImage

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

    val coroutineScope = rememberCoroutineScope()

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
        SpotifyLoginWebView(
            onSpDcFound = { spDcCookie ->
                showWebView = false
                isRunning = true

                coroutineScope.launch {
                    val result = spotifyRepo.login(spDcCookie)
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            }
        }
        return
    }

    // Main Home Screen Interface
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            spotifyRepo.logout()
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            isLoggedIn = false
                            browseSections = null
                        }
                    }) {
                        Text("Logout", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = modifier.fillMaxSize().padding(paddingValues)) {
            if (isRunning && browseSections == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMessage != null) {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    Text("Error:", color = MaterialTheme.colorScheme.error)
                    Text(errorMessage!!)
                    Button(onClick = {
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
                    }) {
                        Text("Retry")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    browseSections?.let { sections ->
                        items(sections) { section ->
                            BrowseSectionView(section = section)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowseSectionView(section: BrowseSection) {
    if (section.items.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(section.items) { item ->
                when (item) {
                    is BrowseSectionItem.PlaylistItem -> PlaylistItemCard(item.playlist.name, item.playlist.description, item.playlist.images, onClick = {})
                    is BrowseSectionItem.AlbumItem -> PlaylistItemCard(item.album.name, item.album.artists.joinToString(", ") { it.name }, item.album.images, onClick = {})
                    is BrowseSectionItem.ArtistItem -> PlaylistItemCard(item.artist.name, "Artist", item.artist.images, isCircle = true, onClick = {})
                }
            }
        }
    }
}

@Composable
fun PlaylistItemCard(
    title: String,
    subtitle: String?,
    images: List<SpotifyImage>?,
    isCircle: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        val imageUrl = images?.maxByOrNull { it.width ?: 0 }?.url
        
        Surface(
            modifier = Modifier
                .size(140.dp)
                .clip(if (isCircle) RoundedCornerShape(70.dp) else RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (imageUrl != null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text(title.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = Color.White)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                    Text(title.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginWebView(onSpDcFound: (String) -> Unit, onCancel: () -> Unit) {
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

                        // Guard to prevent multiple cookie interceptions.
                        // The WebView fires both doUpdateVisitedHistory and onPageFinished
                        // during redirects, which would trigger multiple login attempts.
                        private var cookieAlreadyFound = false

                        private fun checkCookieAndFinish(url: String?) {
                            // If we already found the cookie, skip all further checks
                            if (cookieAlreadyFound) return

                            if (url != null && url.contains("spotify.com")) {
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null && cookies.contains("sp_dc=")) {
                                    val spDc = cookies.split(";")
                                        .map { it.trim() }
                                        .find { it.startsWith("sp_dc=") }
                                        ?.substringAfter("sp_dc=")

                                    if (!spDc.isNullOrEmpty()) {
                                        // Set the guard BEFORE calling the callback
                                        cookieAlreadyFound = true
                                        onSpDcFound(spDc)
                                    }
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            checkCookieAndFinish(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            checkCookieAndFinish(url)
                        }
                    }

                    // URL REAL: Pasarela de login oficial de Spotify
                    loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}