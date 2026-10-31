package com.user.localagent

import org.junit.Assert.*
import org.junit.Test

class UnicodeTextTest {
    @Test fun headAndTailOmitOnlyTheScalarThatCrossesTheirBudget() {
        val text="가🙂나"
        assertEquals("가",text.safeHead(2))
        assertEquals("가🙂",text.safeHead(3))
        assertEquals("나",text.safeTail(2))
        assertEquals("🙂나",text.safeTail(3))
        assertEquals("", "🙂".safeHead(1))
        assertEquals("", "🙂".safeTail(1))
    }

    @Test fun everyBudgetProducesAValidPrefixAndSuffixWithoutReplacementCharacters() {
        val original="한글🙂🚀🇰🇷 e\u0301 👩‍💻끝"
        for(budget in 0..original.length+2) {
            val head=original.safeHead(budget)
            val tail=original.safeTail(budget)
            assertTrue(original.startsWith(head));assertTrue(original.endsWith(tail))
            assertTrue(head.length<=budget);assertTrue(tail.length<=budget)
            assertEquals(head,head.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            assertEquals(tail,tail.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            assertFalse('\uFFFD' in head);assertFalse('\uFFFD' in tail)
        }
    }

    @Test fun longSourceAndMutableBuffersKeepTheOriginalFullText() {
        val original="자료🙂".repeat(8000)+"마지막 본문🚀"
        val buffer=StringBuilder(original)
        val prefix=buffer.safeHead(1600)
        val suffix=buffer.safeTail(2400)
        assertTrue(original.startsWith(prefix));assertTrue(original.endsWith(suffix))
        assertEquals(original,buffer.toString())
        assertEquals(original,buffer.safeHead(Int.MAX_VALUE))
        assertEquals(original,buffer.safeTail(Int.MAX_VALUE))
    }

    @Test fun emptyZeroAndInvalidBudgetsHaveExplicitBehavior() {
        assertEquals("","".safeHead(0));assertEquals("","".safeTail(0))
        assertEquals("","abc".safeHead(0));assertEquals("","abc".safeTail(0))
        assertTrue(runCatching {"abc".safeHead(-1)}.isFailure)
        assertTrue(runCatching {"abc".safeTail(-1)}.isFailure)
    }
}
