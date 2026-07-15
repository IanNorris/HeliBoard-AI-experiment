// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/** Ranked next-word predictions (most likely first) for a preceding-word context. */
fun interface NextWordPredictor {
    /** Predicted next words after [contextWords], best first. Empty when nothing is predicted. */
    fun predictNextWords(contextWords: List<String>): List<String>
}

/**
 * A [CompletionProvider] that builds multi-word suggestions WITHOUT a language model, by chaining
 * the keyboard's own next-word predictor: take its top ("centre slot") pick, append it, predict the
 * next word, and repeat. Because that predictor is fed by the user's personal history dictionary, the
 * chains are personalized and in the user's own register — the trade-off is that a greedy chain can
 * drift bland, which is mitigated by seeding each candidate from a distinct first word and by a small
 * repeat guard.
 *
 * Pure logic (no Android): the actual prediction is injected as a [NextWordPredictor], so this is
 * unit-testable on the JVM while the IME supplies a predictor backed by its DictionaryFacilitator.
 */
class NgramChainCompletionProvider @JvmOverloads constructor(
    private val predictor: NextWordPredictor,
    private val maxChainWords: Int = 6,
) : CompletionProvider {

    override val name: String get() = "ngram-chain"

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }

    override fun generate(context: CompletionContext, max: Int): List<CompletionCandidate> {
        if (max <= 0) return emptyList()
        val leftWords = context.leftContext.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val prefix = context.currentWordPrefix

        // Seeds are the first word of each candidate: mid-word we trust the dictionary's completions
        // of the fragment; whole-word we ask the predictor for the likely next words.
        val seeds: List<String> = if (prefix.isNotEmpty()) {
            context.dictionaryWords.filter { it.startsWith(prefix, ignoreCase = true) && it.length >= prefix.length }.take(max)
        } else {
            predictor.predictNextWords(leftWords).filter { it.isNotBlank() }.take(max)
        }
        if (seeds.isEmpty()) return emptyList()

        val candidates = ArrayList<CompletionCandidate>(seeds.size)
        for (seed in seeds) {
            val chain = buildChain(leftWords, seed)
            if (chain.isNotEmpty()) {
                candidates.add(CompletionCandidate(CompletionCandidate.mergeTrailingPunctuation(chain)))
            }
        }
        return dedupeDistinct(candidates, max)
    }

    /** Greedily extend [seed] by repeatedly taking the predictor's top non-repeating next word. */
    private fun buildChain(leftWords: List<String>, seed: String): List<String> {
        val chain = arrayListOf(seed)
        val ctx = ArrayList(leftWords).apply { add(seed) }
        while (chain.size < maxChainWords) {
            val next = predictor.predictNextWords(ctx)
                .firstOrNull { it.isNotBlank() && !isImmediateRepeat(chain, it) } ?: break
            chain.add(next)
            ctx.add(next)
        }
        return chain
    }

    /** Reject a word equal to either of the last two chain words, to break "the the"/2-cycles. */
    private fun isImmediateRepeat(chain: List<String>, word: String): Boolean {
        val n = chain.size
        return (n >= 1 && chain[n - 1].equals(word, ignoreCase = true)) ||
            (n >= 2 && chain[n - 2].equals(word, ignoreCase = true))
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
}
