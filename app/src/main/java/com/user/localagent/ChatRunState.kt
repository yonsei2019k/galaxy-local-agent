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
) {
    init {
        AgentWorkMode.parse(mode)
        goal?.let {it.validate();require(it.threadId==threadId&&it.projectId==projectId){"목표 스냅샷의 대화·프로젝트가 실행과 다릅니다."}}
        parentGoalStartRunId?.let {require(goal!=null&&mode in setOf("act","debug")&&it.isNotBlank()&&it.length<=100&&it!=runId&&it.none {c->c.code<32}){"목표 시작 요청의 원래 실행 연결이 올바르지 않습니다."}}
        pcDataBinding?.let {require(it.matches(Regex("[a-f0-9]{64}"))){"PC 자료 선택의 고정 버전이 올바르지 않습니다."}}
        kakaoReadBinding?.let {require(runCatching{java.util.UUID.fromString(it).toString()==it}.getOrDefault(false))}
        require(capturedPcRevision==null||capturedPcRevision>=0)
        require(!allowCloudText||capturedPcRevision!=null){"클라우드 사용은 현재 요청에 캡처한 명시 권한이 필요합니다."}
    }
    fun toJson():String=buildJsonObject {
        put("version",1);put("runId",runId);put("threadId",threadId);put("branchId",branchId)
        put("projectId",projectId);put("text",text);put("modelId",modelId);put("thinking",thinking);put("userMessageId",userMessageId);put("allowTools",allowTools)
        put("mode",mode);put("queueOrder",queueOrder)
        put("workspaceBinding",workspaceBinding)
        put("pcDataBinding",pcDataBinding)
        put("kakaoReadBinding",kakaoReadBinding)
        put("pcImageRecoveryAllowed",pcImageRecoveryAllowed)
        put("capturedPcRevision",capturedPcRevision);put("allowCloudText",allowCloudText)
        put("previousSteps",buildJsonArray {previousSteps.forEach {step->add(buildJsonObject {put("tool",step.tool);put("arguments",step.arguments);put("state",step.evidence.state.name);put("summary",step.evidence.summary);put("effectNotApplied",step.evidence.effectNotApplied);step.evidence.continuationId?.let {put("continuationId",it)}})}})
        put("attachments",buildJsonArray {attachments.forEach {a->add(buildJsonObject {put("id",a.id);put("name",a.name);put("mimeType",a.mimeType);put("bytes",a.bytes)})}})
        continuation?.let {put("continuation",it.json())}
        goal?.let {put("goal",it.toJson())}
        parentGoalStartRunId?.let {put("parentGoalStartRunId",it)}
        readbackObligations?.let {put("readbackObligations",ChatReadbackObligationCodec.encode(it))}
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
                o["previousSteps"]?.jsonArray.orEmpty().map {element->val step=element.jsonObject;val tool=step.getValue("tool").jsonPrimitive.content;require(tool in ToolRegistry.definitions){"등록되지 않은 도구의 실행 근거는 복원할 수 없습니다."};val args=step.getValue("arguments").jsonObject;ToolRegistry.validate(ToolCall(java.util.UUID.randomUUID().toString(),tool,args));HarnessStep(tool,args,StepEvidence(tool,VerificationState.valueOf(step.getValue("state").jsonPrimitive.content),step.getValue("summary").jsonPrimitive.content,step["continuationId"]?.jsonPrimitive?.contentOrNull,step["effectNotApplied"]?.jsonPrimitive?.boolean?:false))},
                o["queueOrder"]?.jsonPrimitive?.longOrNull?:0,o["workspaceBinding"]?.jsonPrimitive?.contentOrNull,
                o["goal"]?.takeUnless {it is JsonNull}?.jsonObject?.let(GoalDefinition::parse),
                o["parentGoalStartRunId"]?.jsonPrimitive?.contentOrNull,
                o["pcDataBinding"]?.jsonPrimitive?.contentOrNull,o["kakaoReadBinding"]?.jsonPrimitive?.contentOrNull,
                o["pcImageRecoveryAllowed"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["capturedPcRevision"]?.takeUnless {it is JsonNull}?.let {require(it is JsonPrimitive&&!it.isString);it.long},
                o["allowCloudText"]?.let {require(it is JsonPrimitive&&!it.isString);it.boolean}?:false,
                o["readbackObligations"]?.let(ChatReadbackObligationCodec::decode))
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
