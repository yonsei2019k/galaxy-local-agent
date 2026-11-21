package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.data.ChatAccessScope
import kotlinx.serialization.json.*

private val goalLaunchTools=setOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)
private val goalLaunchUuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

/** Immutable host continuation chain, stored only in the encrypted queue context. */
data class GoalSegmentBinding(
    val goalId:String,val originalGoalVersion:Long,val workDigest:String,val scopeKey:String,
    val scopeRevision:Long,val useOwnerMemory:Boolean,val memoryEnabled:Boolean,
    val rootRunId:String,val parentRunId:String?,val parentCheckpointDigest:String?,val segmentIndex:Int,
    val startedAtMs:Long,val consumedToolCalls:Int,val noProgressSegments:Int,val totalSegments:Int=segmentIndex,
) {
    init {
        require(listOf(goalId,scopeKey,rootRunId).all {it.isNotBlank()&&it.length<=256&&it.none {c->c.code<32}})
        require(originalGoalVersion>=0&&scopeRevision>=0&&workDigest.matches(Regex("[a-f0-9]{64}")))
        require(segmentIndex in 1..MAX_SEGMENTS&&consumedToolCalls in 0..MAX_TOOL_CALLS&&noProgressSegments in 0..MAX_NO_PROGRESS)
        require(totalSegments in segmentIndex..MAX_SEGMENTS)
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
    }
    companion object {
        const val MAX_SEGMENTS=8
        const val MAX_TOOL_CALLS=144
        const val MAX_DURATION_MS=30*60*1000L
        const val MAX_NO_PROGRESS=3
        internal fun parse(o:JsonObject):GoalSegmentBinding {
            require(o.keys==setOf("version","goalId","originalGoalVersion","workDigest","scopeKey","scopeRevision","useOwnerMemory","memoryEnabled","rootRunId","parentRunId","parentCheckpointDigest","segmentIndex","startedAtMs","consumedToolCalls","noProgressSegments","totalSegments"))
            fun s(k:String)=o.getValue(k).jsonPrimitive.let {require(it.isString);it.content}
            fun l(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.long}
            fun b(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.boolean}
            fun optional(k:String)=o.getValue(k).takeUnless {it is JsonNull}?.jsonPrimitive?.let {require(it.isString);it.content}
            require(l("version")==1L)
            val index=l("segmentIndex");val count=l("consumedToolCalls");val progress=l("noProgressSegments");val total=l("totalSegments")
            require(index in 1..MAX_SEGMENTS.toLong()&&count in 0..MAX_TOOL_CALLS.toLong()&&progress in 0..MAX_NO_PROGRESS.toLong()&&total in index..MAX_SEGMENTS.toLong())
            return GoalSegmentBinding(s("goalId"),l("originalGoalVersion"),s("workDigest"),s("scopeKey"),l("scopeRevision"),b("useOwnerMemory"),b("memoryEnabled"),
                s("rootRunId"),optional("parentRunId"),optional("parentCheckpointDigest"),index.toInt(),l("startedAtMs"),count.toInt(),progress.toInt(),total.toInt())
        }
    }
}

internal enum class GoalAutopilotPauseReason(val code:String) {
    LEGACY_CONTEXT("goal_autopilot_legacy_context"), STOPPED("goal_autopilot_stopped"),
    UNKNOWN_EFFECT("goal_autopilot_unknown_effect"), FAILED_STEP("goal_autopilot_failed_step"),
    OWNER_HANDOFF("goal_autopilot_owner_handoff"), REQUIRED_READBACK("goal_autopilot_required_readback"),
    SEGMENT_BUDGET("goal_autopilot_segment_budget"), TOOL_BUDGET("goal_autopilot_tool_budget"),
    TIME_BUDGET("goal_autopilot_time_budget"), NO_PROGRESS("goal_autopilot_no_progress"),
    CRITERIA_READY("goal_autopilot_criteria_ready"),
    INVALID_CHECKPOINT("goal_autopilot_invalid_checkpoint"),
    ;
    val description:String get()=when(this) {
        SEGMENT_BUDGET->"목표의 누적 실행 구간 한도 8개에 도달해 일시 정지했습니다. 확인된 결과를 보존했으며 전체 완료를 뜻하지 않습니다."
        TOOL_BUDGET->"목표의 누적 도구 실행 한도 144회에 도달해 일시 정지했습니다. 확인된 결과를 보존했으며 전체 완료를 뜻하지 않습니다."
        TIME_BUDGET->"목표의 최초 시작부터 30분 실행 한도에 도달해 일시 정지했습니다. 확인된 결과를 보존했으며 전체 완료를 뜻하지 않습니다."
        NO_PROGRESS->"3개 실행 구간에서 새로운 검증 결과가 없어 일시 정지했습니다. 실패 기록과 기존 결과는 보존했습니다."
        UNKNOWN_EFFECT->"이전 실행의 실제 효과가 불확실해 일시 정지했습니다. 대상 상태를 확인하기 전에 변경을 반복하지 않습니다."
        FAILED_STEP->"이전 변경 작업의 실패 결과가 확인되지 않아 일시 정지했습니다. 기존 실패 기록을 유지합니다."
        OWNER_HANDOFF->"추가 지시나 외부 앱의 실제 결과 확인이 필요해 일시 정지했습니다."
        REQUIRED_READBACK->"이전 변경 결과의 실제 재조회가 확인되지 않아 일시 정지했습니다."
        CRITERIA_READY->"완료 기준이 충족되어 추가 실행을 멈췄습니다. 목표의 최종 완료 확인은 별도입니다."
        LEGACY_CONTEXT->"기존 요청에는 자동 연속 실행 연결이 없어 일시 정지했습니다. 이전 권한을 확대하지 않았습니다."
        INVALID_CHECKPOINT->"저장된 실제 실행 근거를 확인하지 못해 일시 정지했습니다. 근거를 버리고 다시 실행하지 않습니다."
        STOPPED->"목표 실행이 중지되었거나 원래 실행 조건을 유지하지 않아 일시 정지했습니다."
    }
}
internal sealed interface GoalSegmentDecision {
    data class Continue(val binding:GoalSegmentBinding,val ledger:List<GoalLedgerEntry>):GoalSegmentDecision
    data class Pause(val reason:GoalAutopilotPauseReason):GoalSegmentDecision
}
internal class GoalAutopilotPaused(val reason:GoalAutopilotPauseReason):IllegalStateException(reason.description)
internal data class GoalEntryBudget(val originalGoalVersion:Long,val startedAtMs:Long,val totalSegments:Int,val noProgressSegments:Int)

