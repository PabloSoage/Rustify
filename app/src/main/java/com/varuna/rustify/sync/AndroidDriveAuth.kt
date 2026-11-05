package com.varuna.rustify.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.varuna.rustify.R
import com.varuna.rustify.util.DeviceName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Drive's sign-in on the phone, as Settings always offered it: Play Services' `AuthorizationClient`
 * ([GoogleDriveSync], the official build) or the browser with AppAuth ([AppAuthDriveAuth], any build).
 */
class AndroidDriveAuth(private val context: Context) : DriveAuth {
    private val play = GoogleDriveSync(context)
    private val browser = AppAuthDriveAuth(context)

    override val methods = listOf(DriveAuth.PLAY, DriveAuth.BROWSER)

    override val device: RustifyBackup.Device
        get() = RustifyBackup.Device(deviceId(), DeviceName.of(context))

    /** Stable device id (for diagnostics/merge only, not a secret). */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }.getOrDefault("unknown")

    // Web client id: if blank, Play Services' way is not configured (see GoogleDriveSync.kt).
    override fun isConfigured(method: String): Boolean =
        if (method == DriveAuth.BROWSER) browser.isConfigured()
        else context.getString(R.string.default_web_client_id).isNotBlank()

    override suspend fun silentToken(method: String): String? = suspendCancellableCoroutine { cont ->
        fun answer(token: String?) { if (cont.isActive) cont.resume(token) }
        if (method == DriveAuth.BROWSER) {
            // AppAuth: a fresh token silently; with no authorization, the browser is needed.
            browser.getFreshToken(onToken = ::answer, onNone = { answer(null) }, onError = { answer(null) })
        } else {
            // Play Services: a token if consent is cached.
            play.authorize(onToken = ::answer, onNeedConsent = { answer(null) }, onError = { answer(null) })
        }
    }

    @Composable
    override fun rememberSignIn(method: String): suspend () -> String {
        val pending = remember { arrayOfNulls<CompletableDeferred<String>>(1) }
        // The OAuth consent flow (IntentSender from AuthorizationClient).
        val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            play.handleAuthorizationResult(
                result.data,
                onToken = { pending[0]?.complete(it) },
                onError = { pending[0]?.completeExceptionally(it) }
            )
        }
        // The AppAuth Custom Tab (Intent, not IntentSender): on return the code is exchanged for tokens.
        val custom = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            browser.handleResponse(
                result.data,
                onToken = { pending[0]?.complete(it) },
                onError = { pending[0]?.completeExceptionally(it) }
            )
        }
        return remember(method) {
            suspend {
                val done = CompletableDeferred<String>()
                pending[0] = done
                if (method == DriveAuth.BROWSER) {
                    val intent = browser.authRequestIntent() ?: throw IllegalStateException("not configured")
                    custom.launch(intent)
                } else {
                    play.authorize(
                        onToken = { done.complete(it) },
                        onNeedConsent = { sender -> consent.launch(IntentSenderRequest.Builder(sender).build()) },
                        onError = { done.completeExceptionally(it) }
                    )
                }
                done.await()
            }
        }
    }

    override fun unlink() {
        play.unlink()
        browser.signOut()
    }
}
