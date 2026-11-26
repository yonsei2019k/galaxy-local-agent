package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.data.ChatAccessScope
import kotlinx.serialization.json.*

private val goalLaunchTools=setOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)
private val goalLaunchUuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

/** Only an exact phone-written start/resume receipt can close a cloud dispatch. */
internal fun verifiedGoalDispatchChild(steps: List<HarnessStep>): String? =
    steps.mapNotNull { step ->
        step.evidence.continuationId?.takeIf { step.tool in goalLaunchTools &&
            step.evidence.state==VerificationState.VERIFIED && !step.evidence.effectNotApplied &&
            it.startsWith("goal-run:") }?.removePrefix("goal-run:")?.takeIf(goalLaunchUuid::matches)
    }.distinct().singleOrNull()

/** Immutable host continuation chain, stored only in the encrypted queue context. */
data class GoalSegmentBinding(
    val goalId:String,val originalGoalVersion:Long,val workDigest:String,val scopeKey:String,
    val scopeRevision:Long,val useOwnerMemory:Boolean,val memoryEnabled:Boolean,
    val rootRunId:String,val parentRunId:String?,val parentCheckpointDigest:String?,val segmentIndex:Int,
    val startedAtMs:Long,val consumedToolCalls:Int,val noProgressSegments:Int,val totalSegments:Int=segmentIndex,
    val phaseStartSegments:Int=0,val phaseStartCalls:Int=0,
) {
    init {
        require(listOf(goalId,scopeKey,rootRunId).all {it.isNotBlank()&&it.length<=256&&it.none {c->c.code<32}})
        require(originalGoalVersion>=0&&scopeRevision>=0&&workDigest.matches(Regex("[a-f0-9]{64}")))
        require(segmentIndex in 1..MAX_SEGMENTS&&consumedToolCalls in 0..MAX_TOOL_CALLS&&noProgressSegments in 0..MAX_NO_PROGRESS)
        require(totalSegments in segmentIndex..MAX_SEGMENTS)
        require(phaseStartSegments in 0 until totalSegments && phaseStartCalls in 0..consumedToolCalls)
        require(startedAtMs>=0&&startedAtMs<=Long.MAX_VALUE-MAX_DURATION_MS)
        require((segmentIndex==1)==(parentRunId==null&&parentCheckpointDigest==null))
        if(segmentIndex>1)require(parentRunId!=null&&parentRunId.isNotBlank()&&parentRunId.length<=100&&
            parentRunId.none {it.code<32}&&parentCheckpointDigest?.matches(Regex("[a-f0-9]{64}"))==true)
    }
    internal fun json():JsonObject=buildJsonObject {
        put("version",1);put("goalId",goalId);put("originalGoalVersion",originalGoalVersion);put("workDigest",workDigest)
        put("scopeKey",scopeKey);put("scopeRevision",scopeRevision);put("useOwnerMemory",useOwnerMemory);put("memoryEnabled",memoryEnabled)
        put("rootRunId",rootRunId);put("parentRunId",parentRunId);put("parentCheckpointDigest",parentCheckpointDigest)
        put("segmentIndex",segmentIndex);put("startedAtMs",startedAtMs);put("consumedToolCalls",consumedToolCalls);put("noProgressSegments",noProgressSegments);put("totalSegments",totalSegments)
        if(phaseStartSegments!=0||phaseStartCalls!=0){put("phaseStartSegments",phaseStartSegments);put("phaseStartCalls",phaseStartCalls)}
    }
    companion object {
        const val PHASE_SEGMENTS=8
        const val PHASE_TOOL_CALLS=144
        const val MAX_SEGMENTS=32
        const val MAX_TOOL_CALLS=576
        const val MAX_DURATION_MS=30*60*1000L
        const val MAX_NO_PROGRESS=3
        internal fun parse(o:JsonObject):GoalSegmentBinding {
            require(o.keys-setOf("phaseStartSegments","phaseStartCalls")==setOf("version","goalId","originalGoalVersion","workDigest","scopeKey","scopeRevision","useOwnerMemory","memoryEnabled","rootRunId","parentRunId","parentCheckpointDigest","segmentIndex","startedAtMs","consumedToolCalls","noProgressSegments","totalSegments"))
            fun s(k:String)=o.getValue(k).jsonPrimitive.let {require(it.isString);it.content}
            fun l(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.long}
            fun b(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.boolean}
            fun optional(k:String)=o.getValue(k).takeUnless {it is JsonNull}?.jsonPrimitive?.let {require(it.isString);it.content}
            require(l("version")==1L && ("phaseStartSegments" in o)==("phaseStartCalls" in o))
            val index=l("segmentIndex");val count=l("consumedToolCalls");val progress=l("noProgressSegments");val total=l("totalSegments")
            require(index in 1..MAX_SEGMENTS.toLong()&&count in 0..MAX_TOOL_CALLS.toLong()&&progress in 0..MAX_NO_PROGRESS.toLong()&&total in index..MAX_SEGMENTS.toLong())
            return GoalSegmentBinding(s("goalId"),l("originalGoalVersion"),s("workDigest"),s("scopeKey"),l("scopeRevision"),b("useOwnerMemory"),b("memoryEnabled"),
                s("rootRunId"),optional("parentRunId"),optional("parentCheckpointDigest"),index.toInt(),l("startedAtMs"),count.toInt(),progress.toInt(),total.toInt(),
                if("phaseStartSegments" in o)l("phaseStartSegments").also { require(it in 0 until total) }.toInt() else 0,
                if("phaseStartCalls" in o)l("phaseStartCalls").also { require(it in 0..count) }.toInt() else 0)
        }
    }
}

