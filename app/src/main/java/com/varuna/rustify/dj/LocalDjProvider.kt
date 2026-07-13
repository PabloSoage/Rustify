package com.varuna.rustify.dj

/**
 * E90 — Provider "LLM local" (~100M on-device). STUB intencionado.
 *
 * Nota honesta (ver docs/90-ai-dj-assistant.md §4.1): un modelo de ~100M probablemente rinde mal
 * para esta tarea, y la inferencia on-device (MediaPipe LLM Inference / llama.cpp) es un integrón
 * grande con coste real de RAM/almacenamiento/batería que choca con la restricción del usuario.
 * Por eso se deja la interfaz lista pero SIN implementar del todo: no bloquea el resto de la feature.
 *
 * TODO(E90): integrar un runtime on-device (p. ej. MediaPipe `LlmInference` con un `.task`, o un
 * servidor LLM local externo por HTTP en 127.0.0.1 — ver §4.2 del diseño) y producir el mismo JSON
 * `{intro, seeds, queries}` que [ApiDjProvider]. Hasta entonces, degrada al heurístico offline.
 */
class LocalDjProvider(
    private val fallback: DjProvider = HeuristicDjProvider()
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        // Sin runtime on-device implementado todavía: se delega en el heurístico para que la UI
        // siga funcionando aunque el usuario seleccione "Local".
        val plan = fallback.plan(context, request)
        return plan.copy(
            intro = plan.intro + " (DJ local no disponible aún: usando modo heurístico)"
        )
    }
}
