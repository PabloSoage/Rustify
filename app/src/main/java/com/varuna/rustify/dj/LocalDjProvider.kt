package com.varuna.rustify.dj

/**
 * "Local LLM" provider (~100M on-device). Intentional stub.
 *
 * A ~100M model likely performs poorly for this task, and on-device inference (MediaPipe LLM
 * Inference / llama.cpp) is a large integration with real RAM/storage/battery cost. The interface
 * is therefore left ready but not fully implemented, so it does not block the rest of the feature.
 *
 * TODO: integrate an on-device runtime (e.g. MediaPipe `LlmInference` with a `.task`, or an external
 * local LLM server over HTTP on 127.0.0.1) that produces the same `{intro, seeds, queries}` JSON as
 * [ApiDjProvider]. Until then, it degrades to the offline heuristic.
 */
class LocalDjProvider(
    private val fallback: DjProvider = HeuristicDjProvider()
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        // No on-device runtime implemented yet: delegate to the heuristic so the UI keeps working
        // even when the user selects "Local".
        val plan = fallback.plan(context, request)
        return plan.copy(
            intro = plan.intro + " (DJ local no disponible aún: usando modo heurístico)"
        )
    }
}