internal enum class GoalAutopilotPauseReason(val code:String) {
    LEGACY_CONTEXT("goal_autopilot_legacy_context"), STOPPED("goal_autopilot_stopped"),
    UNKNOWN_EFFECT("goal_autopilot_unknown_effect"), FAILED_STEP("goal_autopilot_failed_step"),
    OWNER_HANDOFF("goal_autopilot_owner_handoff"), REQUIRED_READBACK("goal_autopilot_required_readback"),
    SEGMENT_BUDGET("goal_autopilot_segment_budget"), TOOL_BUDGET("goal_autopilot_tool_budget"),
    TIME_BUDGET("goal_autopilot_time_budget"), NO_PROGRESS("goal_autopilot_no_progress"),
    INVALID_CHECKPOINT("goal_autopilot_invalid_checkpoint"),
    ;
    val description:String get()=when(this) {
        SEGMENT_BUDGET->"이번 목표에서 이어서 실행할 구간 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        TOOL_BUDGET->"이번 목표에서 도구 실행 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        TIME_BUDGET->"이번 구간의 활동 시간 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        NO_PROGRESS->"3개 실행 구간에서 새로운 검증 결과가 없어 일시 정지했습니다. 실패 기록과 기존 결과는 보존했습니다."
        UNKNOWN_EFFECT->"이전 실행의 실제 효과가 불확실해 일시 정지했습니다. 대상 상태를 확인하기 전에 변경을 반복하지 않습니다."
        FAILED_STEP->"이전 변경 작업의 실패 결과가 확인되지 않아 일시 정지했습니다. 기존 실패 기록을 유지합니다."
        OWNER_HANDOFF->"추가 지시나 외부 앱의 실제 결과 확인이 필요해 일시 정지했습니다."
        REQUIRED_READBACK->"이전 변경 결과의 실제 재조회가 확인되지 않아 일시 정지했습니다."
        LEGACY_CONTEXT->"기존 요청에는 자동 연속 실행 연결이 없어 일시 정지했습니다. 이전 권한을 확대하지 않았습니다."
        INVALID_CHECKPOINT->"저장된 실제 실행 근거를 확인하지 못해 일시 정지했습니다. 근거를 버리고 다시 실행하지 않습니다."
        STOPPED->"목표 실행이 중지되었거나 원래 실행 조건을 유지하지 않아 일시 정지했습니다."
    }
}
internal sealed interface GoalSegmentDecision {
    data class Continue(val binding:GoalSegmentBinding,val ledger:List<GoalLedgerEntry>,val readback:GoalReadbackSeed?=null):GoalSegmentDecision
    data object Complete:GoalSegmentDecision
    data class Pause(val reason:GoalAutopilotPauseReason):GoalSegmentDecision
}
internal class GoalAutopilotPaused(val reason:GoalAutopilotPauseReason):IllegalStateException(reason.description)
internal data class GoalEntryBudget(val originalGoalVersion:Long,val startedAtMs:Long,val totalSegments:Int,val noProgressSegments:Int,
    val phaseStartSegments:Int=0,val phaseStartCalls:Int=0)

