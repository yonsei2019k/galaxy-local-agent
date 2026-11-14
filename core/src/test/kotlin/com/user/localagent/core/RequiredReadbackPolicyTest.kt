package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic contract cases only. Not run here; no phone/provider/model result is asserted. */
class RequiredReadbackPolicyTest {
    private val binding=RequiredReadbackPolicy.Binding("run-demo","scope-demo","workspace-demo")
    private fun snapshot(id:String="file-demo",revision:String="v1")=RequiredReadbackPolicy.Snapshot(
        RequiredReadbackPolicy.Resource("project_file",id),revision,"0".repeat(64))
    private fun step(tool:String,state:VerificationState=VerificationState.VERIFIED)=HarnessStep(ToolCall("case-demo",tool,buildJsonObject{}),StepEvidence(tool,state,"host test"))
    private fun assess(steps:List<HarnessStep>,mutations:List<RequiredReadbackPolicy.Mutation>,reads:List<RequiredReadbackPolicy.Read>,
        allowed:Set<String> = setOf("read_project_file","write_project_file"),remainingSteps:Int=4)=RequiredReadbackPolicy.assess(
            binding,setOf("read_project_file","write_project_file"),allowed,steps,mutations,reads,remainingSteps,4)
    private fun mutation(index:Int=1,target:RequiredReadbackPolicy.Snapshot=snapshot())=
        RequiredReadbackPolicy.Mutation(binding,index,"write_project_file",target)
    private fun read(index:Int,targets:List<RequiredReadbackPolicy.Snapshot> = listOf(snapshot()))=
        RequiredReadbackPolicy.Read(binding,index,"read_project_file",targets)

