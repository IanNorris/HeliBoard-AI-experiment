// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * Blends the personalized n-gram [chain] with the language-model [llm] into one candidate list.
 *
 * The design (a "gated merge", not a fixed slot split or a naive global sort):
 *  - The chain is fantastic WHEN the user's history is predictive, but most typing isn't; there the
 *    LLM's fluent generic continuations are wanted. So we admit up to [maxChainRows] chain candidates
 *    that look strong (multi-word — a lone predicted word adds nothing over the normal suggestion
 *    strip), ALWAYS reserve at least [minLlmRows] slot(s) for the LLM, and fill the rest by a simple
 *    rank. When the chain and the LLM independently produce the same continuation, that agreement is
 *    the highest-signal candidate, so it is surfaced first.
 *  - For responsiveness, the chain (which needs no model inference) is produced first and published
 *    immediately via [onPartial]; the LLM is then generated and the blended result returned. The
 *    engine guards both publishes with the same epoch, so a stale partial can never be shown.
 *
 * Scoring here is deliberately simple/heuristic (multi-word and agreement preferred); it is a
 * placeholder for the offline-calibrated per-source acceptance probabilities the desktop harness will
 * produce. Pure logic (no Android), so the merge is unit-testable with fake providers.
 */
class HybridCompletionProvider @JvmOverloads constructor(
    private val chain: CompletionProvider,
    private val llm: CompletionProvider,
    private val maxChainRows: Int = 2,
    private val minLlmRows: Int = 1,
) : CompletionProvider {

    override val name: String get() = "hybrid"

    override fun generate(
        context: CompletionContext,
        max: Int,
        onPartial: ((List<CompletionCandidate>) -> Unit)?,
    ): List<CompletionCandidate> {
        // 1) instant, personalized chain — publish it right away so the strip feels live
        val chainCands = chain.generate(context, max)
        if (chainCands.isNotEmpty()) onPartial?.invoke(chainCands.take(max))

        // 2) slower LLM continuations
        val llmCands = llm.generate(context, max)

        // 3) gated merge
        return blend(chainCands, llmCands, max)
    }

    /** Merge chain + LLM candidates into a single ranked list of at most [max] rows. Pure function. */
    internal fun blend(
        chainCands: List<CompletionCandidate>,
        llmCands: List<CompletionCandidate>,
        max: Int,
    ): List<CompletionCandidate> {
        if (max <= 0) return emptyList()
        if (llmCands.isEmpty()) return chainCands.take(max)
        if (chainCands.isEmpty()) return llmCands.take(max)

        val out = ArrayList<CompletionCandidate>(max)
        val usedKeys = HashSet<String>()

        fun key(c: CompletionCandidate) = c.words.take(2).joinToString(" ") { it.lowercase() }

        // strong chain rows = multi-word (a lone word is already covered by the suggestion strip)
        val strongChain = chainCands.filter { it.words.size >= 2 }
        // agreement: a chain row whose first two words an LLM row also produced — highest signal
        val llmKeys = llmCands.map { key(it) }.toHashSet()
        val agreeing = strongChain.filter { key(it) in llmKeys }
        val chainOnly = strongChain.filter { key(it) !in llmKeys }

        // reserve at least minLlmRows slots for the LLM (but never more than available)
        val llmReserve = minLlmRows.coerceAtMost(max).coerceAtMost(llmCands.size)
        val chainBudget = (max - llmReserve).coerceAtLeast(0).coerceAtMost(maxChainRows)

        // 1) agreeing rows first, then chain-only, up to the chain budget
        for (c in agreeing + chainOnly) {
            if (out.size >= chainBudget) break
            if (usedKeys.add(key(c))) out.add(c)
        }
        // 2) fill remaining slots with LLM candidates (skipping ones already represented by agreement)
        for (c in llmCands) {
            if (out.size >= max) break
            if (usedKeys.add(key(c))) out.add(c)
        }
        // 3) if still short (e.g. few LLM rows), backfill with remaining MULTI-word chain candidates
        //    only (never a lone word, which the normal suggestion strip already covers)
        if (out.size < max) {
            for (c in chainCands) {
                if (out.size >= max) break
                if (c.words.size < 2) continue
                if (usedKeys.add(key(c))) out.add(c)
            }
        }
        return out
    }
}