/** Explicit START/RESUME/steer is not a budget-renewal receipt. Count every earlier goal run. */
internal fun goalEntryBudget(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,nowMs:Long):GoalEntryBudget {
    if(sources.isEmpty())return GoalEntryBudget(goal.updatedAt,nowMs,1,0)
    val requests=sources.map {QueuedChatRequest.fromJson(it.contextJson)}
    val consumed=maxOf(sources.size,requests.maxOf {it.goalSegment?.totalSegments?:1})
    if(consumed>=GoalSegmentBinding.MAX_SEGMENTS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.SEGMENT_BUDGET)
    val latest=requests.last()
    val phase=latest.goalSegment
    if(consumed-(phase?.phaseStartSegments?:0)>=GoalSegmentBinding.PHASE_SEGMENTS)
        throw GoalAutopilotPaused(GoalAutopilotPauseReason.SEGMENT_BUDGET)
    val started=phase?.startedAtMs?:requests.minOf {it.queueOrder}
    if(started<=0||nowMs<started||nowMs-started>=GoalSegmentBinding.MAX_DURATION_MS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.TIME_BUDGET)
    val noProgress=phase?.let {if(hasNewGoalProgress(latest,goal)) 0 else it.noProgressSegments+1}?:0
    if(noProgress>=GoalSegmentBinding.MAX_NO_PROGRESS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.NO_PROGRESS)
    val original=requests.minBy {it.goalSegment?.startedAtMs?:it.queueOrder}.let {it.goalSegment?.originalGoalVersion?:checkNotNull(it.goal).updatedAt}
    return GoalEntryBudget(original,started,consumed+1,noProgress,phase?.phaseStartSegments?:0,phase?.phaseStartCalls?:0)
}

private fun hasNewGoalProgress(request:QueuedChatRequest,goal:GoalDefinition):Boolean {
    val seen=request.goalLedger.mapNotNull {goalProgressKey(it.step)}.toSet()
    return request.previousSteps.drop(request.goalReadbackSeedCount).mapNotNull(::goalProgressKey).any {it !in seen} ||
        goal.criteria.any {now->now.status==GoalCriterionStatus.VERIFIED &&
            checkNotNull(request.goal).criteria.none {it.id==now.id&&it.status==GoalCriterionStatus.VERIFIED}}
}

internal val goalRecoverablePauseCodes=setOf("reasoning_budget_exhausted","final_synthesis_needs_action","partial_verified_browser_reads",
    "partial_verified_sources","partial_verified_tool_budget","partial_recoverable_provider","goal_readback_retry",
    GoalAutopilotPauseReason.TOOL_BUDGET.code,GoalAutopilotPauseReason.TIME_BUDGET.code)

internal fun goalMayResumeFrom(row:ChatRunRecord):Boolean =
    row.status=="completed" || row.status=="interrupted"&&row.errorCode=="process_restarted" ||
        row.status=="paused"&&row.errorCode in goalRecoverablePauseCodes

