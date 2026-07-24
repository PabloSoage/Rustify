package com.varuna.rustify.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.varuna.rustify.R
import com.varuna.rustify.sync.AppAuthDriveAuth.Companion.REDIRECT_URI
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues

/**
 * Google Drive authentication **via the browser** (AppAuth / RFC 8252 + PKCE), following the
 * paimon.moe web model: **a single "Web" OAuth client that is NOT tied to the APK signature**, so it
 * works on any build (including forks) without the user registering anything.
 *
 * The Drive REST layer ([GoogleDriveSync]) is token-agnostic, so this backend only produces *access
 * tokens* (and refreshes them). It coexists with the Play Services `AuthorizationClient` backend;
 * the user chooses the method in Settings ([DriveSyncPrefs.authMethod]).
 *
 * **Configuration without touching code** (`res/values/strings.xml`, `translatable=false`):
 *  - `drive_appauth_client_id`  → Client ID of the **Web** OAuth client.
 *  - `drive_appauth_client_secret` → its secret (Google requires it for the token endpoint of a Web
 *    client; in an installed app it is NOT confidential — PKCE is the real protection).
 *
 * The **redirect** is an https App Link on a verified domain ([REDIRECT_URI]); see the manifest
 * (`RedirectUriReceiverActivity`) and the host's `assetlinks.json`.
 */
class AppAuthDriveAuth(private val appContext: Context) {

    companion object {
        val AUTH_ENDPOINT: Uri = "https://accounts.google.com/o/oauth2/v2/auth".toUri()
        val TOKEN_ENDPOINT: Uri = "https://oauth2.googleapis.com/token".toUri()
        const val REDIRECT_URI = "https://rustify-music.github.io/oauth2redirect"
        private const val PREFS = "rustify_settings"
        private const val K_STATE = "drive_appauth_state"
    }

    private fun clientId(): String = appContext.getString(R.string.drive_appauth_client_id).trim()
    private fun clientSecret(): String = appContext.getString(R.string.drive_appauth_client_secret).trim()

    /** true if at least a Client ID is configured (without it, this method cannot work). */
    fun isConfigured(): Boolean = clientId().isNotEmpty()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadState(): AuthState {
        val raw = prefs().getString(K_STATE, null) ?: return AuthState()
        return runCatching { AuthState.jsonDeserialize(raw) }.getOrDefault(AuthState())
    }

    private fun saveState(state: AuthState) {
        prefs().edit { putString(K_STATE, state.jsonSerializeString()) }
    }

    /** Forgets the local authorization (the user can revoke the permission from their Google account). */
    fun signOut() { prefs().edit { remove(K_STATE) } }

    private fun serviceConfig() = AuthorizationServiceConfiguration(AUTH_ENDPOINT, TOKEN_ENDPOINT)

    private fun clientAuth(): ClientAuthentication =
        if (clientSecret().isEmpty()) NoClientAuthentication.INSTANCE else ClientSecretPost(clientSecret())

    /**
     * Intent the Activity launches with `StartActivityForResult` to open the consent flow in a
     * Custom Tab. Returns null if not configured.
     */
    fun authRequestIntent(): Intent? {
        if (!isConfigured()) return null
        val request = AuthorizationRequest.Builder(
            serviceConfig(), clientId(), ResponseTypeValues.CODE, REDIRECT_URI.toUri()
        ).setScopes("openid", GoogleDriveSync.SCOPE_APPDATA).build()
        val service = AuthorizationService(appContext)
        val intent = service.getAuthorizationRequestIntent(request)
        service.dispose()
        return intent
    }

    /**
     * Processes the `data` Intent returned by the Custom Tab: exchanges the code for tokens (storing
     * the refresh token) and delivers the access token.
     */
    fun handleResponse(data: Intent?, onToken: (String) -> Unit, onError: (Throwable) -> Unit) {
        val resp = data?.let { AuthorizationResponse.fromIntent(it) }
        val authEx = data?.let { AuthorizationException.fromIntent(it) }
        if (resp == null) { onError(authEx ?: IllegalStateException("No authorization response")); return }
        val service = AuthorizationService(appContext)
        service.performTokenRequest(resp.createTokenExchangeRequest(), clientAuth()) { tokenResp, tokenEx ->
            val state = loadState()
            state.update(resp, authEx)
            state.update(tokenResp, tokenEx)
            saveState(state)
            service.dispose()
            val token = tokenResp?.accessToken
            if (token != null) onToken(token)
            else onError(tokenEx ?: IllegalStateException("Token exchange failed"))
        }
    }

    /**
     * A fresh access token **silently** (refreshes using the stored refresh token). [onNone] if
     * there is no prior authorization (the interactive flow is required).
     */
    fun getFreshToken(onToken: (String) -> Unit, onNone: () -> Unit, onError: (Throwable) -> Unit) {
        val state = loadState()
        if (!state.isAuthorized) { onNone(); return }
        val service = AuthorizationService(appContext)
        state.performActionWithFreshTokens(service, clientAuth()) { accessToken, _, ex ->
            saveState(state)
            service.dispose()
            if (accessToken != null) onToken(accessToken)
            else onError(ex ?: IllegalStateException("Token refresh failed"))
        }
    }
}
