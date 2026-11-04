// shared/src/desktopMain/kotlin/com/varuna/rustify/dj/DesktopLocalDjProvider.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log

/**
 * The DJ's "local" mode on the desktop: a language model on this computer, asked as [ApiDjProvider]
 * asks a service (the same prompt, the same `{intro, seeds, queries}` answer), at a server found on
 * loopback ([LocalLlmDetector]) or, with none of those or when chosen, at Rustify's own ([managed]:
 * [LocalModelManager], started when what it needs is on disk). The phone's equivalent is Gemini Nano.
 *
 * With nothing running it is the heuristic, and says so in the intro, as the phone's does when its
 * model is missing.
 */
class DesktopLocalDjProvider(
    private val prefs: KeyValueStore,
    private val managed: suspend () -> LocalLlmSettings.Target? = { null },
    private val fallback: DjProvider = HeuristicDjProvider(),
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        val target = LocalLlmSettings.target(prefs, managed)
        if (target == null) {
            val plan = fallback.plan(context, request)
            val why = if (context.language.startsWith("es")) "no hay ningún modelo local en marcha" else "no local model is running"
            return plan.copy(intro = "${plan.intro} ($why)")
        }
        Log.i(TAG, "asking ${target.kind.label} at ${target.baseUrl}, model ${target.model}")
        // A small model on a CPU can take a while; past two minutes the heuristic is the better DJ.
        return ApiDjProvider(target.baseUrl, target.model, apiKey = "", fallback = fallback, readTimeoutMs = 120_000)
            .plan(context, request)
    }

    private companion object {
        const val TAG = "LocalDj"
    }
}
