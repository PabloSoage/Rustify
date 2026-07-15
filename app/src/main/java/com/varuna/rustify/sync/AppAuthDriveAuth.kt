package com.varuna.rustify.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.varuna.rustify.R
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
 * E50 (B) — Autenticación de Google Drive **vía navegador** (AppAuth / RFC 8252 + PKCE), al estilo
 * del modelo web de paimon.moe: **un único OAuth client tipo "Web" que NO va atado a la firma del
 * APK**, así que funciona en cualquier build (incluidos forks) sin que el usuario registre nada.
 *
 * El REST de Drive ([GoogleDriveSync]) es agnóstico al token, por lo que este backend solo se ocupa
 * de producir *access tokens* (y refrescarlos). Coexiste con el backend A (Play Services
 * `AuthorizationClient`); el usuario elige el método en Ajustes ([DriveSyncPrefs.authMethod]).
 *
 * **Config sin tocar código** (`res/values/strings.xml`, `translatable=false`):
 *  - `drive_appauth_client_id`  → Client ID del OAuth client **Web**.
 *  - `drive_appauth_client_secret` → su secret (Google lo exige para el token endpoint de un client
 *    Web; en una app instalada NO es confidencial — PKCE es la protección real).
 *
 * El **redirect** es un App Link https en un dominio verificado ([REDIRECT_URI]); ver el manifest
 * (`RedirectUriReceiverActivity`) y el `assetlinks.json` del host.
 */
class AppAuthDriveAuth(private val appContext: Context) {

    companion object {
        val AUTH_ENDPOINT: Uri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
        val TOKEN_ENDPOINT: Uri = Uri.parse("https://oauth2.googleapis.com/token")
        const val REDIRECT_URI = "https://rustify-music.github.io/oauth2redirect"
        private const val PREFS = "rustify_settings"
        private const val K_STATE = "drive_appauth_state"
    }

    private fun clientId(): String = appContext.getString(R.string.drive_appauth_client_id).trim()
    private fun clientSecret(): String = appContext.getString(R.string.drive_appauth_client_secret).trim()

    /** true si hay al menos un Client ID configurado (sin él, este método no puede funcionar). */
    fun isConfigured(): Boolean = clientId().isNotEmpty()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadState(): AuthState {
        val raw = prefs().getString(K_STATE, null) ?: return AuthState()
        return runCatching { AuthState.jsonDeserialize(raw) }.getOrDefault(AuthState())
    }

    private fun saveState(state: AuthState) {
        prefs().edit { putString(K_STATE, state.jsonSerializeString()) }
    }

    /** ¿Tenemos una autorización previa (refresh token) para refrescar en silencio? */
    fun isAuthorized(): Boolean = loadState().isAuthorized

    /** Olvida la autorización local (el usuario puede revocar el permiso desde su cuenta Google). */
    fun signOut() { prefs().edit { remove(K_STATE) } }

    private fun serviceConfig() = AuthorizationServiceConfiguration(AUTH_ENDPOINT, TOKEN_ENDPOINT)

    private fun clientAuth(): ClientAuthentication =
        if (clientSecret().isEmpty()) NoClientAuthentication.INSTANCE else ClientSecretPost(clientSecret())

    /**
     * Intent que la Activity lanza con `StartActivityForResult` para abrir el consentimiento en un
     * Custom Tab. Devuelve null si no está configurado.
     */
    fun authRequestIntent(): Intent? {
        if (!isConfigured()) return null
        val request = AuthorizationRequest.Builder(
            serviceConfig(), clientId(), ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI)
        ).setScopes("openid", GoogleDriveSync.SCOPE_APPDATA).build()
        val service = AuthorizationService(appContext)
        val intent = service.getAuthorizationRequestIntent(request)
        service.dispose()
        return intent
    }

    /**
     * Procesa el `data` Intent que devuelve el Custom Tab: intercambia el code por tokens (guardando
     * el refresh token) y entrega el access token.
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
     * Access token fresco **en silencio** (refresca con el refresh token guardado). [onNone] si no
     * hay autorización previa (hace falta el flujo interactivo).
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
