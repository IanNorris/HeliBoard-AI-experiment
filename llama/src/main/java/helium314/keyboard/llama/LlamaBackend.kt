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