    @Test fun initialReadCannotSatisfyTheReadAfterWriteCriterion(){
        val result=assess(listOf(step("read_project_file"),step("write_project_file")),listOf(mutation()),listOf(read(0)))
        assertFalse(result.readbackSatisfied)
        assertEquals(RequiredReadbackPolicy.Reason.NO_LATER_READ,result.pending.single().reason)
    }
    @Test fun laterSameResourceAndVersionSatisfiesOnlyThisCriterion(){
        val result=assess(listOf(step("read_project_file"),step("write_project_file"),step("read_project_file")),listOf(mutation()),listOf(read(0),read(2)))
        assertTrue(result.readbackSatisfied)
    }
    @Test fun emptyWrongTargetAndStaleVersionNeverSatisfyReadback(){
        val steps=listOf(step("read_project_file"),step("write_project_file"),step("read_project_file"))
        assertEquals(RequiredReadbackPolicy.Reason.TARGET_NOT_RETURNED,assess(steps,listOf(mutation()),listOf(read(2,emptyList()))).pending.single().reason)
        assertFalse(assess(steps,listOf(mutation()),listOf(read(2,listOf(snapshot("other-demo"))))).readbackSatisfied)
        assertEquals(RequiredReadbackPolicy.Reason.VERSION_MISMATCH,assess(steps,listOf(mutation()),listOf(read(2,listOf(snapshot(revision="old"))))).pending.single().reason)
    }
    @Test fun currentRunBindingAndHostProofCannotBeReplacedByToolNameOrSummary(){
        val steps=listOf(step("read_project_file"),step("write_project_file"),step("read_project_file"))
        assertTrue(RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF in assess(steps,emptyList(),listOf(read(2))).blockers)
        val foreign=read(2).copy(binding=binding.copy(runId="foreign-demo"))
        assertTrue(RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF in assess(steps,listOf(mutation()),listOf(foreign)).blockers)
        assertEquals(RequiredReadbackPolicy.Reason.MISSING_READ_PROOF,assess(steps,listOf(mutation()),emptyList()).pending.single().reason)
    }
    @Test fun revokedAllowedToolAndExhaustedBudgetCannotBeExpanded(){
        val steps=listOf(step("read_project_file"),step("write_project_file"))
        val blocked=assess(steps,listOf(mutation()),listOf(read(0)),allowed=setOf("write_project_file"),remainingSteps=0)
        assertTrue(RequiredReadbackPolicy.Blocker.REQUEST_TOOL_UNAVAILABLE in blocked.blockers)
        assertTrue(RequiredReadbackPolicy.Blocker.BUDGET_EXHAUSTED in blocked.blockers)
        assertFalse(blocked.readbackSatisfied)
    }
    @Test fun latestVersionAndLatestReadAreAuthoritative(){
        val steps=listOf(step("write_project_file"),step("read_project_file"),step("write_project_file"),step("read_project_file"),step("read_project_file"))
        val ms=listOf(mutation(0,snapshot(revision="old")),mutation(2,snapshot()))
        val staleLater=listOf(read(1,listOf(snapshot(revision="old"))),read(3),read(4,listOf(snapshot(revision="changed-again"))))
        assertFalse(assess(steps,ms,staleLater).readbackSatisfied)
        assertTrue(assess(steps,ms,listOf(read(1,listOf(snapshot(revision="old"))),read(3))).readbackSatisfied)
    }
    @Test fun noModelSelectedReadMutationRelationCannotAuthorizeNewWork(){
        val result=RequiredReadbackPolicy.assess(binding,setOf("write_project_file"),setOf("write_project_file","read_project_file"),
            emptyList(),emptyList(),emptyList(),4,4)
        assertFalse(result.applicable)
        assertFalse(result.readbackSatisfied)
        assertTrue(result.pending.isEmpty())
    }
    @Test fun unrelatedLaterLookupDoesNotEraseSameTargetReadback(){
        val steps=listOf(step("read_project_file"),step("write_project_file"),step("read_project_file"),step("read_project_file"))
        assertTrue(assess(steps,listOf(mutation()),listOf(read(2),read(3,listOf(snapshot("other-demo"))))).readbackSatisfied)
    }
    @Test fun twoDifferentWrittenResourcesNeedTwoTargetReadbacks(){
        val first=snapshot("first-demo");val second=snapshot("second-demo")
        val steps=listOf(step("write_project_file"),step("write_project_file"),step("read_project_file"))
        val result=assess(steps,listOf(mutation(0,first),mutation(1,second)),listOf(read(2,listOf(first))))
        assertFalse(result.readbackSatisfied)
        assertEquals(second,result.pending.single().expected)
    }
    @Test fun filteredNoteSearchWithoutActualReturnedIdCannotVerifyTheSavedNote(){
        val target=RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource("note","note-demo"),"revision-1","0".repeat(64))
        val result=RequiredReadbackPolicy.assess(binding,setOf("save_note","search_notes"),setOf("save_note","search_notes"),
            listOf(step("save_note"),step("search_notes")),
            listOf(RequiredReadbackPolicy.Mutation(binding,0,"save_note",target)),
            listOf(RequiredReadbackPolicy.Read(binding,1,"search_notes",emptyList())),3,3)
        assertFalse(result.readbackSatisfied)
        assertEquals(RequiredReadbackPolicy.Reason.TARGET_NOT_RETURNED,result.pending.single().reason)
    }
    @Test fun failedPreparedAndUnverifiedReadsCannotSupplyVerifiedHostProof(){
        for(state in listOf(VerificationState.FAILED,VerificationState.PREPARED,VerificationState.UNVERIFIED)){
            val result=assess(listOf(step("write_project_file"),step("read_project_file",state)),listOf(mutation(0)),listOf(read(1)))
            assertFalse(result.readbackSatisfied)
            assertTrue(RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF in result.blockers)
        }
    }
    @Test fun partialAndTruncatedHostObservationsRemainIncompleteEvenWithMatchingTarget(){
        val steps=listOf(step("write_project_file"),step("read_project_file"))
        // Host adapter derives false from either a source partial flag or a truncated model/read result.
        val result=assess(steps,listOf(mutation(0)),listOf(read(1).copy(completeForTarget=false)))
        assertFalse(result.readbackSatisfied)
        assertEquals(RequiredReadbackPolicy.Reason.INCOMPLETE_READ,result.pending.single().reason)
    }
}
