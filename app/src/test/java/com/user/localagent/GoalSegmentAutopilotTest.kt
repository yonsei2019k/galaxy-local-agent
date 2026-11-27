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
    /** active=null models a run written before segment active time was recorded. */
    private fun context(request:QueuedChatRequest,active:Long?)=active?.let {JsonObject(Json.parseToJsonElement(request.toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(it))).toString()}?:request.toJson()
    private fun row(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted",active:Long?=100,
        startedAt:Long?=null,finishedAt:Long?=null)=
        ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,userMessageId=request.userMessageId,
            projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status=status,contextJson=context(request,active),errorCode=errorCode,
            startedAt=startedAt,finishedAt=finishedAt)
    private fun decide(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted",now:Long=started+100,
        unknown:Boolean=false,goal:GoalDefinition=checkNotNull(request.goal),active:Long?=100)=goalSegmentDecision(row(request,status,errorCode,active),goal,"scope",now,unknown)
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
        assertEquals(goalDigest(row(parent).contextJson),next.binding.parentCheckpointDigest)
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
    @Test fun eightSegmentsAndThirtyMinutesArePausesRatherThanGoalCompletion() {
        val eighth=segment().copy(rootRunId="root-run",segmentIndex=8,totalSegments=8,parentRunId="previous",parentCheckpointDigest="a".repeat(64))
        pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,decide(request(binding=eighth)))
        val spent=segment().copy(activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-100)
        pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(request(binding=spent),active=100))
        assertTrue(decide(request(binding=spent),active=99) is GoalSegmentDecision.Continue)
        assertEquals(GoalStatus.ACTIVE,goal().status)
    }
    @Test fun thirtyMinutesCountActiveSegmentTimeNotWallClockGapsOrClockSteps() {
        val binding=segment().copy(activeElapsedMs=120_000)
        for(now in listOf(started-1,started+10*GoalSegmentBinding.MAX_DURATION_MS)) {
            val next=decide(request(binding=binding),now=now,active=60_000) as GoalSegmentDecision.Continue
            assertEquals(180_000L,next.binding.activeElapsedMs)
        }
    }
    @Test fun aParentWithoutRecordedActiveTimeIsChargedItsWallSpanOrTheWholeRemainder() {
        val parent=request(binding=segment().copy(activeElapsedMs=1_000))
        val spanned=goalSegmentDecision(row(parent,active=null,startedAt=50_000,finishedAt=55_000),checkNotNull(parent.goal),"scope",started+100,false) as GoalSegmentDecision.Continue
        assertEquals(6_000L,spanned.binding.activeElapsedMs)
        pause(GoalAutopilotPauseReason.TIME_BUDGET,goalSegmentDecision(row(parent,active=null),checkNotNull(parent.goal),"scope",started+100,false))
        for(bad in listOf(-1L,GoalSegmentBinding.MAX_DURATION_MS+1))
            pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(parent,active=bad))
    }
    @Test fun aLegacyBindingWithoutActiveTimeIsReadConservativelyAndNeverRenewed() {
        val encoded=Json.parseToJsonElement(request().toJson()).jsonObject
        val added=setOf("activeElapsedMs","budgetWindow","budgetBaseToolCalls","transientRetries")
        val legacy=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject-added))).toString()
        val partial=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject-"budgetWindow"))).toString()
        assertTrue(runCatching {QueuedChatRequest.fromJson(partial)}.isFailure)
        assertEquals(0,QueuedChatRequest.fromJson(legacy).goalSegment!!.budgetWindow)
        assertEquals(1_000L,QueuedChatRequest.fromJson(legacy,started+1_000).goalSegment!!.activeElapsedMs)
        assertEquals(GoalSegmentBinding.MAX_DURATION_MS,QueuedChatRequest.fromJson(legacy,started+5*GoalSegmentBinding.MAX_DURATION_MS).goalSegment!!.activeElapsedMs)
        assertEquals(0L,QueuedChatRequest.fromJson(legacy,started-5).goalSegment!!.activeElapsedMs)
        val negative=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject+("activeElapsedMs" to JsonPrimitive(-1))))).toString()
        assertTrue(runCatching {QueuedChatRequest.fromJson(negative)}.isFailure)
        val plain=request()
        assertEquals(plain.toJson(),QueuedChatRequest.fromJson(context(plain,100)).toJson()) // The run-context key is not request state.
    }
    @Test fun runtimeTimeBudgetUsesTheCarriedActiveTimePlusTheCurrentSegment() {
        val segment=segment().copy(activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-1_000)
        assertNull(goalBudgetStop(segment,0,segment.activeElapsedMs+999,0,false))
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,goalBudgetStop(segment,0,segment.activeElapsedMs+1_000,0,false))
        var now=0L;val clock=GoalActiveClock({now},baselineMs=500)
        now=10_000;assertEquals(10_500L,clock.activeMs())
        kotlinx.coroutines.runBlocking {clock.excluding {now=70_000}} // Owner approval wait is not active time.
        now=71_000;assertEquals(11_500L,clock.activeMs())
    }
    @Test fun cumulativeToolBudgetIncludesAllEarlierSegmentsAndCurrentAttempts() {
        val history=(0 until 126).map {GoalLedgerEntry("past-${it/18}",it%18,step("old-$it"))}
        val binding=segment().copy(consumedToolCalls=126)
        pause(GoalAutopilotPauseReason.TOOL_BUDGET,decide(request((0 until 18).map {step("new-$it")},binding,history)))
    }
    @Test fun threeSegmentsWithoutNewVerifiedHostCallsPause() {
        val same=step()
        val history=listOf(GoalLedgerEntry("past",0,same))
        pause(GoalAutopilotPauseReason.NO_PROGRESS,decide(request(listOf(same),segment().copy(consumedToolCalls=1,noProgressSegments=2),history)))
        val next=decide(request(listOf(step("different")),segment().copy(consumedToolCalls=1,noProgressSegments=2),history)) as GoalSegmentDecision.Continue
        assertEquals(0,next.binding.noProgressSegments)
    }
    @Test fun completionCriteriaReadyStillRequiresSeparateHostFinalization() {
        val current=goal().copy(criteria=goal().criteria.map {it.copy(status=GoalCriterionStatus.USER_CONFIRMED)})
        pause(GoalAutopilotPauseReason.CRITERIA_READY,decide(goal=current))
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
    @Test fun ownerResumeOpensANewBudgetWindowWithoutDroppingTheLedgerOrItsDuplicateGuards() {
        val args=buildJsonObject {put("content","Exact QA report")}
        val write=GoalLedgerEntry("first",0,HarnessStep("save_note",args,StepEvidence("save_note",VerificationState.VERIFIED,"Verified write")))
        val spent=request(binding=segment().copy(totalSegments=8,consumedToolCalls=1,noProgressSegments=2,activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-1),ledger=listOf(write))
        val source=GoalRunEvidenceSource(spent.runId,spent.threadId,spent.projectId,"completed",context(spent,GoalSegmentBinding.MAX_DURATION_MS))
        assertTrue(runCatching {goalEntryBudget(goal(listOf("first","run-a")),listOf(source),started+100)}.isFailure) // Without an owner action, still spent.
        val renewed=goalEntryBudget(goal(listOf("first","run-a")),listOf(source),started+100,renewBudget=true)
        assertEquals(1,renewed.totalSegments);assertEquals(0,renewed.noProgressSegments);assertEquals(0L,renewed.activeElapsedMs)
        assertEquals(1,renewed.budgetWindow);assertEquals(10L,renewed.originalGoalVersion)
        // The new window's chain carries the whole ledger; an exact earlier write is still blocked.
        val ledger=completedGoalLedger(spent)
        val linked=goal(listOf("first","run-a","run-n"))
        val window=segment(linked).copy(rootRunId="run-n",consumedToolCalls=ledger.size,budgetWindow=1,budgetBaseToolCalls=ledger.size)
        val next=QueuedChatRequest("run-n","thread","branch","project","QA request","cloud-model",false,emptyList(),"user-n",workspaceBinding="workspace",
            goal=linked,capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,goalSegment=window,goalLedger=ledger)
        val task=TaskGoal("Save the QA report",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(next,task,ToolCall("duplicate","save_note",args),emptyList())}.isFailure)
        assertTrue(decide(next.copy(previousSteps=listOf(step("fresh")))) is GoalSegmentDecision.Continue)
    }
    @Test fun anUncertainOrHandedOffEffectAlsoRefusesARenewedWindow() {
        val unverified=request(listOf(HarnessStep("save_note",buildJsonObject {put("content","QA")},StepEvidence("save_note",VerificationState.UNVERIFIED,"Timed out"))))
        val prepared=request(listOf(HarnessStep("prepare_sms",buildJsonObject {put("phoneNumber","01000000000");put("message","QA")},StepEvidence("prepare_sms",VerificationState.PREPARED,"Prepared only"))))
        for(parent in listOf(unverified,prepared)) {
            val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
            assertTrue(runCatching {collectGoalLedger(goal(),listOf(source),"workspace","scope",renewBudget=true)}.isFailure)
        }
    }
    @Test fun aRenewedWindowFreesSpaceOnlyByDroppingOldReadOnlyEntries() {
        val writes=(0 until 14).map {GoalLedgerEntry("w-${it/18}",it%18,HarnessStep("save_note",buildJsonObject {put("content","QA $it")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved")))}
        val reads=(0 until 130).map {GoalLedgerEntry("r-${it/18}",it%18,step("read-$it"))}
        val pruned=goalLedgerForRenewedWindow(reads.take(65)+writes+reads.drop(65))
        assertEquals(GoalSegmentBinding.MAX_TOOL_CALLS-WorkSegments.STEP_BUDGET,pruned.size)
        assertEquals(writes.map {it.json()},pruned.filter {it.step.tool=="save_note"}.map {it.json()})
        assertEquals(reads.takeLast(112).map {it.json()},pruned.filter {it.step.tool=="search_notes"}.map {it.json()}) // Oldest reads go first.
        val small=reads.take(10)+writes
        assertEquals(small,goalLedgerForRenewedWindow(small))
        val onlyWrites=(0 until 144).map {GoalLedgerEntry("m-${it/18}",it%18,HarnessStep("save_note",buildJsonObject {put("content","M $it")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved")))}
        assertEquals(GoalAutopilotPauseReason.TOOL_BUDGET,(runCatching {goalLedgerForRenewedWindow(onlyWrites)}.exceptionOrNull() as GoalAutopilotPaused).reason)
    }
    @Test fun windowToolBudgetCountsOnlyCallsAfterTheWindowStartedAndTheWindowCountIsBounded() {
        val history=(0 until 120).map {GoalLedgerEntry("past-${it/18}",it%18,step("past-$it"))}
        val windowed=segment().copy(consumedToolCalls=120,budgetWindow=3,budgetBaseToolCalls=120)
        assertTrue(decide(request((0 until 18).map {step("new-$it")},windowed,history)) is GoalSegmentDecision.Continue)
        val parent=request(binding=segment().copy(budgetWindow=GoalSegmentBinding.MAX_BUDGET_WINDOWS))
        val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
        assertEquals(GoalAutopilotPauseReason.SEGMENT_BUDGET,(runCatching {goalEntryBudget(goal(),listOf(source),started+100,renewBudget=true)}.exceptionOrNull() as GoalAutopilotPaused).reason)
        assertTrue(runCatching {segment().copy(budgetBaseToolCalls=1)}.isFailure)
    }
    @Test fun steeringAndAResumeInsideAGoalSegmentCannotRenewTheOriginalGoalBudget() {
        val parent=request(binding=segment().copy(totalSegments=7))
        val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
        val budget=goalEntryBudget(goal(),listOf(source),started+10*GoalSegmentBinding.MAX_DURATION_MS)
        assertEquals(started,budget.startedAtMs);assertEquals(8,budget.totalSegments);assertEquals(10L,budget.originalGoalVersion)
        assertEquals(100L,budget.activeElapsedMs)
        val eighth=source.copy(contextJson=context(parent.copy(goalSegment=parent.goalSegment!!.copy(totalSegments=8)),100))
        val segmentError=runCatching {goalEntryBudget(goal(),listOf(eighth),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.SEGMENT_BUDGET,segmentError.reason)
        val spent=source.copy(contextJson=context(parent,GoalSegmentBinding.MAX_DURATION_MS))
        val timeError=runCatching {goalEntryBudget(goal(),listOf(spent),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,timeError.reason)
        val unrecorded=runCatching {goalEntryBudget(goal(),listOf(source.copy(contextJson=parent.toJson())),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,unrecorded.reason)
    }
    @Test fun liveCurrentCallsCannotPassTheCumulative144BoundaryOfAnImmutableQueuedRequest() {
        val history=(0 until 143).map {GoalLedgerEntry("past-${it/18}",it%18,step("past-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=143),history)
        val task=TaskGoal("Read QA",listOf("Read actual result"))
        val call=ToolCall("last","search_notes",buildJsonObject {put("query","last")})
        requireGoalLedgerAllowsCall(request,task,call,emptyList())
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,call,listOf(step("actual-144")))}.isFailure)
    }
    @Test fun eightSequentialSegmentsKeepAll144PositionsAndStopWithoutCompletingTheGoal() {
        var current=request()
        for(index in 1..8) {
            current=current.copy(previousSteps=(0 until 18).map {step("segment-$index-$it")})
            assertEquals(index*18,completedGoalLedger(current).size)
            val result=decide(current)
            if(index==8) {
                pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,result)
                assertEquals(8,current.goalSegment!!.totalSegments)
            } else {
                val next=result as GoalSegmentDecision.Continue
                val id="run-${index+1}"
                current=current.copy(runId=id,userMessageId="user-${index+1}",previousSteps=emptyList(),
                    goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),goalSegment=next.binding,goalLedger=next.ledger)
                assertEquals(current,QueuedChatRequest.fromJson(current.toJson()))
            }
        }
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
