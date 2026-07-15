// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * The text context handed to a [CompletionProvider] to generate continuations.
 *
 * @property leftContext committed text to the left of the cursor (may be truncated to a window)
 * @property currentWordPrefix the in-progress word the user is currently typing (may be empty)
 */
data class CompletionContext(
    val leftContext: String,
    val currentWordPrefix: String,
)

/**
 * Produces multi-word continuation candidates for the text before the cursor.
 *
 * Implementations may be expensive (for example an on-device language model running through
 * llama.cpp), so [generate] is expected to be called off the UI thread by the [CompletionEngine].
 * The engine handles debouncing, prefix re-filtering and cancellation, so a provider only needs to
 * turn a [CompletionContext] into candidates.
 */
interface CompletionProvider {
    /** Human-readable name, for settings and diagnostics. */
    val name: String

    /**
     * Generate up to [max] candidates continuing [context]. When
     * [CompletionContext.currentWordPrefix] is non-empty, the first word of each returned candidate
     * should start with it (case-insensitive); the engine also filters defensively, so a provider
     * that ignores the prefix still behaves correctly, just less efficiently.
     */
    fun generate(context: CompletionContext, max: Int): List<CompletionCandidate>
}
