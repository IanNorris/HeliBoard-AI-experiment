// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A multi-word continuation the user can accept as a whole or only up to a chosen word.
 *
 * [words] holds the individual whitespace-delimited words, e.g. `["good", "time", "last", "week"]`.
 * The first word is treated as the completion of the word the user is currently typing, so it is
 * the one matched against the in-progress prefix; the remaining words are the look-ahead the user
 * can "scrub" through and accept by tapping.
 */
data class CompletionCandidate(val words: List<String>) {
    init { require(words.isNotEmpty()) { "a completion candidate must have at least one word" } }

    val firstWord: String get() = words.first()

    val fullText: String get() = words.joinToString(" ")

    /**
     * The text to commit when the user accepts up to and including the word at [wordIndex].
     * Tapping "last" (index 2) in "good time last week" yields "good time last".
     */
    fun acceptedText(wordIndex: Int): String {
        require(wordIndex in words.indices) { "word index $wordIndex out of range 0..${words.lastIndex}" }
        return words.take(wordIndex + 1).joinToString(" ")
    }

    /**
     * Whether the first word starts with [prefix] (case-insensitive). An empty prefix matches all.
     * This is the cheap per-keystroke filter that lets the strip narrow as the user types without
     * asking the provider to regenerate.
     */
    fun matchesPrefix(prefix: String): Boolean =
        prefix.isEmpty() || firstWord.startsWith(prefix, ignoreCase = true)

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Builds a candidate from a plain string like "good time last week". */
        fun of(text: String): CompletionCandidate =
            CompletionCandidate(mergeTrailingPunctuation(text.trim().split(WHITESPACE).filter { it.isNotEmpty() }))

        /**
         * Fold standalone punctuation tokens onto the preceding word so trailing marks stay attached
         * ("good", "night", "?" -> "good", "night?"). A token counts as punctuation when it contains
         * no letters or digits. This keeps the strip clean and makes "night?" a single tappable unit.
         */
        fun mergeTrailingPunctuation(words: List<String>): List<String> {
            val out = ArrayList<String>(words.size)
            for (w in words) {
                if (w.isEmpty()) continue
                if (out.isNotEmpty() && w.none { it.isLetterOrDigit() }) {
                    out[out.lastIndex] = out.last() + w
                } else {
                    out.add(w)
                }
            }
            return out
        }
    }
}
