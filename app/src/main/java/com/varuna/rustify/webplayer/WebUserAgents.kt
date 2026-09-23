package com.varuna.rustify.webplayer

/**
 * The user agents the web player presents, derived from the system WebView's own.
 *
 * Pure string work, kept out of [WebPlayerController] so it can be tested without a WebView.
 */
internal object WebUserAgents {

    /**
     * Chrome for Android, as a **phone**.
     *
     * The WebView's markers (`; wv`, `Version/4.0`) come off so the page sees a browser rather than
     * an embedded view. And `Mobile` goes in if the system left it out — which it does on every
     * tablet. Measured on a Mi Pad 6S Pro: without `Mobile`, Spotify answers the track page with
     * "Navegador no compatible — Spotify no está disponible en este navegador" and nothing plays;
     * with it, the same page in the same WebView loads and plays. The mobile page was only ever
     * validated on a phone, where the token is already there, which is how this went unnoticed.
     */
    fun mobile(systemUa: String): String {
        val browser = systemUa.replace("; wv", "").replace("Version/4.0 ", "")
        return if (MOBILE_SAFARI.containsMatchIn(browser)) browser
        else browser.replaceFirst(SAFARI, "Mobile Safari/")
    }

    /**
     * Chrome on Windows, keeping the system WebView's Chrome major version so it never goes stale as
     * the device updates.
     */
    fun desktop(systemUa: String): String {
        val chromeMajor = Regex("Chrome/(\\d+)").find(systemUa)?.groupValues?.get(1) ?: "124"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }

    private val MOBILE_SAFARI = Regex("""\bMobile Safari/""")
    private val SAFARI = Regex("""\bSafari/""")
}
