// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosLocale.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.defaultLanguage
// A star import, as in :shared's Platform.ios.kt: Foundation declares much of NSLocale in categories,
// which Kotlin/Native may turn into extensions that need importing by name.
import platform.Foundation.*

/**
 * The language the interface is in: iOS's twin of the JVM's default `Locale`, which Android and the
 * desktop set to the language picked in Settings (`app_language`).
 *
 * Two readers. Compose Multiplatform's resources take the language from `NSLocale.preferredLanguages`,
 * which follows `AppleLanguages` in the app's defaults: so a picked language is written there, and
 * the root recomposes under it (JetBrains' own recipe for changing the language at run time). The
 * formats ([formatCount], the dates, the names of months) read [current], which is that language
 * alone — as the JVM's default is `Locale.forLanguageTag(code)` — or the system's locale for
 * `"system"`.
 */
object IosLocale {
    private const val APPLE_LANGUAGES = "AppleLanguages"
    const val SYSTEM = "system"

    /** The code Settings stored ("es", "ja", …), or [SYSTEM]. */
    var code: String = SYSTEM
        private set

    /** The locale the formats use now. */
    var current: NSLocale = NSLocale.currentLocale
        private set

    /** The interface's language as a two-letter code, for the core (Spotify's titles). */
    val languageCode: String
        get() = if (code == SYSTEM) defaultLanguage() else code.substringBefore('-').substringBefore('_')

    /**
     * Once at launch, before anything is drawn. What an earlier launch wrote to `AppleLanguages` is
     * removed first, so that "system" is the system's again and not the last language picked.
     */
    fun install(code: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(APPLE_LANGUAGES)
        apply(code)
    }

    /** The language picked in Settings; the root recomposes the interface after this. */
    fun apply(code: String) {
        this.code = code
        val defaults = NSUserDefaults.standardUserDefaults
        if (code == SYSTEM) {
            defaults.removeObjectForKey(APPLE_LANGUAGES)
            current = NSLocale.currentLocale
        } else {
            defaults.setObject(listOf(code), forKey = APPLE_LANGUAGES)
            current = NSLocale(localeIdentifier = code)
        }
    }

    /** The first of the user's languages, as iOS lists them now ("es-ES"). */
    fun preferredLanguage(): String? = NSLocale.preferredLanguages.firstOrNull() as? String
}
