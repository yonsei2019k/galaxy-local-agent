package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskHarnessPatchPreviewRecoveryTest {
    private fun args(id:String="note-1",scope:String="qa-notes",old:String="missing",new:String="corrected")=buildJsonObject{
        put("id",id);put("scope",scope);put("expectedUpdatedAt","1");put("oldText",old);put("newText",new)
    }
    private fun goal(vararg tools:String,budget:Int=6)=TaskGoal("Correct only the observed original note.",listOf("Verify the approved exact edit."),budget,tools.toSet())
    private fun call(arguments:JsonObject=args(old="actual"),tool:String="patch_note")=ToolCall("qa",tool,arguments)
    private fun step(arguments:JsonObject=args(),state:VerificationState=VerificationState.FAILED,noEffect:Boolean=true,tool:String="patch_note")=
        HarnessStep(tool,arguments,StepEvidence(tool,state,"Host evidence",effectNotApplied=noEffect))

    @Test fun confirmedPreviewRejectionAllowsCorrectedSameTargetWithoutClaimingSuccess(){
        val failed=step();val task=goal("patch_note")
        assertTrue(TaskHarness.beforeStep(task,listOf(failed),call()) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(task,listOf(failed),true) is HarnessDecision.Stop)
        assertEquals(VerificationState.FAILED,failed.evidence.state)
        assertTrue(TaskHarness.decide(task,listOf(failed,step(args(old="actual"),VerificationState.VERIFIED,false)),true) is HarnessDecision.Finish)
    }
    @Test fun anotherIdCollectionOrMutationToolCannotReplacePendingPreviewRepair(){
        val failed=step();val task=goal("patch_note","update_note")
        for(next in listOf(call(args(id="note-2",old="actual")),call(args(scope="other",old="actual")),call(buildJsonObject{put("id","note-1");put("scope","qa-notes");put("content","body")},"update_note"))){
            assertTrue(TaskHarness.beforeStep(task,listOf(failed),next) is HarnessDecision.Stop)
        }
        for(other in listOf(step(args(id="note-2",old="actual"),VerificationState.VERIFIED,false),step(args(scope="other",old="actual"),VerificationState.VERIFIED,false))){
            assertTrue(TaskHarness.decide(goal("patch_note"),listOf(failed,other),true) is HarnessDecision.Stop)
        }
    }
    @Test fun unresolvedMutationStillBlocksRepairDespiteLaterVerifiedState(){
        for(state in listOf(VerificationState.FAILED,VerificationState.UNVERIFIED)){
            val uncertain=step(state=state,noEffect=false)
            assertTrue(TaskHarness.beforeStep(goal("patch_note"),listOf(uncertain),call()) is HarnessDecision.Stop)
            assertTrue(TaskHarness.decide(goal("patch_note"),listOf(uncertain,step(args(old="actual"),VerificationState.VERIFIED,false)),true) is HarnessDecision.Stop)
        }
    }
    @Test fun equivalentCallRepeatAndOriginalStepBudgetRemainStops(){
        val failed=step();val repeated=TaskHarness.beforeStep(goal("patch_note"),listOf(failed),call(args()))
        assertTrue(repeated is HarnessDecision.Stop&&repeated.repeated)
        assertTrue(TaskHarness.beforeStep(goal("patch_note",budget=1),listOf(failed),call()) is HarnessDecision.Stop)
    }
    @Test fun canonicalJsonTargetHasNoDelimiterCollision(){
        val first=step(args(id="a|b",scope="c"));val other=args(id="a",scope="b|c",old="actual")
        assertTrue(TaskHarness.beforeStep(goal("patch_note"),listOf(first),call(other)) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal("patch_note"),listOf(first,step(other,VerificationState.VERIFIED,false)),true) is HarnessDecision.Stop)
    }
    @Test fun missingBlankOrProtectedTargetCannotGrantRecovery(){
        for(bad in listOf(JsonObject(args()-"id"),args(id=" "),JsonObject(args()-"scope"),args(scope=" "),args(scope=InternalNoteCollections.protected.first()))){
            assertTrue(TaskHarness.beforeStep(goal("patch_note"),listOf(step(bad)),call()) is HarnessDecision.Stop)
        }
    }
    @Test fun readsRemainPossibleButPreparedFollowupDoesNotFinish(){
        val failed=step();val read=call(buildJsonObject{put("id","note-1");put("scope","qa-notes");put("expectedUpdatedAt","1")},"read_note")
        assertTrue(TaskHarness.beforeStep(goal("patch_note","read_note"),listOf(failed),read) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(goal("patch_note"),listOf(failed,step(args(old="actual"),VerificationState.PREPARED,false)),true) is HarnessDecision.Stop)
    }
}
