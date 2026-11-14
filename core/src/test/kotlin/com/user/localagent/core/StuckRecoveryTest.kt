package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StuckRecoveryTest {
    @Test fun repeatsEscalateFromReminderToStop(){
        val recovery=StuckRecovery()
        assertEquals(StuckRecovery.Action.REMIND,recovery.onRepeat())
        assertEquals(StuckRecovery.Action.REDIRECT,recovery.onRepeat())
        assertEquals(StuckRecovery.Action.STOP,recovery.onRepeat())
        assertTrue(recovery.prompt(StuckRecovery.Action.REDIRECT,"calculate").contains("calculate"))
    }
    @Test fun roundBudgetWarnsLateAndForcesWrapUpOnLastRound(){
        assertEquals("",RoundBudget.hint(0,12))
        assertTrue(RoundBudget.hint(8,12).contains("남은 단계가 3번"))
        assertTrue(RoundBudget.hint(11,12).contains("마지막 단계"))
    }
    @Test fun anImmediateRepeatIsMarkedAsRepeatedNotAsAPlainStop(){
        val call=ToolCall("a","calculate",buildJsonObject{put("expression","1+1")})
        val goal=TaskGoal("계산",listOf("계산 확인"),requiredTools=setOf("calculate"))
        val done=listOf(HarnessStep(call,StepEvidence("calculate",VerificationState.VERIFIED,"2")))
        val decision=TaskHarness.beforeStep(goal,done,call)
        assertTrue(decision is HarnessDecision.Stop&&decision.repeated)
    }
}
