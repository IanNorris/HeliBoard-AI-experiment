// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the model-backed provider's lifecycle, fallback and candidate expansion via a fake backend. */
class ModelCompletionProviderTest {

    private class FakeBackend(
        var output: String = "good time last week",
        var outputs: List<String>? = null,
        var scoredOutputs: List<ScoredCandidate>? = null,
        var failLoad: Boolean = false,
        var failGenerate: Boolean = false,
    ) : InferenceBackend {
        var loadCount = 0
        var generateCount = 0
        var closeCount = 0
        private var loaded = false
        override fun load(modelPath: String) {
            loadCount++
            if (failLoad) throw RuntimeException("load boom")
            loaded = true
        }
        override val isLoaded: Boolean get() = loaded
        override fun generate(prompt: String, maxTokens: Int): String {
            generateCount++
            if (failGenerate) throw RuntimeException("gen boom")
            return output
        }
        override fun generateMulti(prompt: String, maxTokens: Int, count: Int): List<String> {
            generateCount++
            if (failGenerate) throw RuntimeException("gen boom")
            return outputs ?: listOf(output)
        }
        override fun generateMultiScored(prompt: String, maxTokens: Int, count: Int): List<ScoredCandidate> {
            scoredOutputs?.let {
                generateCount++
                if (failGenerate) throw RuntimeException("gen boom")
                return it
            }
            return super.generateMultiScored(prompt, maxTokens, count)
        }
        override fun close() { closeCount++; loaded = false }
    }

    private fun ctx(left: String = "Yeah, I had a ", prefix: String = "") =
        CompletionContext(left, prefix)

    @Test
    fun generate_midWord_usesDictionaryWordAnchor() {
        // dictionary says "ti" -> "time"; model continues "What time" with "is it"
        val backend = FakeBackend(output = " is it")
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(CompletionContext("What ", "ti", dictionaryWords = listOf("time")), max = 3)
        assertEquals(listOf("time", "is", "it"), result[0].words)
    }

    @Test
    fun generate_midWord_reattachesPrefixToCompleteWord() {
        // user typed "What ti"; model continues "What ti" with "me is it"
        val backend = FakeBackend(output = "me is it")
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        val result = provider.generate(CompletionContext("What ", "ti"), max = 3)
        // first word must include the typed fragment
        assertEquals(listOf("time", "is", "it"), result[0].words)
    }

    @Test
    fun generate_midWord_rejectsWhenModelStartsNewWord() {
        // continuation begins with a space -> model treated "ti" as complete -> no mid-word completion
        val backend = FakeBackend(output = " is it")
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        assertTrue(provider.generate(CompletionContext("What ", "ti"), max = 3).isEmpty())
    }

    @Test
    fun generate_returnsDistinctCandidatesFromMultipleSamples() {
        val backend = FakeBackend(outputs = listOf(
            "good time last week", "great night out tonight", "good time last week"))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        // distinct on first two words; the duplicate "good time..." is dropped
        assertEquals(2, result.size)
        assertEquals(listOf("good", "time", "last", "week"), result[0].words)
        assertEquals(listOf("great", "night", "out", "tonight"), result[1].words)
    }

    @Test
    fun generate_capsCandidatesToMaxWords() {
        val backend = FakeBackend(output = "one two three four five six seven eight nine ten")
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        assertEquals(
            listOf("one", "two", "three", "four", "five", "six", "seven", "eight"),
            result[0].words
        ) // capped at MAX_WORDS (8)
    }

    @Test
    fun generate_loadsModelLazilyOnlyOnce() {
        val backend = FakeBackend()
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        provider.generate(ctx(), 3)
        provider.generate(ctx(), 3)
        assertEquals(1, backend.loadCount) // stays warm
        assertEquals(2, backend.generateCount)
    }

    @Test
    fun generate_noModelPath_returnsEmptyAndDoesNotLoad() {
        val backend = FakeBackend()
        val provider = ModelCompletionProvider(backend, { null })
        assertTrue(provider.generate(ctx(), 3).isEmpty())
        assertEquals(0, backend.loadCount)
        assertFalse(provider.isModelAvailable())
    }

