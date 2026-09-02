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
 * Google Drive sync client (private AppData folder).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  AUTHENTICATION — steps the user MUST perform in the Google Cloud Console
 * ─────────────────────────────────────────────────────────────────────────────
 *  Without this configuration, sync will NOT work at runtime (it yields DEVELOPER_ERROR
 *  or "access denied"). The code is complete; only the Cloud project is missing.
 *
 *  1. Create a project at https://console.cloud.google.com/.
 *  2. APIs & Services → Enable APIs → enable the **Google Drive API**.
 *  3. OAuth consent screen: type *External*; add ONLY the scope
 *     `https://www.googleapis.com/auth/drive.appdata` (+ basic openid/email).
 *     `drive.appdata` is NOT sensitive → avoids manual verification. Move to
 *     *In production* so the token does not expire after 7 days (Testing mode).
 *  4. Credentials → Create OAuth client ID **Android**: package
 *     `com.varuna.rustify` + SHA-1 of EACH keystore (debug, release, and, if you use
 *     Play App Signing, the Play signing key). A wrong SHA-1 = error 10.
 *  5. Credentials → Create OAuth client ID **Web**: copy its Client ID into
 *     `res/values/strings.xml` → `default_web_client_id`. This Web client id is the
 *     one the library uses as the `serverClientId`/token audience.
 *
 *  CHOSEN AUTH: `play-services-auth` `AuthorizationClient`
 *  ([Identity.getAuthorizationClient]) — requests an **access token** directly with the
 *  `drive.appdata` scope via an `IntentSender` flow (modern Credential Manager; the
 *  classic `GoogleSignIn` is deprecated). The token lives in Play Services memory;
 *  the app does NOT persist it.
 *
 *  TRANSPORT: REST Drive v3 over `spaces=appDataFolder` using OkHttp (already pulled in
 *  transitively by Coil), without dragging in `google-api-client`.
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
     * Starts (or resumes) authorization to obtain an access token with the `drive.appdata` scope.
     *
     * @param onToken invoked with the access token if consent already exists.
     * @param onNeedConsent invoked with an [IntentSender] that the Activity must launch with an
     *   `ActivityResultLauncher<IntentSenderRequest>`; the result is then processed with
     *   [handleAuthorizationResult].
     * @param onError invoked on failure.
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

    /** Processes the `data` Intent returned by the consent flow. */
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
     * "Unlink account": AuthorizationClient does not expose a stable direct revoke, so the app
     * simply forgets the local link state (see [DriveSyncPrefs]). The user can revoke the permission
     * from their Google account ("Apps with access to your account"). This method exists for API
     * symmetry.
     */
    fun unlink() { /* no-op: the link state is cleared in DriveSyncPrefs */ }

    // ---------------------------------------------------------------------
    // REST Drive v3 — appDataFolder
    // ---------------------------------------------------------------------

    /** Finds the backup file in appDataFolder. Returns its fileId or null. */
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
            val body = resp.assertOk().body.string()
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            return if (files.length() > 0) files.getJSONObject(0).optString("id").ifBlank { null } else null
        }
    }

    /** Downloads and parses the container. Returns null if it does not exist in Drive. */
    @Throws(IOException::class)
    fun download(accessToken: String, fileId: String? = findBackupFileId(accessToken)): JSONObject? {
        val id = fileId ?: return null
        val url = "$DRIVE_FILES/$id".toUrlBuilder().addQueryParameter("alt", "media").build()
        val req = Request.Builder().url(url).get().bearer(accessToken).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.assertOk().body.string()
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /**
     * Uploads the container: creates the file (multipart, with `parents:[appDataFolder]`) if it
     * does not exist, or updates it (PATCH media) if it already does. Returns the fileId.
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
                JSONObject(resp.assertOk().body.string()).optString("id")
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
    // OkHttp helpers
    // ---------------------------------------------------------------------

    private fun Request.Builder.bearer(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private fun Response.assertOk(): Response {
        if (!isSuccessful) {
            val err = body.string()
            throw IOException("Drive HTTP $code: ${err.take(500)}")
        }
        return this
    }

    private fun String.toUrlBuilder(): HttpUrl.Builder = this.toHttpUrl().newBuilder()
}
