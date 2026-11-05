package com.varuna.rustify.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drive's sign-in on iOS: Google's page in an `ASWebAuthenticationSession`, which takes back the
 * `rustify://oauth2redirect` the https redirect page sends an iOS sign-in to (its `state` says so).
 */
class IosDriveAuth(platform: AppPlatform) : DriveAuth {
    private val oauth = DriveOAuth(platform.prefs(), WebAuthenticationBrowser())

    override val methods = listOf(DriveAuth.BROWSER)

    override val device: RustifyBackup.Device
        get() = RustifyBackup.Device(
            UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "unknown",
            UIDevice.currentDevice.name
        )

    override fun isConfigured(method: String): Boolean = oauth.isConfigured

    override suspend fun silentToken(method: String): String? = oauth.silentToken()

    @Composable
    override fun rememberSignIn(method: String): suspend () -> String = remember { suspend { oauth.signIn() } }

    override fun unlink() = oauth.signOut()
}

private class WebAuthenticationBrowser : DriveOAuth.Browser {
    override suspend fun open(): DriveOAuth.Session = object : DriveOAuth.Session {
        override val statePrefix = "rustify-ios-"

        /** The session holds its presentation provider weakly: this keeps it until the answer. */
        private var anchor: Anchor? = null

        override suspend fun await(url: String): Map<String, String> = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val session = ASWebAuthenticationSession(
                    uRL = NSURL.URLWithString(url)!!,
                    callbackURLScheme = "rustify",
                ) { callback, error ->
                    anchor = null
                    val answer = callback?.absoluteString
                    if (answer != null) cont.resume(DriveOAuth.queryOf(answer))
                    else cont.resumeWithException(DriveException(error?.localizedDescription ?: "cancelled"))
                }
                session.presentationContextProvider = Anchor().also { anchor = it }
                session.prefersEphemeralWebBrowserSession = false
                cont.invokeOnCancellation { session.cancel() }
                session.start()
            }
        }
    }

    private class Anchor : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
                ?: UIApplication.sharedApplication.windows.first() as UIWindow
    }
}
