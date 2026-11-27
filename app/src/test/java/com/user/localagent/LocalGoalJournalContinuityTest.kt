package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic local goal checkpoints through the shared write-ahead recorder. No model, device or native library. */
class LocalGoalJournalContinuityTest {
    private val goal=GoalDefinition("goal-l","thread","project","Verify the requested local QA result",
        listOf(GoalCriterion("criterion","Actual result is verified")),status=GoalStatus.ACTIVE,linkedRunIds=listOf("run-l"),updatedAt=20)
    private val segment=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,"run-l",null,null,1,1_000_000,0,0)
    private val request=QueuedChatRequest("run-l","thread","branch","project","Local QA request","local-model",false,emptyList(),"user-l",
        workspaceBinding="workspace",goal=goal,goalSegment=segment)
    private val call=ToolCall("call-1","search_notes",buildJsonObject {put("query","QA")})
    private val read=ToolResult(call.id,true,"Synthetic note list",VerificationState.VERIFIED,"Synthetic exact host result")
    private val step=HarnessStep(call,StepEvidence(call.name,VerificationState.VERIFIED,read.verificationSummary))

    private class Store {var checkpoint:String?=null}
    private fun recorder(store:Store,binding:String=goalJournalBinding(request,"scope").toString(),journal:GoalExecutionJournal=FakeGoalExecutionJournal(goalJournalBinding(request,"scope").toString()))=
        GoalEffectRecorder(journal,{binding},{store.checkpoint=it})
    private fun row(store:Store,status:String,steps:List<HarnessStep>):ChatRunRecord {
        val captured=Json.parseToJsonElement(request.copy(previousSteps=steps).toJson()).jsonObject
        val context=store.checkpoint?.let {JsonObject(captured+("sharedEngineState" to Json.parseToJsonElement(it)))}?:captured
        return ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,userMessageId=request.userMessageId,
            projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status=status,contextJson=context.toString())
    }
    private fun decide(row:ChatRunRecord)=goalSegmentDecision(row,goal,"scope",1_000_100,goalNativeEffectsUnresolved(row,"scope",true))

    @Test fun aLocalGoalSegmentThatFinishesNormallyContinuesToTheNextSegment() = runBlocking {
        val store=Store();val effects=recorder(store)
        assertEquals(read,effects.run(call,{error("refused")}){read})
        store.checkpoint=effects.sealCheckpoint()
        val parent=row(store,"completed",listOf(step))
        assertFalse(goalNativeEffectsUnresolved(parent,"scope",true))
        assertFalse(goalSourceNativeEffectsUnresolved(parent))
        assertTrue(decide(parent) is GoalSegmentDecision.Continue)
    }
    @Test fun aToolSegmentWithoutAnyToolCallStillLeavesAClosedRecord() = runBlocking {
        val store=Store();val effects=recorder(store)
        store.checkpoint=effects.sealCheckpoint()
        assertTrue(decide(row(store,"completed",emptyList())) is GoalSegmentDecision.Continue)
    }
    @Test fun processDeathWhileALocalToolRunsIsAnUnknownEffect() = runBlocking {
        val store=Store();val effects=recorder(store)
        assertTrue(effects.begin(call)) // The write-ahead checkpoint is durable before the effect would start.
        val died=row(store,"interrupted",listOf(step))
        assertTrue(goalNativeEffectsUnresolved(died,"scope",false))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(died))
        store.checkpoint=effects.sealCheckpoint() // A late seal cannot launder the in-flight call.
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(row(store,"completed",listOf(step))))
    }
    @Test fun anUnfinishedLocalRecordOrAMissingRecordIsNeverAContinuation() = runBlocking {
        val store=Store();recorder(store).run(call,{error("refused")}){read}
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(row(store,"completed",listOf(step))))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(row(Store(),"completed",listOf(step))))
    }
    @Test fun aMismatchedBindingRefusesTheEffectWithoutStartingIt() = runBlocking {
        val store=Store();var started=false
        val refused=ToolResult(call.id,false,GOAL_JOURNAL_REFUSED,VerificationState.FAILED,GOAL_JOURNAL_REFUSED,effectNotApplied=true)
        val effects=recorder(store,binding=goalJournalBinding(request,"other-scope").toString())
        assertEquals(refused,effects.run(call,{refused}){started=true;read})
        assertFalse(started);assertNull(store.checkpoint)
    }
    @Test fun anUncertainLocalWriteBlocksEveryLaterEffectInTheSameSegment() = runBlocking {
        val store=Store();val effects=recorder(store)
        val write=ToolCall("call-w","save_note",buildJsonObject {put("content","QA")})
        effects.run(write,{error("refused")}){ToolResult(write.id,false,"timeout",VerificationState.UNVERIFIED,"Synthetic timeout")}
        var started=false
        effects.run(call,{read.copy(success=false)}){started=true;read}
        assertFalse(started)
        store.checkpoint=effects.sealCheckpoint()
        assertTrue(goalNativeEffectsUnresolved(row(store,"completed",emptyList()),"scope",true))
    }
    @Test fun localProfileNeverClaimsCloudAndStaysInsideTheRemainingGoalBudget() {
        val profile=Json.parseToJsonElement(localGoalJournalProfile(request)).jsonObject
        assertEquals("local_phone",profile.getValue("provider").jsonPrimitive.content)
        assertFalse(profile.getValue("cloudAuthorized").jsonPrimitive.boolean)
        assertEquals(WorkSegments.STEP_BUDGET,profile.getValue("maxToolCalls").jsonPrimitive.int)
        assertEquals(GoalSegmentBinding.MAX_DURATION_MS,profile.getValue("deadlineMs").jsonPrimitive.long)
        val pc=Json.parseToJsonElement(localGoalJournalProfile(request.copy(modelId="paired-pc-text-fast4b-v1"))).jsonObject
        assertEquals("local_pc",pc.getValue("provider").jsonPrimitive.content)
        assertEquals(JsonNull,goalJournalBinding(request,"scope").getValue("authorizationRevision"))
    }
}
