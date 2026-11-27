package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic restart checkpoints. No process, service, model or device is involved. */
class GoalRestartRecoveryTest {
    private val goal=GoalDefinition("goal-r","thread","project","Verify the requested restart QA result",
        listOf(GoalCriterion("criterion","Actual result is verified")),status=GoalStatus.ACTIVE,linkedRunIds=listOf("run-r"),updatedAt=20)
    private val started=1_000_000L
    private val segment=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,"run-r",null,null,1,started,0,0)
    private val request=QueuedChatRequest("run-r","thread","branch","project","Restart QA request","local-model",false,emptyList(),"user-r",
        workspaceBinding="workspace",goal=goal,goalSegment=segment)
    private val call=ToolCall("call-1","search_notes",buildJsonObject {put("query","QA")})
    private val read=ToolResult(call.id,true,"Synthetic note list",VerificationState.VERIFIED,"Synthetic exact host result")
    private val step=HarnessStep(call,StepEvidence(call.name,VerificationState.VERIFIED,read.verificationSummary))

    /** finish=false leaves the call in flight, as at a process death during the effect. */
    private fun checkpoint(finish:Boolean=true,seal:Boolean=false):String = runBlocking {
        var stored:String?=null
        val effects=GoalEffectRecorder(FakeGoalExecutionJournal(goalJournalBinding(request,"scope").toString()),{goalJournalBinding(request,"scope").toString()},{stored=it})
        check(effects.begin(call));if(finish)effects.finish(read)
        if(seal)stored=effects.sealCheckpoint()
        checkNotNull(stored)
    }
    private fun row(native:String?,status:String="interrupted",errorCode:String?="process_restarted"):ChatRunRecord {
        val captured=JsonObject(Json.parseToJsonElement(request.copy(previousSteps=listOf(step)).toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(100)))
        val context=native?.let {JsonObject(captured+("sharedEngineState" to Json.parseToJsonElement(it)))}?:captured
        return ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,userMessageId=request.userMessageId,
            projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status=status,contextJson=context.toString(),errorCode=errorCode)
    }
    private fun decide(row:ChatRunRecord)=goalSegmentDecision(row,goal,"scope",started+100,goalRunEffectsUnresolved(row,"scope"))
    private fun restart(row:ChatRunRecord)=goalRestartDecision(row,goal,"scope",started+100,goalRunEffectsUnresolved(row,"scope"))

    @Test fun aRestartedParentWithAResolvedRecordIsAContinuableCheckpoint() {
        val parent=row(checkpoint())
        assertFalse(goalRunEffectsUnresolved(parent,"scope"))
        assertFalse(goalSourceNativeEffectsUnresolved(parent))
        val next=decide(parent) as GoalSegmentDecision.Continue
        val child=request.copy(runId="run-s",userMessageId="user-s",goal=goal.copy(linkedRunIds=listOf("run-r","run-s")),goalSegment=next.binding,goalLedger=next.ledger)
        requireGoalLaunchBinding(child,child.goal,parent,false,started+200) // Queue and drain re-validate with the same rule.
        assertTrue(restart(parent) is GoalSegmentDecision.Continue)
    }
    @Test fun aRestartedParentWithAnInFlightCallStaysAnUnknownEffect() {
        val parent=row(checkpoint(finish=false))
        assertTrue(goalRunEffectsUnresolved(parent,"scope"))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(parent))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),restart(parent))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),restart(row(null)))
    }
    @Test fun onlyAProcessRestartRelaxesTheClosedRecordRequirement() {
        assertTrue(goalRunEffectsUnresolved(row(checkpoint(),status="completed",errorCode=null),"scope"))
        assertFalse(goalRunEffectsUnresolved(row(checkpoint(seal=true),status="completed",errorCode=null),"scope"))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT),decide(row(checkpoint(),errorCode="request_failed_before_finalization")))
    }
    @Test fun anOrphanActiveGoalAfterRestartPausesWithADistinctReasonOrContinues() {
        assertTrue(restart(row(checkpoint(seal=true),status="completed",errorCode=null)) is GoalSegmentDecision.Continue)
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.PROCESS_RESTARTED),restart(row(checkpoint(seal=true),status="cancelled",errorCode=null)))
        assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.PROCESS_RESTARTED),
            goalRestartDecision(row(checkpoint()),goal.copy(outcome="Changed work"),"scope",started+100,false))
        assertTrue(GoalAutopilotPauseReason.PROCESS_RESTARTED.description.startsWith("앱이 다시 시작되어"))
    }
}
