package com.user.localagent.integrations

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenActionRecoveryTest {
    private val actionName=ScreenControlToolCatalog.ACTION
    private val readName=ScreenControlToolCatalog.READ
    private val goal=TaskGoal("Open the selected order",listOf("Open the same observed order"),requiredTools=setOf(actionName))
    private val oldId="a".repeat(64)
    private val newId="b".repeat(64)
    private val target="c".repeat(64)
    private val old=action("old",oldId,"e1")
    private val next=action("next",newId,"e4")
    private val before=read("before",oldId,mapOf("e1" to target))
    private val after=read("after",newId,mapOf("e4" to target))
    private val stale=ObservedToolOutcome(old,ToolResult(old.id,false,"read_screen",VerificationState.FAILED,
        ScreenControlToolCatalog.STALE_SNAPSHOT_RECEIPT,effectNotApplied=true))

    @Test
    fun sameGoalCanReobserveThenProposeTheFreshRef() {
        val rejected=listOf(before,stale)
        val recoveryRead=after.call
        assertTrue(preflight(rejected,recoveryRead) is HarnessDecision.Advance)
        assertTrue(preflight(rejected,next) is HarnessDecision.Stop)
        val reobserved=rejected+after
        val steps=steps(reobserved)
        assertTrue(TaskHarness.beforeStep(goal,steps,next) is HarnessDecision.Stop)
        assertTrue(preflight(reobserved,next) is HarnessDecision.Advance)
        assertTrue(finish(reobserved) is HarnessDecision.Stop)
        assertTrue(finish(reobserved+success(next)) is HarnessDecision.Finish)
    }

    @Test
    fun staleAfterOwnerApprovalStopsBeforeAnySecondRecovery() {
        val laterId="d".repeat(64)
        val readyForApproval=listOf(before,stale,after)
        assertTrue(preflight(readyForApproval,next) is HarnessDecision.Advance)
        var dispatches=0
        val mismatch=assertThrows(ScreenControl.SnapshotChanged::class.java) {
            ScreenControl.requireCurrentSnapshot(newId,laterId,approvedSnapshot=newId)
            dispatches++
        }
        assertEquals(0,dispatches)
        val result=mismatch.failure(next.id)
        assertFalse(result.success)
        assertTrue(result.effectNotApplied)
        assertEquals(ScreenControlToolCatalog.APPROVED_SNAPSHOT_CHANGED_RECEIPT,result.verificationSummary)
        assertTrue(ScreenControlToolCatalog.approvalInvalidated(next.name,result.verification,result.verificationSummary,result.effectNotApplied))
        val run=readyForApproval+ObservedToolOutcome(next,result)
        assertEquals(HarnessDecision.Stop(ScreenControlToolCatalog.APPROVED_SNAPSHOT_CHANGED_NOTICE),
            TaskHarness.decide(goal,steps(run)))
        val laterRead=read("later",laterId,mapOf("e6" to target))
        assertTrue(preflight(run,laterRead.call) is HarnessDecision.Stop)
        assertTrue(preflight(run+laterRead,action("again",laterId,"e6")) is HarnessDecision.Stop)

        val firstApprovalMismatch=assertThrows(ScreenControl.SnapshotChanged::class.java) {
            ScreenControl.requireCurrentSnapshot(oldId,newId,approvedSnapshot=oldId)
        }
        val firstApprovalRun=listOf(before,ObservedToolOutcome(old,firstApprovalMismatch.failure(old.id)))
        assertTrue(preflight(firstApprovalRun,after.call) is HarnessDecision.Stop)
        assertTrue(preflight(firstApprovalRun+after,next) is HarnessDecision.Stop)
    }

    @Test
    fun ambiguousChangedOrUnsettledEvidenceCannotReopenDispatch() {
        val ambiguous=read("ambiguous",newId,mapOf("e4" to target,"e5" to target))
        val foreign=read("foreign",newId,mapOf("e4" to target),packageDigest="e".repeat(64))
        val different=read("different",newId,mapOf("e4" to "d".repeat(64)))
        for (changed in listOf(ambiguous,foreign,different)) {
            assertTrue(preflight(listOf(before,stale,changed),next) is HarnessDecision.Stop)
        }
        val unknown=stale.copy(result=stale.result.copy(effectNotApplied=false,verification=VerificationState.UNVERIFIED))
        assertTrue(preflight(listOf(before,unknown,after),next) is HarnessDecision.Stop)
        assertTrue(preflight(listOf(before,stale,after),action("previous",oldId,"e1")) is HarnessDecision.Stop)
        assertTrue(preflight(listOf(before,stale,after),action("changed",newId,"e4",kind="type")) is HarnessDecision.Stop)
    }

    @Test
    fun modelEvidenceKeepsRefOutsideTheUsualThreeRows() {
        val screen=buildJsonObject {
            put("snapshotId",newId)
            put("elements",buildJsonArray {
                for (index in 1..8) add(buildJsonObject {put("ref","e$index");put("label","Button $index")})
            })
        }
        val fresh=after.copy(result=after.result.copy(content=screen.toString()))
        val result=checkNotNull(ScreenActionRecovery.modelRead(listOf(before,stale,fresh),fresh.call))
        val data=Json.parseToJsonElement(result).jsonObject.getValue("data").jsonObject
        assertEquals(newId,data.getValue("snapshotId").jsonPrimitive.content)
        assertEquals("e4",data.getValue("elements").jsonArray.first().jsonObject.getValue("ref").jsonPrimitive.content)
    }

    private fun action(id:String,snapshot:String,ref:String,kind:String="tap")=ToolCall(id,actionName,buildJsonObject {
        put("action",kind);put("snapshotId",snapshot);put("ref",ref)
        if(kind=="type")put("text","changed")
    })
    private fun read(id:String,snapshot:String,targets:Map<String,String>,packageDigest:String="f".repeat(64))=
        ObservedToolOutcome(ToolCall(id,readName,JsonObject(emptyMap())),
            ToolResult(id,true,"{}",VerificationState.VERIFIED,screenReadEvidence=ScreenReadEvidence(snapshot,packageDigest,10,targets)))
    private fun success(call:ToolCall)=ObservedToolOutcome(call,ToolResult(call.id,true,"{}",VerificationState.VERIFIED))
    private fun steps(outcomes:List<ObservedToolOutcome>)=outcomes.map {(call,result)->HarnessStep(call,
        StepEvidence(call.name,result.verification,result.verificationSummary,effectNotApplied=result.effectNotApplied))}
    private fun preflight(outcomes:List<ObservedToolOutcome>,call:ToolCall):HarnessDecision {
        val steps=steps(outcomes)
        return TaskHarness.beforeStep(goal,steps,call,ScreenActionRecovery.boundFailures(steps,outcomes,call))
    }
    private fun finish(outcomes:List<ObservedToolOutcome>):HarnessDecision {
        val steps=steps(outcomes)
        return TaskHarness.decide(goal,steps,requestedFinish=true,recoveredScreenSteps=ScreenActionRecovery.boundFailures(steps,outcomes))
    }
}
