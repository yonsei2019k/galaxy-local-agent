package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectSelectedActionGoalTest {
    private val allowed=setOf("search_notes","read_note","patch_note")
    private val original="메모의 'old'만 'new'로 바꾸고 다시 읽어줘. 다른 줄은 유지해."
    private fun goal()=DirectSelectedActionGoal.initial(original,6,emptyList())
    private fun patch(old:String="old")=ToolCall("patch","patch_note",buildJsonObject{
        put("id","note-a");put("scope","qa");put("expectedUpdatedAt","1");put("oldText",old);put("newText","new")
    })
    private fun read()=ToolCall("read","read_note",buildJsonObject{put("id","note-a");put("scope","qa");put("expectedUpdatedAt","1")})
    @Test fun gateRequiresAllFourConditions(){
        assertTrue(DirectSelectedActionGoal.enabled(true,true,true,true))
        assertFalse(DirectSelectedActionGoal.enabled(false,true,true,true));assertFalse(DirectSelectedActionGoal.enabled(true,false,true,true))
        assertFalse(DirectSelectedActionGoal.enabled(true,true,false,true));assertFalse(DirectSelectedActionGoal.enabled(true,true,true,false))
    }
    @Test fun selectionKeepsOriginalRequestBudgetAndOnlyChosenActionCriteria(){
        val selected=DirectSelectedActionGoal.select(goal(),patch(),allowed)
        assertEquals(original,selected.ownerRequest);assertEquals(6,selected.stepBudget)
        assertEquals(setOf("patch_note","read_note"),selected.requiredTools)
        assertFalse("search_notes" in selected.requiredTools)
        assertTrue(TaskHarness.beforeStep(selected,emptyList(),patch()) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(selected,emptyList(),true) is HarnessDecision.Stop)
    }
    @Test fun lookupOnlyCannotProveOriginalEditComplete(){
        val before=goal();assertSame(before,DirectSelectedActionGoal.select(before,read(),allowed))
        val observed=listOf(HarnessStep(read(),StepEvidence("read_note",VerificationState.VERIFIED,"Synthetic provider")))
        assertTrue(TaskHarness.decide(before,observed,true) is HarnessDecision.Stop)
    }
    @Test fun unknownUnofferedMalformedOrUnverifiableActionCannotBeAdmitted(){
        assertTrue(runCatching{DirectSelectedActionGoal.select(goal(),patch(),setOf("read_note"))}.isFailure)
        assertTrue(runCatching{DirectSelectedActionGoal.select(goal(),patch(),setOf("patch_note"))}.isFailure)
        assertTrue(runCatching{DirectSelectedActionGoal.select(goal(),patch().copy(name="unknown"),allowed)}.isFailure)
        assertTrue(runCatching{DirectSelectedActionGoal.select(goal(),patch().copy(arguments=JsonObject(patch().arguments-"id")),allowed)}.isFailure)
    }
    @Test fun selectedFailureAndUnknownEffectStillNeedRecoveryNotSuccess(){
        val selected=DirectSelectedActionGoal.select(goal(),patch(),allowed)
        val failed=HarnessStep(patch(),StepEvidence("patch_note",VerificationState.FAILED,"Unknown commit",effectNotApplied=false))
        assertTrue(TaskHarness.beforeStep(selected,listOf(failed),patch("other")) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(selected,listOf(failed),true) is HarnessDecision.Stop)
        assertEquals(selected.requiredTools,DirectSelectedActionGoal.initial(original,6,listOf(failed)).requiredTools)
    }
    @Test fun selectionCannotEraseAnExistingReadbackOrExpandOriginalStepBudget(){
        val selected=DirectSelectedActionGoal.select(goal(),patch(),allowed)
        assertSame(selected,DirectSelectedActionGoal.select(selected,read(),allowed))
        assertEquals(6,DirectSelectedActionGoal.select(selected,patch(),allowed).stepBudget)
        assertTrue(runCatching{DirectSelectedActionGoal.initial("x".repeat(6001),6,emptyList())}.isFailure)
    }
}
