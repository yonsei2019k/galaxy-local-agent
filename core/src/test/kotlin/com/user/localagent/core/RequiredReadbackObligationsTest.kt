package com.user.localagent.core

import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.core.RequiredReadbackPolicy as P
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic candidates, not executed by the staging author. No effect/model/device claims. */
class RequiredReadbackObligationsTest {
    private val first=L.Context("first","thread",null,"scope","workspace")
    private val resumed=first.copy(runId="resumed")
    private val pair=setOf("save_note","search_notes")
    private val readOnly=setOf("search_notes")
    private fun snapshot(id:String="note-a",revision:String="1",sha:String="a".repeat(64))=P.Snapshot(P.Resource("note",id),revision,sha)
    private fun step(tool:String,query:String="value",limit:Int?=null)=HarnessStep(tool,buildJsonObject {
        if(tool=="save_note"){put("title","synthetic");put("content","value")}else{put("query",query);limit?.let {put("limit",it)}}
    },StepEvidence(tool,VerificationState.VERIFIED,"host fixture"))
    private fun saved():Pair<L.State,List<HarnessStep>> {
        val steps=listOf(step("save_note"))
        val state=L.capture(L.select(null,first,pair),first,steps,listOf(P.Mutation(first.proofBinding(),0,"save_note",snapshot())),emptyList(),true)!!
        return state to steps
    }
    private fun carried(state:L.State,old:List<HarnessStep>,new:List<HarnessStep> = old)=L.carry(state,first,resumed,old,new,old.indices.associateWith {it})
    private fun assess(state:L.State?,steps:List<HarnessStep>,allowed:Set<String> = readOnly)=L.assess(state,resumed,steps,1,allowed,18-steps.size,3)
    private fun read(state:L.State,steps:List<HarnessStep>,returned:List<P.Snapshot>,complete:Boolean=true,
        incomplete:Set<P.Resource> = emptySet(),signatures:Map<Int,String> = emptyMap()):L.State =
        L.capture(state,resumed,steps,emptyList(),listOf(P.Read(resumed.proofBinding(),steps.lastIndex,"search_notes",returned,complete,incomplete)),true,signatures)!!

