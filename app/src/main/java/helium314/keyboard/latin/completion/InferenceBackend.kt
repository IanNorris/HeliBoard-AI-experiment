// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * The thin, swappable boundary between the completion feature and an actual inference engine.
 *
 * Concrete implementations (a MediaPipe adapter now, a llama.cpp adapter later, or an out-of-process
 * AIDL client) live behind this interface so the provider/engine never depend on a specific runtime.
 * All methods run on the provider's single worker thread and may block; they must never be called on
 * the IME/UI thread.
 */
interface InferenceBackend {
    /** Load the model from [modelPath]. Throws on failure. Idempotent if already loaded. */
    fun load(modelPath: String)

    /** Whether a model is currently loaded and ready to generate. */
    val isLoaded: Boolean

    /**
     * Generate a continuation for [prompt]. Blocking. [maxTokens] bounds the output length.
     * Returns raw model text (parsing/cleanup happens in [ResponseParser]).
     */
    fun generate(prompt: String, maxTokens: Int): String

    /** Release native resources. Safe to call repeatedly; a later [load] can re-init. */
    fun close()
}
