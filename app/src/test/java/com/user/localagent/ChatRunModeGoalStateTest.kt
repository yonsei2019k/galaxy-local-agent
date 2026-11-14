package com.user.localagent

import com.user.localagent.core.AgentWorkMode
import com.user.localagent.core.GoalCriterion
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus
import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatRunModeGoalStateTest {
    private fun goal()=GoalDefinition(id="synthetic-goal",threadId="thread",projectId="project",outcome="합성 문서 검증",
        criteria=listOf(GoalCriterion(id="criterion",text="실제 파일 내용 확인")),constraints=listOf("외부 전송 없음"),status=GoalStatus.ACTIVE)
    private fun request(mode:String="act",allowTools:Boolean=true,goal:GoalDefinition?=null)=QueuedChatRequest(
        "run","thread","branch","project","합성 요청","auto",false,emptyList(),"user",
        allowTools=allowTools,mode=mode,workspaceBinding="synthetic-binding",goal=goal)

    @Test fun everyModeSurvivesQueuedPersistenceWithItsExactBoundary(){
        AgentWorkMode.entries.forEach {mode->
            val decoded=QueuedChatRequest.fromJson(request(mode.wireName).toJson())
            assertEquals(mode.wireName,decoded.mode);assertTrue(decoded.allowTools)
        }
    }
    @Test fun historicalNoToolRequestsNeverBecomeExecutableAfterRoundTrip(){
        val decoded=QueuedChatRequest.fromJson(request("plan",allowTools=false).toJson())
        assertFalse(decoded.allowTools);assertEquals("plan",decoded.mode)
    }
    @Test fun ordinaryChatDoesNotAcquireTheRoomStoredGoal(){
        val decoded=QueuedChatRequest.fromJson(request().toJson())
        assertNull(decoded.goal);assertFalse(Json.parseToJsonElement(decoded.toJson()).jsonObject.containsKey("goal"))
    }
    @Test fun goalSnapshotKeepsAllCriteriaConstraintsAndRunBinding(){
        val value=goal().copy(linkedRunIds=listOf("run"),updatedAt=1234)
        val decoded=QueuedChatRequest.fromJson(request(goal=value).toJson())
        assertEquals(value,decoded.goal);assertEquals(listOf("run"),decoded.goal!!.linkedRunIds)
        assertEquals(listOf("외부 전송 없음"),decoded.goal!!.constraints)
        assertEquals("실제 파일 내용 확인",decoded.goal!!.criteria.single().text)
        assertEquals(GoalStatus.ACTIVE,decoded.goal!!.status)
    }
    @Test fun goalFromAnotherThreadOrProjectCannotBeQueued(){
        assertThrows(IllegalArgumentException::class.java){request(goal=goal().copy(threadId="other"))}
        assertThrows(IllegalArgumentException::class.java){request(goal=goal().copy(projectId="other"))}
    }
    @Test fun missingGoalFieldKeepsLegacyQueueReadable(){
        val json=Json.parseToJsonElement(request().toJson()).jsonObject
        val legacy=JsonObject(json-"goal")
        assertNull(QueuedChatRequest.fromJson(legacy.toString()).goal)
    }
    @Test fun unknownModeCannotDefaultToWriteCapableAct(){
        val json=Json.parseToJsonElement(request().toJson()).jsonObject
        assertThrows(IllegalArgumentException::class.java){QueuedChatRequest.fromJson(JsonObject(json+("mode" to JsonPrimitive("unrecognized"))).toString())}
    }
    @Test fun requestCopyPreservesGoalWhileTextContinuationDisablesActions(){
        val source=request(goal=goal())
        val continuation=source.copy(allowTools=false,text="이어쓰기")
        val decoded=QueuedChatRequest.fromJson(continuation.toJson())
        assertFalse(decoded.allowTools);assertEquals(source.goal,decoded.goal)
    }
    private fun writeStep(state:VerificationState=VerificationState.VERIFIED)=HarnessStep("save_note",buildJsonObject {put("content","합성 메모")},StepEvidence("save_note",state,"저장·재조회 확인"))
    private fun evidenceSource(goal:GoalDefinition=goal(),steps:List<HarnessStep> = listOf(writeStep()),status:String="completed"):GoalRunEvidenceSource {
        val queued=request(goal=goal).copy(previousSteps=steps)
        return GoalRunEvidenceSource("run","thread","project",status,queued.toJson())
    }
    @Test fun sameGoalCarriesVerifiedWriteAndBlocksRepeatingIt(){
        val goal=goal().copy(linkedRunIds=listOf("run"))
        val inherited=inheritVerifiedGoalSteps(goal,listOf(evidenceSource(goal)),"synthetic-binding")
        assertEquals(1,inherited.size)
        val task=TaskGoal(goal.outcome,listOf("저장 검증"),requiredTools=setOf("save_note"))
        assertTrue(TaskHarness.beforeStep(task,inherited,ToolCall("new","save_note",writeStep().arguments)) is HarnessDecision.Stop)
        assertTrue(inherited.single().evidence.summary.contains("전체 목표 완료를 뜻하지 않음"))
        assertEquals(GoalStatus.ACTIVE,goal.status)
    }
    @Test fun changedGoalOutcomeConstraintsOrCriterionNeverInheritsProof(){
        val original=goal().copy(linkedRunIds=listOf("run"));val source=evidenceSource(original)
        listOf(original.copy(outcome="다른 목표"),original.copy(constraints=listOf("다른 조건")),
            original.copy(criteria=listOf(GoalCriterion(id="criterion",text="다른 완료 기준"))),
            original.copy(criteria=listOf(GoalCriterion(id="different-id",text=original.criteria.single().text))))
            .forEach {changed->assertTrue(inheritVerifiedGoalSteps(changed,listOf(source),"synthetic-binding").isEmpty())}
    }
    @Test fun unrelatedScopeOrPreparedProofCannotSuppressNewWork(){
        val goal=goal().copy(linkedRunIds=listOf("run"));val source=evidenceSource(goal)
        assertTrue(inheritVerifiedGoalSteps(goal,listOf(source.copy(threadId="other")),"synthetic-binding").isEmpty())
        assertTrue(inheritVerifiedGoalSteps(goal,listOf(evidenceSource(goal,listOf(writeStep(VerificationState.PREPARED)))),"synthetic-binding").isEmpty())
    }
    @Test fun malformedLinkedContextCannotEraseDuplicateWriteGuards(){
        val goal=goal().copy(linkedRunIds=listOf("run"));val source=evidenceSource(goal)
        assertTrue(runCatching {inheritVerifiedGoalSteps(goal,listOf(source.copy(contextJson="{invalid")),"synthetic-binding")}.isFailure)
        val original=Json.parseToJsonElement(source.contextJson).jsonObject
        val malformed=JsonObject(original+("readbackObligations" to buildJsonObject {put("version",1);put("authority","write")}))
        assertTrue(runCatching {inheritVerifiedGoalEvidence(goal,listOf(source.copy(contextJson=malformed.toString())),"synthetic-binding")}.isFailure)
    }
    @Test fun ongoingSameGoalMustFinishBeforeAnotherRunCanBeQueued(){
        val goal=goal().copy(linkedRunIds=listOf("run"))
        assertThrows(IllegalStateException::class.java){inheritVerifiedGoalSteps(goal,listOf(evidenceSource(goal,status="running")),"synthetic-binding")}
    }
}