/** Pure host decision. unknownEffects must come from the real runtime journal, never model prose. */
internal fun goalSegmentDecision(parent:ChatRunRecord,goal:GoalDefinition,scopeKey:String,nowMs:Long,unknownEffects:Boolean,
    useNativeBudget:Boolean=false):GoalSegmentDecision {
    val request=try {QueuedChatRequest.fromJson(parent.contextJson)}catch(_:Exception) {
        return GoalSegmentDecision.Pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
    }
    val segment=request.goalSegment?:return GoalSegmentDecision.Pause(GoalAutopilotPauseReason.LEGACY_CONTEXT)
    requireGoalParentRow(request,parent)
    check(request.goal!=null&&sameGoalWork(request.goal,goal)&&parent.id in goal.linkedRunIds&&request.goal.linkedRunIds.all {it in goal.linkedRunIds}&&
        segment.scopeKey==scopeKey&&segment.workDigest==goalWorkDigest(goal)) {"목표 구간의 원래 작업·자료 범위가 변경되었습니다."}
    fun pause(reason:GoalAutopilotPauseReason)=GoalSegmentDecision.Pause(reason)
    if(goal.status!=GoalStatus.ACTIVE||!request.allowTools||request.mode !in setOf("act","debug"))return pause(GoalAutopilotPauseReason.STOPPED)
    if(request.continuation!=null)return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    if(unknownEffects)return pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT)
    val ledger=completedGoalLedger(request)
    if(ledger.any {goalUnverifiedEffect(it.step)})return pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT)
    if(ledger.any {goalUncertainFailure(it.step)})return pause(GoalAutopilotPauseReason.FAILED_STEP)
    if(ledger.any {PreparedHandoff.pendingEffect(it.step)})return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    if(!goalMayResumeFrom(parent)&&parent.status!="paused")return pause(GoalAutopilotPauseReason.STOPPED)
    var completedMedia=false
    val clockShift = if (parent.status=="paused" && parent.errorCode=="waiting_goal_condition") {
        val wake = Json.parseToJsonElement(parent.contextJson).jsonObject["hostGoalWake"] as? JsonObject
            ?: return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
        val parkedAt=wake["parkedAt"]?.jsonPrimitive?.longOrNull ?: return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        val wokeAt=wake["wokeAt"]?.jsonPrimitive?.longOrNull ?: return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        val finalStep=request.previousSteps.lastOrNull()
        val authReady=wake["event"] in setOf(JsonPrimitive(WakeEvent.OWNER_AUTH_DONE.name),JsonPrimitive(WakeEvent.TIME_REACHED.name)) &&
            finalStep?.tool in setOf("read_app_browser_page","open_app_browser_page") &&
            finalStep?.evidence?.effectNotApplied==true &&
            finalStep.evidence.summary==com.user.localagent.compute.BrowserAgentTools.PHONE_AUTH_REQUIRED
        val waitReady=finalStep?.tool==GoalWaitToolCatalog.WAIT && finalStep.evidence.state==VerificationState.VERIFIED
        if(parkedAt !in segment.startedAtMs..wokeAt || wokeAt>nowMs ||
            wokeAt-parkedAt>GoalWaitPolicy.MAX_DAYS*86_400_000L ||
            (!authReady && !waitReady))
            return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        if(waitReady && wake["event"]==JsonPrimitive(WakeEvent.MEDIA_ENDED.name)) {
            val condition=finalStep?.arguments?.get("condition") as? JsonObject
            val item=condition?.get("ledgerItemId")?.jsonPrimitive?.contentOrNull
            completedMedia=item!=null&&condition?.get("kind")==JsonPrimitive("media_end")&&request.goalLedger.none {entry->
                entry.step.tool==GoalWaitToolCatalog.WAIT&&
                    (entry.step.arguments["condition"] as? JsonObject)?.get("ledgerItemId")==JsonPrimitive(item)
            }
        }
        wokeAt-parkedAt
    } else {
        if(!goalMayResumeFrom(parent)&&parent.errorCode!="execution_not_verified")return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
        0L
    }
    val readbackContext=RequiredReadbackObligations.Context(request.runId,request.threadId,request.projectId,scopeKey,request.workspaceBinding)
    val assessment=RequiredReadbackObligations.assess(request.readbackObligations,readbackContext,request.previousSteps,
        request.previousSteps.size,ToolRegistry.definitions.keys,1,1)
    val recovery=if(assessment.pending.isEmpty())null else GoalReadbackSeed.from(request,assessment)
    if(assessment.blockers.isNotEmpty()||assessment.pending.isNotEmpty()&&recovery==null)
        return pause(GoalAutopilotPauseReason.REQUIRED_READBACK)
    if(goalCompletionProofsMatch(request,goal)&&recovery==null)return GoalSegmentDecision.Complete
    if(parent.errorCode=="execution_not_verified"&&recovery==null)return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    val progressed=completedMedia||recovery==null&&hasNewGoalProgress(request,goal)
    val noProgress=if(progressed)0 else segment.noProgressSegments+1
    val budget=segment.copy(startedAtMs=segment.startedAtMs+clockShift)
    if(segment.totalSegments>=GoalSegmentBinding.MAX_SEGMENTS)return pause(GoalAutopilotPauseReason.SEGMENT_BUDGET)
    if(ledger.size>=GoalSegmentBinding.MAX_TOOL_CALLS||!goalLedgerFits(ledger))return pause(GoalAutopilotPauseReason.TOOL_BUDGET)
    val limit=goalBudgetStop(budget,ledger.size,nowMs,noProgress,useNativeBudget)
    val observedMilestones=request.goalLedger.mapNotNull {goalMilestoneKey(it.step)}.toSet()
    val earnedPhase=completedMedia || goal.criteria.any {criterion->
        criterion.status==GoalCriterionStatus.VERIFIED&&request.goal.criteria.none {it.id==criterion.id&&it.status==GoalCriterionStatus.VERIFIED}
    } || request.previousSteps.drop(request.goalReadbackSeedCount).mapNotNull(::goalMilestoneKey).any {it !in observedMilestones}
    val renew=limit in setOf(GoalAutopilotPauseReason.SEGMENT_BUDGET,GoalAutopilotPauseReason.TOOL_BUDGET,GoalAutopilotPauseReason.TIME_BUDGET) &&
        earnedPhase && recovery==null && nowMs>=budget.startedAtMs
    if(limit!=null&&!renew)return pause(limit)
    val nextStart=if(renew)maxOf(budget.startedAtMs,parent.finishedAt?:parent.updatedAt) else budget.startedAtMs
    if(nextStart>nowMs)return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
    return GoalSegmentDecision.Continue(budget.copy(parentRunId=request.runId,parentCheckpointDigest=goalDigest(parent.contextJson),
        segmentIndex=segment.segmentIndex+1,totalSegments=segment.totalSegments+1,consumedToolCalls=ledger.size,noProgressSegments=noProgress,
        startedAtMs=nextStart,phaseStartSegments=if(renew)segment.totalSegments else segment.phaseStartSegments,
        phaseStartCalls=if(renew)ledger.size else segment.phaseStartCalls),ledger,recovery)
}

