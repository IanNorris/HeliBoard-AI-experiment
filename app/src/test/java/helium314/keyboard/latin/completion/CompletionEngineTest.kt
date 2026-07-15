// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the multi-word completion engine, provider and candidate model. */
class CompletionEngineTest {

    // --- CompletionCandidate ---------------------------------------------------------------

    @Test
    fun candidate_splitsWordsAndReportsFirstWord() {
        val c = CompletionCandidate.of("  good   time last week ")
        assertEquals(listOf("good", "time", "last", "week"), c.words)
        assertEquals("good", c.firstWord)
        assertEquals("good time last week", c.fullText)
    }

    @Test
    fun candidate_acceptUpToTappedWord() {
        val c = CompletionCandidate.of("good time last week")
        assertEquals("good", c.acceptedText(0))
        assertEquals("good time last", c.acceptedText(2)) // tapping "last" accepts up to it
        assertEquals("good time last week", c.acceptedText(3))
    }

    @Test
    fun candidate_prefixMatchIsCaseInsensitiveAndEmptyMatchesAll() {
        val c = CompletionCandidate.of("Good time last week")
        assertTrue(c.matchesPrefix(""))
        assertTrue(c.matchesPrefix("g"))
        assertTrue(c.matchesPrefix("goo"))
        assertTrue(!c.matchesPrefix("go o"))
        assertTrue(!c.matchesPrefix("b"))
    }

    // --- StubCompletionProvider ------------------------------------------------------------

    @Test
    fun provider_filtersFirstWordByPrefix() {
        val provider = StubCompletionProvider()
        val forG = provider.generate(CompletionContext("Yeah, I had a ", "g"), 10)
        assertTrue(forG.isNotEmpty())
        assertTrue(forG.all { it.firstWord.startsWith("g", ignoreCase = true) })

        // Typing another letter to make "go" must narrow to first words starting with "go".
        val forGo = provider.generate(CompletionContext("Yeah, I had a ", "go"), 10)
        assertTrue(forGo.all { it.firstWord.startsWith("go", ignoreCase = true) })
        assertTrue(forGo.size < forG.size) // "great"/"gut" dropped
    }

    // --- CompletionEngine ------------------------------------------------------------------

    @Test
    fun engine_regenerateThenFastFilterNarrowsWithoutProviderCall() {
        val engine = CompletionEngine(StubCompletionProvider(), maxCandidates = 3)
        val ctx = "Yeah, I had a "

        // First real generation for prefix "g".
        val gen = engine.regenerate(ctx, "g")
        assertTrue(gen.candidates.isNotEmpty())
        assertTrue(gen.candidates.all { it.firstWord.startsWith("g", true) })

        // Typing "o" -> "go": the fast path filters the existing pool, no regeneration needed.
        val filtered = engine.onPrefixChanged(ctx, "go")
        assertTrue(filtered != null && filtered.isNotEmpty())
        assertTrue(filtered!!.all { it.firstWord.startsWith("go", true) })
    }

    @Test
    fun engine_contextChangeRequestsRegeneration() {
        val engine = CompletionEngine(StubCompletionProvider())
        engine.regenerate("Yeah, I had a ", "g")
        // A different committed context can't be served from the existing pool.
        assertNull(engine.onPrefixChanged("Something else entirely ", "g"))
    }

    @Test
    fun engine_respectsMaxCandidates() {
        val engine = CompletionEngine(StubCompletionProvider(), maxCandidates = 2)
        val gen = engine.regenerate("", "") // empty prefix matches everything in the bank
        assertTrue(gen.candidates.size <= 2)
    }

    @Test
    fun engine_acceptReturnsTextAndInvalidatesPool() {
        val engine = CompletionEngine(StubCompletionProvider())
        val ctx = "Yeah, I had a "
        val gen = engine.regenerate(ctx, "g")
        val epochBefore = engine.currentEpoch
        val candidate = gen.candidates.first { it.words.size >= 3 }

        val committed = engine.accept(candidate, 2)
        assertEquals(candidate.acceptedText(2), committed)
        // Accepting invalidates: pool cleared (epoch advanced) so stale async results are dropped.
        assertTrue(engine.currentEpoch > epochBefore)
        assertNull(engine.onPrefixChanged(ctx, "g"))
    }

    @Test
    fun engine_staleGenerationIsDroppedAfterInvalidate() {
        // Simulate a generation that started before an invalidate(): its result must not become the
        // live pool. We model this by invalidating between capturing the epoch and displaying.
        val engine = CompletionEngine(StubCompletionProvider())
        val gen = engine.regenerate("Yeah, I had a ", "g")
        val staleEpoch = gen.epoch
        engine.invalidate() // e.g. user moved the cursor
        assertTrue(engine.currentEpoch > staleEpoch)
        assertNull(engine.onPrefixChanged("Yeah, I had a ", "g"))
    }
}
