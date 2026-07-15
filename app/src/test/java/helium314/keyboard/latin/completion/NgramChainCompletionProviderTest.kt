// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the n-gram chaining provider with a scripted predictor (no Android / dictionary). */
class NgramChainCompletionProviderTest {

    /** Predictor driven by a map from the last context word to a ranked list of next words. */
    private class ScriptedPredictor(val script: Map<String, List<String>>) : NextWordPredictor {
        override fun predictNextWords(contextWords: List<String>): List<String> =
            script[contextWords.lastOrNull()] ?: emptyList()
    }

    @Test
    fun wholeWord_chainsFromTopPredictionThroughSeveralWords() {
        val predictor = ScriptedPredictor(mapOf(
            "had" to listOf("a", "the"),
            "a" to listOf("great", "good"),
            "great" to listOf("time", "day"),
            "time" to listOf("last", "with"),
            "last" to listOf("night", "week"),
            "night" to listOf("with"),
        ))
        val provider = NgramChainCompletionProvider(predictor, maxChainWords = 5)
        val result = provider.generate(CompletionContext("Yeah I had", ""), max = 3)
        // seeds are the top-2 predictions after "had": "a" and "the"; the "a" chain fills 5 words
        assertEquals(listOf("a", "great", "time", "last", "night"), result[0].words)
    }

    @Test
    fun midWord_usesDictionaryWordsAsSeeds() {
        val predictor = ScriptedPredictor(mapOf(
            "meeting" to listOf("at", "with"),
            "at" to listOf("noon"),
        ))
        val provider = NgramChainCompletionProvider(predictor, maxChainWords = 3)
        val ctx = CompletionContext("are we ", "meetin", dictionaryWords = listOf("meeting", "meetings"))
        val result = provider.generate(ctx, max = 3)
        assertEquals("meeting", result[0].firstWord)
        assertEquals(listOf("meeting", "at", "noon"), result[0].words)
    }

    @Test
    fun chain_stopsWhenPredictorRepeatsAWord() {
        // "the" -> "the" would loop; the repeat guard breaks it
        val predictor = ScriptedPredictor(mapOf(
            "on" to listOf("the"),
            "the" to listOf("the"),
        ))
        val provider = NgramChainCompletionProvider(predictor, maxChainWords = 6)
        val result = provider.generate(CompletionContext("sitting on", ""), max = 1)
        assertEquals(listOf("the"), result[0].words) // did not become "the the the ..."
    }

    @Test
    fun chain_dedupesCandidatesWithSameFirstTwoWords() {
        val predictor = ScriptedPredictor(mapOf(
            "had" to listOf("a", "a"), // both seeds identical
            "a" to listOf("good"),
            "good" to listOf("time"),
        ))
        val provider = NgramChainCompletionProvider(predictor, maxChainWords = 3)
        val result = provider.generate(CompletionContext("I had", ""), max = 3)
        assertEquals(1, result.size)
    }

    @Test
    fun noPredictions_returnsEmpty() {
        val provider = NgramChainCompletionProvider(ScriptedPredictor(emptyMap()))
        assertTrue(provider.generate(CompletionContext("nothing here", ""), max = 3).isEmpty())
    }
}
