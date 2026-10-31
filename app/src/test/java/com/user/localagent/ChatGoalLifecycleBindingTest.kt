package com.user.localagent

import com.user.localagent.core.GoalCriterion
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus
import com.user.localagent.data.ChatRunRecord
import org.junit.Assert.*
import org.junit.Test

class ChatGoalLifecycleBindingTest {
    private fun goal()=GoalDefinition("goal-a","thread-a","project-a","Synthetic goal",listOf(GoalCriterion("criterion-a","Verified output")),
        status=GoalStatus.ACTIVE,linkedRunIds=listOf("run-a"),updatedAt=10L)
    private fun request(goal:GoalDefinition=goal())=QueuedChatRequest("run-a","thread-a","branch-a","project-a","Synthetic request","model-a",true,
        emptyList(),"user-a",goal=goal,workspaceBinding="workspace-a")
    private fun run(request:QueuedChatRequest=request())=ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,
        projectId=request.projectId,userMessageId=request.userMessageId,modelId=request.modelId,thinking=request.thinking,contextJson=request.toJson())

    @Test fun exactHostRunReturnsItsCapturedBranchModelAndOriginalGoal() {
        val expected=request()
        assertEquals(expected,requireGoalRunBinding(goal(),run(expected)))
        assertEquals("branch-a",requireGoalRunBinding(goal(),run(expected)).branchId)
        assertTrue(requireGoalRunBinding(goal(),run(expected)).thinking)
    }

    @Test fun unlinkedIdCannotAuthorizeCancellingARealSameScopeRun() {
        assertTrue(runCatching {requireGoalRunBinding(goal().copy(linkedRunIds=emptyList()),run())}.isFailure)
    }

    @Test fun anotherGoalInSameChatIsNeverAStopTarget() {
        val other=goal().copy(id="other-goal")
        assertTrue(runCatching {requireGoalRunBinding(goal(),run(request(other)))}.isFailure)
    }

    @Test fun anotherThreadOrProjectCannotBeImportedAsLinkedAuthority() {
        for (changed in listOf(run().copy(threadId="other-thread"),run().copy(projectId="other-project"))) {
            assertTrue(runCatching {requireGoalRunBinding(goal(),changed)}.isFailure)
        }
    }

    @Test fun rowAndCapturedBranchOrRunDisagreementRejectsWithoutTargetFallback() {
        for (changed in listOf(run().copy(branchId="other-branch"),run().copy(id="other-run"))) {
            assertTrue(runCatching {requireGoalRunBinding(goal(),changed)}.isFailure)
        }
    }

    @Test fun ordinaryUnattachedRunCannotBeStoppedByAddingOnlyItsIdToGoal() {
        assertTrue(runCatching {requireGoalRunBinding(goal(),run(request().copy(goal=null)))}.isFailure)
    }

    @Test fun capturedGoalMustAlsoContainItsOwnRunLink() {
        val detached=request(goal().copy(linkedRunIds=emptyList()))
        assertTrue(runCatching {requireGoalRunBinding(goal(),run(detached))}.isFailure)
    }

    @Test fun malformedSavedContextFailsClosedAndDoesNotSelectCurrentUiState() {
        assertTrue(runCatching {requireGoalRunBinding(goal(),run().copy(contextJson="{}"))}.isFailure)
    }

    @Test fun modifiedMessageModelOrThinkingMetadataCannotBorrowOriginalGoalAuthority() {
        for(changed in listOf(run().copy(userMessageId="other-user"),run().copy(modelId="other-model"),run().copy(thinking=false))) {
            assertTrue(runCatching {requireGoalRunBinding(goal(),changed)}.isFailure)
        }
    }

    @Test fun oldSameGoalWorkCanBeStoppedAfterNewerGoalTextWhileOtherGoalsStayExcluded() {
        // Quiescing is by actual goal identity/scope, even when stopping an old version of its work.
        assertEquals("run-a",requireGoalRunBinding(goal().copy(outcome="Changed goal text",updatedAt=11),run()).runId)
    }
}
