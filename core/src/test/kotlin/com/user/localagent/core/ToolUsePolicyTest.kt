package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ToolUsePolicyTest {
    @Test fun verificationToolsAreRegisteredReadOnlyTools(){
        ToolUsePolicy.verificationTools.forEach {
            assertTrue(it in ToolRegistry.definitions)
            assertTrue(ToolReadPolicy.isReadOnly(it))
        }
    }
    @Test fun answersGetVerificationToolsButClarifyAndCancelDoNot(){
        val offered=setOf("calculate","date_calculate","get_current_datetime","save_note")
        assertEquals(setOf("calculate","date_calculate","get_current_datetime"),ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,offered))
        assertEquals(emptySet<String>(),ToolUsePolicy.withVerification(emptySet(),SemanticAction.CLARIFY,offered))
        assertEquals(setOf("save_note"),ToolUsePolicy.withVerification(setOf("save_note"),SemanticAction.CANCEL,offered))
        assertFalse("save_note" in ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,offered))
        assertEquals(setOf("calculate"),ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,setOf("calculate")))
    }
    @Test fun policyIsToolFirstAndNamesOnlyOfferedTools(){
        val prompt=AssistantPolicy.build(setOf("calculate","read_skill"),"2026-11-14T08:30:00+09:00")
        assertTrue(prompt.contains("도구를 먼저 사용"))
        assertTrue(prompt.contains("calculate"));assertFalse(prompt.contains("date_calculate"))
        assertTrue(prompt.contains("read_skill로 읽고"))
        assertFalse(prompt.contains("꼭 필요할 때만"))
        assertTrue(SemanticPlannerInstructions.build(setOf("calculate")).contains("Prefer tools"))
    }
    @Test fun gateOnlyConsidersDraftsWithNumbers(){
        assertTrue(ToolUsePolicy.worthVerifying("합계는 1,250원"))
        assertFalse(ToolUsePolicy.worthVerifying("안녕하세요"))
        assertTrue(ToolUsePolicy.gateSystem(setOf("calculate")).contains("review_next"))
    }
}
