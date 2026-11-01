package com.user.localagent

import org.junit.Assert.*
import org.junit.Test

class TruncatedToolRecoveryTest {
    private val cut="""{"type":"tool_call","tool":"analyze_sms","arguments":{"question":"일정을 추출"""
    @Test fun cutToolCallFromTheReportedOutputIsRecoverable() {
        assertEquals(GeneratedResponseKind.TOOL,generatedResponseKind(cut,finished=true))
        assertTrue(truncatedToolCallRecoverable(generatedResponseKind(cut,true),structured=false,truncated=true))
    }
    @Test fun completeOrUnknownFinishAndProseAreNotRetried() {
        assertFalse(truncatedToolCallRecoverable(GeneratedResponseKind.TOOL,false,false))
        assertFalse(truncatedToolCallRecoverable(GeneratedResponseKind.TOOL,false,null))
        assertFalse(truncatedToolCallRecoverable(GeneratedResponseKind.PROSE,true,true))
        assertFalse(truncatedToolCallRecoverable(GeneratedResponseKind.UNDECIDED,false,true))
        assertTrue(truncatedToolCallRecoverable(GeneratedResponseKind.UNDECIDED,true,true))
    }
}