/** Explicit START/RESUME/steer is not a budget-renewal receipt. Count every earlier goal run. */
internal fun goalEntryBudget(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,nowMs:Long):GoalEntryBudget {
    if(sources.isEmpty())return GoalEntryBudget(goal.updatedAt,nowMs,1,0)
    val requests=sources.map {QueuedChatRequest.fromJson(it.contextJson)}
    val consumed=maxOf(sources.size,requests.maxOf {it.goalSegment?.totalSegments?:1})
    if(consumed>=GoalSegmentBinding.MAX_SEGMENTS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.SEGMENT_BUDGET)
    val started=requests.minOf {it.goalSegment?.startedAtMs?:it.queueOrder}
    if(started<=0||nowMs<started||nowMs-started>=GoalSegmentBinding.MAX_DURATION_MS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.TIME_BUDGET)
    val latest=requests.last()
    val noProgress=latest.goalSegment?.let {goalNoProgressCount(latest,goal)}?:0
    if(noProgress>=GoalSegmentBinding.MAX_NO_PROGRESS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.NO_PROGRESS)
    val original=requests.minBy {it.goalSegment?.startedAtMs?:it.queueOrder}.let {it.goalSegment?.originalGoalVersion?:checkNotNull(it.goal).updatedAt}
    return GoalEntryBudget(original,started,consumed+1,noProgress)
}

private fun goalNoProgressCount(request:QueuedChatRequest,goal:GoalDefinition):Int {
    val segment=checkNotNull(request.goalSegment)
    if(segment.noProgressSegments>=GoalSegmentBinding.MAX_NO_PROGRESS)return segment.noProgressSegments
    val oldCalls=request.goalLedger.map {goalCallDigest(it.step)}.toSet()
    val progress=request.previousSteps.any {it.evidence.state==VerificationState.VERIFIED&&!it.evidence.effectNotApplied&&
        it.tool !in GoalToolCatalog.names&&it.tool !in SkillToolCatalog.names&&goalCallDigest(it) !in oldCalls}||
        goal.criteria.any {now->now.status==GoalCriterionStatus.VERIFIED&&checkNotNull(request.goal).criteria.none {it.id==now.id&&it.status==now.status&&it.evidence==now.evidence}}
    return if(progress)0 else segment.noProgressSegments+1
}

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
    if(ledger.any {it.step.evidence.state==VerificationState.UNVERIFIED})return pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT)
    if(ledger.any {goalUncertainFailure(it.step)})return pause(GoalAutopilotPauseReason.FAILED_STEP)
    if(ledger.any {PreparedHandoff.pendingEffect(it.step)})return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    if(parent.status !in setOf("completed","paused"))return pause(GoalAutopilotPauseReason.STOPPED)
    if(parent.status=="paused"&&parent.errorCode!="reasoning_budget_exhausted")return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    val readbackContext=RequiredReadbackObligations.Context(request.runId,request.threadId,request.projectId,scopeKey,request.workspaceBinding)
    val assessment=RequiredReadbackObligations.assess(request.readbackObligations,readbackContext,request.previousSteps,
        request.previousSteps.size,ToolRegistry.definitions.keys,1,1)
    if(assessment.pending.isNotEmpty()||assessment.blockers.isNotEmpty())return pause(GoalAutopilotPauseReason.REQUIRED_READBACK)
    if(goal.completionReady)return pause(GoalAutopilotPauseReason.CRITERIA_READY) // Host goal finalization remains separate.
    val noProgress=goalNoProgressCount(request,goal)
    goalBudgetStop(segment,ledger.size,nowMs,noProgress,useNativeBudget)?.let{return pause(it)}
    return GoalSegmentDecision.Continue(segment.copy(parentRunId=request.runId,parentCheckpointDigest=goalDigest(parent.contextJson),
        segmentIndex=segment.segmentIndex+1,totalSegments=segment.totalSegments+1,consumedToolCalls=ledger.size,noProgressSegments=noProgress),ledger)
}

internal fun goalBudgetStop(segment:GoalSegmentBinding,toolCount:Int,nowMs:Long,noProgress:Int,useNative:Boolean):GoalAutopilotPauseReason? {
    if(useNative){
        return when(uniffi.ziphap_android.nativeGoalBudgetStop(segment.totalSegments.toUInt(),toolCount.toUInt(),
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
        segment.totalSegments>=GoalSegmentBinding.MAX_SEGMENTS->GoalAutopilotPauseReason.SEGMENT_BUDGET
        toolCount>=GoalSegmentBinding.MAX_TOOL_CALLS->GoalAutopilotPauseReason.TOOL_BUDGET
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
            decision.ledger.map {it.json()}==request.goalLedger.map {it.json()}) {"실제 부모 체크포인트에서 이 다음 구간을 재검증하지 못했습니다."}
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
