// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.llama

/**
 * Thin wrapper over [LlamaNative] providing a synchronous load/generate/close surface. The :app
 * module adapts this to its InferenceBackend interface (kept separate so this module has no
 * dependency on :app). All calls must run on a single worker thread.
 */
class LlamaBackend(
    private val nCtx: Int = 256,
    private val nThreads: Int = defaultThreads(),
) {
    @Volatile private var handle: Long = 0L

    val isAvailable: Boolean get() = LlamaNative.isAvailable
    val isLoaded: Boolean get() = handle != 0L

    /** Load a GGUF model from [modelPath]. Throws on failure. */
    fun load(modelPath: String) {
        if (handle != 0L) return
        val h = LlamaNative.load(modelPath, nCtx, nThreads)
        if (h == 0L) throw IllegalStateException("llama model load failed: $modelPath")
        handle = h
    }

    fun generate(prompt: String, maxTokens: Int): String {
        val h = handle
        if (h == 0L) return ""
        return LlamaNative.generate(h, prompt, maxTokens)
    }

    /** A generated continuation with its confidence score (mean-per-word logprob; higher = better). */
    data class ScoredCompletion(val text: String, val score: Float)

    /** Generate [count] diverse short continuations for [prompt], each with a confidence score. */
    fun generateMulti(prompt: String, maxTokens: Int, count: Int): List<ScoredCompletion> {
        val h = handle
        if (h == 0L) return emptyList()
        return LlamaNative.generateMulti(h, prompt, maxTokens, count)
            .split('\n')
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val tab = line.indexOf('\t')
                if (tab < 0) {
                    ScoredCompletion(line.trim(), Float.NEGATIVE_INFINITY)
                } else {
                    val score = line.substring(0, tab).trim().toFloatOrNull() ?: Float.NEGATIVE_INFINITY
                    val text = line.substring(tab + 1).trim()
                    if (text.isEmpty()) null else ScoredCompletion(text, score)
                }
            }
    }

    fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.free(h)
    }

    companion object {
        private fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, 4)
    }
}
