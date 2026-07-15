// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A [CompletionProvider] backed by an on-device language model via an [InferenceBackend].
 *
 * Lifecycle and threading are owned here so the [CompletionEngine] stays runtime-agnostic:
 * - The model is loaded lazily on the first [generate] after a model path is available, then kept
 *   warm; [releaseModel] unloads it (called on memory pressure / idle / feature disable).
 * - [generate] is synchronous and expected to be called on the engine's background worker; it never
 *   touches the IME thread. If the model isn't loaded (or loading fails), it returns no candidates
 *   so the engine transparently falls back to the stub / n-gram.
 * - Multiple candidates are produced cheaply from a single generation by progressive word splitting
 *   ("good time last week" -> also "good time last", "good time", "good"), which suits a keyboard
 *   and avoids asking a small model for numbered alternatives it parses poorly.
 *
 * The concrete model is never loaded on API < the backend's requirement; the host gates the whole
 * feature before constructing this, and [modelPathProvider] returns null until a model is installed.
 */
class ModelCompletionProvider(
    private val backend: InferenceBackend,
    private val modelPathProvider: () -> String?,
    private val maxTokens: Int = 12,
) : CompletionProvider {

    override val name: String get() = "on-device-model"

    @Volatile private var loadFailed = false

    /** Whether a model is installed and the backend is ready (or can be made ready). */
    fun isModelAvailable(): Boolean = modelPathProvider() != null && !loadFailed

    override fun generate(context: CompletionContext, max: Int): List<CompletionCandidate> {
        val prompt = PromptBuilder.build(context.leftContext) ?: return emptyList()
        if (!ensureLoaded()) return emptyList()

        val raw = try {
            backend.generate(prompt, maxTokens)
        } catch (e: Exception) {
            // a generation failure shouldn't kill the feature; degrade to no model candidates
            return emptyList()
        }
        val parsed = ResponseParser.parse(raw, promptEcho = prompt) ?: return emptyList()
        return expand(parsed, max)
    }

    /** Load the model if needed; returns false (and latches failure) if it can't be made ready. */
    private fun ensureLoaded(): Boolean {
        if (backend.isLoaded) return true
        if (loadFailed) return false
        val path = modelPathProvider() ?: return false
        return try {
            backend.load(path)
            backend.isLoaded
        } catch (e: Exception) {
            loadFailed = true
            false
        }
    }

    /** Unload the model and reset the failure latch so a later attempt can retry. */
    fun releaseModel() {
        try { backend.close() } catch (_: Exception) { }
        loadFailed = false
    }

    /**
     * Turn one continuation into up to [max] progressively-shorter candidates, longest first, so the
     * user sees the full continuation plus shorter safe prefixes to tap.
     */
    private fun expand(candidate: CompletionCandidate, max: Int): List<CompletionCandidate> {
        if (max <= 1 || candidate.words.size <= 1) return listOf(candidate)
        val result = ArrayList<CompletionCandidate>(minOf(max, candidate.words.size))
        var len = candidate.words.size
        while (len >= 1 && result.size < max) {
            result.add(CompletionCandidate(candidate.words.take(len)))
            len--
        }
        return result
    }
}
