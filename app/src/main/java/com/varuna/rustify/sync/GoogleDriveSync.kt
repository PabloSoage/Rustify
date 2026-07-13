package com.varuna.rustify.sync

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * E50 — Cliente de sincronización con Google Drive (carpeta AppData privada).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  AUTENTICACIÓN — pasos que DEBE hacer el usuario en Google Cloud Console
 * ─────────────────────────────────────────────────────────────────────────────
 *  Sin esta configuración la sync NO funcionará en runtime (dará DEVELOPER_ERROR
 *  o "access denied"). El código está completo; solo falta el proyecto Cloud.
 *
 *  1. Crear un proyecto en https://console.cloud.google.com/.
 *  2. APIs & Services → Enable APIs → habilitar **Google Drive API**.
 *  3. OAuth consent screen: tipo *External*; añadir SOLO el scope
 *     `https://www.googleapis.com/auth/drive.appdata` (+ openid/email básicos).
 *     `drive.appdata` NO es sensible → evita verificación manual. Pasar a
 *     *In production* para que el token no caduque a los 7 días (modo Testing).
 *  4. Credentials → Create OAuth client ID **Android**: package
 *     `com.varuna.rustify` + SHA-1 de CADA keystore (debug, release y, si usas
 *     Play App Signing, la clave de firma de Play). SHA-1 errónea = error 10.
 *  5. Credentials → Create OAuth client ID **Web**: copia su Client ID en
 *     `res/values/strings.xml` → `default_web_client_id`. Este Web client id es
 *     el que la librería usa como `serverClientId`/audiencia del token.
 *
 *  AUTH ELEGIDA: `play-services-auth` `AuthorizationClient`
 *  ([Identity.getAuthorizationClient]) — pide directamente un **access token**
 *  con el scope `drive.appdata` vía un flujo `IntentSender` (Credential Manager
 *  moderno; `GoogleSignIn` clásico está deprecado). El token vive en memoria de
 *  Play Services; la app NO lo persiste.
 *
 *  TRANSPORTE: REST Drive v3 sobre `spaces=appDataFolder` usando OkHttp (ya viene
 *  transitivamente con Coil), sin arrastrar `google-api-client`.
 */
class GoogleDriveSync(private val appContext: Context) {

    companion object {
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }

    private val scope = Scope(SCOPE_APPDATA)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------------
    // AUTH
    // ---------------------------------------------------------------------

    /**
     * Lanza (o reanuda) la autorización para obtener un access token con scope
     * `drive.appdata`.
     *
     * @param onToken invocado con el access token si ya hay consentimiento.
     * @param onNeedConsent invocado con un [IntentSender] que la Activity debe
     *   lanzar con un `ActivityResultLauncher<IntentSenderRequest>`; el resultado
     *   se procesa luego con [handleAuthorizationResult].
     * @param onError invocado si falla.
     */
    fun authorize(
        onToken: (String) -> Unit,
        onNeedConsent: (IntentSender) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(scope))
            .build()
        Identity.getAuthorizationClient(appContext)
            .authorize(request)
            .addOnSuccessListener { result: AuthorizationResult ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        onNeedConsent(pendingIntent.intentSender)
                    } else {
                        onError(IllegalStateException("Authorization needs consent but no PendingIntent"))
                    }
                } else {
                    val token = result.accessToken
                    if (token != null) onToken(token)
                    else onError(IllegalStateException("Authorization succeeded but access token was null"))
                }
            }
            .addOnFailureListener { onError(it) }
    }

    /** Procesa el `data` Intent devuelto por el flujo de consentimiento. */
    fun handleAuthorizationResult(
        data: Intent?,
        onToken: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        runCatching {
            val result = Identity.getAuthorizationClient(appContext)
                .getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token != null) onToken(token) else onError(IllegalStateException("No access token after consent"))
        }.onFailure(onError)
    }

    /**
     * "Desvincular cuenta": AuthorizationClient no expone un revoke directo estable,
     * así que la app simplemente olvida el estado local de vinculación (ver
     * [DriveSyncPrefs]). El usuario puede revocar el permiso desde su cuenta Google
     * ("Apps con acceso a tu cuenta"). Este método existe para simetría de la API.
     */
    fun unlink() { /* no-op: el estado de vinculación se limpia en DriveSyncPrefs */ }

    // ---------------------------------------------------------------------
    // REST Drive v3 — appDataFolder
    // ---------------------------------------------------------------------

    /** Busca el fichero de backup en appDataFolder. Devuelve su fileId o null. */
    @Throws(IOException::class)
    fun findBackupFileId(accessToken: String): String? {
        val url = DRIVE_FILES.toUrlBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '${RustifyBackup.DRIVE_FILE_NAME}'")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "10")
            .build()
        val req = Request.Builder().url(url).get().bearer(accessToken).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.assertOk().body?.string().orEmpty()
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            return if (files.length() > 0) files.getJSONObject(0).optString("id").ifBlank { null } else null
        }
    }

    /** Descarga y parsea el contenedor. Devuelve null si no existe en Drive. */
    @Throws(IOException::class)
    fun download(accessToken: String, fileId: String? = findBackupFileId(accessToken)): JSONObject? {
        val id = fileId ?: return null
        val url = "$DRIVE_FILES/$id".toUrlBuilder().addQueryParameter("alt", "media").build()
        val req = Request.Builder().url(url).get().bearer(accessToken).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.assertOk().body?.string().orEmpty()
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /**
     * Sube el contenedor: crea el fichero (multipart, con `parents:[appDataFolder]`)
     * si no existe, o lo actualiza (PATCH media) si ya existe. Devuelve el fileId.
     */
    @Throws(IOException::class)
    fun upload(accessToken: String, container: JSONObject, existingFileId: String? = findBackupFileId(accessToken)): String {
        val jsonBody = container.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return if (existingFileId == null) {
            // CREATE — multipart: metadata + media
            val metadata = JSONObject().apply {
                put("name", RustifyBackup.DRIVE_FILE_NAME)
                put("parents", JSONArray().put("appDataFolder"))
            }
            val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(jsonBody)
                .build()
            val url = DRIVE_UPLOAD.toUrlBuilder().addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", "id").build()
            val req = Request.Builder().url(url).post(multipart).bearer(accessToken).build()
            http.newCall(req).execute().use { resp ->
                JSONObject(resp.assertOk().body?.string().orEmpty()).optString("id")
            }
        } else {
            // UPDATE — PATCH media
            val url = "$DRIVE_UPLOAD/$existingFileId".toUrlBuilder()
                .addQueryParameter("uploadType", "media")
                .addQueryParameter("fields", "id").build()
            val req = Request.Builder().url(url).patch(jsonBody).bearer(accessToken).build()
            http.newCall(req).execute().use { resp ->
                resp.assertOk()
                existingFileId
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers OkHttp
    // ---------------------------------------------------------------------

    private fun Request.Builder.bearer(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private fun Response.assertOk(): Response {
        if (!isSuccessful) {
            val err = body?.string().orEmpty()
            throw IOException("Drive HTTP $code: ${err.take(500)}")
        }
        return this
    }

    private fun String.toUrlBuilder(): HttpUrl.Builder = this.toHttpUrl().newBuilder()
}
