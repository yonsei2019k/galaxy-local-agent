package com.user.localagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalAnswerContentTest {
    @Test fun hostWarningAndArtifactTailRemainVisibleAfterCachedModelParts() {
        val body = "Synthetic model answer.\n\nSource not verified.\n[[screen:web-research]]"
        val state = UiAnswerState(parts = listOf(UiAnswerPart(1, "Synthetic model answer.")))
        val parts = canonicalAnswerParts(body, state)
        assertEquals(body, parts.joinToString("") { it.text })
        val blocks = answerDisplayBlocks(body, state, false)
        assertTrue(blocks.joinToString("") { it.text }.contains("Source not verified."))
        assertEquals(2, parts.size)
    }

    @Test fun cachedStateAheadOfDatabaseNeverDisplaysUncommittedText() {
        val state = UiAnswerState(parts = listOf(UiAnswerPart(1, "Saved pending native output")))
        assertEquals("Saved", canonicalAnswerParts("Saved", state).joinToString("") { it.text })
    }

    @Test fun obsoletePartContentCannotReplaceAnEditedCanonicalBody() {
        val state = UiAnswerState(parts = listOf(UiAnswerPart(1, "Old answer")))
        assertEquals("New canonical answer", canonicalAnswerParts("New canonical answer", state).single().text)
    }

    @Test fun largeUnicodeCanonicalBodyAndAllHostTailRoundTripWithoutClipping() {
        val first = "합성🙂 first\n".repeat(3000)
        val second = "합성🙂 second\n".repeat(3000)
        val tail = "\n\nSynthetic host verification result."
        val body = first + second + tail
        val state = UiAnswerState(parts = listOf(UiAnswerPart(1, first), UiAnswerPart(2, second)))
        assertEquals(body, canonicalAnswerParts(body, state).joinToString("") { it.text })
        assertEquals(body, answerDisplayBlocks(body, state, false).joinToString("") { it.text })
    }

    @Test fun emptyOrUserMessageNeverUsesAssistantStateAsItsBody() {
        val state = UiAnswerState(parts = listOf(UiAnswerPart(1, "Only in stale state")))
        assertEquals("", canonicalAnswerParts("", state).joinToString("") { it.text })
        assertEquals("Owner source", answerDisplayBlocks("Owner source", state, true).joinToString("") { it.text })
    }
}
