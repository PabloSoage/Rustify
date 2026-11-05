// shared/src/wasmJsMain/kotlin/com/varuna/rustify/bridge/CoreCall.wasm.kt
package com.varuna.rustify.bridge

import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonObject

private fun postBlocking(url: String, body: String): String = js(
    """(function() {
        var x = new XMLHttpRequest();
        x.open('POST', url, false);
        x.setRequestHeader('Content-Type', 'application/json');
        try { x.send(body); } catch (e) { return ''; }
        if (x.status === 401) { window.location.reload(); }
        return x.responseText;
    })()"""
)

private fun post(url: String, body: String): Promise<JsString> = js(
    """fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body })
        .then(function(r) {
            if (r.status === 401) { window.location.reload(); }
            return r.text();
        })"""
)

private const val FAILURE = """{"success":false,"error":"the server could not be reached","kind":"transient"}"""

/**
 * The core on the web: the same dispatcher as iOS, on the server (`rustify-server`, `/api/call`).
 *
 * [callBlocking] holds the browser's only thread until the answer comes, with a synchronous request: it
 * is for what the other platforms call on the main thread because it only touches memory, a round trip
 * to a server on the same network, and there is no other way to answer a plain function here.
 */
actual object CoreCall {
    /** The server told the core its folders when it started, and refuses a browser that names others. */
    private val SERVERS_OWN = setOf("initCacheDir", "initSpotifyCacheDir")
    private const val DONE = """{"success":true}"""

    actual fun callBlocking(method: String, args: JsonObject): String =
        if (method in SERVERS_OWN) DONE else postBlocking("api/call/$method", args.toString()).ifEmpty { FAILURE }

    actual suspend fun call(method: String, args: JsonObject): String = if (method in SERVERS_OWN) DONE else try {
        post("api/call/$method", args.toString()).await<JsString>().toString()
    } catch (_: Throwable) {
        FAILURE
    }
}
