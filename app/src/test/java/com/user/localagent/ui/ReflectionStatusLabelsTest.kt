package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class ReflectionStatusLabelsTest {
    @Test fun enabledSwitchDoesNotMisrepresentMissingPrerequisitesAsActiveAnalysis(){
        assertTrue(reflectionReadinessMessage(true,true,false).contains("작업 결과 분석"))
        assertTrue(reflectionReadinessMessage(true,false,true).contains("상시 모드 시작"))
        assertTrue(reflectionReadinessMessage(false,true,true).contains("꺼져"))
        assertTrue(reflectionReadinessMessage(true,true,true).contains("시스템 일정"))
    }
    @Test fun scheduledAndUnknownStatesNeverClaimSuccessfulExecution(){
        assertTrue(reflectionStatusMessage("scheduled").contains("성공을 뜻하지"))
        assertTrue(reflectionStatusMessage("unknown-state").contains("확인하지 못"))
        assertTrue(reflectionStatusMessage("proposal_saved_for_approval").contains("승인"))
    }
}
