// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * Builds the prompt fed to an on-device base language model from the editor context.
 *
 * The model is asked to continue the user's text, so the prompt is simply the most recent slice of
 * committed text before the cursor. Crucially it does NOT include the in-progress word: the model
 * generates whole-word continuations and the [CompletionEngine] narrows them by the typed prefix,
 * which avoids the fragile "complete this partial token" path.
 *
 * Only a bounded, recent window is used (never the whole editor buffer) for latency and privacy.
 */
object PromptBuilder {
    const val DEFAULT_MAX_CHARS = 512

    /**
     * @param leftContext committed text before the in-progress word (already prefix-stripped by the engine)
     * @param maxChars    maximum characters of context to keep (from the end)
     * @return the prompt string, or null if there is no usable context
     */
    fun build(leftContext: String, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        if (leftContext.isEmpty()) return null
        var start = maxOf(0, leftContext.length - maxChars)
        if (start > 0) {
            // avoid a partial leading word: prefer the next word boundary inside the window,
            // but if the final word is itself longer than the window, back up to keep it whole
            val fwd = leftContext.indexOf(' ', start)
            start = if (fwd in start until leftContext.length - 1) {
                fwd + 1
            } else {
                val back = leftContext.lastIndexOf(' ', start)
                if (back >= 0) back + 1 else 0
            }
        }
        val window = leftContext.substring(start)
        // keep a single trailing space (so the model starts a fresh word) but collapse any run
        val trimmedEnd = window.trimEnd()
        if (trimmedEnd.isEmpty()) return null
        val hadTrailingSpace = window != trimmedEnd
        return if (hadTrailingSpace) "$trimmedEnd " else trimmedEnd
    }
}
