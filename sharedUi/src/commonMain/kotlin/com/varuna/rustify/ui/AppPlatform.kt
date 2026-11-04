// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/AppPlatform.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment
import com.varuna.rustify.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.io.File

/**
 * What the shared screens used Android's `Context` for (docs serie-4.x/26, measured: preferences,
 * folders, toasts, sharing, links, the clipboard), as one interface each platform implements once.
 * Android's is over the `Context`, with the same preference files and folders as before, so nothing
 * stored moves; the desktop's is over `java.util.prefs`, the app's data folder and the window.
 */
interface AppPlatform {
    val kind: Kind

    /** The version the user sees ("5.0.0b"); empty when it is not known (a run from the IDE). */
    val appVersion: String

    /**
     * The country the system is set to ("ES"; empty when it names none), whatever language the app
     * was given. Not the JVM's default locale's: a language picked in Settings replaces that with a
     * language alone, and the country goes with it.
     */
    val systemCountry: String

    /** A preferences file by its Android name (`rustify_settings`, `rustify_spotify_prefs`…). */
    fun prefs(name: String = SETTINGS): KeyValueStore

    /** Where the app keeps its own files (Android's `filesDir`). */
    val filesDir: File

    /** What may be deleted to make room (Android's `cacheDir`). */
    val cacheDir: File

    /** A short message over the interface (Android's `Toast`). */
    fun toast(message: String, long: Boolean = false)

    /** Opens a web page or a link in the system's handler for it. */
    fun openUrl(url: String)

    /**
     * Offers [text] to the system's share sheet (on the desktop, copies it); false when nothing could
     * take it.
     */
    fun share(text: String, subject: String? = null): Boolean

    /**
     * Shares an image file of type [mimeType] with [text] as its caption (none when blank); false
     * when nothing could take it. A screen sharing what it shows uses [rememberShareImage].
     */
    fun shareImage(file: File, text: String, mimeType: String = "image/*"): Boolean

    fun copyToClipboard(label: String, text: String)

    /** The clipboard's text, or null when it holds none (or cannot be read). */
    fun readClipboard(): String?

    /** Whether there is a network to try at all; "no" turns an error into "you are offline". */
    fun isOnline(): Boolean

    enum class Kind { ANDROID, DESKTOP }

    companion object {
        const val SETTINGS = "rustify_settings"
    }
}

val LocalAppPlatform = staticCompositionLocalOf<AppPlatform> { error("no AppPlatform provided") }

private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * The language the interface is in, for strings looked up outside a composition (a toast from a
 * click handler). Outside one, Compose Multiplatform would use the system's language, and Android
 * lets the user pick another for the app; [RememberStringEnvironment] at the root keeps this current.
 */
object StringEnvironment {
    @Volatile
    var current: ResourceEnvironment? = null
}

/** Call once at the root of the interface, inside whatever sets its language. */
@Composable
fun RememberStringEnvironment() {
    StringEnvironment.current = rememberResourceEnvironment()
}

/** A string resource outside a composition, in the interface's language. */
suspend fun appString(resource: StringResource, vararg args: Any): String =
    StringEnvironment.current?.let { getString(it, resource, *args) } ?: getString(resource, *args)

/**
 * A toast from a string resource, from anywhere (a click handler, a coroutine): the text is looked up
 * off the caller, in the interface's language, and shown when it is ready.
 */
fun AppPlatform.toast(resource: StringResource, vararg args: Any, long: Boolean = false) {
    toastScope.launch { toast(appString(resource, *args), long) }
}
