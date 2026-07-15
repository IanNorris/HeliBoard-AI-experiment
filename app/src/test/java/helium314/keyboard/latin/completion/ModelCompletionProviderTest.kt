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
        override fun close() { closeCount++; loaded = false }
    }

    private fun ctx(left: String = "Yeah, I had a ", prefix: String = "") =
        CompletionContext(left, prefix)

    @Test
    fun generate_midWord_usesDictionaryWordAnchor() {
        // dictionary says "ti" -> "time"; model continues "What time" with "is it"
        val backend = FakeBackend(output = " is it")
        val provider = ModelCompletionProvider(backend, { "/models/x.gguf" })
        val result = provider.generate(CompletionContext("What ", "ti", dictionaryWord = "time"), max = 3)
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
    fun generate_returnsProgressiveCandidatesFromOneContinuation() {
        val backend = FakeBackend(output = "good time last week")
        val provider = ModelCompletionProvider(backend, { "/models/x.task" })
        val result = provider.generate(ctx(), max = 3)
        // longest first, then shorter safe prefixes
        assertEquals(listOf("good", "time", "last", "week"), result[0].words)
        assertEquals(listOf("good", "time", "last"), result[1].words)
        assertEquals(listOf("good", "time"), result[2].words)
        assertEquals(3, result.size)
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
}
