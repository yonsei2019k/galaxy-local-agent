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
    @Test fun readRecoveryBlocksSameCallWithoutBanningTheNextObservedPage(){
        fun page(offset:Int)=ToolCall("page","read_note",buildJsonObject{
            put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");put("offset",offset);put("limit",100)
        })
        val goal=TaskGoal("원문 조회",listOf("필요한 범위 조회"),requiredTools=setOf("read_note"))
        val observed=listOf(HarnessStep(page(900),StepEvidence("read_note",VerificationState.VERIFIED,"nextOffset1000")))
        val same=TaskHarness.beforeStep(goal,observed,page(900))
        assertTrue(same is HarnessDecision.Stop&&same.repeated)
        assertTrue(TaskHarness.beforeStep(goal,observed,page(1000)) is HarnessDecision.Advance)
        val prompt=StuckRecovery().prompt(StuckRecovery.Action.REDIRECT,"read_note")
        assertTrue(prompt.contains("같은 인자"));assertTrue(prompt.contains("다른 인자가 필요한 읽기"))
        assertFalse(prompt.contains("read_note 은 다시 쓰지 말고"))
    }
    @Test fun mutationRecoveryAndStopBudgetKeepTheirExistingRestrictions(){
        val recovery=StuckRecovery()
        assertTrue(recovery.prompt(StuckRecovery.Action.REDIRECT,"patch_note").contains("patch_note 은 다시 쓰지 말고"))
        assertEquals(StuckRecovery.Action.REMIND,recovery.onRepeat())
        assertEquals(StuckRecovery.Action.REDIRECT,recovery.onRepeat())
        assertEquals(StuckRecovery.Action.STOP,recovery.onRepeat())
        assertEquals("",recovery.prompt(StuckRecovery.Action.STOP,"read_note"))
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