internal fun goalToolRoom(segment:GoalSegmentBinding,steps:Int):Int =
    minOf(GoalSegmentBinding.MAX_TOOL_CALLS-segment.consumedToolCalls,
        GoalSegmentBinding.PHASE_TOOL_CALLS-(segment.consumedToolCalls-segment.phaseStartCalls))-steps

internal fun goalBudgetStop(segment:GoalSegmentBinding,toolCount:Int,nowMs:Long,noProgress:Int,useNative:Boolean):GoalAutopilotPauseReason? {
    if(useNative){
        return when(uniffi.ziphap_android.nativeGoalBudgetStop((segment.totalSegments-segment.phaseStartSegments).toUInt(),(toolCount-segment.phaseStartCalls).toUInt(),
            segment.startedAtMs.toULong(),nowMs.toULong(),noProgress.toUInt())){
            uniffi.ziphap_android.NativeGoalBudgetStop.SEGMENTS->GoalAutopilotPauseReason.SEGMENT_BUDGET
            uniffi.ziphap_android.NativeGoalBudgetStop.TOOLS->GoalAutopilotPauseReason.TOOL_BUDGET
            uniffi.ziphap_android.NativeGoalBudgetStop.TIME->GoalAutopilotPauseReason.TIME_BUDGET
            uniffi.ziphap_android.NativeGoalBudgetStop.NO_PROGRESS->GoalAutopilotPauseReason.NO_PROGRESS
            null->null
        }
    }
    // Reference policy for JVM tests without the Android native library.
    return when {
        segment.totalSegments-segment.phaseStartSegments>=GoalSegmentBinding.PHASE_SEGMENTS->GoalAutopilotPauseReason.SEGMENT_BUDGET
        toolCount-segment.phaseStartCalls>=GoalSegmentBinding.PHASE_TOOL_CALLS->GoalAutopilotPauseReason.TOOL_BUDGET
        nowMs<segment.startedAtMs||nowMs-segment.startedAtMs>=GoalSegmentBinding.MAX_DURATION_MS->GoalAutopilotPauseReason.TIME_BUDGET
        noProgress>=GoalSegmentBinding.MAX_NO_PROGRESS->GoalAutopilotPauseReason.NO_PROGRESS
        else->null
    }
}

