package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Host-only encrypted ledger; never an action grant or current-segment completion proof. */
data class GoalLedgerEntry(val sourceRunId:String,val stepIndex:Int,val step:HarnessStep) {
    init {require(sourceRunId.isNotBlank()&&sourceRunId.length<=100&&sourceRunId.none {it.code<32});require(stepIndex in 0 until WorkSegments.STEP_BUDGET)}
    override fun equals(other:Any?):Boolean=other is GoalLedgerEntry&&sourceRunId==other.sourceRunId&&stepIndex==other.stepIndex&&
        step.tool==other.step.tool&&step.arguments==other.step.arguments&&step.evidence==other.step.evidence
    override fun hashCode():Int=java.util.Objects.hash(sourceRunId,stepIndex,step.tool,step.arguments,step.evidence)
    internal fun json():JsonObject=buildJsonObject {
        put("sourceRunId",sourceRunId);put("stepIndex",stepIndex);put("tool",step.tool);put("arguments",step.arguments)
        put("state",step.evidence.state.name);put("summary",step.evidence.summary);put("effectNotApplied",step.evidence.effectNotApplied)
        step.evidence.continuationId?.let {put("continuationId",it)}
        step.evidence.progressDigest?.let {put("progressDigest",it)}
        step.evidence.auditId?.let {put("auditId",it)}
        step.evidence.outcomeProof?.let {put("outcomeProof",it)}
    }
    companion object {
        internal fun parse(o:JsonObject):GoalLedgerEntry {
            require(o.keys.all {it in setOf("sourceRunId","stepIndex","tool","arguments","state","summary","effectNotApplied","continuationId","progressDigest","auditId","outcomeProof")})
            fun s(key:String)=o.getValue(key).jsonPrimitive.let {require(it.isString);it.content}
            val tool=s("tool");val args=o.getValue("arguments").jsonObject
            val state=VerificationState.valueOf(s("state"))
            val definition=ToolRegistry.definitions.getValue(tool)
            if(state==VerificationState.FAILED)require(args.keys.all {it in definition.arguments})
            else ToolRegistry.validate(ToolCall("goal-ledger",tool,args))
            val index=o.getValue("stepIndex").jsonPrimitive.let {require(!it.isString);it.int}
            val noEffect=o.getValue("effectNotApplied").jsonPrimitive.let {require(!it.isString);it.boolean}
            return GoalLedgerEntry(s("sourceRunId"),index,HarnessStep(tool,args,StepEvidence(tool,state,s("summary"),
                o["continuationId"]?.jsonPrimitive?.let {require(it.isString);it.content},noEffect,
                o["progressDigest"]?.jsonPrimitive?.let {require(it.isString);it.content},
                o["auditId"]?.jsonPrimitive?.let {require(it.isString);it.content},
                o["outcomeProof"]?.jsonPrimitive?.let {require(it.isString);it.content})))
        }
    }
}

internal fun goalDigest(text:String):String=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
private fun goalCanonical(value:JsonElement):String=when(value) {
    is JsonObject->value.keys.sorted().joinToString(prefix="{",postfix="}") {JsonPrimitive(it).toString()+":"+goalCanonical(value.getValue(it))}
    is JsonArray->value.joinToString(prefix="[",postfix="]") {goalCanonical(it)}
    else->value.toString()
}
internal fun goalCallDigest(step:HarnessStep):String=goalDigest(step.tool+":"+goalCanonical(step.arguments))
private val goalBrowserTools=AppBrowserToolCatalog.names+PcBrowserToolCatalog.names

private fun progressRelevant(tool:String):Boolean=tool !in GoalToolCatalog.names&&
    tool !in setOf(GoalProgressToolCatalog.READ,GoalProgressToolCatalog.VERIFY,GoalWaitToolCatalog.WAIT,
        "get_current_datetime","get_agent_capabilities")&&tool !in SkillToolCatalog.names

