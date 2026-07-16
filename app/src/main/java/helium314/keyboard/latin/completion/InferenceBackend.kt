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
        generateMulti(prompt, maxTokens, count).map { ScoredCandidate(it) }

    /**
     * Like [generateMultiScored] but also returns aggregate generation stats (prompt tokens, prefill
     * vs total time) for the debug panel. Default: candidates with empty stats.
     */
    fun generateMultiScoredWithStats(prompt: String, maxTokens: Int, count: Int): Pair<List<ScoredCandidate>, GenerationStats> =
        generateMultiScored(prompt, maxTokens, count) to GenerationStats()

    /** Release native resources. Safe to call repeatedly; a later [load] can re-init. */
    fun close()
}

/**
 * A raw generated continuation together with its model-confidence [score] (higher is better) and
 * optional per-candidate timing ([genTokens], [genMs]) for the debug panel.
 */
data class ScoredCandidate(
    val text: String,
    val score: Float = 0f,
    val genTokens: Int = 0,
    val genMs: Long = 0,
)

/** Aggregate stats for one multi-candidate generation, surfaced to the debug panel. */
data class GenerationStats(
    val promptTokens: Int = 0,
    val prefillMs: Long = 0,
    val totalMs: Long = 0,
) {
    /** Overall generated-tokens-per-second across the candidates (0 if unknown). */
    fun tokensPerSecond(genTokens: Int): Double =
        if (totalMs > 0) genTokens * 1000.0 / totalMs else 0.0
}
