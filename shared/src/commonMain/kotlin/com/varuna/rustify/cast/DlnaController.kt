// shared/src/commonMain/kotlin/com/varuna/rustify/cast/DlnaController.kt
package com.varuna.rustify.cast

import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Telling a DLNA renderer what to do — E16.
 *
 * UPnP AVTransport, which is SOAP over HTTP: an XML envelope with a `SOAPAction` header. Old and
 * verbose, and the reason it is worth it is in [CastDiscovery] — it is the path where the address of
 * the device is known, which is what the server's allow-list needs.
 *
 * Everything here talks to the **device**. The audio itself never passes through: the device fetches
 * it from our own server, which is the entire point of casting rather than streaming.
 *
 * ## What a renderer does with a URL
 *
 * `SetAVTransportURI` hands it the address; `Play` starts it. From then on the device is doing HTTP
 * range requests against the host, and the host is a web server for as long as the session lasts.
 * Nothing about that is hidden — see `server/lan.rs`.
 */
object DlnaController {

    private const val TAG = "DlnaController"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val TIMEOUT_MS = 5_000L

    private val client: HttpClient by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = TIMEOUT_MS
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    /**
     * Hands the device a URL and starts it.
     *
     * [title] and [artist] go in the metadata so the device's own screen shows something other than
     * a file name — which is most of what makes it feel like casting rather than a URL being poked
     * into a TV.
     */
    suspend fun play(
        controlUrl: String,
        mediaUrl: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Boolean {
        val metadata = didl(mediaUrl, title, artist, durationMs)
        val set = soap(
            controlUrl,
            "SetAVTransportURI",
            """<InstanceID>0</InstanceID>
               <CurrentURI>${escape(mediaUrl)}</CurrentURI>
               <CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>"""
        )
        if (!set) return false
        return soap(controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun pause(controlUrl: String): Boolean =
        soap(controlUrl, "Pause", "<InstanceID>0</InstanceID>")

    suspend fun resume(controlUrl: String): Boolean =
        soap(controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    suspend fun stop(controlUrl: String): Boolean =
        soap(controlUrl, "Stop", "<InstanceID>0</InstanceID>")

    /** Seeks to [positionMs]. `REL_TIME` takes `H:MM:SS`, which is what [clock] produces. */
    suspend fun seek(controlUrl: String, positionMs: Long): Boolean = soap(
        controlUrl,
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${clock(positionMs)}</Target>"
    )

    /**
     * Where the device says it is, in milliseconds, or null if it will not say.
     *
     * Asked rather than assumed: once the device is playing, it owns the clock. A progress bar
     * driven by the host's idea of the position drifts within a minute.
     */
    suspend fun position(controlUrl: String): Long? {
        val body = soapRaw(controlUrl, "GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return null
        val rel = tag(body, "RelTime") ?: return null
        return millis(rel)
    }

    /**
     * What the device says it is doing — `PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`, `TRANSITIONING`,
     * `NO_MEDIA_PRESENT` — or null if it will not say.
     *
     * Asked separately from [position] because the position alone cannot tell a finished track from
     * a device that stopped answering: both look like a clock that stopped moving, and the two need
     * opposite responses. One advances the queue; the other ends the session.
     */
    suspend fun transportState(controlUrl: String): String? {
        val body = soapRaw(controlUrl, "GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return tag(body, "CurrentTransportState")?.trim()?.takeIf { it.isNotBlank() }
    }

    // --------------------------------------------------------------------------------------------

    private suspend fun soap(controlUrl: String, action: String, body: String): Boolean =
        soapRaw(controlUrl, action, body) != null

    private suspend fun soapRaw(controlUrl: String, action: String, body: String): String? =
        withContext(Dispatchers.IO) {
            val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:$action xmlns:u="$AV_TRANSPORT">$body</u:$action></s:Body>
</s:Envelope>"""
            runCatching {
                val response = client.post(controlUrl) {
                    header("SOAPAction", "\"$AV_TRANSPORT#$action\"")
                    setBody(TextContent(envelope, ContentType.Text.Xml.withCharset(Charsets.UTF_8)))
                }
                val code = response.status.value
                val text = response.bodyAsText()
                if (code !in 200..299) {
                    Log.w(TAG, "$action answered $code: ${text.take(300)}")
                    return@runCatching null
                }
                text
            }.getOrElse {
                Log.w(TAG, "$action failed", it)
                null
            }
        }

    /**
     * The metadata envelope a renderer expects alongside the URL.
     *
     * Minimal on purpose. A renderer that dislikes one optional field tends to reject the whole
     * request rather than ignore the field, so there is nothing here that is not needed.
     */
    internal fun didl(url: String, title: String, artist: String, durationMs: Long): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
<item id="0" parentID="-1" restricted="1">
<dc:title>${escape(title)}</dc:title>
<upnp:artist>${escape(artist)}</upnp:artist>
<upnp:class>object.item.audioItem.musicTrack</upnp:class>
<res protocolInfo="http-get:*:audio/mpeg:*" duration="${clock(durationMs)}">${escape(url)}</res>
</item>
</DIDL-Lite>"""

    /** `H:MM:SS`, which is what AVTransport means by a time. */
    internal fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        val minStr = if (minutes < 10) "0$minutes" else "$minutes"
        val secStr = if (seconds < 10) "0$seconds" else "$seconds"
        return "$hours:$minStr:$secStr"
    }

    /** The inverse of [clock], tolerant of the fractional seconds some devices add. */
    internal fun millis(clock: String): Long? {
        val parts = clock.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].substringBefore('.').toLongOrNull() ?: return null
        return ((h * 3600) + (m * 60) + s) * 1000
    }

    internal fun tag(xml: String, name: String): String? {
        val open = xml.indexOf("<$name>")
        if (open < 0) return null
        val start = open + name.length + 2
        val end = xml.indexOf("</$name>", start)
        if (end < 0) return null
        return xml.substring(start, end)
    }

    /**
     * XML-escapes a value.
     */
    internal fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