/** The model receives the usual tool output. Only a hash of the observed result is carried in the ledger. */
internal fun observedGoalProgress(call:ToolCall,result:ToolResult):String? {
    if(!result.success||result.verification!=VerificationState.VERIFIED||result.effectNotApplied||!progressRelevant(call.name))return null
    val content=result.content
    if(content.isBlank()||content.length>128_000)return null
    val payload=runCatching {Json.parseToJsonElement(content) as? JsonObject}.getOrNull()
    val observation=when {
        call.name in goalBrowserTools -> {
            val page=payload?:return null
            val fields=listOf("url","title","text").map {key->
                page[key]?.jsonPrimitive?.contentOrNull.orEmpty().replace(Regex("\\s+")," ").trim()
            }
            if(fields.drop(1).all(String::isBlank))return null
            JsonArray(fields.map(::JsonPrimitive))
        }
        call.name==GoalProgressToolCatalog.UPDATE -> {
            // Only genuinely completed ledger items qualify. Revision, notes and audit IDs can change on every write.
            val items=(payload?.get("ledger") as? JsonObject)?.get("items") as? JsonArray?:return null
            val done=items.mapNotNull {item->
                val entry=item.jsonObject
                entry["id"]?.takeIf {entry["status"]==JsonPrimitive("done")}
            }.sortedBy {it.toString()}
            if(done.isEmpty())return null
            JsonArray(done)
        }
        payload!=null -> JsonObject(payload-filteredGoalObservationKeys)
        else -> JsonPrimitive(content.replace(Regex("\\s+")," ").trim())
    }
    if(observation is JsonObject && observation.isEmpty())return null
    return goalDigest(goalCanonical(observation))
}

private val filteredGoalObservationKeys=setOf("snapshotId","auditId","requestId","runId","callId","elapsedMs","capturedAt","updatedAt")

internal fun goalProgressKey(step:HarnessStep):String? {
    if(step.evidence.state!=VerificationState.VERIFIED||step.evidence.effectNotApplied||!progressRelevant(step.tool))return null
    step.evidence.progressDigest?.let {
        val kind=if(step.tool in goalBrowserTools)"browser-page" else step.tool
        return "$kind:$it"
    }
    // Older browser checkpoints contain only a rendered status, with no stable observation to compare.
    return if(step.tool in goalBrowserTools||step.tool==GoalProgressToolCatalog.UPDATE)null else goalCallDigest(step)
}

