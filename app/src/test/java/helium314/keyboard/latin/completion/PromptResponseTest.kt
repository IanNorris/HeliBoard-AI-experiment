// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for prompt construction and model-output parsing. */
class PromptResponseTest {

    // --- PromptBuilder ---------------------------------------------------------------------

    @Test
    fun prompt_nullForEmptyContext() {
        assertNull(PromptBuilder.build(""))
    }

    @Test
    fun prompt_keepsTrailingSpaceSoModelStartsNewWord() {
        assertEquals("Yeah, I had a ", PromptBuilder.build("Yeah, I had a "))
    }

    @Test
    fun prompt_collapsesMultipleTrailingSpacesToOne() {
        assertEquals("hello ", PromptBuilder.build("hello    "))
    }

    @Test
    fun prompt_noTrailingSpacePreserved() {
        assertEquals("hello", PromptBuilder.build("hello"))
    }

    @Test
    fun prompt_truncatesToRecentWindowWithoutCuttingMidWord() {
        // 3-char window on "abcd efgh" would start mid-"efgh"; builder advances past the space
        val result = PromptBuilder.build("abcd efgh", maxChars = 3)
        assertEquals("efgh", result)
    }

    @Test
    fun prompt_windowLargerThanContextReturnsAll() {
        assertEquals("short text", PromptBuilder.build("short text", maxChars = 999))
    }

    // --- ResponseParser --------------------------------------------------------------------

    @Test
    fun parse_nullForEmpty() {
        assertNull(ResponseParser.parse(""))
        assertNull(ResponseParser.parse("   "))
    }

    @Test
    fun parse_simpleContinuation() {
        val c = ResponseParser.parse("good time last week")
        assertEquals(listOf("good", "time", "last", "week"), c!!.words)
    }

    @Test
    fun parse_stripsEchoedPrompt() {
        val c = ResponseParser.parse("Yeah, I had a good time", promptEcho = "Yeah, I had a ")
        assertEquals(listOf("good", "time"), c!!.words)
    }

    @Test
    fun parse_takesOnlyFirstLine() {
        val c = ResponseParser.parse("good time last night\nand then we left")
        assertEquals(listOf("good", "time", "last", "night"), c!!.words)
    }

    @Test
    fun parse_removesLeadingJunkAndQuotes() {
        val c = ResponseParser.parse("  - \"great idea")
        assertEquals(listOf("great", "idea"), c!!.words)
    }

    @Test
    fun parse_dropsControlCharacters() {
        val c = ResponseParser.parse("good\u0000 time\u0007")
        assertEquals(listOf("good", "time"), c!!.words)
    }

    @Test
    fun parse_capsWordCount() {
        val c = ResponseParser.parse("one two three four five six seven eight", maxWords = 3)
        assertEquals(listOf("one", "two", "three"), c!!.words)
    }

    @Test
    fun parse_collapsesInternalWhitespace() {
        val c = ResponseParser.parse("good    time")
        assertEquals(listOf("good", "time"), c!!.words)
    }

    @Test
    fun parse_resultFeedsAcceptedTextForTapToAccept() {
        // end-to-end: a parsed candidate supports word-level accept like the stub ones
        val c = ResponseParser.parse("good time last week")!!
        assertEquals("good time last", c.acceptedText(2))
        assertTrue(c.matchesPrefix("go"))
    }

    @Test
    fun parse_attachesTrailingPunctuationToWord() {
        // model emits a detached "?" -> it should merge onto the preceding word, not be its own chip
        val c = ResponseParser.parse("are you there ?")!!
        assertEquals(listOf("are", "you", "there?"), c.words)
    }

    @Test
    fun parse_mergesMultiCharPunctuationToken() {
        val c = ResponseParser.parse("wait what ...")!!
        assertEquals(listOf("wait", "what..."), c.words)
    }

    @Test
    fun mergeTrailingPunctuation_keepsIntraWordApostrophes() {
        // apostrophes inside a word must NOT trigger a merge (the token has letters)
        assertEquals(
            listOf("I'll", "be", "there!"),
            CompletionCandidate.mergeTrailingPunctuation(listOf("I'll", "be", "there", "!")),
        )
    }
}
