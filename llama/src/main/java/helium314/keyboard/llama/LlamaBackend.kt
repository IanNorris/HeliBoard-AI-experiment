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

    /** A generated continuation with its confidence score and per-candidate timing (diagnostics). */
    data class ScoredCompletion(val text: String, val score: Float, val genTokens: Int = 0, val genMs: Long = 0)

    /** Aggregate stats for a multi-generation, used by the debug panel. */
    data class GenStats(val promptTokens: Int, val prefillMs: Long, val totalMs: Long)

    /** Candidates plus aggregate stats for one [generateMulti] call. */
    data class MultiResult(val candidates: List<ScoredCompletion>, val stats: GenStats)

    /** Generate [count] diverse short continuations for [prompt], with scores and timing stats.
     *  [budgetMs] is a total wall-clock budget across candidates (0 = unbounded). */
    fun generateMulti(prompt: String, maxTokens: Int, count: Int, budgetMs: Int = 0): MultiResult {
        val h = handle
        if (h == 0L) return MultiResult(emptyList(), GenStats(0, 0, 0))
        val raw = LlamaNative.generateMulti(h, prompt, maxTokens, count, budgetMs)
        var stats = GenStats(0, 0, 0)
        val candidates = ArrayList<ScoredCompletion>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            if (line.startsWith("#STATS\t")) {
                val f = line.split('\t')
                stats = GenStats(
                    promptTokens = f.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                    prefillMs = f.getOrNull(2)?.trim()?.toLongOrNull() ?: 0,
                    totalMs = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0,
                )
                continue
            }
            // per-candidate: "score\tgenTokens\tgenMs\ttext" (tolerates the older "score\ttext")
            val f = line.split('\t', limit = 4)
            if (f.size >= 4) {
                val text = f[3].trim()
                if (text.isEmpty()) continue
                candidates.add(ScoredCompletion(
                    text = text,
                    score = f[0].trim().toFloatOrNull() ?: Float.NEGATIVE_INFINITY,
                    genTokens = f[1].trim().toIntOrNull() ?: 0,
                    genMs = f[2].trim().toLongOrNull() ?: 0,
                ))
            } else {
                val tab = line.indexOf('\t')
                val text = (if (tab < 0) line else line.substring(tab + 1)).trim()
                if (text.isEmpty()) continue
                val score = if (tab < 0) Float.NEGATIVE_INFINITY else line.substring(0, tab).trim().toFloatOrNull() ?: Float.NEGATIVE_INFINITY
                candidates.add(ScoredCompletion(text, score))
            }
        }
        return MultiResult(candidates, stats)
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
