// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * The thin, swappable boundary between the completion feature and an actual inference engine.
 *
 * Concrete implementations (a llama.cpp adapter, or an out-of-process AIDL client) live behind this
 * interface so the provider/engine never depend on a specific runtime.
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

    /**
     * Generate up to [count] diverse short continuations of [prompt]. Backends that support cheap
     * multi-sampling (llama.cpp) override this; the default falls back to a single generation.
     */
    fun generateMulti(prompt: String, maxTokens: Int, count: Int): List<String> =
        listOf(generate(prompt, maxTokens)).filter { it.isNotEmpty() }

    /**
     * Like [generateMulti] but each candidate carries a confidence score (mean-per-word logprob;
     * higher is better) used for low-confidence suppression and reranking. The default wraps
     * [generateMulti] with a neutral score, so backends without real scores still work.
     */
    fun generateMultiScored(prompt: String, maxTokens: Int, count: Int): List<ScoredCandidate> =
        generateMulti(prompt, maxTokens, count).map { ScoredCandidate(it, 0f) }

    /** Release native resources. Safe to call repeatedly; a later [load] can re-init. */
    fun close()
}

/** A raw generated continuation together with its model-confidence [score] (higher is better). */
data class ScoredCandidate(val text: String, val score: Float)
