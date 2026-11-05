// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebVideo.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.WebElementView
import com.varuna.rustify.platform.throughServer
import com.varuna.rustify.player.LocalPlayer
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.w3c.dom.HTMLVideoElement
import kotlin.math.abs

/*
 * Video in the browser, as on iOS: a muted <video> under the track screen. The Canvas loops; the
 * song's video follows the engine, which goes on playing the sound. Everything comes through the
 * server (/api/fetch), the page's own origin. YouTube answers the video as an HLS manifest, which
 * only Safari reads by itself: elsewhere hls.js does (served by the server, hls.light.min.js, never
 * from a CDN), its requests rewritten through the server too.
 */

/** Loads hls.js once, then answers whether it can play here (false where the browser reads HLS itself). */
private fun ensureHls(onReady: () -> Unit): Unit = js("""{
    if (window.Hls) { onReady(); return; }
    var s = document.getElementById('rustify-hls');
    if (!s) {
        s = document.createElement('script');
        s.id = 'rustify-hls';
        s.src = 'hls.light.min.js';
        document.head.appendChild(s);
    }
    s.addEventListener('load', function () { onReady(); });
}""")

/** hls.js works here (Media Source Extensions); where it does not (an iPhone's Safari), the browser's own HLS. */
private fun hlsJsSupported(): Boolean = js("!!(window.Hls && window.Hls.isSupported())")

/**
 * Puts the element's layer behind Compose's canvas, which leaves the element's place clear: Compose
 * puts it above by default, and the song's video, the size of the screen under the controls, hid
 * them all. Done once the element is in the page, which is a frame or two after it is made.
 */
private fun sendBehindCanvas(video: HTMLVideoElement): Unit = js("""{
    var tries = 0;
    (function place() {
        var layer = video.parentElement && video.parentElement.parentElement;
        if (layer) {
            layer.style.zIndex = '-1';
            // A stacking context for the layer, or the page's own background paints over it.
            if (layer.parentElement) layer.parentElement.style.zIndex = '0';
        }
        else if (tries++ < 120) requestAnimationFrame(place);
    })();
}""")

/** An hls.js player on [video], every request of it sent through the server; answered for [stopHls]. */
private fun startHls(video: HTMLVideoElement, url: String, proxy: String): JsAny = js("""(function () {
    var hls = new window.Hls({
        xhrSetup: function (xhr, target) { xhr.open('GET', proxy + encodeURIComponent(target), true); },
        maxBufferLength: 20
    });
    hls.loadSource(url);
    hls.attachMedia(video);
    return hls;
})()""")

private fun stopHls(hls: JsAny): Unit = js("{ try { hls.destroy(); } catch (e) {} }")

private fun playMuted(video: HTMLVideoElement): Unit = js("{ video.muted = true; var p = video.play(); if (p) p.catch(function () {}); }")

private fun newVideo(loop: Boolean, cover: Boolean): HTMLVideoElement =
    (document.createElement("video") as HTMLVideoElement).apply {
        muted = true
        setAttribute("muted", "")
        setAttribute("playsinline", "")
        this.loop = loop
        style.width = "100%"
        style.height = "100%"
        style.setProperty("object-fit", if (cover) "cover" else "contain")
        style.setProperty("background", "#000")
        // The track screen's controls take the touches, not the video.
        style.setProperty("pointer-events", "none")
    }

/** The prefix of a URL the server fetches for the page (`/api/fetch?url=`), for hls.js's requests. */
private val proxyPrefix: String by lazy { throughServer("") }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoElement(url: String, loop: Boolean, cover: Boolean, follow: Boolean) {
    var hls: JsAny? = null
    val video = androidx.compose.runtime.remember(url) { newVideo(loop, cover) }
    DisposableEffect(url) {
        sendBehindCanvas(video)
        val hlsManifest = url.contains(".m3u8") || url.contains("/manifest/hls")
        if (!hlsManifest) {
            video.src = throughServer(url)
            playMuted(video)
        } else {
            // YouTube signs the stream for the server's address: every request goes through the server,
            // which hls.js does; the browser's own HLS (Safari) is left only for where hls.js cannot run.
            ensureHls {
                if (hlsJsSupported()) hls = startHls(video, url, proxyPrefix) else video.src = throughServer(url)
                playMuted(video)
            }
        }
        onDispose {
            hls?.let(::stopHls)
            video.pause()
            video.removeAttribute("src")
            video.load()
        }
    }
    if (follow) {
        val engine = LocalPlayer.current
        LaunchedEffect(url) {
            while (isActive) {
                val now = engine.state.value
                if (now.isPlaying && video.paused) playMuted(video) else if (!now.isPlaying && !video.paused) video.pause()
                val at = video.currentTime * 1000
                if (video.readyState >= 1 && abs(at - now.positionMs) > FOLLOW_WITHIN_MS) video.currentTime = now.positionMs / 1000.0
                delay(FOLLOW_EVERY_MS)
            }
        }
    }
    WebElementView(factory = { video }, modifier = Modifier.fillMaxSize())
    // The element is behind the canvas (sendBehindCanvas): its place on the canvas is cleared to let it
    // show, as Compose does by itself on iOS. Whatever the screen draws after this (the controls) stays.
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) })
}

/** A Canvas: a short clip, on a loop and silent, under the track screen. */
@Composable
internal fun WebCanvasVideo(url: String, fitWidth: Boolean) = VideoElement(url, loop = true, cover = !fitWidth, follow = false)

/** The song's video on the Video tab, silent, following the engine that plays the sound. */
@Composable
internal fun WebPlayerVideo(url: String) = VideoElement(url, loop = false, cover = false, follow = true)

private const val FOLLOW_WITHIN_MS = 1_000.0
private const val FOLLOW_EVERY_MS = 500L
