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
    val phaseStartSegments:Int=0,val phaseStartCalls:Int=0,val artifactWrapUp:Boolean=false,
    val artifactAuthResumed:Boolean=false,val archivedCalls:Int=0,val archivedObservations:String="",
    /** Active time of all earlier segments, carried by the host; never derived from the wall clock of a later read. */
    val activeElapsedMs:Long=0,
    /** Owner-renewed budget window (0 = original). Only an owner action opens the next one; the ledger is never reset. */
    val budgetWindow:Int=0,
    /** Ledger size when this window opened; this window's tool budget counts only later calls. */
    val budgetBaseToolCalls:Int=0,
    /** Consecutive host retries after a resolved transient failure; a normal checkpoint resets it. */
    val transientRetries:Int=0,
) {
    init {
        require(listOf(goalId,scopeKey,rootRunId).all {it.isNotBlank()&&it.length<=256&&it.none {c->c.code<32}})
        require(originalGoalVersion>=0&&scopeRevision>=0&&workDigest.matches(Regex("[a-f0-9]{64}")))
        require(segmentIndex>0&&consumedToolCalls>=0&&noProgressSegments in 0..MAX_NO_PROGRESS)
        require(archivedCalls in 0..consumedToolCalls && (archivedCalls==0)==archivedObservations.isEmpty())
        if(archivedCalls>0) GoalObservationIndex.decode(archivedObservations)
        require(totalSegments>=segmentIndex)
        require(phaseStartSegments in 0 until totalSegments && phaseStartCalls in 0..consumedToolCalls)
        require(startedAtMs>=0&&startedAtMs<=Long.MAX_VALUE-MAX_DURATION_MS)
        require(!artifactAuthResumed||artifactWrapUp)
        require(budgetWindow in 0..MAX_BUDGET_WINDOWS&&budgetBaseToolCalls in 0..consumedToolCalls&&transientRetries in 0..MAX_TRANSIENT_RETRIES)
        require(activeElapsedMs in 0..MAX_DURATION_MS)
        require((segmentIndex==1)==(parentRunId==null&&parentCheckpointDigest==null))
        if(segmentIndex>1)require(parentRunId!=null&&parentRunId.isNotBlank()&&parentRunId.length<=100&&
            parentRunId.none {it.code<32}&&parentCheckpointDigest?.matches(Regex("[a-f0-9]{64}"))==true)
    }
    internal fun json():JsonObject=buildJsonObject {
        put("version",if(archivedCalls>0||totalSegments>32||consumedToolCalls>MAX_LEDGER_ENTRIES)2 else 1);put("goalId",goalId);put("originalGoalVersion",originalGoalVersion);put("workDigest",workDigest)
        put("scopeKey",scopeKey);put("scopeRevision",scopeRevision);put("useOwnerMemory",useOwnerMemory);put("memoryEnabled",memoryEnabled)
        put("rootRunId",rootRunId);put("parentRunId",parentRunId);put("parentCheckpointDigest",parentCheckpointDigest)
        put("segmentIndex",segmentIndex);put("startedAtMs",startedAtMs);put("consumedToolCalls",consumedToolCalls);put("noProgressSegments",noProgressSegments);put("totalSegments",totalSegments)
        if(archivedCalls>0){put("archivedCalls",archivedCalls);put("archivedObservations",archivedObservations)}
        if(artifactWrapUp)put("artifactWrapUp",true)
        if(artifactAuthResumed)put("artifactAuthResumed",true)
        if(phaseStartSegments!=0||phaseStartCalls!=0){put("phaseStartSegments",phaseStartSegments);put("phaseStartCalls",phaseStartCalls)}
        put("activeElapsedMs",activeElapsedMs);put("budgetWindow",budgetWindow);put("budgetBaseToolCalls",budgetBaseToolCalls)
        put("transientRetries",transientRetries)
    }
    companion object {
        const val PHASE_SEGMENTS=8
        const val PHASE_TOOL_CALLS=144
        const val MAX_LEDGER_ENTRIES=576
        const val MAX_DURATION_MS=30*60*1000L
        const val MAX_NO_PROGRESS=3
        const val MAX_BUDGET_WINDOWS=100
        const val MAX_TRANSIENT_RETRIES=2
        private val clockKeys=setOf("activeElapsedMs","budgetWindow","budgetBaseToolCalls","transientRetries")
        internal fun parse(o:JsonObject,nowMs:Long=System.currentTimeMillis()):GoalSegmentBinding {
            val legacy=o.keys.none {it in clockKeys}
            require(legacy || o.keys.containsAll(clockKeys))
            require(o.keys-clockKeys-setOf("archivedCalls","archivedObservations","phaseStartSegments","phaseStartCalls","artifactWrapUp","artifactAuthResumed")==setOf("version","goalId","originalGoalVersion","workDigest","scopeKey","scopeRevision","useOwnerMemory","memoryEnabled","rootRunId","parentRunId","parentCheckpointDigest","segmentIndex","startedAtMs","consumedToolCalls","noProgressSegments","totalSegments"))
            fun s(k:String)=o.getValue(k).jsonPrimitive.let {require(it.isString);it.content}
            fun l(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.long}
            fun b(k:String)=o.getValue(k).jsonPrimitive.let {require(!it.isString);it.boolean}
            fun optional(k:String)=o.getValue(k).takeUnless {it is JsonNull}?.jsonPrimitive?.let {require(it.isString);it.content}
            val version=l("version")
            require(version in 1L..2L && ("archivedCalls" in o)==("archivedObservations" in o))
            require(version==2L || "archivedCalls" !in o)
            require( ("phaseStartSegments" in o)==("phaseStartCalls" in o))
            val index=l("segmentIndex");val count=l("consumedToolCalls");val progress=l("noProgressSegments");val total=l("totalSegments")
            require(index in 1..Int.MAX_VALUE.toLong()&&count in 0..Int.MAX_VALUE.toLong()&&progress in 0..MAX_NO_PROGRESS.toLong()&&total in index..Int.MAX_VALUE.toLong())
            require(version==2L || total<=32 && count<=MAX_LEDGER_ENTRIES)
            return GoalSegmentBinding(s("goalId"),l("originalGoalVersion"),s("workDigest"),s("scopeKey"),l("scopeRevision"),b("useOwnerMemory"),b("memoryEnabled"),
                s("rootRunId"),optional("parentRunId"),optional("parentCheckpointDigest"),index.toInt(),l("startedAtMs"),count.toInt(),progress.toInt(),total.toInt(),
                if("phaseStartSegments" in o)l("phaseStartSegments").also { require(it in 0 until total) }.toInt() else 0,
                if("phaseStartCalls" in o)l("phaseStartCalls").also { require(it in 0..count) }.toInt() else 0,
                o["artifactWrapUp"]?.jsonPrimitive?.let {require(!it.isString);it.boolean}?:false,
                o["artifactAuthResumed"]?.jsonPrimitive?.let {require(!it.isString);it.boolean}?:false,
                if("archivedCalls" in o)l("archivedCalls").also {require(it in 1..count)}.toInt() else 0,
                if("archivedObservations" in o)s("archivedObservations") else "",
                if(legacy)(nowMs-l("startedAtMs")).coerceIn(0,MAX_DURATION_MS)else l("activeElapsedMs"),
                if(legacy)0 else l("budgetWindow").also {require(it in 0..MAX_BUDGET_WINDOWS.toLong())}.toInt(),
                if(legacy)0 else l("budgetBaseToolCalls").also {require(it in 0..count)}.toInt(),
                if(legacy)0 else l("transientRetries").also {require(it in 0..MAX_TRANSIENT_RETRIES.toLong())}.toInt())
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
    PROCESS_RESTARTED("goal_autopilot_process_restarted"),
    TRANSIENT_FAILURE("goal_autopilot_transient_failure"),
    ;
    val description:String get()=when(this) {
        SEGMENT_BUDGET->"이번 목표에서 이어서 실행할 구간 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        TOOL_BUDGET->"이번 목표에서 도구 실행 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        TIME_BUDGET->"이번 구간의 활동 시간 한도에 도달했어요. 확인한 결과는 남겨뒀어요."
        NO_PROGRESS->"세 차례 이어서 시도했지만 다음 단계로 넘어갈 단서를 찾지 못했어요. 어디까지 진행했는지 작업 내역에서 볼 수 있어요."
        UNKNOWN_EFFECT->"마지막 요청이 처리됐는지 확인이 필요해요. 같은 요청이 두 번 처리될 수 있어 잠시 멈췄어요."
        FAILED_STEP->"변경을 시도한 뒤 결과를 확인하지 못했어요. 앞에서 확인한 내용은 작업 내역에 남아 있어요."
        OWNER_HANDOFF->"추가 지시나 외부 앱의 실제 결과 확인이 필요해 일시 정지했습니다."
        REQUIRED_READBACK->"변경을 요청했는데 다시 조회했을 때 결과를 확인할 수 없었어요."
        LEGACY_CONTEXT->"기존 요청에는 자동 연속 실행 연결이 없어 일시 정지했습니다. 이전 권한을 확대하지 않았습니다."
        INVALID_CHECKPOINT->"저장된 실제 실행 근거를 확인하지 못해 일시 정지했습니다. 근거를 버리고 다시 실행하지 않습니다."
        STOPPED->"목표 실행이 중지되었거나 원래 실행 조건을 유지하지 않아 일시 정지했습니다."
        PROCESS_RESTARTED->"앱이 다시 시작되어 목표를 일시 정지했습니다. 확인된 결과를 보존했고 재개할 수 있습니다."
        TRANSIENT_FAILURE->"연결 지연 같은 일시 장애가 반복되어 목표를 일시 정지했습니다. 확인된 결과를 보존했으며 연결 상태를 확인한 뒤 재개할 수 있습니다."
    }
}
internal sealed interface GoalSegmentDecision {
    data class Continue(val binding:GoalSegmentBinding,val ledger:List<GoalLedgerEntry>,val readback:GoalReadbackSeed?=null):GoalSegmentDecision
    data object Complete:GoalSegmentDecision
    data class Pause(val reason:GoalAutopilotPauseReason):GoalSegmentDecision
}
internal class GoalAutopilotPaused(val reason:GoalAutopilotPauseReason):IllegalStateException(reason.description)
internal data class GoalEntryBudget(val originalGoalVersion:Long,val startedAtMs:Long,val totalSegments:Int,val noProgressSegments:Int,
    val phaseStartSegments:Int=0,val phaseStartCalls:Int=0,val activeElapsedMs:Long=0,val budgetWindow:Int=0,val budgetBaseToolCalls:Int=0)

/** Explicit START/RESUME/steer is not a budget-renewal receipt. Count every earlier goal run. */
internal fun goalEntryBudget(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,nowMs:Long,
    ownerWaitMs:Long=0,renewBudget:Boolean=false):GoalEntryBudget {
    if(sources.isEmpty())return GoalEntryBudget(goal.updatedAt,nowMs,1,0)
    val requests=sources.map {QueuedChatRequest.fromJson(it.contextJson,nowMs)}
    val original=requests.first().let {it.goalSegment?.originalGoalVersion?:checkNotNull(it.goal).updatedAt}
    val window=requests.maxOf {it.goalSegment?.budgetWindow?:0}
    if(renewBudget) {
        if(window>=GoalSegmentBinding.MAX_BUDGET_WINDOWS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.SEGMENT_BUDGET)
        return GoalEntryBudget(original,nowMs,1,0,budgetWindow=window+1)
    }
    val current=requests.indices.filter {(requests[it].goalSegment?.budgetWindow?:0)==window}
    val consumed=maxOf(current.size,current.maxOf {requests[it].goalSegment?.totalSegments?:1})
    val latest=requests[current.last()]
    val phase=latest.goalSegment
    if(consumed-(phase?.phaseStartSegments?:0)>=GoalSegmentBinding.PHASE_SEGMENTS)
        throw GoalAutopilotPaused(GoalAutopilotPauseReason.SEGMENT_BUDGET)
    val started=phase?.startedAtMs?:requests[current.first()].queueOrder
    val last=sources[current.last()]
    val active=if(phase!=null)phase.activeElapsedMs+goalRunActiveMs(last.contextJson,last.startedAt,last.finishedAt)
        else current.sumOf {i->sources[i].let {goalRunActiveMs(it.contextJson,it.startedAt,it.finishedAt)}}
    if(started<=0||active>=GoalSegmentBinding.MAX_DURATION_MS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.TIME_BUDGET)
    val noProgress=phase?.let {if(ownerWaitMs>0||hasNewGoalProgress(latest,goal)) 0 else it.noProgressSegments+1}?:0
    if(noProgress>=GoalSegmentBinding.MAX_NO_PROGRESS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.NO_PROGRESS)
    return GoalEntryBudget(original,started,consumed+1,noProgress,phase?.phaseStartSegments?:0,
        maxOf(phase?.phaseStartCalls?:0,phase?.budgetBaseToolCalls?:0),active,window,phase?.budgetBaseToolCalls?:0)
}

private fun hasNewGoalProgress(request:QueuedChatRequest,goal:GoalDefinition):Boolean {
    val prior=request.goalLedger.map {it.step}
    val fresh=request.previousSteps.drop(request.goalReadbackSeedCount)
    val seen=prior.mapNotNull(::goalMilestoneKey).toSet()
    val archived=GoalObservationIndex.decode(request.goalSegment?.archivedObservations.orEmpty())
    return fresh.mapNotNull(::goalMilestoneKey).any {it !in seen && !archived.contains(it)} ||
        fresh.any {step->
            val key=goalRepeatedReadKey(step)
            key!=null && !archived.contains(key) && prior.any {it.tool==step.tool && it.arguments==step.arguments && goalRepeatedReadKey(it)!=null} &&
                prior.none {goalRepeatedReadKey(it)==key}
        } ||
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
    val request=try {QueuedChatRequest.fromJson(parent.contextJson,nowMs)}catch(_:Exception) {
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
    val totalCalls=Math.addExact(segment.archivedCalls,ledger.size)
    val archive=GoalObservationIndex.decode(segment.archivedObservations)
    if(ledger.any {goalUnverifiedEffect(it.step)})return pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT)
    if(ledger.any {goalUncertainFailure(it.step)})return pause(GoalAutopilotPauseReason.FAILED_STEP)
    if(ledger.any {PreparedHandoff.pendingEffect(it.step)})return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    val transient=goalTransientFailure(parent)
    if(transient&&segment.transientRetries>=GoalSegmentBinding.MAX_TRANSIENT_RETRIES)return pause(GoalAutopilotPauseReason.TRANSIENT_FAILURE)
    if(!transient&&!goalMayResumeFrom(parent)&&parent.status!="paused")return pause(GoalAutopilotPauseReason.STOPPED)
    var completedMedia=false
    var resumedArtifactAuth=false
    val clockShift = if (parent.status=="paused" && parent.errorCode=="waiting_goal_condition") {
        val wake = Json.parseToJsonElement(parent.contextJson).jsonObject["hostGoalWake"] as? JsonObject
            ?: return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
        val parkedAt=wake["parkedAt"]?.jsonPrimitive?.longOrNull ?: return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        val wokeAt=wake["wokeAt"]?.jsonPrimitive?.longOrNull ?: return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        val finalStep=request.previousSteps.lastOrNull()
        val authReady=wake["event"] in setOf(JsonPrimitive(WakeEvent.OWNER_AUTH_DONE.name),JsonPrimitive(WakeEvent.TIME_REACHED.name)) &&
            finalStep?.tool in goalAuthReadTools &&
            finalStep?.evidence?.effectNotApplied==true &&
            finalStep.evidence.summary==com.user.localagent.compute.BrowserAgentTools.PHONE_AUTH_REQUIRED
        val waitReady=finalStep?.tool==GoalWaitToolCatalog.WAIT && finalStep.evidence.state==VerificationState.VERIFIED
        if(parkedAt !in segment.startedAtMs..wokeAt || wokeAt>nowMs ||
            (!authReady && !waitReady))
            return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
        resumedArtifactAuth=segment.artifactWrapUp&&!segment.artifactAuthResumed&&authReady&&
            wake["event"]==JsonPrimitive(WakeEvent.OWNER_AUTH_DONE.name)
        if(waitReady && wake["event"]==JsonPrimitive(WakeEvent.MEDIA_ENDED.name)) {
            val condition=finalStep?.arguments?.get("condition") as? JsonObject
            val item=condition?.get("ledgerItemId")?.jsonPrimitive?.contentOrNull
            completedMedia=item!=null&&condition?.get("kind")==JsonPrimitive("media_end")&&!archive.contains("media:$item")&&request.goalLedger.none {entry->
                entry.step.tool==GoalWaitToolCatalog.WAIT&&
                    (entry.step.arguments["condition"] as? JsonObject)?.get("ledgerItemId")==JsonPrimitive(item)
            }
        }
        wokeAt-parkedAt
    } else {
        if(!transient&&!goalMayResumeFrom(parent)&&parent.errorCode!="execution_not_verified")return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
        0L
    }
    val assessment=assessGoalReadback(request,scopeKey)
    val recovery=if(assessment.pending.isEmpty())null else GoalReadbackSeed.from(request,assessment)
    if(assessment.blockers.isNotEmpty()||assessment.pending.isNotEmpty()&&recovery==null)
        return pause(GoalAutopilotPauseReason.REQUIRED_READBACK)
    val complete=goalCompletionProofsMatch(request,goal)&&recovery==null
    val wrapUp=complete&&(!segment.artifactWrapUp||resumedArtifactAuth)&&
        ledger.any {it.step.tool in goalArtifactBrowserReads&&it.step.evidence.state==VerificationState.VERIFIED}
    if(complete&&!wrapUp)return GoalSegmentDecision.Complete
    if(parent.errorCode=="execution_not_verified"&&recovery==null)return pause(GoalAutopilotPauseReason.OWNER_HANDOFF)
    val progressed=wrapUp||completedMedia||recovery==null&&hasNewGoalProgress(request,goal)
    val noProgress=if(progressed)0 else segment.noProgressSegments+1
    val budget=segment.copy(startedAtMs=segment.startedAtMs+clockShift)
    val active=(segment.activeElapsedMs+goalRunActiveMs(parent.contextJson,parent.startedAt,parent.finishedAt)).coerceAtMost(GoalSegmentBinding.MAX_DURATION_MS)
    val limit=goalBudgetStop(budget,totalCalls,active,noProgress,useNativeBudget)
    val observedMilestones=request.goalLedger.mapNotNull {goalMilestoneKey(it.step)}.toSet()
    val earnedPhase=completedMedia || goal.criteria.any {criterion->
        criterion.status==GoalCriterionStatus.VERIFIED&&request.goal.criteria.none {it.id==criterion.id&&it.status==GoalCriterionStatus.VERIFIED}
    } || request.previousSteps.drop(request.goalReadbackSeedCount).mapNotNull(::goalMilestoneKey).any {it !in observedMilestones && !archive.contains(it)}
    val renew=limit in setOf(GoalAutopilotPauseReason.SEGMENT_BUDGET,GoalAutopilotPauseReason.TOOL_BUDGET,GoalAutopilotPauseReason.TIME_BUDGET) &&
        earnedPhase && recovery==null && nowMs>=budget.startedAtMs
    if(limit!=null&&!renew)return if(complete)GoalSegmentDecision.Complete else pause(limit)
    val nextStart=if(renew)maxOf(budget.startedAtMs,parent.finishedAt?:parent.updatedAt) else budget.startedAtMs
    if(renew&&nextStart>nowMs)return pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT)
    val retained=compactGoalLedger(ledger,archive)?:return pause(GoalAutopilotPauseReason.TOOL_BUDGET)
    val archivedCalls=totalCalls-retained.size
    return GoalSegmentDecision.Continue(budget.copy(parentRunId=request.runId,parentCheckpointDigest=goalDigest(parent.contextJson),
        segmentIndex=Math.incrementExact(segment.segmentIndex),totalSegments=Math.incrementExact(segment.totalSegments),consumedToolCalls=totalCalls,
        archivedCalls=archivedCalls,archivedObservations=if(archivedCalls>0)archive.encode() else "",noProgressSegments=noProgress,
        startedAtMs=nextStart,phaseStartSegments=if(renew)segment.totalSegments else segment.phaseStartSegments,
        phaseStartCalls=if(renew)totalCalls else segment.phaseStartCalls,artifactWrapUp=wrapUp,
        activeElapsedMs=if(renew)0L else active,transientRetries=if(transient)segment.transientRetries+1 else 0,
        artifactAuthResumed=wrapUp&&resumedArtifactAuth),retained,recovery)
}

internal fun goalToolRoom(segment:GoalSegmentBinding,steps:Int):Int =
    GoalSegmentBinding.PHASE_TOOL_CALLS-(segment.consumedToolCalls-maxOf(segment.phaseStartCalls,segment.budgetBaseToolCalls))-steps

/** Fixed codes the host itself writes for a connection/PC-availability failure. Never model text or a provider message. */
private val transientFailureCodes=setOf("SocketTimeoutException","ConnectException","PcUnavailable","PcBusy","PcAgentProviderBusy")
private val transientPauseCodes=setOf("planning_pc_busy","planning_pc_unavailable","tool_call_truncated")
internal fun goalTransientFailure(parent:ChatRunRecord):Boolean {
    val code=parent.errorCode?:return false
    return when(parent.status) {
        "failed"->code in transientFailureCodes||code.matches(Regex("PC_BRIDGE_TIMEOUT_[A-Z_]{1,64}"))
        "paused"->code in transientPauseCodes||code.matches(Regex("planning_[a-z_]{1,48}_timeout"))
        else->false
    }
}
internal fun goalTransientRetryDelayMs(attempt:Int):Long=30_000L*attempt.coerceIn(1,GoalSegmentBinding.MAX_TRANSIENT_RETRIES)

internal fun goalParentRestarted(parent:ChatRunRecord)=parent.status=="interrupted"&&parent.errorCode=="process_restarted"

/**
 * The one effect rule every goal caller uses. An interrupted run can never start another effect, so its record
 * need not be closed; it still needs no in-flight call and no uncertainty. Every other run needs a closed record.
 */
internal fun goalRunEffectsUnresolved(row:ChatRunRecord,scopeKey:String):Boolean=goalNativeEffectsUnresolved(row,scopeKey,requireClosed=!goalParentRestarted(row))

/** Startup decision for an ACTIVE goal whose last linked run ended while the app was not running. */
internal fun goalRestartDecision(parent:ChatRunRecord,goal:GoalDefinition,scopeKey:String,nowMs:Long,unknownEffects:Boolean,
    useNativeBudget:Boolean=false):GoalSegmentDecision = try {
    when(val decision=goalSegmentDecision(parent,goal,scopeKey,nowMs,unknownEffects,useNativeBudget)) {
        GoalSegmentDecision.Complete, is GoalSegmentDecision.Continue->decision
        is GoalSegmentDecision.Pause->if(decision.reason==GoalAutopilotPauseReason.STOPPED)GoalSegmentDecision.Pause(GoalAutopilotPauseReason.PROCESS_RESTARTED)else decision
    }
}catch(_:Exception){GoalSegmentDecision.Pause(GoalAutopilotPauseReason.PROCESS_RESTARTED)}

/** activeElapsedMs is the carried plus current segment active time, never a wall-clock difference. */
internal fun goalBudgetStop(segment:GoalSegmentBinding,toolCount:Int,activeElapsedMs:Long,noProgress:Int,useNative:Boolean):GoalAutopilotPauseReason? {
    if(useNative){
        return when(uniffi.ziphap_android.nativeGoalBudgetStop((segment.totalSegments-segment.phaseStartSegments).toUInt(),(toolCount-maxOf(segment.phaseStartCalls,segment.budgetBaseToolCalls)).toUInt(),
            activeElapsedMs.toULong(),noProgress.toUInt())){
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
        toolCount-maxOf(segment.phaseStartCalls,segment.budgetBaseToolCalls)>=GoalSegmentBinding.PHASE_TOOL_CALLS->GoalAutopilotPauseReason.TOOL_BUDGET
        activeElapsedMs>=GoalSegmentBinding.MAX_DURATION_MS->GoalAutopilotPauseReason.TIME_BUDGET
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
        check(pcBrowserWorkKey(original)==pcBrowserWorkKey(request)){"이어갈 PC 작업이 바뀌었습니다."}
        val decision=goalSegmentDecision(row,goal,segment.scopeKey,nowMs,unknownEffects=false,useNativeBudget=useNativeBudget)
        check(decision is GoalSegmentDecision.Continue&&decision.binding==segment&&
            decision.ledger==request.goalLedger&&request.goalReadbackSeedCount==(decision.readback?.steps?.size?:0)&&
            request.readbackObligations==decision.readback?.forRun(request.runId)&&
            request.previousSteps.size==(decision.readback?.steps?.size?:0)&&
            request.previousSteps.zip(decision.readback?.steps.orEmpty()).all {(a,b)->a.tool==b.tool&&a.arguments==b.arguments&&a.evidence==b.evidence}) {"실제 부모 체크포인트에서 이 다음 구간을 재검증하지 못했습니다."}
        return
    }
    request.goalOwnerReplyParentId?.let { parentId->
        val row=checkNotNull(parent)
        check(row.id==parentId&&goalDigest(row.contextJson)==request.goalOwnerReplyParentDigest&&
            row.status=="paused"&&row.errorCode==GOAL_OWNER_INPUT)
        val original=QueuedChatRequest.fromJson(row.contextJson)
        requireGoalParentRow(original,row)
        requireSameGoalAuthority(original,request)
        check(sameGoalWork(checkNotNull(original.goal),goal)&&pcBrowserWorkKey(original)==pcBrowserWorkKey(request))
        val assessment=assessGoalReadback(original,checkNotNull(request.goalSegment).scopeKey)
        check(assessment.blockers.isEmpty())
        if(assessment.pending.isNotEmpty()) {
            check(GoalReadbackSeed.from(original,assessment)?.matches(request)==true)
            check(request.previousSteps.size==request.goalReadbackSeedCount)
        } else check(request.goalReadbackSeedCount==0&&request.readbackObligations==null)
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


internal val goalArtifactBrowserReads=setOf("read_pc_browser_page","read_app_browser_page")
internal val goalArtifactSaveTools=setOf("save_browser_pdf","save_public_pdf")
internal val goalArtifactWrapUpTools=goalArtifactBrowserReads+(goalArtifactSaveTools-"save_public_pdf")

internal const val GOAL_OWNER_INPUT = "goal_owner_input"

internal val goalAuthReadTools = setOf("read_app_browser_page","open_app_browser_page","read_pc_browser_page","open_pc_browser_page")

internal fun pcBrowserWorkKey(request:QueuedChatRequest):String {
    val scope=request.browserWorkRootRunId?:request.goalSegment?.rootRunId?:request.browserAuthParentRunId?:request.runId
    return goalDigest(listOf(request.threadId,request.branchId,request.projectId.orEmpty(),scope).joinToString("\n"))
}
