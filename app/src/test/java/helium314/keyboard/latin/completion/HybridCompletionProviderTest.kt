// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the gated-merge blend of the personalized chain and the LLM. */
class HybridCompletionProviderTest {

    private class FixedProvider(val cands: List<CompletionCandidate>) : CompletionProvider {
        override val name = "fixed"
        var partialPublished: List<CompletionCandidate>? = null
        override fun generate(
            context: CompletionContext,
            max: Int,
            onPartial: ((List<CompletionCandidate>) -> Unit)?,
        ): List<CompletionCandidate> = cands
    }

    private fun c(text: String) = CompletionCandidate.of(text)
    private fun ctx() = CompletionContext("I had a ", "")

    @Test
    fun blend_publishesChainInstantlyBeforeLlm() {
        val chain = FixedProvider(listOf(c("great time last week")))
        val llm = FixedProvider(listOf(c("good day out today")))
        val hybrid = HybridCompletionProvider(chain, llm)
        var partial: List<CompletionCandidate>? = null
        hybrid.generate(ctx(), 3) { partial = it }
        assertEquals(listOf("great", "time", "last", "week"), partial!![0].words)
    }

    @Test
    fun blend_reservesAtLeastOneLlmSlot() {
        // three strong chain phrases, but the LLM must still get a slot
        val chain = FixedProvider(listOf(c("a b c"), c("d e f"), c("g h i")))
        val llm = FixedProvider(listOf(c("x y z")))
        val hybrid = HybridCompletionProvider(chain, llm, maxChainRows = 2, minLlmRows = 1)
        val out = hybrid.blend(chain.cands, llm.cands, max = 3)
        assertEquals(3, out.size)
        assertTrue("LLM row must be present", out.any { it.words == listOf("x", "y", "z") })
        assertEquals("at most maxChainRows chain rows", 2, out.count { it.words[0] in listOf("a", "d", "g") })
    }

    @Test
    fun blend_surfacesAgreementFirst() {
        // chain and LLM independently produce "good time ..." -> that agreement ranks first
        val chain = FixedProvider(listOf(c("great night out"), c("good time soon")))
        val llm = FixedProvider(listOf(c("good time later"), c("nice day ahead")))
        val hybrid = HybridCompletionProvider(chain, llm, maxChainRows = 2, minLlmRows = 1)
        val out = hybrid.blend(chain.cands, llm.cands, max = 3)
        assertEquals("good", out[0].words[0])
        assertEquals("time", out[0].words[1])
    }

    @Test
    fun blend_dropsLoneChainWords() {
        // a single-word chain "died" must not take a row over real phrases
        val chain = FixedProvider(listOf(c("died"), c("great time last week")))
        val llm = FixedProvider(listOf(c("good day out")))
        val hybrid = HybridCompletionProvider(chain, llm)
        val out = hybrid.blend(chain.cands, llm.cands, max = 3)
        assertTrue(out.none { it.words == listOf("died") })
    }

    @Test
    fun blend_fallsBackToOneSourceWhenOtherEmpty() {
        val chain = FixedProvider(listOf(c("great time last week")))
        val empty = FixedProvider(emptyList())
        val hybrid = HybridCompletionProvider(chain, empty)
        assertEquals(1, hybrid.blend(chain.cands, empty.cands, 3).size)
        assertEquals(1, hybrid.blend(empty.cands, chain.cands, 3).size)
    }
}
