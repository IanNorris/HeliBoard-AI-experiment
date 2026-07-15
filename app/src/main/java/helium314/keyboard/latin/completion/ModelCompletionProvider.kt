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

        // Mid-word anchoring: if the dictionary has a completion for the fragment (e.g. "ti"->"time"),
        // prompt the model with the COMPLETED word ("What time") and prepend that word to the result.
        val anchorWord = context.dictionaryWord.takeIf {
            it.isNotEmpty() && it.startsWith(partial, ignoreCase = true) && it.length > partial.length
        }
        val basePrompt = when {
            partial.isEmpty() -> context.leftContext
            anchorWord != null -> context.leftContext + anchorWord
            else -> context.leftContext + partial
        }
        val prompt = PromptBuilder.build(basePrompt) ?: return emptyList()
        if (!ensureLoaded()) return emptyList()

        // Generate several diverse SHORT continuations and show them as distinct options (rather than
        // one long phrase truncated to different lengths). Each is capped to a few words for keyboard
        // usefulness; tap-to-accept then extends from the new context.
        val raws = try {
            backend.generateMulti(prompt, maxTokens, OVERSAMPLE)
        } catch (e: Exception) {
            return emptyList()
        }

        val candidates = ArrayList<CompletionCandidate>()
        for (raw in raws) {
            val cand = when {
                partial.isEmpty() ->
                    ResponseParser.parse(raw, promptEcho = prompt, maxWords = MAX_WORDS)
                anchorWord != null -> {
                    val c = ResponseParser.parse("$anchorWord ${raw.trimStart()}", maxWords = MAX_WORDS)
                    c?.takeIf { it.firstWord.equals(anchorWord, ignoreCase = true) }
                }
                else -> {
                    if (raw.isEmpty() || raw[0].isWhitespace()) null
                    else ResponseParser.parse(partial + raw, maxWords = MAX_WORDS)
                        ?.takeIf { it.firstWord.startsWith(partial, ignoreCase = true) }
                }
            } ?: continue
            candidates.add(cand)
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
