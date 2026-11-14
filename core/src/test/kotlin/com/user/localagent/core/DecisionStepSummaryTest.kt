package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class DecisionStepSummaryTest {
    @Test fun requiredToolAndRemainingWorkAreStated() {
        val text=DecisionStepSummary.beforeTool("search_notes",0,12,setOf("search_notes","save_note"),null,null)
        assertTrue(text.contains("단계 1/12"));assertTrue(text.contains("필수 도구입니다"));assertTrue(text.contains("save_note"))
    }
    @Test fun unverifiedPreviousResultIsTheStatedReason() {
        val text=DecisionStepSummary.beforeTool("search_notes",2,12,emptySet(),"save_note",VerificationState.UNVERIFIED)
        assertTrue(text.contains("직전 save_note 결과가 검증되지 않아"));assertTrue(text.contains("답변이나 추가 확인"))
    }
}
