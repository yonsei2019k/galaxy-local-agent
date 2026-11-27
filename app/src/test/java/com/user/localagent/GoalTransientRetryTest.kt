package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transient-failure checkpoints. No PC, network or provider is contacted. */
class GoalTransientRetryTest {
    private val started=1_000_000L
    private val goal=GoalDefinition("goal-t","thread","project","Verify the requested retry QA result",
        listOf(GoalCriterion("criterion","Actual result is verified")),status=GoalStatus.ACTIVE,linkedRunIds=listOf("run-t"),updatedAt=20)
    private fun segment(retries:Int=0)=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,"run-t",null,null,1,started,0,0,
        transientRetries=retries)
    private fun step(state:VerificationState=VerificationState.VERIFIED,tool:String="search_notes")=HarnessStep(tool,
        if(tool=="search_notes")buildJsonObject {put("query","QA")}else buildJsonObject {put("content","QA")},StepEvidence(tool,state,"Synthetic exact host result"))
    private fun request(retries:Int=0,steps:List<HarnessStep> = listOf(step()))=QueuedChatRequest("run-t","thread","branch","project","Retry QA request",
        "cloud-model",false,emptyList(),"user-t",previousSteps=steps,workspaceBinding="workspace",goal=goal,capturedPcRevision=5,
        allowExternalAgent=true,externalAgentRevision=7,goalSegment=segment(retries))
    private fun decide(status:String,code:String?,retries:Int=0,unknown:Boolean=false,steps:List<HarnessStep> = listOf(step())):GoalSegmentDecision {
        val r=request(retries,steps)
        val context=JsonObject(Json.parseToJsonElement(r.toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(100))).toString()
        return goalSegmentDecision(ChatRunRecord(id=r.runId,threadId=r.threadId,branchId=r.branchId,userMessageId=r.userMessageId,projectId=r.projectId,
            modelId=r.modelId,thinking=r.thinking,status=status,contextJson=context,errorCode=code),goal,"scope",started+100,unknown)
    }
    private fun pause(reason:GoalAutopilotPauseReason,actual:GoalSegmentDecision)=assertEquals(GoalSegmentDecision.Pause(reason),actual)

    @Test fun aResolvedTransientFailureOpensABoundedRetrySegment() {
        for((status,code) in listOf("failed" to "PC_BRIDGE_TIMEOUT_POST_LAUNCH","failed" to "SocketTimeoutException","failed" to "ConnectException",
            "failed" to "PcUnavailable","failed" to "PcBusy","failed" to "PcAgentProviderBusy","paused" to "planning_pc_busy",
            "paused" to "planning_pc_unavailable","paused" to "planning_local_timeout","paused" to "tool_call_truncated")) {
            val first=decide(status,code) as GoalSegmentDecision.Continue
            assertEquals(1,first.binding.transientRetries)
            assertEquals(2,(decide(status,code,retries=1) as GoalSegmentDecision.Continue).binding.transientRetries)
            pause(GoalAutopilotPauseReason.TRANSIENT_FAILURE,decide(status,code,retries=2))
        }
        assertTrue(GoalAutopilotPauseReason.TRANSIENT_FAILURE.description.contains("일시 장애가 반복되어"))
    }
    @Test fun aNormalCheckpointResetsTheConsecutiveRetryCount() {
        assertEquals(0,(decide("completed",null,retries=2) as GoalSegmentDecision.Continue).binding.transientRetries)
        assertEquals(0,(decide("paused","reasoning_budget_exhausted",retries=1) as GoalSegmentDecision.Continue).binding.transientRetries)
    }
    @Test fun nonTransientOrOwnerDependentStopsAreNotRetried() {
        for(code in listOf("IllegalStateException","SSLHandshakeException","UnknownHostException","request_failed_before_finalization",null))
            pause(GoalAutopilotPauseReason.STOPPED,decide("failed",code))
        for(code in listOf("planning_pc_required","awaiting_owner_input","execution_not_verified"))
            pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide("paused",code))
        pause(GoalAutopilotPauseReason.STOPPED,decide("cancelled","PC_BRIDGE_TIMEOUT_POST_LAUNCH"))
    }
    @Test fun aTransientFailureNeverHidesAnUncertainEffect() {
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide("failed","PC_BRIDGE_TIMEOUT_POST_LAUNCH",unknown=true))
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide("failed","SocketTimeoutException",steps=listOf(step(VerificationState.UNVERIFIED,"save_note"))))
        pause(GoalAutopilotPauseReason.FAILED_STEP,decide("paused","planning_pc_busy",steps=listOf(step(VerificationState.FAILED,"save_note"))))
    }
    @Test fun retryWaitGrowsWithTheAttemptAndTheCountIsPersistedStrictly() {
        assertEquals(30_000L,goalTransientRetryDelayMs(1));assertEquals(60_000L,goalTransientRetryDelayMs(2))
        assertTrue(runCatching {segment(GoalSegmentBinding.MAX_TRANSIENT_RETRIES+1)}.isFailure)
        val encoded=Json.parseToJsonElement(request(1).toJson()).jsonObject
        assertEquals(1,QueuedChatRequest.fromJson(encoded.toString()).goalSegment!!.transientRetries)
        val malformed=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject+("transientRetries" to JsonPrimitive(3))))).toString()
        assertTrue(runCatching {QueuedChatRequest.fromJson(malformed)}.isFailure)
    }
}
