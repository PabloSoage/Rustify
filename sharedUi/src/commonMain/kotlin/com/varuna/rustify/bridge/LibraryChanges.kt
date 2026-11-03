// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/LibraryChanges.kt
package com.varuna.rustify.bridge

/**
 * "The library changed" from the repositories to whoever mirrors it outside the app: on Android the
 * Android Auto tree (`MediaBrowserNotifier`), which the app sets as [listener]. The repositories are
 * shared code and cannot call into the app, so they announce instead.
 */
object LibraryChanges {
    @Volatile
    var listener: () -> Unit = {}

    fun notifyChanged() = listener()
}
