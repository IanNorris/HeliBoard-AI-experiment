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
    private val maxTokens: Int = 14,
    private val budgetMs: Int = 0,
    private val contextChars: Int = PromptBuilder.DEFAULT_MAX_CHARS,
) : CompletionProvider {

    /** Supplies the installed model path (or null if absent). A SAM type so Java can pass a method ref. */
    fun interface ModelPathProvider {
        fun getPath(): String?
    }

    override val name: String get() = "on-device-model"

    @Volatile private var loadFailed = false

    private companion object {
        // Oversample only slightly beyond what we show: each extra candidate costs full decode time,
        // so 4 (show 3) balances dedup yield against the latency budget (was 5).
        const val OVERSAMPLE = 4
        const val MAX_WORDS = 8    // continuation length; the panel wraps long lines onto more rows
        const val MIN_WORDS = 2    // prefer multi-word phrases; single words are only used to fill slots
        // Low-confidence suppression thresholds on the mean-per-word logprob score. Conservative:
        // they only kill genuinely unlikely output (e.g. a bare random number) rather than trimming
        // plausible-but-unusual phrasing. Tune on-device from logged scores.
        const val CONFIDENCE_FLOOR = -5.0f   // absolute floor; below this a candidate is junk
        const val RELATIVE_GAP = 3.0f        // drop candidates far worse than the best in the batch
    }

    /**
     * Drop low-confidence candidates: anything below [CONFIDENCE_FLOOR] outright, and anything more
     * than [RELATIVE_GAP] nats/word worse than the best candidate in the batch. The effect is that a
     * weak context yields fewer (or zero) suggestions instead of confidently-wrong junk.
     */
    private fun suppressLowConfidence(scored: List<ScoredCandidate>): List<ScoredCandidate> {
        val aboveFloor = scored.filter { it.score.isFinite() && it.score >= CONFIDENCE_FLOOR }
        if (aboveFloor.isEmpty()) return emptyList()
        val best = aboveFloor.maxOf { it.score }
        return aboveFloor.filter { it.score >= best - RELATIVE_GAP }
    }

    /** Whether a model is installed and the backend is ready (or can be made ready). */
    fun isModelAvailable(): Boolean = modelPathProvider.getPath() != null && !loadFailed

    override fun generate(context: CompletionContext, max: Int, onPartial: ((List<CompletionCandidate>) -> Unit)?): List<CompletionCandidate> {
        val partial = context.currentWordPrefix
        // nothing to work with (no committed context and no in-progress word) -> don't even load
        if (context.leftContext.isBlank() && partial.isEmpty()) return emptyList()
        if (!ensureLoaded()) return emptyList()

        // Whole-word mode (just typed a space): generate several diverse short continuations, then
        // suppress the low-confidence ones so a weak context shows fewer/zero rather than junk.
        if (partial.isEmpty()) {
            val prompt = PromptBuilder.build(context.leftContext, contextChars) ?: return emptyList()
            val (scored, stats) = try { backend.generateMultiScoredWithStats(prompt, maxTokens, OVERSAMPLE, budgetMs) }
                catch (e: Exception) { return emptyList() }
            publishDebug(prompt, scored, stats)
            val candidates = suppressLowConfidence(scored)
                .mapNotNull { ResponseParser.parse(it.text, promptEcho = prompt, maxWords = MAX_WORDS) }
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
                val prompt = PromptBuilder.build(context.leftContext + word, contextChars) ?: continue
                val raw = try { backend.generate(prompt, maxTokens) } catch (e: Exception) { "" }
                val text = if (raw.isBlank()) word else "$word ${raw.trimStart()}"
                val cand = ResponseParser.parse(text, maxWords = MAX_WORDS)
                    ?.takeIf { it.firstWord.equals(word, ignoreCase = true) }
                if (cand != null) candidates.add(cand)
            }
            if (candidates.isNotEmpty()) return dedupeDistinct(candidates, max)
        }

        // No dictionary completion available: best-effort raw-fragment continuation, still suppressing
        // low-confidence output.
        val prompt = PromptBuilder.build(context.leftContext + partial, contextChars) ?: return emptyList()
        val (scored, stats) = try { backend.generateMultiScoredWithStats(prompt, maxTokens, OVERSAMPLE, budgetMs) }
            catch (e: Exception) { return emptyList() }
        publishDebug(prompt, scored, stats)
        val candidates = suppressLowConfidence(scored).mapNotNull { sc ->
            val raw = sc.text
            if (raw.isEmpty() || raw[0].isWhitespace()) null
            else ResponseParser.parse(partial + raw, maxWords = MAX_WORDS)
                ?.takeIf { it.firstWord.startsWith(partial, ignoreCase = true) }
        }
        return dedupeDistinct(candidates, max)
    }

    /** Record the last generation for the debug overlay (no-op unless the debug panel is enabled). */
    private fun publishDebug(prompt: String, scored: List<ScoredCandidate>, stats: GenerationStats) {
        if (!CompletionDebug.enabled) return
        val totalGenTokens = scored.sumOf { it.genTokens }
        CompletionDebug.publish(CompletionDebug.Snapshot(
            source = "LLM",
            prompt = prompt,
            candidates = scored.map {
                CompletionDebug.DebugCandidate(it.text, it.score, it.genTokens, it.genMs, "LLM")
            },
            promptTokens = stats.promptTokens,
            prefillMs = stats.prefillMs,
            totalMs = stats.totalMs,
            tokensPerSecond = stats.tokensPerSecond(totalGenTokens),
        ))
    }

    /**
     * Keep candidates that are distinct on their first two words, capped at [max]. Multi-word
     * candidates are preferred: single-word continuations (which the normal suggestion strip already
     * covers) are only included to fill remaining slots once the multi-word ones are exhausted, so a
     * lone word never displaces a real phrase in the panel.
     */
    private fun dedupeDistinct(candidates: List<CompletionCandidate>, max: Int): List<CompletionCandidate> {
        val seen = HashSet<String>()
        val multiWord = ArrayList<CompletionCandidate>(max)
        val singleWord = ArrayList<CompletionCandidate>()
        for (c in candidates) {
            if (c.words.isEmpty()) continue
            val key = c.words.take(2).joinToString(" ") { it.lowercase() }
            if (!seen.add(key)) continue
            if (c.words.size >= MIN_WORDS) multiWord.add(c) else singleWord.add(c)
        }
        val out = ArrayList<CompletionCandidate>(max)
        out.addAll(multiWord.take(max))
        if (out.size < max) out.addAll(singleWord.take(max - out.size))
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