    @Test fun selectedPairSurvivesNarrowedReadOnlyReplanAndPause() {
        val (state,steps)=saved()
        val resumedState=L.select(carried(state,steps),resumed,readOnly)!!
        assertEquals(state.selected,resumedState.selected)
        assertFalse(assess(resumedState,steps).readbackSatisfied)
        assertEquals("search_notes",assess(resumedState,steps).pending.single().readTool)
        assertEquals(readOnly,TaskGoal("verify saved result",listOf("same target"),requiredTools=readOnly).requiredTools)
    }
    @Test fun selectedPairWithNoAppliedReceiptCannotDisappearAtCompletion() {
        val selected=L.select(null,first,pair)!!
        val carried=L.carry(selected,first,resumed,emptyList(),emptyList(),emptyMap())
        val narrowed=L.select(carried,resumed,readOnly)
        val result=L.assess(narrowed,resumed,emptyList(),0,readOnly,18,3,requestedFinish=true)
        assertFalse(result.readbackSatisfied)
        assertTrue(P.Blocker.MISSING_MUTATION_PROOF in result.blockers)
        assertTrue(result.pending.isEmpty()) // No target/revision is reconstructed or invented.
    }
    @Test fun emptyAndWrongTargetAfterResumeCannotFinish() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val carried=carried(state,old)
        assertEquals(P.Reason.TARGET_NOT_RETURNED,assess(read(carried,steps,emptyList()),steps).pending.single().reason)
        assertFalse(assess(read(carried,steps,listOf(snapshot("different"))),steps).readbackSatisfied)
    }
    @Test fun sameTargetReadCompletesCriterionWithoutWriteAuthorityOrNewMutationReceipt() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val result=read(carried(state,old),steps,listOf(snapshot()))
        assertTrue(assess(result,steps).readbackSatisfied)
        assertEquals(1,result.applied.size)
        assertFalse("save_note" in readOnly)
        assertEquals(1,steps.count { it.tool=="save_note" })
    }
    @Test fun repeatedWriteRemainsStoppedByExistingHarnessAndIsNeverExecuted() {
        val (_,steps)=saved()
        var writes=0
        val call=ToolCall("duplicate","save_note",steps.single().arguments)
        val decision=TaskHarness.beforeStep(TaskGoal("verify",listOf("saved and read"),requiredTools=readOnly),steps,call)
        if(decision is HarnessDecision.Advance)writes++
        assertTrue(decision is HarnessDecision.Stop)
        assertEquals(0,writes)
    }
    @Test fun staleRevisionAndContentDoNotMatchActualAppliedTarget() {
        val (state,old)=saved();val steps=old+step("search_notes");val carried=carried(state,old)
        assertEquals(P.Reason.VERSION_MISMATCH,assess(read(carried,steps,listOf(snapshot(revision="0"))),steps).pending.single().reason)
        assertFalse(assess(read(carried,steps,listOf(snapshot(sha="b".repeat(64)))),steps).readbackSatisfied)
    }
    @Test fun foreignRunScopeWorkspaceOrThreadNeverBecomeCurrentProof() {
        val (state,steps)=saved()
        for(other in listOf(resumed.copy(scopeKey="other"),resumed.copy(workspaceBinding="other"),resumed.copy(threadId="other"),resumed.copy(projectId="other")))
            assertTrue(runCatching { L.carry(state,first,other,steps,steps,steps.indices.associateWith {it}) }.isFailure)
        assertTrue(P.Blocker.INVALID_HOST_PROOF in assess(state,steps).blockers)
        val foreign=P.Mutation(first.proofBinding().copy(runId="foreign"),0,"save_note",snapshot())
        val captured=L.capture(L.select(null,first,pair),first,steps,listOf(foreign),emptyList(),true)!!
        assertTrue(captured.invalidHostProof)
    }
    @Test fun legacyVerifiedMutationWithoutTargetReceiptFailsClosed() {
        val (_,steps)=saved()
        assertEquals(setOf(P.Blocker.MISSING_MUTATION_PROOF),assess(null,steps).blockers)
        assertFalse(assess(null,steps).readbackSatisfied)
    }
    @Test fun freshOrdinaryRequestInheritsNothing() {
        assertNull(L.select(null,resumed,readOnly))
        assertFalse(L.assess(null,resumed,emptyList(),0,emptySet(),18,3).applicable)
    }
    @Test fun sourceStepArgumentsAndVerificationMustStillMatch() {
        val (state,steps)=saved()
        val changed=HarnessStep("save_note",buildJsonObject {put("content","changed")},StepEvidence("save_note",VerificationState.VERIFIED,"same summary"))
        assertTrue(runCatching { carried(state,steps,listOf(changed)) }.isFailure)
        val unverified=HarnessStep("save_note",steps.single().arguments,StepEvidence("save_note",VerificationState.UNVERIFIED,"same"))
        assertTrue(runCatching { carried(state,steps,listOf(unverified)) }.isFailure)
    }
    @Test fun missingReadAuthorityAndExhaustedBudgetDoNotGrantExecution() {
        val (state,old)=saved();val carried=carried(state,old)
        assertTrue(P.Blocker.REQUEST_TOOL_UNAVAILABLE in assess(carried,old,emptySet()).blockers)
        assertTrue(P.Blocker.BUDGET_EXHAUSTED in L.assess(carried,resumed,old,1,readOnly,0,3).blockers)
    }
    @Test fun completeReadProofSurvivesAnotherCheckpointWithoutNeedingAnyNewAuthority() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val complete=read(carried(state,old),steps,listOf(snapshot()))
        val third=resumed.copy(runId="third")
        val copied=L.carry(complete,resumed,third,steps,steps,steps.indices.associateWith {it})
        assertTrue(L.assess(copied,third,steps,steps.size,emptySet(),16,3).readbackSatisfied)
    }
    @Test fun laterSameTargetPartialInvalidatesEarlierCompleteReadAcrossResume() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val complete=read(carried(state,old),steps,listOf(snapshot()))
        val later=steps+step("search_notes")
        val partial=read(complete,later,emptyList(),incomplete=setOf(snapshot().resource))
        assertEquals(P.Reason.INCOMPLETE_READ,assess(partial,later).pending.single().reason)
    }
    @Test fun sameEffectiveQuerySignatureAssociatesResumedEmptyReadWithPriorTarget() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val signature="c".repeat(64)
        val complete=read(carried(state,old),steps,listOf(snapshot()),signatures=mapOf(1 to signature))
        val third=resumed.copy(runId="third")
        val carried=L.carry(complete,resumed,third,steps,steps,steps.indices.associateWith {it})
        // The host normalizes omitted/default limit. Exact call digests intentionally differ.
        val later=steps+step("search_notes",limit=20)
        val current=L.capture(carried,third,later,emptyList(),listOf(P.Read(third.proofBinding(),2,"search_notes",emptyList())),true,mapOf(2 to signature))!!
        assertEquals(P.Reason.TARGET_NOT_RETURNED,L.assess(current,third,later,2,readOnly,15,3).pending.single().reason)
    }
    @Test fun unrelatedReadDoesNotEraseGoodTargetProof() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val complete=read(carried(state,old),steps,listOf(snapshot()),signatures=mapOf(1 to "c".repeat(64)))
        val later=steps+step("search_notes",query="unrelated")
        val current=read(complete,later,listOf(snapshot("other")),signatures=mapOf(2 to "d".repeat(64)))
        assertTrue(assess(current,later).readbackSatisfied)
    }
    @Test fun olderAssociatedQueryStillInvalidatesAfterDifferentGoodQueryAndPause() {
        val (state,old)=saved();val q1=old+step("search_notes",query="Q1")
        val good1=read(carried(state,old),q1,listOf(snapshot()),signatures=mapOf(1 to "c".repeat(64)))
        val q2=q1+step("search_notes",query="Q2")
        val good2=read(good1,q2,listOf(snapshot()),signatures=mapOf(2 to "d".repeat(64)))
        assertEquals(2,good2.applied.single().coverage.size)
        val third=resumed.copy(runId="third")
        val checkpoint=L.carry(good2,resumed,third,q2,q2,q2.indices.associateWith {it})
        val later=q2+step("search_notes",query="Q1",limit=20)
        val empty=L.capture(checkpoint,third,later,emptyList(),listOf(P.Read(third.proofBinding(),3,"search_notes",emptyList())),true,mapOf(3 to "c".repeat(64)))!!
        assertFalse(L.assess(empty,third,later,3,readOnly,14,3).readbackSatisfied)
        assertEquals(P.Reason.TARGET_NOT_RETURNED,L.assess(empty,third,later,3,readOnly,14,3).pending.single().reason)
    }
    @Test fun goalMappingCannotCollapseDistinctReadPositionsOrReverseReadAfterWriteOrder() {
        val (state,old)=saved();val steps=old+step("search_notes")
        val good=read(carried(state,old),steps,listOf(snapshot()))
        val twice=steps+step("search_notes")
        val goodTwice=read(good,twice,listOf(snapshot()))
        assertTrue(runCatching {L.carry(goodTwice,resumed,resumed.copy(runId="third"),twice,steps,mapOf(0 to 0,1 to 1,2 to 1))}.isFailure)
        val reversed=listOf(steps[1],steps[0])
        assertTrue(runCatching {L.carry(good,resumed,resumed.copy(runId="third"),steps,reversed,mapOf(0 to 1,1 to 0))}.isFailure)
    }
    @Test fun associatedQueryCoverageSurvivesAnotherMutationOfTheSameResource() {
        val (state,old)=saved();val q1=old+step("search_notes",query="Q1")
        val good1=read(carried(state,old),q1,listOf(snapshot()),signatures=mapOf(1 to "c".repeat(64)))
        val changed=q1+step("update_note")+step("search_notes",query="Q2")
        val selected=L.select(good1,resumed,setOf("update_note","search_notes"))
        val target=snapshot(revision="2",sha="b".repeat(64))
        val good2=L.capture(selected,resumed,changed,listOf(P.Mutation(resumed.proofBinding(),2,"update_note",target)),
            listOf(P.Read(resumed.proofBinding(),3,"search_notes",listOf(target))),true,mapOf(3 to "d".repeat(64)))!!
        assertTrue(assess(good2,changed).readbackSatisfied)
        val third=resumed.copy(runId="third")
        val checkpoint=L.carry(good2,resumed,third,changed,changed,changed.indices.associateWith {it})
        val later=changed+step("search_notes",query="Q1")
        val empty=L.capture(checkpoint,third,later,emptyList(),listOf(P.Read(third.proofBinding(),4,"search_notes",emptyList())),true,mapOf(4 to "c".repeat(64)))!!
        assertFalse(L.assess(empty,third,later,4,readOnly,13,3).readbackSatisfied)
    }
    @Test fun alignmentFailureAndDuplicateMutationReceiptsStayInvalid() {
        val (_,steps)=saved();val initial=L.select(null,first,pair)
        val m=P.Mutation(first.proofBinding(),0,"save_note",snapshot())
        assertTrue(L.capture(initial,first,steps,listOf(m),emptyList(),false)!!.invalidHostProof)
        assertTrue(L.capture(initial,first,steps,listOf(m,m),emptyList(),true)!!.invalidHostProof)
    }
    @Test fun ambiguousGoalDedupMappingRefusesTwoDistinctEffects() {
        val (state,old)=saved();val one=carried(state,old)
        val different=one.copy(applied=one.applied.map { it.copy(source=it.source.copy(sourceRunId="another"),after=snapshot("another-target")) })
        assertTrue(runCatching {L.merge(listOf(one,different),resumed)}.isFailure)
    }
}
