// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/EngineAudioLabTap.kt
package com.varuna.rustify.ui

import com.varuna.rustify.bridge.NativeEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.concurrent.Volatile

/**
 * [AudioLabTap] over the core engine's output tap ([NativeEngine.engineTapNative]).
 * Used on desktop and iOS, where the app plays through `core_engine`.
 */
class EngineAudioLabTap : AudioLabTap {
    @Volatile
    private var lastRate = 0

    override val sampleRate: Int
        get() {
            val (rate, _) = parseTap(NativeEngine.engineTapNative(0))
            lastRate = rate
            return rate
        }

    override fun latestSamples(n: Int): FloatArray {
        val (rate, samples) = parseTap(NativeEngine.engineTapNative(n))
        lastRate = rate
        return samples
    }

    private fun parseTap(raw: String): Pair<Int, FloatArray> {
        if (raw.isBlank() || raw == "{}") return 0 to FloatArray(0)
        return try {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return 0 to FloatArray(0)
            val rate = (obj["sampleRate"] as? JsonPrimitive)?.intOrNull ?: 0
            val arr = obj["samples"] as? JsonArray
            val samples = if (arr != null) {
                FloatArray(arr.size) { (arr[it] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f }
            } else {
                FloatArray(0)
            }
            rate to samples
        } catch (_: Exception) {
            0 to FloatArray(0)
        }
    }
}
