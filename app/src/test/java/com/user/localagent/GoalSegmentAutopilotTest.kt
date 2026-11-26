package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.data.ChatAccessScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic host checkpoints only. These do not establish actual model/device workflow success. */
class GoalSegmentAutopilotTest {
    private val started=1_000_000L
    private fun goal(ids:List<String> = listOf("run-a"))=GoalDefinition("goal-a","thread","project","Verify the requested QA result",
        listOf(GoalCriterion("criterion","Actual result is verified")),status=GoalStatus.ACTIVE,linkedRunIds=ids,updatedAt=20)
    private fun step(query:String="QA",state:VerificationState=VerificationState.VERIFIED,noEffect:Boolean=false)=HarnessStep("search_notes",
        buildJsonObject {put("query",query)},StepEvidence("search_notes",state,"Synthetic exact host result",effectNotApplied=noEffect))
    private fun segment(goal:GoalDefinition=goal())=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,
        "run-a",null,null,1,started,0,0)
    private fun request(steps:List<HarnessStep> = listOf(step()),binding:GoalSegmentBinding=segment(),ledger:List<GoalLedgerEntry> = emptyList())=
        QueuedChatRequest("run-a","thread","branch","project","QA request","cloud-model",false,emptyList(),"user-a",
            previousSteps=steps,workspaceBinding="workspace",goal=goal((listOf("run-a")+ledger.map {it.sourceRunId}).distinct()),capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,
            goalSegment=binding,goalLedger=ledger)
    private fun row(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted")=
        ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,userMessageId=request.userMessageId,
            projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status=status,contextJson=request.toJson(),errorCode=errorCode,updatedAt=started+10)
    private fun decide(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted",now:Long=started+100,
        unknown:Boolean=false,goal:GoalDefinition=checkNotNull(request.goal))=goalSegmentDecision(row(request,status,errorCode),goal,"scope",now,unknown)
    private fun pause(expected:GoalAutopilotPauseReason,actual:GoalSegmentDecision)=assertEquals(GoalSegmentDecision.Pause(expected),actual)
    private fun child(parent:QueuedChatRequest,next:GoalSegmentDecision.Continue)=parent.copy(runId="run-b",userMessageId="user-b",
        previousSteps=emptyList(),goal=goal(listOf("run-a","run-b")),goalSegment=next.binding,goalLedger=next.ledger)

    @Test fun nextSegmentStartsWithAnEmptyLocalBudgetAndRetainsEveryOriginalPosition() {
        val parent=request((1..18).map {step("QA-$it")})
        val next=decide(parent) as GoalSegmentDecision.Continue
        val child=child(parent,next)
        assertTrue(child.previousSteps.isEmpty());assertEquals(18,child.goalLedger.size)
        assertEquals(18,next.binding.consumedToolCalls);assertEquals(2,next.binding.segmentIndex)
        assertEquals(10L,next.binding.originalGoalVersion);assertEquals("run-a",next.binding.rootRunId)
        assertEquals(goalDigest(parent.toJson()),next.binding.parentCheckpointDigest)
        assertEquals((0..17).toList(),child.goalLedger.map {it.stepIndex})
        assertEquals(child.toJson(),QueuedChatRequest.fromJson(child.toJson()).toJson())
        assertEquals(child,QueuedChatRequest.fromJson(child.toJson()))
    }
    @Test fun unknownRuntimeEffectCannotBeReplayedEvenWhenAllVisibleStepsSayVerified() {
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(unknown=true))
    }
    @Test fun unverifiedStepsAndKnownFailuresAreRetainedAndStopAutomaticContinuation() {
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(request(listOf(step(state=VerificationState.UNVERIFIED)))))
        for(noEffect in listOf(false,true)) {
            val failed=HarnessStep("update_note",buildJsonObject {put("id","QA-id");put("content","QA corrected");put("scope","QA")},
                StepEvidence("update_note",VerificationState.FAILED,"Synthetic failure",effectNotApplied=noEffect))
            val parent=request(listOf(failed))
            assertEquals(VerificationState.FAILED,completedGoalLedger(parent).single().step.evidence.state)
            if(noEffect)assertTrue(decide(parent) is GoalSegmentDecision.Continue)
            else pause(GoalAutopilotPauseReason.FAILED_STEP,decide(parent))
        }
    }
    @Test fun pendingExternalEffectDoesNotBecomeCompletedOrAnAutomaticNextSegment() {
        val prepared=HarnessStep("prepare_sms",buildJsonObject {put("phoneNumber","01000000000");put("message","QA")},
            StepEvidence("prepare_sms",VerificationState.PREPARED,"Prepared only"))
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide(request(listOf(prepared))))
    }
    @Test fun screenOpeningIsPreparedWithoutInventingAProviderEffect() {
        val opener=HarnessStep("open_url",buildJsonObject {put("url","https://example.com")},StepEvidence("open_url",VerificationState.PREPARED,"Opened only"))
        assertTrue(decide(request(listOf(opener))) is GoalSegmentDecision.Continue)
    }
    @Test fun missingReadbackProofNeverDisappearsAcrossARunBoundary() {
        val write=HarnessStep("save_note",buildJsonObject {put("content","QA content")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved"))
        pause(GoalAutopilotPauseReason.REQUIRED_READBACK,decide(request(listOf(write))))
    }
    @Test fun pausedClarificationCancelledFailedAndInterruptedParentsCannotContinue() {
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide(errorCode="awaiting_owner_input"))
        for(status in listOf("cancelled","interrupted","failed","running","queued","waiting_approval"))
            pause(GoalAutopilotPauseReason.STOPPED,decide(status=status))
    }
    @Test fun partialAndInterruptedCheckpointsCanContinueOnlyWithoutUnknownEffects() {
        for(code in listOf("partial_verified_browser_reads","partial_verified_tool_budget","partial_recoverable_provider")) {
            assertTrue(decide(errorCode=code) is GoalSegmentDecision.Continue)
            pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(errorCode=code,unknown=true))
        }
        assertTrue(decide(status="interrupted",errorCode="process_restarted") is GoalSegmentDecision.Continue)
        pause(GoalAutopilotPauseReason.STOPPED,decide(status="interrupted",errorCode="process_restarted_approval"))
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(status="interrupted",errorCode="process_restarted",unknown=true))
    }

    @Test fun browserProgressUsesObservedPageRatherThanSnapshotOrCallArguments() {
        fun read(snapshot:String,body:String):HarnessStep {
            val call=ToolCall("fixture","read_app_browser_page",buildJsonObject {})
            val result=ToolResult(call.id,true,buildJsonObject {
                put("snapshotId",snapshot);put("url","https://example.com/status");put("title","Reservation");put("text",body)
            }.toString(),VerificationState.VERIFIED,"Observed page")
            return HarnessStep(call,StepEvidence(call.name,result.verification,result.verificationSummary,
                progressDigest=observedGoalProgress(call,result)))
        }
        val old=read("old-snapshot","Reservation pending")
        val history=listOf(GoalLedgerEntry("past",0,old))
        val binding=segment().copy(consumedToolCalls=1,noProgressSegments=2)
        pause(GoalAutopilotPauseReason.NO_PROGRESS,decide(request(listOf(read("new-snapshot","Reservation pending")),binding,history)))
        val changed=request(listOf(read("new-snapshot","Reservation confirmed")),binding,history)
        assertTrue(decide(changed) is GoalSegmentDecision.Continue)
        assertEquals(changed.toJson(),QueuedChatRequest.fromJson(changed.toJson()).toJson())
    }

    @Test fun criterionEvidenceIsBoundToRealCurrentOrInheritedAuditSteps() {
        val original=step()
        val verified=HarnessStep(original.tool,original.arguments,original.evidence.copy(auditId="real-proof"))
        val checkpoint=request(listOf(original),segment().copy(consumedToolCalls=1),listOf(GoalLedgerEntry("past",0,verified)))
        assertEquals(setOf("real-proof"),goalCriterionEvidence(checkpoint,checkpoint.previousSteps).keys)
        assertTrue(goalCriterionEvidence(request(),listOf(original)).isEmpty())
        assertEquals(checkpoint.toJson(),QueuedChatRequest.fromJson(checkpoint.toJson()).toJson())
        assertFalse(goalCriterionEvidenceTool(GoalProgressToolCatalog.VERIFY))
    }

    @Test fun timedWaitExcludesOnlyHostRecordedIdleTimeAndStillRequiresVerifiedCheckpoint() {
        val parkedAt=started+3_000
        val wokeAt=parkedAt+86_400_000
        val wait=HarnessStep(GoalWaitToolCatalog.WAIT,buildJsonObject {
            put("goalId","goal-a");put("expectedUpdatedAt","20")
            put("condition",buildJsonObject {put("kind","at_time");put("atEpochMillis",wokeAt);put("needsPlayback",false)})
        },StepEvidence(GoalWaitToolCatalog.WAIT,VerificationState.VERIFIED,"Wait recorded"))
        val request=request(listOf(step(),wait))
        val parked=row(request,errorCode="waiting_goal_condition")
        fun decideParked(parent:ChatRunRecord,unknown:Boolean=false)=
            goalSegmentDecision(parent,checkNotNull(request.goal),"scope",wokeAt,unknown)
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decideParked(parked))
        val woken=parked.copy(contextJson=JsonObject(Json.parseToJsonElement(parked.contextJson).jsonObject+
            ("hostGoalWake" to buildJsonObject {put("parkedAt",parkedAt);put("wokeAt",wokeAt)})).toString())
        val next=decideParked(woken) as GoalSegmentDecision.Continue
        assertEquals(3_000L,wokeAt-next.binding.startedAtMs)
        assertEquals(2,next.binding.consumedToolCalls)
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decideParked(woken,true))
    }

    @Test fun eightSegmentsAndThirtyMinutesArePausesRatherThanGoalCompletion() {
        val eighth=segment().copy(rootRunId="root-run",segmentIndex=8,totalSegments=8,parentRunId="previous",parentCheckpointDigest="a".repeat(64))
        pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,decide(request(emptyList(),binding=eighth)))
        pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(request(emptyList()),now=started+GoalSegmentBinding.MAX_DURATION_MS))
        val extended=decide(request(binding=eighth)) as GoalSegmentDecision.Continue
        assertEquals(9,extended.binding.totalSegments)
        assertEquals(8,extended.binding.phaseStartSegments)
        assertEquals(1,extended.binding.phaseStartCalls)
        assertEquals(144,goalToolRoom(extended.binding,0))
        val hardLimit=eighth.copy(totalSegments=GoalSegmentBinding.MAX_SEGMENTS,phaseStartSegments=24)
        pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,decide(request(binding=hardLimit)))
        pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(now=started-1))
        assertEquals(GoalStatus.ACTIVE,goal().status)
    }
    @Test fun cumulativeToolBudgetIncludesAllEarlierSegmentsAndCurrentAttempts() {
        val history=(0 until 126).map {GoalLedgerEntry("past-${it/18}",it%18,step("old-$it"))}
        val binding=segment().copy(consumedToolCalls=126)
        val next=decide(request((0 until 18).map {step("new-$it")},binding,history)) as GoalSegmentDecision.Continue
        assertEquals(144,next.binding.consumedToolCalls)
        assertEquals(144,next.binding.phaseStartCalls)
        assertEquals(144,next.ledger.size)
        pause(GoalAutopilotPauseReason.TOOL_BUDGET,decide(request((0 until 18).map {step("old-$it")},binding,history)))
    }
    @Test fun threeSegmentsWithoutNewVerifiedHostCallsPause() {
        val same=step()
        val history=listOf(GoalLedgerEntry("past",0,same))
        pause(GoalAutopilotPauseReason.NO_PROGRESS,decide(request(listOf(same),segment().copy(consumedToolCalls=1,noProgressSegments=2),history)))
        val next=decide(request(listOf(step("different")),segment().copy(consumedToolCalls=1,noProgressSegments=2),history)) as GoalSegmentDecision.Continue
        assertEquals(0,next.binding.noProgressSegments)
    }
    @Test fun completionCriteriaReadyReturnsExplicitCompletionDecision() {
        val current=goal().copy(criteria=goal().criteria.map {it.copy(status=GoalCriterionStatus.USER_CONFIRMED)})
        assertEquals(GoalSegmentDecision.Complete,decide(goal=current))
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(goal=current,unknown=true))
        assertEquals(GoalStatus.ACTIVE,current.status)
    }
    @Test fun oldQueuedGoalDoesNotAcquireAutomaticRunAuthority() {
        val legacy=request().copy(goalSegment=null,goalLedger=emptyList())
        pause(GoalAutopilotPauseReason.LEGACY_CONTEXT,decide(legacy))
        val encoded=Json.parseToJsonElement(legacy.toJson()).jsonObject
        assertFalse(encoded.containsKey("goalSegment"));assertFalse(encoded.containsKey("goalLedger"))
    }
    @Test fun exactParentCheckpointAndScopeAreRequiredAtChildLaunch() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        requireGoalLaunchBinding(child,child.goal,row(parent),false,started+200)
        assertTrue(runCatching {requireGoalLaunchBinding(child,child.goal,row(parent.copy(text="Changed after checkpoint")),false,started+200)}.isFailure)
        assertTrue(runCatching {goalSegmentDecision(row(parent),goal(),"other-scope",started+100,false)}.isFailure)
        assertTrue(runCatching {requireGoalLaunchBinding(child,child.goal,row(parent),true,started+200)}.isFailure)
    }
    @Test fun noChangedModelBranchProjectOrConsentCanBorrowParentAuthority() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        val changed=listOf(child.copy(branchId="other"),child.copy(modelId="other"),child.copy(capturedPcRevision=6),
            child.copy(externalAgentRevision=8),child.copy(allowExternalAgent=false),child.copy(pcDataBinding="a".repeat(64)),
            child.copy(workspaceBinding="other"),child.copy(mode="debug"))
        changed.forEach {assertTrue(runCatching {requireGoalLaunchBinding(it,it.goal,row(parent),false,started+200)}.isFailure)}
        for(binding in listOf(child.goalSegment!!.copy(scopeRevision=5),child.goalSegment.copy(useOwnerMemory=true),child.goalSegment.copy(memoryEnabled=false))) {
            assertTrue(runCatching {requireSameGoalAuthority(parent,child.copy(goalSegment=binding))}.isFailure)
        }
    }
    @Test fun missingOrAlteredLedgerCannotBorrowAnOtherwiseValidParentDigest() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        val altered=child.copy(goalLedger=listOf(GoalLedgerEntry("run-a",0,step("Altered body"))))
        assertTrue(runCatching {requireGoalLaunchBinding(altered,altered.goal,row(parent),false,started+200)}.isFailure)
        assertTrue(runCatching {child.copy(goalLedger=emptyList())}.isFailure)
    }
    @Test fun aVerifiedWriteOlderThanSixEntriesStillBlocksAnExactDuplicate() {
        val args=buildJsonObject {put("content","Exact QA report")}
        val write=HarnessStep("save_note",args,StepEvidence("save_note",VerificationState.VERIFIED,"Verified before boundary"))
        val history=listOf(GoalLedgerEntry("first",0,write))+(1..20).map {GoalLedgerEntry("other-${it/18}",it%18,step("old-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=history.size),history)
        val task=TaskGoal("Save the QA report",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,ToolCall("duplicate","save_note",args),emptyList())}.isFailure)
        requireGoalLedgerAllowsCall(request,task,ToolCall("new","save_note",buildJsonObject {put("content","A distinct requested report")}),emptyList())
    }
    @Test fun failedAndOwnerHandoffLedgersCannotBeStrippedToEnableWrites() {
        val failed=GoalLedgerEntry("past",0,HarnessStep("save_note",buildJsonObject {put("content","QA")},StepEvidence("save_note",VerificationState.FAILED,"Uncertain save")))
        val request=request(emptyList(),segment().copy(consumedToolCalls=1),listOf(failed))
        val task=TaskGoal("Save QA",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,ToolCall("write","save_note",buildJsonObject {put("content","QA")}),emptyList())}.isFailure)
        assertEquals(VerificationState.FAILED,QueuedChatRequest.fromJson(request.toJson()).goalLedger.single().step.evidence.state)
    }
    @Test fun malformedUnknownOrOverBudgetBindingsFailClosedDuringRestore() {
        val o=Json.parseToJsonElement(request().toJson()).jsonObject
        val binding=o.getValue("goalSegment").jsonObject
        for(change in listOf(binding+mapOf("segmentIndex" to JsonPrimitive(9)),binding+mapOf("consumedToolCalls" to JsonPrimitive(-1)),
            binding+mapOf("scopeRevision" to JsonPrimitive("4")),binding+mapOf("authority" to JsonPrimitive(true)))) {
            assertTrue(runCatching {QueuedChatRequest.fromJson(JsonObject(o+("goalSegment" to JsonObject(change))).toString())}.isFailure)
        }
    }
    @Test fun actualAccessScopeAndItsRevisionMustMatchBeforeLaunch() {
        val request=request()
        val access=ChatAccessScope("thread","project","scope",false,4,true)
        requireGoalSegmentScope(request,access)
        for(changed in listOf(access.copy(threadId="other"),access.copy(projectId=null),access.copy(scopeKey="owner"),
            access.copy(useOwnerMemory=true),access.copy(threadRevision=5),access.copy(memoryEnabled=false))) {
            assertTrue(runCatching {requireGoalSegmentScope(request,changed)}.isFailure)
        }
    }
    @Test fun removedHistoricalRunCannotSilentlyEraseTheCumulativeLedger() {
        val old=GoalLedgerEntry("earlier",0,step("earlier"))
        val parent=request(binding=segment().copy(consumedToolCalls=1),ledger=listOf(old))
        assertTrue(runCatching {decide(parent,goal=goal())}.isFailure)
        assertTrue(runCatching {parent.copy(goal=goal())}.isFailure)
    }
    @Test fun aNarrowCurrentStepBudgetDoesNotConsumeTheHistoricalLedgerBudget() {
        val history=(0..21).map {GoalLedgerEntry("earlier-${it/18}",it%18,step("read-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=history.size),history)
        val task=TaskGoal("Save requested QA report",listOf("Verify QA"),stepBudget=1,requiredTools=setOf("save_note"))
        requireGoalLedgerAllowsCall(request,task,ToolCall("new","save_note",buildJsonObject {put("content","QA report")}),emptyList())
    }
    @Test fun explicitResumeAndSteeringCannotRenewTheOriginalGoalBudget() {
        val parent=request(binding=segment().copy(totalSegments=7))
        val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",parent.toJson())
        val budget=goalEntryBudget(goal(),listOf(source),started+100)
        assertEquals(started,budget.startedAtMs);assertEquals(8,budget.totalSegments);assertEquals(10L,budget.originalGoalVersion)
        val eighth=source.copy(contextJson=parent.copy(goalSegment=parent.goalSegment!!.copy(totalSegments=8)).toJson())
        val segmentError=runCatching {goalEntryBudget(goal(),listOf(eighth),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.SEGMENT_BUDGET,segmentError.reason)
        val timeError=runCatching {goalEntryBudget(goal(),listOf(source),started+GoalSegmentBinding.MAX_DURATION_MS)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,timeError.reason)
    }
    @Test fun liveCurrentCallsCannotPassTheCumulative144BoundaryOfAnImmutableQueuedRequest() {
        val history=(0 until 143).map {GoalLedgerEntry("past-${it/18}",it%18,step("past-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=143),history)
        val task=TaskGoal("Read QA",listOf("Read actual result"))
        val call=ToolCall("last","search_notes",buildJsonObject {put("query","last")})
        requireGoalLedgerAllowsCall(request,task,call,emptyList())
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,call,listOf(step("actual-144")))}.isFailure)
    }
    @Test fun eightSequentialSegmentsKeepAll144PositionsWhenProgressUnlocksAnotherPhase() {
        var current=request()
        for(index in 1..8) {
            current=current.copy(previousSteps=(0 until 18).map {step("segment-$index-$it")})
            assertEquals(index*18,completedGoalLedger(current).size)
            val next=decide(current) as GoalSegmentDecision.Continue
            val id="run-${index+1}"
            current=current.copy(runId=id,userMessageId="user-${index+1}",previousSteps=emptyList(),
                goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),goalSegment=next.binding,goalLedger=next.ledger)
            assertEquals(current,QueuedChatRequest.fromJson(current.toJson()))
        }
        assertEquals(144,current.goalLedger.size)
        assertEquals(9,current.goalSegment!!.totalSegments)
        assertEquals(8,current.goalSegment!!.phaseStartSegments)
        assertEquals(GoalStatus.ACTIVE,current.goal!!.status)
    }
    @Test fun registeredReadFailureThenVerifiedAlternativeMayContinueWithoutErasingTheFailure() {
        val parent=request(listOf(step("first read",VerificationState.FAILED),step("alternative")))
        val next=decide(parent) as GoalSegmentDecision.Continue
        assertEquals(listOf(VerificationState.FAILED,VerificationState.VERIFIED),next.ledger.map {it.step.evidence.state})
        val nextRequest=child(parent,next)
        requireGoalLedgerAllowsCall(nextRequest,TaskGoal("Read QA",listOf("Actual read")),
            ToolCall("alternative","search_notes",buildJsonObject {put("query","another query")}),emptyList())
    }
    @Test fun anUnregisteredReadPrefixNeverEstablishesAReadOnlyContract() {
        val unknown=HarnessStep("read_unregistered",buildJsonObject {},StepEvidence("read_unregistered",VerificationState.FAILED,"Not a registered tool"))
        assertFalse(ToolReadPolicy.isReadOnly(unknown.tool));assertTrue(goalUncertainFailure(unknown))
        pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT,decide(request(listOf(unknown))))
    }
    @Test fun repeatingOnlyASafeFailedReadStopsAfterThreeSegmentsWithoutProgress() {
        var current=request(listOf(step("same failed read",VerificationState.FAILED)))
        for(index in 1..3) {
            val result=decide(current)
            if(index==3)pause(GoalAutopilotPauseReason.NO_PROGRESS,result)
            else {
                val next=result as GoalSegmentDecision.Continue
                val id="retry-$index"
                current=current.copy(runId=id,userMessageId="retry-user-$index",goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),
                    goalSegment=next.binding,goalLedger=next.ledger)
            }
        }
    }
}
