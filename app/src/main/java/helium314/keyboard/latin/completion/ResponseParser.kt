// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * Turns raw text emitted by an on-device language model into a clean [CompletionCandidate].
 *
 * Small models are noisy: they may echo the prompt, add quotes or list markers, run on for several
 * sentences, or emit control characters. This parser is a pure function (no Android, no model) so it
 * can be unit-tested hard against recorded/synthetic outputs, which is where most of the "does the
 * completion feel good" correctness actually lives.
 */
object ResponseParser {
    const val DEFAULT_MAX_WORDS = 6

    private val WHITESPACE = Regex("\\s+")
    // leading list / quote / bullet markers a model may prepend
    private val LEADING_JUNK = Regex("^[\\s\"'`*\\-–—•.,)\\]]+")

    /**
     * @param rawOutput  the model's raw generated text
     * @param promptEcho if the model echoes the prompt, pass it here to strip a matching prefix
     * @param maxWords   cap on the number of words kept in the continuation
     * @return a candidate, or null if nothing usable could be extracted
     */
    fun parse(rawOutput: String, promptEcho: String? = null, maxWords: Int = DEFAULT_MAX_WORDS): CompletionCandidate? {
        if (rawOutput.isEmpty()) return null
        var text = rawOutput

        // strip an echoed prompt prefix (some models continue by repeating the input)
        if (!promptEcho.isNullOrEmpty() && text.startsWith(promptEcho)) {
            text = text.substring(promptEcho.length)
        }

        // stop at the first line break: we only want a single continuation
        val newline = text.indexOfFirst { it == '\n' || it == '\r' }
        if (newline >= 0) text = text.substring(0, newline)

        // drop control characters (keep normal printable + spaces)
        text = buildString {
            for (c in text) if (c == ' ' || !c.isISOControl()) append(c)
        }

        // remove leading junk (quotes, bullets, stray punctuation) and surrounding whitespace
        text = text.replace(LEADING_JUNK, "").trim()
        if (text.isEmpty()) return null

        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        val capped = words.take(maxWords)
        return CompletionCandidate(capped)
    }
}
