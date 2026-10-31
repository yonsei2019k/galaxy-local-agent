package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalRunLaunchGuardTest {
    private val childId="10000000-0000-4000-8000-000000000001"
    private val parentId="20000000-0000-4000-8000-000000000002"
    private fun goal()=GoalDefinition("goal-a","thread-a","project-a","Synthetic goal",listOf(GoalCriterion("c-a","Actual output")),
        status=GoalStatus.ACTIVE,linkedRunIds=listOf(childId),updatedAt=20L)
    private fun call(name:String=GoalToolCatalog.START)=ToolCall("call-a",name,buildJsonObject {put("goalId","goal-a");put("expectedUpdatedAt","10")})
    private fun result(call:ToolCall=call(),child:String=childId)=ToolResult(call.id,true,buildJsonObject {
        put("scopeBound",true);put("queuedOneBoundedRun",true);put("queuedRunId",child);put("goalId","goal-a")
    }.toString(),VerificationState.VERIFIED,"Goal control receipt, not business completion")
    private fun child()=QueuedChatRequest(childId,"thread-a","branch-a","project-a","Synthetic goal","model-a",true,emptyList(),"child-user",
        workspaceBinding="workspace-a",goal=goal(),parentGoalStartRunId=parentId)
    private fun parentRequest(step:HarnessStep=HarnessStep(call(),goalToolStepEvidence(call(),result())))=QueuedChatRequest(parentId,
        "thread-a","branch-a","project-a","Start my goal","model-a",true,emptyList(),"parent-user",workspaceBinding="workspace-a",previousSteps=listOf(step))
    private fun parent(request:QueuedChatRequest=parentRequest(),status:String="completed")=ChatRunRecord(id=parentId,threadId=request.threadId,
        branchId=request.branchId,projectId=request.projectId,userMessageId=request.userMessageId,modelId=request.modelId,thinking=request.thinking,
        status=status,contextJson=request.toJson())
    private fun rejected(request:QueuedChatRequest=child(),current:GoalDefinition?=goal(),row:ChatRunRecord?=parent(),cancelled:Boolean=false)=
        assertTrue(runCatching {requireGoalLaunchBinding(request,current,row,cancelled)}.isFailure)

    @Test fun actualVerifiedStartAndResumeProduceExactDurableChildReferences() {
        for(tool in listOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)) {
            val call=call(tool)
            val evidence=goalToolStepEvidence(call,result(call))
            assertEquals("goal-run:$childId",evidence.continuationId)
            val captured=parentRequest(HarnessStep(call,evidence))
            val restored=QueuedChatRequest.fromJson(captured.toJson())
            assertEquals(evidence.continuationId,restored.previousSteps.single().evidence.continuationId)
            requireGoalLaunchBinding(child(),goal(),parent(restored),false)
        }
    }

    @Test fun failedParentMayKeepAnAlreadyVerifiedStartButCancelledOrUncommittedCannot() {
        requireGoalLaunchBinding(child(),goal(),parent(status="failed"),false)
        for(status in listOf("queued","running","paused","interrupted","cancelled","waiting_approval"))rejected(row=parent(status=status))
        rejected(row=null)
    }

    @Test fun liveCallerCancellationAlwaysRejectsAQueuedGoalChild() {rejected(cancelled=true)}

    @Test fun deletedPausedCompletedOrDifferentWorkGoalCannotStart() {
        rejected(current=null)
        for(status in listOf(GoalStatus.DRAFT,GoalStatus.PAUSED,GoalStatus.COMPLETE))rejected(current=goal().copy(status=status))
        rejected(current=goal().copy(outcome="Different work"))
        rejected(current=goal().copy(linkedRunIds=emptyList()))
        rejected(current=goal().copy(id="other-goal"))
    }

    @Test fun parentMustContainVerifiedExactGoalAndExactChildInsteadOfOnlySuccessProse() {
        rejected(row=parent(parentRequest().copy(previousSteps=emptyList())))
        val actual=parentRequest().previousSteps.single()
        rejected(row=parent(parentRequest(HarnessStep(actual.tool,actual.arguments,actual.evidence.copy(state=VerificationState.PREPARED,continuationId=null)))))
        rejected(row=parent(parentRequest(HarnessStep(actual.tool,actual.arguments,actual.evidence.copy(continuationId="goal-run:30000000-0000-4000-8000-000000000003")))))
        val otherArguments=buildJsonObject {put("goalId","other-goal");put("expectedUpdatedAt","10")}
        rejected(row=parent(parentRequest(HarnessStep(actual.tool,otherArguments,actual.evidence))))
    }

    @Test fun parentMetadataScopeBranchMessageModelAndThinkingMustMatchItsCapturedRequest() {
        for(changed in listOf(parent().copy(id="wrong"),parent().copy(threadId="other-thread"),parent().copy(projectId="other-project"),
            parent().copy(branchId="other-branch"),parent().copy(modelId="other-model"),parent().copy(thinking=false),parent().copy(userMessageId="other-user")))rejected(row=changed)
        rejected(request=child().copy(workspaceBinding="other-workspace"))
    }

    @Test fun legacyReadOnlyAndTextOnlyParentNeverAuthorizeANewGoalRun() {
        for(mode in listOf("plan","goal","review","research"))rejected(row=parent(parentRequest().copy(mode=mode)))
        rejected(row=parent(parentRequest().copy(allowTools=false)))
        rejected(row=parent().copy(contextJson="{}"))
    }

    @Test fun oldExplicitGuiGoalQueueRetainsCapturedRestrictionsWithoutInventingParent() {
        val old=child().copy(parentGoalStartRunId=null,mode="goal",allowTools=false)
        val restored=QueuedChatRequest.fromJson(old.toJson())
        requireGoalLaunchBinding(restored,goal(),null,false)
        assertEquals("goal",restored.mode);assertFalse(restored.allowTools)
    }

    @Test fun newGoalAnswerContinuationRetainsParentReceiptWithoutRegainingToolPermission() {
        val continuation=child().copy(allowTools=false,attachments=emptyList())
        val restored=QueuedChatRequest.fromJson(continuation.toJson())
        requireGoalLaunchBinding(restored,goal(),parent(),false)
        assertEquals(parentId,restored.parentGoalStartRunId)
        assertEquals("act",restored.mode)
        assertFalse(restored.allowTools)
    }

    @Test fun ordinaryNonGoalRequestIsUnaffectedByGoalLaunchGuard() {
        val ordinary=child().copy(parentGoalStartRunId=null,goal=null)
        requireGoalLaunchBinding(ordinary,null,null,true)
    }

    @Test fun malformedOrUnboundVerifiedStartReceiptCannotCreateDurableLaunchAuthority() {
        val valid=Json.parseToJsonElement(result().content).jsonObject
        for(key in listOf("scopeBound","queuedOneBoundedRun")) {
            for(value in listOf(JsonPrimitive(false),JsonPrimitive("true"),JsonNull)) {
                val modified=JsonObject(valid+(key to value))
                assertTrue(runCatching {goalToolStepEvidence(call(),result().copy(content=modified.toString()))}.isFailure)
            }
        }
        assertTrue(runCatching {goalToolStepEvidence(call(),result(child="not-a-uuid"))}.isFailure)
        assertTrue(runCatching {goalToolStepEvidence(call(),result().copy(id="other-call"))}.isFailure)
        assertTrue(runCatching {goalToolStepEvidence(call(),result().copy(content="{}"))}.isFailure)
    }

    @Test fun nonStartOrUnverifiedResultsNeverGainAChildContinuationReference() {
        val get=ToolCall("get-call",GoalToolCatalog.GET,buildJsonObject {})
        assertNull(goalToolStepEvidence(get,ToolResult(get.id,true,"{}",VerificationState.VERIFIED,"Metadata only")).continuationId)
        assertNull(goalToolStepEvidence(call(),result().copy(success=false,verification=VerificationState.FAILED)).continuationId)
        assertNull(goalToolStepEvidence(call(),result().copy(verification=VerificationState.UNVERIFIED)).continuationId)
    }
}
