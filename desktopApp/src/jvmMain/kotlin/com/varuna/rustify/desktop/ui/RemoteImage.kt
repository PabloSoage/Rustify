// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/RemoteImage.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.SpotifyImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Covers on the desktop, where the phone's Coil is not available: fetched with the JDK's HTTP client,
 * decoded by Skia (which Compose for desktop already carries) and kept in memory, the most recently
 * used few hundred. A cover that fails shows the title's first letter, as the phone does.
 */
internal object ImageCache {
    private const val MAX = 400
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }

    fun peek(url: String?): ImageBitmap? = url?.let { synchronized(cache) { cache[it] } }

    suspend fun load(url: String): ImageBitmap? = peek(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build()
            val bytes = client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body()
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()?.also { synchronized(cache) { cache[url] = it } }
    }
}

/** The image closest to [atLeast] pixels wide without going under it, or the largest there is. */
internal fun List<SpotifyImage>?.best(atLeast: Int): String? {
    val list = this.orEmpty().filter { it.url.isNotBlank() }
    return (list.filter { (it.width ?: 0) >= atLeast }.minByOrNull { it.width ?: Int.MAX_VALUE }
        ?: list.maxByOrNull { it.width ?: 0 })?.url
}

@Composable
internal fun RemoteImage(url: String?, title: String, shape: Shape, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(ImageCache.peek(url)) }
    LaunchedEffect(url) {
        if (bitmap == null && !url.isNullOrBlank()) bitmap = ImageCache.load(url)
    }
    Box(modifier.clip(shape).background(Palette.Card), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(title.take(1).uppercase(), color = Palette.Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}
