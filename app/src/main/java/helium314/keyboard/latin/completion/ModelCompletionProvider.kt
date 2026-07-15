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
class ModelCompletionProvider @JvmOverloads constructor(
    private val backend: InferenceBackend,
    private val modelPathProvider: ModelPathProvider,
    private val maxTokens: Int = 8,
) : CompletionProvider {

    /** Supplies the installed model path (or null if absent). A SAM type so Java can pass a method ref. */
    fun interface ModelPathProvider {
        fun getPath(): String?
    }

    override val name: String get() = "on-device-model"

    @Volatile private var loadFailed = false

    private companion object {
        const val OVERSAMPLE = 5   // generate this many, dedupe down to the requested count
        const val MAX_WORDS = 4    // keyboard-appropriate short continuations
    }

    /** Whether a model is installed and the backend is ready (or can be made ready). */
    fun isModelAvailable(): Boolean = modelPathProvider.getPath() != null && !loadFailed

    override fun generate(context: CompletionContext, max: Int): List<CompletionCandidate> {
        val partial = context.currentWordPrefix
        // nothing to work with (no committed context and no in-progress word) -> don't even load
        if (context.leftContext.isBlank() && partial.isEmpty()) return emptyList()
        if (!ensureLoaded()) return emptyList()

        // Whole-word mode (just typed a space): generate several diverse short continuations.
        if (partial.isEmpty()) {
            val prompt = PromptBuilder.build(context.leftContext) ?: return emptyList()
            val raws = try { backend.generateMulti(prompt, maxTokens, OVERSAMPLE) }
                catch (e: Exception) { return emptyList() }
            val candidates = raws.mapNotNull { ResponseParser.parse(it, promptEcho = prompt, maxWords = MAX_WORDS) }
            return dedupeDistinct(candidates, max)
        }

        // Mid-word: anchor on the dictionary's top completions of the fragment (e.g. "ti" ->
        // "time"/"timer"/"timing"), completing the current word reliably, then let the model
        // continue each. This makes current-word prediction work where the small model alone can't.
        val anchors = context.dictionaryWords
            .filter { it.startsWith(partial, ignoreCase = true) && it.length > partial.length }
            .take(max)
        if (anchors.isNotEmpty()) {
            val candidates = ArrayList<CompletionCandidate>()
            for (word in anchors) {
                val prompt = PromptBuilder.build(context.leftContext + word) ?: continue
                val raw = try { backend.generate(prompt, maxTokens) } catch (e: Exception) { "" }
                val text = if (raw.isBlank()) word else "$word ${raw.trimStart()}"
                val cand = ResponseParser.parse(text, maxWords = MAX_WORDS)
                    ?.takeIf { it.firstWord.equals(word, ignoreCase = true) }
                if (cand != null) candidates.add(cand)
            }
            if (candidates.isNotEmpty()) return dedupeDistinct(candidates, max)
        }

        // No dictionary completion available: best-effort raw-fragment continuation.
        val prompt = PromptBuilder.build(context.leftContext + partial) ?: return emptyList()
        val raws = try { backend.generateMulti(prompt, maxTokens, OVERSAMPLE) }
            catch (e: Exception) { return emptyList() }
        val candidates = raws.mapNotNull { raw ->
            if (raw.isEmpty() || raw[0].isWhitespace()) null
            else ResponseParser.parse(partial + raw, maxWords = MAX_WORDS)
                ?.takeIf { it.firstWord.startsWith(partial, ignoreCase = true) }
        }
        return dedupeDistinct(candidates, max)
    }

    /** Keep candidates that are distinct on their first two words, capped at [max]. */
    private fun dedupeDistinct(candidates: List<CompletionCandidate>, max: Int): List<CompletionCandidate> {
        val seen = HashSet<String>()
        val out = ArrayList<CompletionCandidate>(max)
        for (c in candidates) {
            if (c.words.isEmpty()) continue
            val key = c.words.take(2).joinToString(" ") { it.lowercase() }
            if (seen.add(key)) {
                out.add(c)
                if (out.size >= max) break
            }
        }
        return out
    }

    /** Load the model if needed; returns false (and latches failure) if it can't be made ready. */
    private fun ensureLoaded(): Boolean {
        if (backend.isLoaded) return true
        if (loadFailed) return false
        val path = modelPathProvider.getPath() ?: return false
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
}
