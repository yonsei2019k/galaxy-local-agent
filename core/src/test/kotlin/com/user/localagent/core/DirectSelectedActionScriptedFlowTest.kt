package com.user.localagent.core

import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.core.RequiredReadbackPolicy as P
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Scripted providers with real host goal/harness/ledger. Not Android coordinator or model evidence. */
class DirectSelectedActionScriptedFlowTest {
    @Test fun selectionIsCheckpointedBeforeEffectAndRequiresSameTargetAfterRead(){
        val allowed=setOf("patch_note","read_note")
        val call=ToolCall("patch","patch_note",buildJsonObject{put("id","note-a");put("scope","qa");put("expectedUpdatedAt","1");put("oldText","old");put("newText","new")})
        val original=DirectSelectedActionGoal.initial("원래 메모를 고친 뒤 같은 메모를 확인한다.",6,emptyList())
        val goal=DirectSelectedActionGoal.select(original,call,allowed)
        val context=L.Context("run","thread","project","project:project",null)
        val checkpoint=checkNotNull(L.select(null,context,goal.requiredTools))
        assertEquals(setOf(L.Pair.NOTE_PATCH),checkpoint.selected)
        var effects=0
        assertTrue(TaskHarness.beforeStep(goal,emptyList(),call) is HarnessDecision.Advance)
        check(L.Pair.NOTE_PATCH in checkpoint.selected);effects++ // Synthetic provider begins only after checkpoint.
        val patch=HarnessStep(call,StepEvidence("patch_note",VerificationState.VERIFIED,"Synthetic committed receipt"))
        val after=P.Snapshot(P.Resource("note_body_v1","note-a"),"2","a".repeat(64))
        val applied=checkNotNull(L.capture(checkpoint,context,listOf(patch),listOf(P.Mutation(context.proofBinding(),0,"patch_note",after)),emptyList(),true))
        assertFalse(L.assess(applied,context,listOf(patch),0,allowed,5,3,true).readbackSatisfied)
        val readCall=ToolCall("read","read_note",buildJsonObject{put("id","note-a");put("scope","qa");put("expectedUpdatedAt","2")})
        val steps=listOf(patch,HarnessStep(readCall,StepEvidence("read_note",VerificationState.VERIFIED,"Synthetic complete delivery")))
        val wrong=after.copy(resource=P.Resource("note_body_v1","other"))
        val wrongRead=checkNotNull(L.capture(applied,context,steps,emptyList(),listOf(P.Read(context.proofBinding(),1,"read_note",listOf(wrong),true)),true))
        assertFalse(L.assess(wrongRead,context,steps,0,allowed,4,3,true).readbackSatisfied)
        val correct=checkNotNull(L.capture(applied,context,steps,emptyList(),listOf(P.Read(context.proofBinding(),1,"read_note",listOf(after),true)),true))
        assertTrue(TaskHarness.decide(goal,steps,true) is HarnessDecision.Finish)
        assertTrue(L.assess(correct,context,steps,0,allowed,4,3,true).readbackSatisfied)
        assertEquals(1,effects)
    }
    @Test fun selectedButUnappliedActionAndReadOnlyNarrowingCannotFinish(){
        val goal=DirectSelectedActionGoal.initial("원래 수정 조건 유지",6,listOf(HarnessStep("patch_note",buildJsonObject{put("id","note-a");put("scope","qa")},StepEvidence("patch_note",VerificationState.FAILED,"Preview rejected",effectNotApplied=true))))
        val context=L.Context("run","thread","project","project:project",null)
        val state=checkNotNull(L.select(null,context,goal.requiredTools))
        val narrowed=checkNotNull(L.select(state,context,setOf("read_note")))
        assertEquals(state.selected,narrowed.selected)
        assertFalse(L.assess(narrowed,context,emptyList(),0,setOf("read_note"),6,3,true).readbackSatisfied)
    }
}