private fun requireGoalParentRow(request:QueuedChatRequest,row:ChatRunRecord) {
    check(request.runId==row.id&&request.threadId==row.threadId&&request.projectId==row.projectId&&request.branchId==row.branchId&&
        request.userMessageId==row.userMessageId&&request.modelId==row.modelId&&request.thinking==row.thinking) {"목표의 실제 원래 실행 기록과 불변 문맥이 다릅니다."}
}

internal fun requireSameGoalAuthority(original:QueuedChatRequest,next:QueuedChatRequest) {
    check(original.threadId==next.threadId&&original.projectId==next.projectId&&original.branchId==next.branchId&&original.modelId==next.modelId&&
        original.thinking==next.thinking&&(!next.allowTools||original.allowTools)&&original.mode==next.mode&&original.workspaceBinding==next.workspaceBinding&&
        original.pcDataBinding==next.pcDataBinding&&original.capturedPcRevision==next.capturedPcRevision&&
        original.capturedPcTextDefault==next.capturedPcTextDefault&&original.allowCloudText==next.allowCloudText&&
        original.allowExternalAgent==next.allowExternalAgent&&original.externalAgentRevision==next.externalAgentRevision&&
        original.kakaoReadBinding==next.kakaoReadBinding&&original.pcImageRecoveryAllowed==next.pcImageRecoveryAllowed) {"이전 목표 실행의 권한·모델·자료 연결을 확대하거나 변경하지 않았습니다."}
    original.goalSegment?.let {before->next.goalSegment?.let {after->
        check(before.scopeKey==after.scopeKey&&before.scopeRevision==after.scopeRevision&&before.useOwnerMemory==after.useOwnerMemory&&before.memoryEnabled==after.memoryEnabled) {
            "기존 목표의 자료 접근 권한을 새 실행에서 확대하거나 갱신하지 않았습니다."
        }
    }}
}

internal fun requireGoalSegmentScope(request:QueuedChatRequest,access:ChatAccessScope) {
    val segment=request.goalSegment?:return
    check(request.threadId==access.threadId&&request.projectId==access.projectId&&segment.scopeKey==access.scopeKey&&
        segment.scopeRevision==access.threadRevision&&segment.useOwnerMemory==access.useOwnerMemory&&segment.memoryEnabled==access.memoryEnabled) {
        "목표 구간의 원래 자료 접근 범위·버전이 변경되었습니다."
    }
}

/** A durable control receipt binds one child run; it is never business-goal completion evidence. */
internal fun goalToolStepEvidence(call:ToolCall,result:ToolResult):StepEvidence {
    if(call.name !in goalLaunchTools||!result.success||result.verification!=VerificationState.VERIFIED)
        return AnalysisCompletionEvidence.fromResult(call,result)
    require(result.id==call.id&&result.content.length<=32_000){"목표 실행 영수증의 호출 연결이 올바르지 않습니다."}
    val payload=Json.parseToJsonElement(result.content).jsonObject
    fun trueBoolean(key:String):Boolean=payload[key]?.let {it is JsonPrimitive&&!it.isString&&it.booleanOrNull==true}==true
    require(trueBoolean("scopeBound")&&trueBoolean("queuedOneBoundedRun")){"범위가 지정된 실제 목표 실행 예약을 확인하지 못했습니다."}
    val child=payload["queuedRunId"]?.let {it as? JsonPrimitive}?.takeIf {it.isString}?.content
    require(child!=null&&goalLaunchUuid.matches(child)){"예약된 목표 실행의 실제 식별자가 올바르지 않습니다."}
    require(payload["goalId"]?.jsonPrimitive?.content==call.arguments["goalId"]?.jsonPrimitive?.content){"목표 실행 영수증의 목표가 요청과 다릅니다."}
    return StepEvidence(call.name,result.verification,result.verificationSummary,"goal-run:$child")
}

