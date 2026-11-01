package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ReviewFinalizationRecoveryTest {
    @Test fun incompleteOrMalformedReviewIsNeverCompletionOrAnotherAction() {
        val valid="""{"type":"review_complete","summary":"확인됨"}"""
        assertTrue(AutonomousReviewPolicy.forFinalization(valid,emptySet(),false) is AutonomousReviewPolicy.Decision.Unavailable)
        for(raw in listOf("", "{", "[]", """{"type":"finish"}""",
            """{"type":"review_next","summary":"전송","call":{"type":"tool_call","tool":"unknown","arguments":{}}}""")) {
            assertTrue(AutonomousReviewPolicy.forFinalization(raw,emptySet(),true) is AutonomousReviewPolicy.Decision.Unavailable)
        }
        assertTrue(AutonomousReviewPolicy.forFinalization(valid,emptySet(),true) is AutonomousReviewPolicy.Decision.Complete)
    }
    @Test fun unavailableReviewCannotExpandOfferedAuthority() {
        val raw="""{"type":"review_next","summary":"확인","call":{"type":"tool_call","tool":"get_current_datetime","arguments":{}}}"""
        assertTrue(AutonomousReviewPolicy.forFinalization(raw,emptySet(),true) is AutonomousReviewPolicy.Decision.Unavailable)
        assertTrue(AutonomousReviewPolicy.forFinalization(raw,setOf("get_current_datetime"),true) is AutonomousReviewPolicy.Decision.Next)
    }
    @Test fun narrowPlannerPromptRetainsTrustBoundaryWithoutUnrelatedWorkflows() {
        val prompt=SemanticPlannerInstructions.build(setOf("calculate"))
        assertTrue(prompt.contains("untrusted data"))
        assertTrue(prompt.contains("Preserve every owner condition"))
        assertFalse(prompt.contains("Goals:"))
        assertFalse(prompt.contains("sourceKind+requiredTools"))
        assertTrue(prompt.length<3000)
        val kakao=SemanticPlannerInstructions.build(setOf("read_kakao_screen"))
        assertTrue(kakao.contains("visible Kakao screen only"))
        assertTrue(kakao.contains("no full history/closed rooms"))
    }
}
