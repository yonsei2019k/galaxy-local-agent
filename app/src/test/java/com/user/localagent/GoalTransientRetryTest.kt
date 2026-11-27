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
    @Test fun exactOfflineOrReadTimeoutEvidenceCanWaitBeyondTwoAttemptsWithoutExtendingTheBudget() {
        for((tool,receipt,code) in listOf(
            Triple(PcBrowserToolCatalog.OPEN,WebsiteExecutionPolicy.RECONNECT_CODE,WebsiteExecutionPolicy.RECONNECT_CODE),
            Triple(PcBrowserToolCatalog.READ,"read_timeout_no_mutation",GOAL_BROWSER_READ_RETRY))) {
            val failed=HarnessStep(tool,buildJsonObject {},StepEvidence(tool,VerificationState.FAILED,receipt,effectNotApplied=true))
            val continued=decide("paused",code,retries=2,steps=listOf(failed)) as GoalSegmentDecision.Continue
            assertEquals(2,continued.binding.transientRetries)
            assertEquals(segment().budgetWindow,continued.binding.budgetWindow)
            assertEquals(0,continued.binding.noProgressSegments)
            pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide("paused",code,2,true,listOf(failed)))
            pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide("paused",code,2,steps=listOf(failed.copy(evidence=failed.evidence.copy(summary="OWNER_LOGIN_PAGE_CHANGED")))))
        }
        assertEquals(3_600_000L,goalTransientRetryDelayMs(3))
        assertTrue(decide("paused","planning_pc_unavailable",2,steps=emptyList()) is GoalSegmentDecision.Continue)
        val read=ToolCall("read",PcBrowserToolCatalog.READ,buildJsonObject {})
        val timeout=ToolInterruptionPolicy.timedOut(read)
        assertTrue(goalRetryableBrowserRead(read,timeout))
        assertFalse(goalRetryableBrowserRead(read.copy(id="other"),timeout))
        assertFalse(goalRetryableBrowserRead(read.copy(name=PcBrowserToolCatalog.ACTION),timeout))
        assertFalse(goalRetryableBrowserRead(read,timeout.copy(verificationSummary="OWNER_LOGIN_PAGE_CHANGED")))
    }

    @Test fun persistedTimerCanOnlyWakeItsExactFailedParentAndHonoursOfflineDeadlineAndCancellation() {
        val tool=PcBrowserToolCatalog.READ
        val noEffect=HarnessStep(tool,buildJsonObject {},StepEvidence(tool,VerificationState.FAILED,WebsiteExecutionPolicy.RECONNECT_CODE,effectNotApplied=true))
        val original=request(1,listOf(noEffect))
        val row=ChatRunRecord(id=original.runId,threadId=original.threadId,branchId=original.branchId,projectId=original.projectId,
            userMessageId=original.userMessageId,modelId=original.modelId,thinking=false,status="paused",contextJson=original.toJson(),
            errorCode=WebsiteExecutionPolicy.RECONNECT_CODE)
        val wait=GoalWaitRecord("retry",goal.id,row.threadId,row.branchId,row.id,original.externalAgentRevision,
            WaitCondition.AtTime(started+60_000,false),started,started,started+120_000,dayKey="1970-01-01",transientParentDigest=goalRetryParentDigest(row))
        var disk:String?=null
        fun store()=com.user.localagent.scheduling.GoalWaitStore({disk},{disk=it})
        assertTrue(store().replace(wait,null))
        val restored=store().get(goal.id)!!
        assertTrue(restored.matchesTransientParent(row))
        assertFalse(restored.matchesTransientParent(row.copy(id="another")))
        assertFalse(restored.matchesTransientParent(row.copy(branchId="other")))
        assertFalse(restored.matchesTransientParent(row.copy(errorCode="awaiting_owner_input")))
        assertFalse(restored.matchesTransientParent(row.copy(contextJson=original.copy(capturedPcRevision=14).toJson())))
        assertEquals(started+60_000,GoalWaitPolicy.dueAt(restored))
        val zone=java.time.ZoneOffset.UTC
        val offline=GoalWaitPolicy.onWake(restored,WakeEvent.TIME_REACHED,started+60_000,zone,false) as WaitDecision.RetryLater
        assertTrue(offline.atEpochMillis<=restored.deadlineAt)
        val expired=GoalWaitPolicy.onWake(restored,WakeEvent.TIME_REACHED,restored.deadlineAt,zone,true) as WaitDecision.Stop
        assertEquals("wait_deadline",expired.code)
        val consumed=restored.copy(resumedOnceFor=restored.id)
        assertTrue(store().replace(consumed,restored))
        assertFalse(store().replace(restored,restored))
        assertNull(GoalWaitPolicy.dueAt(store().get(goal.id)!!))
        assertEquals("wait_duplicate_wake",(GoalWaitPolicy.onWake(consumed,WakeEvent.TIME_REACHED,started+60_000,zone,true) as WaitDecision.Stop).code)
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
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide("paused",WebsiteExecutionPolicy.RECONNECT_CODE,unknown=true))
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
