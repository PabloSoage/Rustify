// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/player/DesktopAudioSettings.kt
package com.varuna.rustify.player

import com.varuna.rustify.engine.RustAudioEngine
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The output the engine plays through: the system's default, which it follows when that moves. */
object DesktopOutput {
    private val _name = MutableStateFlow<String?>(null)

    /** Its name, as far as it was last asked; AutoEq keeps a profile under it. */
    val name: StateFlow<String?> = _name

    suspend fun refresh() {
        _name.value = try {
            RustAudioEngine.outputDevice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DesktopOutput", "could not read the output's name", e)
            null
        }
    }
}

/** [AudioSettings] where the engine plays: the stored values are all of it, and AutoEq's output is the engine's. */
class DesktopAudioSettings(prefs: KeyValueStore) : AudioSettings(prefs) {
    override suspend fun refreshOutput() = DesktopOutput.refresh()

    override fun currentOutput(): Output? = DesktopOutput.name.value?.let { Output(it, it) }
}
