// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

/**
 * A lightweight, dependency-free stand-in for a real on-device language model.
 *
 * It returns canned multi-word continuations from a small phrase bank, filtered so the first word
 * matches the in-progress prefix. This exercises the completion engine and UI end-to-end without
 * bundling a model, and documents the seam a real provider must fill: swap this for a
 * llama.cpp-backed [CompletionProvider] later without touching the engine or the UI.
 *
 * The phrases deliberately overlap on their first words so prefix re-filtering is observable, e.g.
 * prefix "g" yields great/good/gut/going, and typing another letter to make "go" narrows it to
 * good/going.
 */
class StubCompletionProvider : CompletionProvider {
    override val name = "stub"

    override fun generate(context: CompletionContext, max: Int, onPartial: ((List<CompletionCandidate>) -> Unit)?): List<CompletionCandidate> {
        val prefix = context.currentWordPrefix
        return PHRASES.asSequence()
            .map { CompletionCandidate.of(it) }
            .filter { it.matchesPrefix(prefix) }
            .take(max)
            .toList()
    }

    companion object {
        private val PHRASES = listOf(
            "great time last night",
            "good time last week",
            "good feeling that it will work",
            "gut feeling that something is off",
            "going to be late today",
            "had a lovely evening",
            "really appreciate your help",
            "let me know when you can",
            "see you later tonight",
            "on my way home now",
        )
    }
}