internal fun goalOutcomeProof(call:ToolCall,result:ToolResult):String? {
    if(!result.success||result.effectNotApplied||result.verification!=VerificationState.VERIFIED)return null
    if(!goalCriterionEvidenceTool(call.name)||result.content.length>40_000)return null
    val actual=runCatching {Json.parseToJsonElement(result.content).jsonObject}.getOrNull()?:return null
    if(call.name!=PcBrowserToolCatalog.READ) {
        val sha=actual["sha256"]?.jsonPrimitive?.contentOrNull
        if(sha==null||!sha.matches(Regex("[a-f0-9]{64}")))return null
    }
    val proof=when(call.name) {
        "save_public_pdf"-> {
            if(actual["mimeType"]!=JsonPrimitive("application/pdf")||
                (actual["pages"]?.jsonPrimitive?.intOrNull?:0)<1||actual["fileName"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())return null
            val target=call.arguments.getValue("url").jsonPrimitive.content
            if(actual["sourceUrl"]!=JsonPrimitive(target))return null
            GoalProofTarget(GoalProofKind.PUBLIC_PDF_SOURCE,target)
        }
        "write_project_file"-> {
            val path=actual["path"]?.jsonPrimitive?.contentOrNull?:return null
            if(actual["verified"]!=JsonPrimitive(true)||path!=call.arguments["path"]?.jsonPrimitive?.contentOrNull)return null
            GoalProofTarget(GoalProofKind.PROJECT_FILE,path)
        }
        PcBrowserToolCatalog.READ-> {
            val title=call.arguments[PcBrowserToolCatalog.COURSE_TITLE]?.jsonPrimitive?.contentOrNull?:return null
            if(actual["verifiedCompletedCourseTitle"]!=JsonPrimitive(title)||
                actual["url"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')!=PcBrowserToolCatalog.KAIRB_COMPLETED_URL)return null
            GoalProofTarget(GoalProofKind.KAIRB_COMPLETED_COURSE,title)
        }
        else->return null
    }
    return runCatching {proof.validate();goalOutcomeDigest(proof)}.getOrNull()
}

internal fun goalOutcomeDigest(proof:GoalProofTarget):String=goalDigest(proof.kind.name+"\n"+proof.target)

internal fun goalCriterionMatches(criterion:GoalCriterion,step:HarnessStep):Boolean =
    criterion.proofTarget?.let {
        (it.kind!=GoalProofKind.KAIRB_COMPLETED_COURSE||criterion.text==it.courseListCriterion())&&
            goalOutcomeDigest(it)==step.evidence.outcomeProof
    }==true

internal fun goalMilestoneKey(step:HarnessStep):String? =
    if(step.tool in goalBrowserTools) null
    else if(step.evidence.outcomeProof!=null ||
        step.tool in setOf(GoalProgressToolCatalog.UPDATE,"search_notes","read_project_file","list_calendar_events","search_web","search_shopping"))
        goalProgressKey(step)
    else null

internal fun goalCriterionEvidenceTool(tool:String):Boolean=tool in setOf("save_public_pdf","write_project_file",PcBrowserToolCatalog.READ)

internal fun goalCriterionEvidence(request:QueuedChatRequest,current:List<HarnessStep>):Map<String,HarnessStep> =
    (request.goalLedger.map {it.step}+current).mapNotNull {step->
        step.evidence.auditId?.takeIf {goalCriterionEvidenceTool(step.tool)&&step.evidence.outcomeProof!=null}?.let {it to step}
    }.toMap()

internal fun goalCompletionProofsMatch(request:QueuedChatRequest,goal:GoalDefinition):Boolean {
    if(!goal.completionReady)return false
    val audits=goalCriterionEvidence(request,request.previousSteps)
    return goal.criteria.all {criterion->
        criterion.status==GoalCriterionStatus.USER_CONFIRMED||
            criterion.evidence.any {ref->audits[ref.removePrefix("tool-run:")]?.let {goalCriterionMatches(criterion,it)}==true}
    }
}

internal fun goalCheckpointSummary(request:QueuedChatRequest):JsonArray=buildJsonArray {
    request.goalLedger.takeLast(8).forEach {entry->
        add(buildJsonObject {
            put("tool",entry.step.tool);put("state",entry.step.evidence.state.name)
            put("summary",entry.step.evidence.summary.take(100))
        })
    }
}

internal fun goalLedgerFits(ledger:List<GoalLedgerEntry>):Boolean=ledger.sumOf {it.json().toString().length+1}<=900_000

internal fun goalUnverifiedEffect(step:HarnessStep):Boolean=
    step.evidence.state==VerificationState.UNVERIFIED&&!step.evidence.effectNotApplied

internal fun goalUncertainFailure(step:HarnessStep):Boolean=step.evidence.state==VerificationState.FAILED&&
    !ToolReadPolicy.isReadOnly(step.tool)&&!step.evidence.effectNotApplied
internal fun goalWorkDigest(goal:GoalDefinition):String=goalDigest(buildJsonObject {
    put("id",goal.id);put("threadId",goal.threadId);put("projectId",goal.projectId);put("outcome",goal.outcome)
    put("constraints",JsonArray(goal.constraints.map(::JsonPrimitive)))
    put("criteria",buildJsonArray {goal.criteria.forEach {add(buildJsonObject {put("id",it.id);put("text",it.text)
        it.proofTarget?.let { proof->put("proof",buildJsonObject {put("kind",proof.kind.name);put("target",proof.target)}) }})}})
}.toString())

/** All states and positions are retained; no deduplication can erase an uncertain effect. */
internal fun completedGoalLedger(request:QueuedChatRequest):List<GoalLedgerEntry> {
    val ledger=request.goalLedger+request.previousSteps.drop(request.goalReadbackSeedCount).mapIndexed {index,step->GoalLedgerEntry(request.runId,index,step)}
    check(ledger.size<=GoalSegmentBinding.MAX_TOOL_CALLS){"목표의 누적 도구 예산을 초과했습니다."}
    check(ledger.map {it.sourceRunId to it.stepIndex}.distinct().size==ledger.size)
    return ledger
}

/** A fresh explicit start/resume preserves the complete host ledger, including safe failures. */
internal fun collectGoalLedger(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,workspaceBinding:String,scopeKey:String):List<GoalLedgerEntry> {
    val entries=linkedMapOf<Pair<String,Int>,GoalLedgerEntry>()
    for(source in sources) {
        check(source.runId in goal.linkedRunIds&&source.threadId==goal.threadId&&source.projectId==goal.projectId)
        check(source.status !in setOf("queued","running","waiting_approval"))
        val request=QueuedChatRequest.fromJson(source.contextJson)
        check(request.runId==source.runId&&request.threadId==source.threadId&&request.projectId==source.projectId&&request.goal?.id==goal.id)
        val all=completedGoalLedger(request)
        check(all.none {goalUnverifiedEffect(it.step)||PreparedHandoff.pendingEffect(it.step)||
            goalUncertainFailure(it.step)}){"목표의 미확인 효과·실패·외부 확인 대기를 버리고 새 실행을 예약하지 않았습니다."}
        if(!sameGoalWork(goal,checkNotNull(request.goal)))continue
        check(request.allowTools&&request.mode in setOf("act","debug")&&request.workspaceBinding==workspaceBinding)
        request.goalSegment?.let {check(it.scopeKey==scopeKey)}
        val context=RequiredReadbackObligations.Context(request.runId,request.threadId,request.projectId,scopeKey,request.workspaceBinding)
        val assessment=RequiredReadbackObligations.assess(request.readbackObligations,context,request.previousSteps,
            request.previousSteps.size,ToolRegistry.definitions.keys,1,1)
        check(assessment.pending.isEmpty()&&assessment.blockers.isEmpty()){"이전 목표 구간의 실제 변경·재조회 근거를 확인한 뒤 이어갈 수 있습니다."}
        for(entry in all) {
            val key=entry.sourceRunId to entry.stepIndex
            val previous=entries[key]
            check(previous==null||previous.json()==entry.json()){"동일 실행 위치의 목표 검증 기록이 변경되었습니다."}
            entries[key]=entry
        }
        check(entries.size<=GoalSegmentBinding.MAX_TOOL_CALLS)
    }
    return entries.values.toList()
}

/** Called in addition to the current segment's normal typed/scope/consent/harness fences. */
internal fun requireGoalLedgerAllowsCall(request:QueuedChatRequest,taskGoal:TaskGoal,call:ToolCall,currentSteps:List<HarnessStep>) {
    if(request.goalSegment==null)return
    check(currentSteps.size<=WorkSegments.STEP_BUDGET)
    check(goalToolRoom(request.goalSegment,currentSteps.size-request.goalReadbackSeedCount)>0){"목표의 누적 도구 예산에 도달했습니다."}
    val steps=request.goalLedger.map {it.step}
    check(steps.none {PreparedHandoff.pendingEffect(it)||goalUnverifiedEffect(it)||
        goalUncertainFailure(it)}){"이전 목표 구간의 실패·불확실한 효과·외부 확인 대기를 먼저 해결해야 합니다."}
    if(ToolReadPolicy.isReadOnly(call.name))return
    // Keep the existing duplicate/recovery semantics without spending this segment's 18-step budget
    // on a historical ledger. Every old effect is checked; no six-entry tail is silently dropped.
    val ledgerGoal=TaskGoal(taskGoal.ownerRequest,taskGoal.successCriteria,WorkSegments.STEP_BUDGET,taskGoal.requiredTools,taskGoal.requiredArtifactFormats)
    for(chunk in steps.filterNot {ToolReadPolicy.isReadOnly(it.tool)}.chunked(WorkSegments.STEP_BUDGET-1)) {
        val decision=TaskHarness.beforeStep(ledgerGoal,chunk,call)
        check(decision !is HarnessDecision.Stop){(decision as HarnessDecision.Stop).reason}
    }
}

/** Host Room run metadata and its encrypted context, never a user message or imported document. */
internal data class GoalRunEvidenceSource(val runId:String,val threadId:String,val projectId:String?,val status:String,val contextJson:String)

internal fun sameGoalWork(left:GoalDefinition,right:GoalDefinition):Boolean =
    left.id==right.id&&left.threadId==right.threadId&&left.projectId==right.projectId&&
        left.outcome==right.outcome&&left.constraints==right.constraints&&
        left.criteria.map {Triple(it.id,it.text,it.proofTarget)}==right.criteria.map {Triple(it.id,it.text,it.proofTarget)}

/** Only same-work, same-scope host-verified steps can suppress a repeated write. */
internal fun inheritVerifiedGoalSteps(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,workspaceBinding:String):List<HarnessStep> =
    inheritVerifiedGoalEvidence(goal,sources,workspaceBinding).steps

internal data class GoalReadbackTransfer(val request:QueuedChatRequest,val indexMap:Map<Int,Int>)
internal data class InheritedGoalEvidence(val steps:List<HarnessStep>,val transfers:List<GoalReadbackTransfer>) {
    fun readbacks(context:RequiredReadbackObligations.Context):RequiredReadbackObligations.State? =
        RequiredReadbackObligations.merge(transfers.mapNotNull { transfer->
            val request=transfer.request
            request.readbackObligations?.let { state->
                val from=RequiredReadbackObligations.Context(request.runId,request.threadId,request.projectId,context.scopeKey,request.workspaceBinding)
                RequiredReadbackObligations.carry(state,from,context,request.previousSteps,steps,transfer.indexMap)
            }
        },context)
}

/** Produces the index mapping while applying the existing scope/work/link guards, never by receipt inference. */
internal fun inheritVerifiedGoalEvidence(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,workspaceBinding:String):InheritedGoalEvidence {
    goal.validate()
    val result=mutableListOf<HarnessStep>()
    val transfers=mutableListOf<GoalReadbackTransfer>()
    for(source in sources){
        if(source.runId !in goal.linkedRunIds||source.threadId!=goal.threadId||source.projectId!=goal.projectId)continue
        // Linked, same-thread/project host context must not disappear after a malformed checkpoint.
        // Dropping it would discard duplicate-write guards along with the missing readback proof.
        val request=QueuedChatRequest.fromJson(source.contextJson)
        val previousGoal=request.goal?:continue
        if(request.runId!=source.runId||request.threadId!=source.threadId||request.projectId!=source.projectId||
            !request.allowTools||request.mode !in setOf("act","debug")||!sameGoalWork(goal,previousGoal))continue
        check(source.status !in setOf("queued","running")){"같은 목표의 실행이 아직 대기·진행 중입니다. 완료하거나 중지한 뒤 다시 추진해주세요."}
        val indexMap=linkedMapOf<Int,Int>()
        for((oldIndex,step) in request.previousSteps.withIndex()){
            if(step.evidence.state!=VerificationState.VERIFIED||step.tool in SkillToolCatalog.names||step.tool in GoalToolCatalog.names)continue
            if(step.tool in setOf("list_project_files","read_project_file","write_project_file")+ProjectStorageToolCatalog.names&&request.workspaceBinding!=workspaceBinding)continue
            val call=runCatching {ToolRegistry.validate(ToolCall("goal-evidence",step.tool,step.arguments))}.getOrNull()?:continue
            if(result.none {it.tool==call.name&&it.arguments==call.arguments})result+=HarnessStep(call,
                StepEvidence(call.name,VerificationState.VERIFIED,"이전 동일 목표 실행에서 검증됨 · 전체 목표 완료를 뜻하지 않음 · "+step.evidence.summary.safeHead(3800)))
            indexMap[oldIndex]=result.indexOfFirst {it.tool==call.name&&it.arguments==call.arguments}
        }
        transfers+=GoalReadbackTransfer(request,indexMap.toMap())
    }
    check(result.size<=6){"같은 목표의 이전 검증 단계가 실행 예산을 넘습니다. 기존 결과를 확인하고 남은 목표를 나누어 추진해주세요. 검증 근거를 버리고 작업을 반복하지 않았습니다."}
    return InheritedGoalEvidence(result.toList(),transfers.toList())
}