/** Pure launch fence. Caller loads the live guarded goal and actual parent row before invoking it. */
internal fun requireGoalLaunchBinding(request:QueuedChatRequest,current:GoalDefinition?,parent:ChatRunRecord?,callerCancelled:Boolean) =
    requireGoalLaunchBinding(request,current,parent,callerCancelled,System.currentTimeMillis(),useNativeBudget=true)

internal fun requireGoalLaunchBinding(request:QueuedChatRequest,current:GoalDefinition?,parent:ChatRunRecord?,callerCancelled:Boolean,nowMs:Long,
    useNativeBudget:Boolean=false) {
    val captured=request.goal?:return
    check(!callerCancelled){"목표 시작을 요청한 작업이 취소되어 새 실행을 시작하지 않았습니다."}
    val goal=checkNotNull(current){"연결된 목표가 삭제되어 실행을 시작하지 않았습니다."}
    check(goal.status==GoalStatus.ACTIVE&&sameGoalWork(captured,goal)&&request.runId in goal.linkedRunIds){"목표의 작업·상태·실행 연결이 변경되어 시작하지 않았습니다."}
    request.goalSegment?.takeIf {it.segmentIndex>1}?.let {segment->
        check(request.parentGoalStartRunId==null){"호스트 구간 연결과 도구 시작 영수증을 혼용하지 않았습니다."}
        val row=checkNotNull(parent){"다음 목표 구간의 실제 부모 실행 기록이 없습니다."}
        check(row.id==segment.parentRunId&&goalDigest(row.contextJson)==segment.parentCheckpointDigest){"부모 목표 실행의 최종 체크포인트가 변경되었습니다."}
        val original=QueuedChatRequest.fromJson(row.contextJson)
        requireSameGoalAuthority(original,request)
        val decision=goalSegmentDecision(row,goal,segment.scopeKey,nowMs,unknownEffects=false,useNativeBudget=useNativeBudget)
        check(decision is GoalSegmentDecision.Continue&&decision.binding==segment&&
            decision.ledger==request.goalLedger&&request.goalReadbackSeedCount==(decision.readback?.steps?.size?:0)&&
            request.readbackObligations==decision.readback?.forRun(request.runId)&&
            request.previousSteps.size==(decision.readback?.steps?.size?:0)&&
            request.previousSteps.zip(decision.readback?.steps.orEmpty()).all {(a,b)->a.tool==b.tool&&a.arguments==b.arguments&&a.evidence==b.evidence}) {"실제 부모 체크포인트에서 이 다음 구간을 재검증하지 못했습니다."}
        return
    }
    val parentId=request.parentGoalStartRunId?:return // Existing explicit GUI submissions retain their old captured mode.
    val row=checkNotNull(parent){"목표 시작 요청의 실제 실행 기록이 없습니다."}
    check(row.id==parentId&&row.id!=request.runId&&row.threadId==request.threadId&&row.projectId==request.projectId&&
        row.branchId==request.branchId&&row.modelId==request.modelId&&row.thinking==request.thinking&&
        row.status in setOf("completed","failed")){"목표 시작 요청이 취소·중단되었거나 원래 실행 범위가 다릅니다."}
    val original=QueuedChatRequest.fromJson(row.contextJson)
    check(original.runId==row.id&&original.threadId==row.threadId&&original.projectId==row.projectId&&original.branchId==row.branchId&&
        original.userMessageId==row.userMessageId&&original.modelId==row.modelId&&original.thinking==row.thinking&&
        original.allowTools&&original.mode in setOf("act","debug")&&original.workspaceBinding==request.workspaceBinding){"목표 시작 요청의 불변 문맥과 실행 범위가 일치하지 않습니다."}
    requireSameGoalAuthority(original,request)
    check(original.previousSteps.any {step->
        step.tool in goalLaunchTools&&step.evidence.state==VerificationState.VERIFIED&&
            step.arguments["goalId"]?.jsonPrimitive?.content==goal.id&&step.evidence.continuationId=="goal-run:${request.runId}"
    }){"이 목표 실행 한 건에 연결된 실제 시작·재개 영수증이 없습니다."}
}