    @Test
    fun generate_emptyContext_returnsEmptyWithoutLoading() {
        val backend = FakeBackend()
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        assertTrue(provider.generate(CompletionContext("", ""), 3).isEmpty())
        assertEquals(0, backend.loadCount)
    }

    @Test
    fun generate_loadFailure_latchesAndFallsBack() {
        val backend = FakeBackend(failLoad = true)
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        assertTrue(provider.generate(ctx(), 3).isEmpty())
        // second call should not keep retrying a failed load
        provider.generate(ctx(), 3)
        assertEquals(1, backend.loadCount)
        assertFalse(provider.isModelAvailable())
    }

    @Test
    fun generate_generationFailure_returnsEmptyButStaysLoaded() {
        val backend = FakeBackend(failGenerate = true)
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        assertTrue(provider.generate(ctx(), 3).isEmpty())
        assertTrue(backend.isLoaded) // a bad generation doesn't unload the model
    }

    @Test
    fun releaseModel_closesBackendAndAllowsReload() {
        val backend = FakeBackend()
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        provider.generate(ctx(), 3)
        provider.releaseModel()
        assertEquals(1, backend.closeCount)
        provider.generate(ctx(), 3)
        assertEquals(2, backend.loadCount) // reloaded after release
    }

    @Test
    fun generate_respectsMaxOfOne() {
        val backend = FakeBackend(output = "good time last week")
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        val result = provider.generate(ctx(), max = 1)
        assertEquals(1, result.size)
        assertEquals(listOf("good", "time", "last", "week"), result[0].words)
    }

    @Test
    fun generate_unparseableOutput_returnsEmpty() {
        val backend = FakeBackend(output = "   \n  ")
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        assertTrue(provider.generate(ctx(), 3).isEmpty())
    }

    @Test
    fun generate_suppressesCandidatesBelowConfidenceFloor() {
        // "31890765" is confident-looking to a naive parser but scores far below the floor -> dropped
        val backend = FakeBackend(scoredOutputs = listOf(
            ScoredCandidate("good time last week", -1.2f),
            ScoredCandidate("31890765", -9.0f),
            ScoredCandidate("great night out", -2.0f),
        ))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        assertEquals(2, result.size)
        assertFalse(result.any { it.firstWord == "31890765" })
    }

    @Test
    fun generate_suppressesCandidatesFarWorseThanBest() {
        // within the floor, but the third is >RELATIVE_GAP (3.0) worse than the best (-1.0) -> dropped
        val backend = FakeBackend(scoredOutputs = listOf(
            ScoredCandidate("good time last week", -1.0f),
            ScoredCandidate("great night out", -2.5f),
            ScoredCandidate("odd murky prose", -4.5f),
        ))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        assertEquals(2, result.size)
        assertFalse(result.any { it.firstWord == "odd" })
    }

    @Test
    fun generate_allLowConfidence_returnsEmpty() {
        val backend = FakeBackend(scoredOutputs = listOf(
            ScoredCandidate("random junk here", -8.0f),
            ScoredCandidate("31890765", -9.5f),
        ))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        assertTrue(provider.generate(ctx(), max = 3).isEmpty())
    }

    @Test
    fun generate_prefersMultiWordCandidatesOverSingleWords() {
        // a lone "died" should not displace real phrases; it only fills a remaining slot (here none)
        val backend = FakeBackend(scoredOutputs = listOf(
            ScoredCandidate("great time last week", -1.0f),
            ScoredCandidate("died", -1.1f),
            ScoredCandidate("good night out tonight", -1.2f),
            ScoredCandidate("lovely weekend away", -1.3f),
        ))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        assertEquals(3, result.size)
        assertFalse(result.any { it.words.size == 1 }) // "died" excluded; three phrases available
    }

    @Test
    fun generate_usesSingleWordOnlyToFillWhenTooFewPhrases() {
        val backend = FakeBackend(scoredOutputs = listOf(
            ScoredCandidate("great time ahead", -1.0f),
            ScoredCandidate("died", -1.1f),
        ))
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(ctx(), max = 3)
        // phrase first, then the single word fills a remaining slot
        assertEquals(listOf("great", "time", "ahead"), result[0].words)
        assertTrue(result.any { it.words == listOf("died") })
    }
}
