package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class RequiredReadbackLateObservationTest {
    private val b=RequiredReadbackPolicy.Binding("qa-run","qa-scope","qa-workspace")
    private val tools=setOf("write_project_file","read_project_file")
    private val a=RequiredReadbackPolicy.Resource("project_file","qa-a.txt")
    private val other=RequiredReadbackPolicy.Resource("project_file","qa-b.txt")
    private val snap=RequiredReadbackPolicy.Snapshot(a,"qa-v1","0".repeat(64))
    private fun read(index:Int,returned:List<RequiredReadbackPolicy.Snapshot> = emptyList(),incomplete:Set<RequiredReadbackPolicy.Resource> = emptySet(),queried:Set<RequiredReadbackPolicy.Resource> = emptySet())=
        RequiredReadbackPolicy.Read(b,index,"read_project_file",returned,true,incomplete,queried)
    private fun assess(last:RequiredReadbackPolicy.Read,remaining:Int=3):RequiredReadbackPolicy.Assessment {
        val names=listOf("write_project_file","read_project_file","read_project_file")
        val steps=names.map{HarnessStep(ToolCall("qa",it,buildJsonObject{}),StepEvidence(it,VerificationState.VERIFIED,"QA host"))}
        return RequiredReadbackPolicy.assess(b,tools,tools,steps,listOf(RequiredReadbackPolicy.Mutation(b,0,"write_project_file",snap)),
            listOf(read(1,listOf(snap)),last),remaining,3)
    }
    @Test fun laterSameIdentityWithoutSnapshotIsIncomplete(){
        val result=assess(read(2,incomplete=setOf(a)))
        assertFalse(result.readbackSatisfied);assertEquals(RequiredReadbackPolicy.Reason.INCOMPLETE_READ,result.pending.single().reason)
    }
    @Test fun laterExactQueryCoverageWithNoTargetInvalidatesOldProof(){
        val result=assess(read(2,queried=setOf(a)))
        assertEquals(RequiredReadbackPolicy.Reason.TARGET_NOT_RETURNED,result.pending.single().reason)
    }
    @Test fun unrelatedIncompleteIdentityDoesNotEraseProof(){assertTrue(assess(read(2,incomplete=setOf(other))).readbackSatisfied)}
    @Test fun targetIncompleteFlagWinsEvenWithOldFullSnapshotInSameReceipt(){
        assertFalse(assess(read(2,listOf(snap),setOf(a))).readbackSatisfied)
    }
    @Test fun malformedKindAndForeignBindingCannotSupplyCoverage(){
        val bad=assess(read(2,queried=setOf(RequiredReadbackPolicy.Resource("note","qa-note"))))
        assertTrue(RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF in bad.blockers)
        val foreign=assess(read(2,incomplete=setOf(a)).copy(binding=b.copy(scopeKey="foreign")))
        assertTrue(RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF in foreign.blockers)
    }
    @Test fun emptyCoverageWithBlankIdentityIsInvalid(){
        val result=assess(read(2,queried=setOf(a.copy(stableId=""))))
        assertTrue(RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF in result.blockers)
    }
    @Test fun exhaustedBudgetRemainsBlockedAfterLateIncompleteObservation(){
        assertTrue(RequiredReadbackPolicy.Blocker.BUDGET_EXHAUSTED in assess(read(2,incomplete=setOf(a)),0).blockers)
    }
}
