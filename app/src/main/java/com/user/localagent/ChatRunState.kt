package com.user.localagent

import com.user.localagent.workspace.ChatAttachment
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.core.*
import kotlinx.serialization.json.*

/** Immutable request binding. Persisted only inside the encrypted application database. */
data class QueuedChatRequest(
    val runId:String,val threadId:String,val branchId:String,val projectId:String?,
    val text:String,val modelId:String,val thinking:Boolean,val attachments:List<ChatAttachment>,
    val userMessageId:String,val continuation:ContinuationSnapshot?=null,val allowTools:Boolean=true,
    val mode:String="act",val previousSteps:List<HarnessStep> = emptyList(),val queueOrder:Long=System.currentTimeMillis(),val workspaceBinding:String?=null,
    val goal:GoalDefinition?=null,val parentGoalStartRunId:String?=null,
    val pcDataBinding:String?=null,
    val kakaoReadBinding:String?=null,
    val pcImageRecoveryAllowed:Boolean=false,
    val capturedPcRevision:Long?=null,
    val allowCloudText:Boolean=false,
    val readbackObligations:RequiredReadbackObligations.State?=null,
    val allowExternalAgent:Boolean=false,
    val externalAgentRevision:Long?=null,
    val goalSegment:GoalSegmentBinding?=null,
    val goalLedger:List<GoalLedgerEntry> = emptyList(),
    val cloudReadOnlyModes:Boolean=false,
    val capturedPcTextDefault:String?=null,
    val scheduledReadTool:String?=null,
    val officeStage: OfficeStage? = null,
    val officeParentId: String? = null,
    val scheduledClockReceiptOnly: Boolean = false,
    val screenVisualBinding: String? = null,
    val browserAuthParentRunId: String? = null,
    val goalReadbackSeedCount:Int=0,
    val browserWorkRootRunId:String?=null,
) {
    init {
        AgentWorkMode.parse(mode)
        goal?.let {it.validate();require(it.threadId==threadId&&it.projectId==projectId){"목표 스냅샷의 대화·프로젝트가 실행과 다릅니다."}}
        parentGoalStartRunId?.let {require(goal!=null&&mode in setOf("act","debug")&&it.isNotBlank()&&it.length<=100&&it!=runId&&it.none {c->c.code<32}){"목표 시작 요청의 원래 실행 연결이 올바르지 않습니다."}}
        pcDataBinding?.let {require(it.matches(Regex("[a-f0-9]{64}"))){"PC 자료 선택의 고정 버전이 올바르지 않습니다."}}
        kakaoReadBinding?.let {require(runCatching{java.util.UUID.fromString(it).toString()==it}.getOrDefault(false))}
        browserAuthParentRunId?.let {
            require(it.isNotBlank()&&it.length<=128&&it!=runId&&it.none(Char::isISOControl))
            require(goal==null&&officeStage==null&&scheduledReadTool==null&&continuation==null&&attachments.isEmpty())
        }
        screenVisualBinding?.let {require(java.util.UUID.fromString(it).toString()==it)}
        require(capturedPcRevision==null||capturedPcRevision>=0)
        require(capturedPcTextDefault==null || capturedPcRevision!=null &&
            capturedPcTextDefault==com.user.localagent.models.PairedPcTextProfileDescriptor.GEMMA_E4B_V1.selectionId)
        require(!allowCloudText||capturedPcRevision!=null){"클라우드 사용은 현재 요청에 캡처한 명시 권한이 필요합니다."}
        require(!cloudReadOnlyModes || allowExternalAgent && com.user.localagent.externalagent.ExternalAgentSettings.isCloud(modelId))
        require(externalAgentRevision==null||externalAgentRevision>=0)
        require(!allowExternalAgent||(externalAgentRevision!=null&&capturedPcRevision!=null))
        scheduledReadTool?.let { tool ->
            require(ScheduledReadPolicy.isId(runId)&&tool in ScheduledReadPolicy.localReads)
            require(modelId==ConversationPrivacy.LOCAL_SELECTION&&!allowExternalAgent&&!allowCloudText&&
                pcDataBinding==null&&kakaoReadBinding==null&&capturedPcRevision==null&&!pcImageRecoveryAllowed&&
                attachments.isEmpty()&&goal==null)
        }
        if (officeStage != null) {
            require(projectId == null && attachments.isEmpty() && continuation == null && goal == null && scheduledReadTool == null)
            if (officeStage == OfficeStage.WORKFLOW) require(threadId == officeRoomId && officeParentId == null)
            else {
                val room = checkNotNull(officeRoomForRun(threadId))
                require(officeParentId != null && runId == officeChildId(officeParentId, room, officeStage))
                require(mode == "review" && allowTools && !allowCloudText)
                require((officeStage == OfficeStage.SUMMARY) == (room == com.user.localagent.core.office.ZipHapRoom.MAIN))
            }
        } else require(officeParentId == null)
        require(!scheduledClockReceiptOnly || scheduledReadTool == ScheduledReadPolicy.CLOCK_TOOL)
        require(goalLedger.size<=GoalSegmentBinding.MAX_TOOL_CALLS)
        require(goalLedger.map {it.sourceRunId to it.stepIndex}.distinct().size==goalLedger.size)
        goalSegment?.let { segment->
            require(goal!=null&&segment.goalId==goal.id&&segment.workDigest==goalWorkDigest(goal))
            require(mode in setOf("act","debug")) // Text-only answer checkpoints keep the chain without gaining tools.
            require((segment.rootRunId==runId)==(segment.segmentIndex==1))
            require(segment.parentRunId!=runId&&segment.consumedToolCalls==goalLedger.size)
            require(goalLedger.all {it.sourceRunId!=runId})
            require(goalLedger.all {it.sourceRunId in goal.linkedRunIds})
        }
        require(goalSegment!=null||goalLedger.isEmpty())
        browserWorkRootRunId?.let { require(goalSegment!=null&&it in checkNotNull(goal).linkedRunIds) }
        require(goalReadbackSeedCount in 0..minOf(6,previousSteps.size))
        require(goalReadbackSeedCount==0||goalSegment?.parentRunId!=null&&readbackObligations!=null&&continuation==null)
    }
    fun toJson():String=buildJsonObject {
        put("version",1);put("runId",runId);put("threadId",threadId);put("branchId",branchId)
        put("projectId",projectId);put("text",text);put("modelId",modelId);put("thinking",thinking);put("userMessageId",userMessageId);put("allowTools",allowTools)
        put("mode",mode);put("queueOrder",queueOrder)
        if(cloudReadOnlyModes)put("cloudReadOnlyModes",true)
        put("workspaceBinding",workspaceBinding)
        put("pcDataBinding",pcDataBinding)
        put("kakaoReadBinding",kakaoReadBinding)
        put("pcImageRecoveryAllowed",pcImageRecoveryAllowed)
        put("capturedPcRevision",capturedPcRevision);put("allowCloudText",allowCloudText)
        capturedPcTextDefault?.let {put("capturedPcTextDefault",it)}
        scheduledReadTool?.let {put("scheduledReadTool",it)}
        officeStage?.let { put("officeStage", it.name) }
        officeParentId?.let { put("officeParentId", it) }
        if (scheduledClockReceiptOnly) put("scheduledClockReceiptOnly", true)
        screenVisualBinding?.let {put("screenVisualBinding",it)}
        browserAuthParentRunId?.let {put("browserAuthParentRunId",it)}
        if(goalReadbackSeedCount!=0)put("goalReadbackSeedCount",goalReadbackSeedCount)
        browserWorkRootRunId?.let {put("browserWorkRootRunId",it)}
        put("previousSteps",buildJsonArray {previousSteps.forEach {step->add(buildJsonObject {put("tool",step.tool);put("arguments",step.arguments);put("state",step.evidence.state.name);put("summary",step.evidence.summary);put("effectNotApplied",step.evidence.effectNotApplied);step.evidence.continuationId?.let {put("continuationId",it)};step.evidence.progressDigest?.let {put("progressDigest",it)};step.evidence.auditId?.let {put("auditId",it)};step.evidence.outcomeProof?.let {put("outcomeProof",it)}})}})
        put("attachments",buildJsonArray {attachments.forEach {a->add(buildJsonObject {put("id",a.id);put("name",a.name);put("mimeType",a.mimeType);put("bytes",a.bytes)})}})
        continuation?.let {put("continuation",it.json())}
        goal?.let {put("goal",it.toJson())}
        parentGoalStartRunId?.let {put("parentGoalStartRunId",it)}
        readbackObligations?.let {put("readbackObligations",ChatReadbackObligationCodec.encode(it))}
        put("allowExternalAgent",allowExternalAgent);put("externalAgentRevision",externalAgentRevision)
        goalSegment?.let {put("goalSegment",it.json());put("goalLedger",buildJsonArray {goalLedger.forEach {entry->add(entry.json())}})}
    }.toString()
    companion object {
        fun fromJson(text:String):QueuedChatRequest {
            val o=Json.parseToJsonElement(text).jsonObject
            require(o.getValue("version").jsonPrimitive.int==1)
            fun s(k:String)=o.getValue(k).jsonPrimitive.content
            return QueuedChatRequest(s("runId"),s("threadId"),s("branchId"),o["projectId"]?.jsonPrimitive?.contentOrNull,
                s("text"),s("modelId"),o.getValue("thinking").jsonPrimitive.boolean,
                o["attachments"]?.jsonArray.orEmpty().map {v->val a=v.jsonObject;ChatAttachment(a.getValue("id").jsonPrimitive.content,a.getValue("name").jsonPrimitive.content,a.getValue("mimeType").jsonPrimitive.content,a.getValue("bytes").jsonPrimitive.long)},s("userMessageId"),
                o["continuation"]?.jsonObject?.let(ContinuationSnapshot::parse),o["allowTools"]?.jsonPrimitive?.booleanOrNull?:true,
                o["mode"]?.jsonPrimitive?.content?:"act",
                o["previousSteps"]?.jsonArray.orEmpty().map {element->val step=element.jsonObject;val tool=step.getValue("tool").jsonPrimitive.content;require(tool in ToolRegistry.definitions){"등록되지 않은 도구의 실행 근거는 복원할 수 없습니다."};val args=step.getValue("arguments").jsonObject;val state=VerificationState.valueOf(step.getValue("state").jsonPrimitive.content);if(state==VerificationState.FAILED)require(args.keys.all{it in ToolRegistry.definitions.getValue(tool).arguments.keys}){"허용되지 않은 도구 인자입니다."} else ToolRegistry.validate(ToolCall(java.util.UUID.randomUUID().toString(),tool,args));HarnessStep(tool,args,StepEvidence(tool,state,step.getValue("summary").jsonPrimitive.content,step["continuationId"]?.jsonPrimitive?.contentOrNull,step["effectNotApplied"]?.jsonPrimitive?.boolean?:false,step["progressDigest"]?.jsonPrimitive?.contentOrNull,step["auditId"]?.jsonPrimitive?.contentOrNull,step["outcomeProof"]?.jsonPrimitive?.contentOrNull))},
                o["queueOrder"]?.jsonPrimitive?.longOrNull?:0,o["workspaceBinding"]?.jsonPrimitive?.contentOrNull,
                o["goal"]?.takeUnless {it is JsonNull}?.jsonObject?.let(GoalDefinition::parse),
                o["parentGoalStartRunId"]?.jsonPrimitive?.contentOrNull,
                o["pcDataBinding"]?.jsonPrimitive?.contentOrNull,o["kakaoReadBinding"]?.jsonPrimitive?.contentOrNull,
                o["pcImageRecoveryAllowed"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["capturedPcRevision"]?.takeUnless {it is JsonNull}?.let {require(it is JsonPrimitive&&!it.isString);it.long},
                o["allowCloudText"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["readbackObligations"]?.let(ChatReadbackObligationCodec::decode),
                o["allowExternalAgent"]?.let{require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["externalAgentRevision"]?.takeUnless{it is JsonNull}?.let{require(it is JsonPrimitive&&!it.isString);it.long},
                o["goalSegment"]?.let {GoalSegmentBinding.parse(it.jsonObject)},
                o["goalLedger"]?.let {value->require(value.jsonArray.size<=GoalSegmentBinding.MAX_TOOL_CALLS);value.jsonArray.map {GoalLedgerEntry.parse(it.jsonObject)}}?:emptyList(),
                o["cloudReadOnlyModes"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["capturedPcTextDefault"]?.let {require(it is JsonPrimitive&&it.isString);it.content},
                o["scheduledReadTool"]?.let {require(it is JsonPrimitive&&it.isString);it.content},
                o["officeStage"]?.jsonPrimitive?.content?.let(OfficeStage::valueOf),
                o["officeParentId"]?.jsonPrimitive?.content,
                o["scheduledClockReceiptOnly"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean} ?: false,
                o["screenVisualBinding"]?.let {require(it is JsonPrimitive&&it.isString);it.content},
                o["browserAuthParentRunId"]?.let {require(it is JsonPrimitive&&it.isString);it.content},
                o["goalReadbackSeedCount"]?.let {require(it is JsonPrimitive&&!it.isString);it.int}?:0,
                o["browserWorkRootRunId"]?.let {require(it is JsonPrimitive&&it.isString);it.content})
        }
    }
}

data class ContinuationSnapshot(val messageId:String,val modelId:String,val user:String,val system:String,
    val history:List<InferenceTurn>,val options:InferenceOptions,val nextPart:Int) {
    fun json():JsonObject=buildJsonObject {
        put("messageId",messageId);put("modelId",modelId);put("user",user);put("system",system);put("nextPart",nextPart)
        put("context",options.contextSize);put("cap",options.maxOutputTokens)
        put("backend",options.backend);put("cpuThreads",options.cpuThreads);put("deviceOnly",options.deviceOnly)
        put("audioEnabled",options.audioEnabled);put("visionEnabled",options.visionEnabled);put("projectorPath",options.projectorPath)
        put("history",buildJsonArray {history.forEach {t->add(buildJsonObject {put("role",t.role);put("text",t.text)})}})
    }
    companion object {fun parse(o:JsonObject):ContinuationSnapshot {
        fun s(k:String)=o.getValue(k).jsonPrimitive.content
        return ContinuationSnapshot(s("messageId"),s("modelId"),s("user"),s("system"),
            o.getValue("history").jsonArray.map {val v=it.jsonObject;InferenceTurn(v.getValue("role").jsonPrimitive.content,v.getValue("text").jsonPrimitive.content)},
            InferenceOptions(contextSize=o.getValue("context").jsonPrimitive.int,backend=o["backend"]?.jsonPrimitive?.content?:"CPU",thinking=false,maxOutputTokens=o.getValue("cap").jsonPrimitive.int,
                cpuThreads=o["cpuThreads"]?.jsonPrimitive?.int?:4,deviceOnly=o["deviceOnly"]?.jsonPrimitive?.boolean?:false,
                audioEnabled=o["audioEnabled"]?.jsonPrimitive?.boolean?:false,visionEnabled=o["visionEnabled"]?.jsonPrimitive?.boolean?:false,projectorPath=o["projectorPath"]?.jsonPrimitive?.contentOrNull),o.getValue("nextPart").jsonPrimitive.int)
    }}
}

internal fun <T> List<T>.takeWhileInclusive(predicate:(T)->Boolean):List<T> {
    val end=indexOfFirst { !predicate(it) };return if(end<0)this else take(end+1)
}
