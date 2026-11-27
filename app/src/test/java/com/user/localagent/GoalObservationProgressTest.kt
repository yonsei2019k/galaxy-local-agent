package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wait-for-state segments. Observation digests are host-computed from synthetic tool output only. */
class GoalObservationProgressTest {
    private val started=1_000_000L
    private val goal=GoalDefinition("goal-o","thread","project","Wait for the synthetic QA job to finish",
        listOf(GoalCriterion("criterion","Actual job state is verified")),status=GoalStatus.ACTIVE,linkedRunIds=listOf("run-1"),updatedAt=20)
    private fun read(content:String,tool:String="search_notes"):HarnessStep {
        val call=ToolCall("c",tool,if(tool=="search_notes")buildJsonObject {put("query","job status")}else buildJsonObject {})
        val result=ToolResult(call.id,true,content,VerificationState.VERIFIED,"Synthetic exact host result")
        return HarnessStep(call,StepEvidence(tool,VerificationState.VERIFIED,"Synthetic exact host result",observationDigest=goalObservationDigest(call,result)))
    }
    private fun first(step:HarnessStep)=QueuedChatRequest("run-1","thread","branch","project","Wait QA","cloud-model",false,emptyList(),"user-1",
        previousSteps=listOf(step),workspaceBinding="workspace",goal=goal,capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,
        goalSegment=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,"run-1",null,null,1,started,0,0))
    private fun decide(request:QueuedChatRequest)=goalSegmentDecision(ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,
        userMessageId=request.userMessageId,projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status="paused",
        contextJson=JsonObject(Json.parseToJsonElement(request.toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(100))).toString(),
        errorCode="reasoning_budget_exhausted"),checkNotNull(request.goal),"scope",started+100,false)
    /** Runs segment after segment, each observing contents[i]; returns every decision. */
    private fun chain(contents:List<String>,tool:String="search_notes"):List<GoalSegmentDecision> {
        var current=first(read(contents.first(),tool));val decisions=mutableListOf<GoalSegmentDecision>()
        for(index in contents.indices) {
            if(index>0)current=current.copy(previousSteps=listOf(read(contents[index],tool)))
            val decision=decide(current);decisions+=decision
            val next=decision as? GoalSegmentDecision.Continue?:break
            val id="run-${index+2}"
            current=current.copy(runId=id,userMessageId="user-${index+2}",previousSteps=emptyList(),
                goal=checkNotNull(current.goal).let {it.copy(linkedRunIds=it.linkedRunIds+id)},goalSegment=next.binding,goalLedger=next.ledger)
        }
        return decisions
    }

    @Test fun repeatingTheSameStatusReadWaitsForAnotherAttemptWithoutClaimingProgress() {
        val decisions=chain(List(4) {"job: running 40%"})
        val last=(decisions.last() as GoalSegmentDecision.Continue).binding
        assertEquals(2,last.noProgressSegments)
        assertEquals(1,last.transientRetries)
        assertEquals(4,decisions.size)
    }
    @Test fun aRepeatedReadWhoseActualObservationChangesIsProgress() {
        val decisions=chain(listOf("job: running 10%","job: running 40%","job: running 70%","job: running 90%"))
        assertTrue(decisions.all {it is GoalSegmentDecision.Continue})
        assertTrue(decisions.all {(it as GoalSegmentDecision.Continue).binding.noProgressSegments==0})
    }
    @Test fun aToolWhoseOutputAlwaysChangesNeverCountsAsProgressByItself() {
        val decisions=chain(listOf("10:00","10:01","10:02","10:03"),tool="get_current_datetime")
        val last=(decisions.last() as GoalSegmentDecision.Continue).binding
        assertEquals(2,last.noProgressSegments)
        assertEquals(2,last.transientRetries)
    }
    @Test fun observationDigestsAreHostComputedOnlyForVerifiedReadsAndSurviveTheLedger() {
        val call=ToolCall("w","save_note",buildJsonObject {put("content","QA")})
        assertNull(goalObservationDigest(call,ToolResult(call.id,true,"Saved",VerificationState.VERIFIED,"Saved")))
        val readCall=ToolCall("r","search_notes",buildJsonObject {put("query","QA")})
        assertNull(goalObservationDigest(readCall,ToolResult(readCall.id,false,"Failed",VerificationState.FAILED,"Failed")))
        val digest=checkNotNull(goalObservationDigest(readCall,ToolResult(readCall.id,true,"QA list",VerificationState.VERIFIED,"Listed")))
        assertTrue(digest.matches(Regex("[a-f0-9]{16}")))
        val parent=first(read("job: running 40%"))
        val next=decide(parent) as GoalSegmentDecision.Continue
        val child=parent.copy(runId="run-2",userMessageId="user-2",previousSteps=emptyList(),goal=goal.copy(linkedRunIds=listOf("run-1","run-2")),
            goalSegment=next.binding,goalLedger=next.ledger)
        val restored=QueuedChatRequest.fromJson(child.toJson())
        assertEquals(parent.previousSteps.single().evidence.observationDigest,restored.goalLedger.single().step.evidence.observationDigest)
        assertEquals(parent.previousSteps.single().evidence.observationDigest,QueuedChatRequest.fromJson(parent.toJson()).previousSteps.single().evidence.observationDigest)
        // Older checkpoints without the field restore as null; a malformed digest is rejected.
        val encoded=Json.parseToJsonElement(parent.toJson()).jsonObject
        val steps=encoded.getValue("previousSteps").jsonArray.map {JsonObject(it.jsonObject-"observationDigest")}
        assertNull(QueuedChatRequest.fromJson(JsonObject(encoded+("previousSteps" to JsonArray(steps))).toString()).previousSteps.single().evidence.observationDigest)
        val bad=encoded.getValue("previousSteps").jsonArray.map {JsonObject(it.jsonObject+("observationDigest" to JsonPrimitive("not-a-digest")))}
        assertTrue(runCatching {QueuedChatRequest.fromJson(JsonObject(encoded+("previousSteps" to JsonArray(bad))).toString())}.isFailure)
    }
}
