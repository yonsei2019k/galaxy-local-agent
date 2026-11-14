package com.user.localagent

import org.junit.Assert.*
import org.junit.Test

class EmbeddedToolCallTest {
    private val envelope="""{"type":"tool_call","tool":"list_skills","arguments":{}}"""
    @Test fun leadInProseBeforeAnEnvelopeIsSeparated(){
        val lead="먼저 확인하겠습니다.\n\n"
        assertEquals(lead.length,embeddedToolCallStart(lead+envelope))
        assertEquals(lead.length,proseVisibleLimit(lead+envelope))
    }
    @Test fun aWholeEnvelopeIsLeftToTheExistingProbe(){assertNull(embeddedToolCallStart(envelope))}
    @Test fun aPartialOpeningIsHeldUntilItResolves(){
        val text="확인합니다 {\"ty"
        assertEquals(text.indexOf('{'),proseVisibleLimit(text))
        val closed="집합 {a, b} 입니다"
        assertEquals(closed.length,proseVisibleLimit(closed))
    }
}
