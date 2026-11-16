package com.user.localagent.externalagent

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.*

/** A phone-created immutable grant. Legacy/default construction never enables an external agent. */
class ExternalAgentCapture(
    val parentRunId:String,val threadId:String,val branchId:String,val projectId:String?,
    val projectBinding:String,val authorityRevision:Long,val requestDigest:String,
    phoneTools:Set<String>,val allowExternalAgent:Boolean=false,
    val expiresAfterMs:Long=300_000,val pcWorkspaceProfile:String="qa",
    val requirePlan:Boolean=false,
) {
    val phoneTools:Set<String> = Collections.unmodifiableSet(phoneTools.toSet())
    val pcTools:Set<String> get()=if(pcWorkspaceProfile=="phone-only")emptySet()else ExternalAgentContract.PC_TOOLS
    init {
        ExternalAgentContract.uuid(parentRunId)
        listOfNotNull(threadId,branchId,projectId,projectBinding).forEach { ExternalAgentContract.bounded(it,256) }
        require(authorityRevision>=0&&requestDigest.matches(Regex("[a-f0-9]{64}")))
        require(expiresAfterMs in 1000..ExternalAgentContract.MAX_TTL_MS)
        require(pcWorkspaceProfile.matches(Regex("[a-z0-9_-]{1,64}")))
        require(this.phoneTools.isNotEmpty()&&this.phoneTools.size<=128&&this.phoneTools.all { it in ToolRegistry.definitions })
        if(requirePlan)require(this.phoneTools.all { it in ExternalAgentContract.NORMAL_PHONE_TOOLS }){"BRIDGE_UNQUALIFIED_NORMAL_TOOL"}
    }
    /** Metadata/schema only: no original request, conversation, attachment, or credential. */
    fun registration():JsonObject {
        check(allowExternalAgent){"EXTERNAL_AGENT_NOT_CAPTURED"}
        val schemas=Json.parseToJsonElement(ToolRegistry.schemaJson(phoneTools.sorted())).jsonArray
        return buildJsonObject {
            put("version",1);put("parentRunId",parentRunId);put("projectBinding",projectBinding)
            put("authorityRevision",authorityRevision);put("allowExternalAgent",true);put("expiresAfterMs",expiresAfterMs)
            put("pcWorkspaceProfile",pcWorkspaceProfile);put("requestDigest",requestDigest)
            if(requirePlan)put("requirePlan",true) // False/legacy wire stays byte-for-byte free of new plan fields.
            put("phoneTools",buildJsonArray { schemas.forEach { schema ->
                val tool=schema.jsonObject
                add(buildJsonObject {
                    put("name",tool.getValue("name"));put("description",tool["description"]?:JsonPrimitive(""))
                    put("inputSchema",tool.getValue("parameters"))
                })
            } })
        }.also { require(it.toString().toByteArray(Charsets.UTF_8).size<=ExternalAgentContract.MAX_WIRE_BYTES) }
    }
    override fun toString()="ExternalAgentCapture(private phone authority)"
}

/** Supplied afresh by the parent host, never decoded from a model/server response. */
data class ExternalAgentLiveAuthority(
    val parentRunId:String,val threadId:String,val branchId:String,val projectId:String?,
    val projectBinding:String,val authorityRevision:Long,val allowExternalAgent:Boolean,
    val phoneTools:Set<String>,val active:Boolean=true,val cancelled:Boolean=false,val awaitingApproval:Boolean=false,
) {
    fun permits(capture:ExternalAgentCapture):Boolean = sameCapturedAuthority(capture)&&!awaitingApproval
    internal fun sameCapturedAuthority(capture:ExternalAgentCapture):Boolean = capture.allowExternalAgent&&allowExternalAgent&&active&&!cancelled&&
        parentRunId==capture.parentRunId&&threadId==capture.threadId&&branchId==capture.branchId&&projectId==capture.projectId&&
        projectBinding==capture.projectBinding&&authorityRevision==capture.authorityRevision&&phoneTools.containsAll(capture.phoneTools)
}

/** Incoming proposals are untrusted arguments, not grants or evidence of an effect. */
class ExternalAgentProposal(
    val callId:String,val sequence:Int,val tool:String,arguments:JsonObject,
    val requestDigest:String,val projectBinding:String,val authorityRevision:Long,
    val state:AgentPhoneCallState=AgentPhoneCallState.QUEUED,val claimRevision:Long?=null,
) {
    val arguments:JsonObject = Json.parseToJsonElement(arguments.toString()).jsonObject
    init { ExternalAgentContract.uuid(callId);require(sequence in 1..18) }
    fun checkedCall(capture:ExternalAgentCapture):ToolCall {
        require(requestDigest==capture.requestDigest&&projectBinding==capture.projectBinding&&authorityRevision==capture.authorityRevision)
        require(tool in capture.phoneTools&&arguments.toString().toByteArray(Charsets.UTF_8).size<=32_768)
        return ToolRegistry.validate(ToolCall(callId,tool,arguments))
    }
    fun fingerprint():String = ExternalAgentContract.sha(buildJsonObject {
        put("callId",callId);put("sequence",sequence);put("tool",tool);put("arguments",ExternalAgentContract.sorted(arguments))
        put("requestDigest",requestDigest);put("projectBinding",projectBinding);put("authorityRevision",authorityRevision)
    }.toString())
    override fun toString()="ExternalAgentProposal(untrusted scoped tool arguments)"
}
enum class AgentPhoneCallState { QUEUED,CLAIMED,UNKNOWN_EFFECT }

enum class AgentPlanState { AWAITING_HOST,ACCEPTED,REJECTED }
/** Ephemeral untrusted intent, never a persistent goal, permission, approval or completion receipt. */
class ExternalAgentPlan(
    val state:AgentPlanState,val planDigest:String,val requestDigest:String,val goal:String,
    requiredTools:Collection<String>,constraints:List<String>,
) {
    private val orderedTools:List<String> = Collections.unmodifiableList(requiredTools.toList())
    val requiredTools:Set<String> = Collections.unmodifiableSet(requiredTools.toSet())
    val constraints:List<String> = Collections.unmodifiableList(constraints.toList())
    init {
        require(planDigest.matches(Regex("[a-f0-9]{64}"))&&requestDigest.matches(Regex("[a-f0-9]{64}")))
        require(goal.isNotBlank()&&goal.length<=1000&&'\u0000' !in goal)
        require(orderedTools.distinct().size==orderedTools.size&&this.requiredTools.size<=12&&this.constraints.size<=6&&this.constraints.all{it.isNotBlank()&&it.length<=240&&'\u0000' !in it})
    }
    fun checked(capture:ExternalAgentCapture):ExternalAgentPlan {
        require(capture.requirePlan&&requestDigest==capture.requestDigest)
        require(requiredTools.all{it in capture.phoneTools||it in capture.pcTools})
        require(planDigest==ExternalAgentContract.planDigest(capture,goal,orderedTools,constraints)){"BRIDGE_PLAN_DIGEST"}
        return this
    }
    override fun toString()="ExternalAgentPlan(untrusted ephemeral intent)"
}

object ExternalAgentContract {
    const val MAX_TTL_MS=900_000L
    const val MAX_WIRE_BYTES=262_144
    const val MAX_RESULT_BYTES=131_072
    /**
     * Phone tools a cloud model may propose: the same registered set the local model is offered
     * (the coordinator still removes project-disabled and work-mode-excluded tools). Each call still runs
     * through runTool: project/mode policy, recipient choice, owner approval and the effect guard.
     * PREPARED hand-offs pause the run. The PC broker accepts at most 128 tools per session.
     */
    val NORMAL_PHONE_TOOLS:Set<String> get() = ToolRegistry.definitions.keys
    val PC_TOOLS:Set<String> = setOf("pc_inspect_workspace","pc_read_file")
    fun uuid(value:String) { require(runCatching { UUID.fromString(value).toString()==value }.getOrDefault(false)){"BRIDGE_UUID"} }
    fun bounded(value:String,max:Int) { require(value.isNotBlank()&&value.length<=max&&value.none { it.code<32 }){"BRIDGE_TEXT"} }
    fun sha(value:String):String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt()and 255) }
    /** Model list order and exact text survive; only JSON OBJECT keys are sorted for the wire hash. */
    fun planDigest(capture:ExternalAgentCapture,goal:String,requiredTools:Collection<String>,constraints:List<String>):String=sha(sorted(buildJsonObject {
        put("version",1);put("parentRunId",capture.parentRunId);put("projectBinding",capture.projectBinding)
        put("authorityRevision",capture.authorityRevision);put("requestDigest",capture.requestDigest);put("goal",goal)
        put("requiredTools",JsonArray(requiredTools.map(::JsonPrimitive)));put("constraints",JsonArray(constraints.map(::JsonPrimitive)))
    }).toString())
    internal fun sorted(value:JsonElement):JsonElement=when(value) {
        is JsonObject->JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray->JsonArray(value.map(::sorted))
        else->value
    }
    internal fun fields(value:JsonObject,required:Set<String>,optional:Set<String> = emptySet()) {
        require(value.keys.containsAll(required)&&value.keys.all { it in required||it in optional }){"BRIDGE_FIELDS"}
    }
    internal fun text(value:JsonObject,key:String):String=(value.getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    internal fun long(value:JsonObject,key:String):Long=(value.getValue(key) as JsonPrimitive).also { require(!it.isString) }.long
    internal fun plan(value:JsonObject,capture:ExternalAgentCapture):ExternalAgentPlan {
        fields(value,setOf("state","planDigest","requestDigest","goal","requiredTools","constraints"))
        val tools=value.getValue("requiredTools").jsonArray.map { (it as JsonPrimitive).also{p->require(p.isString)}.content }
        val constraints=value.getValue("constraints").jsonArray.map { (it as JsonPrimitive).also{p->require(p.isString)}.content }
        require(tools.distinct().size==tools.size)
        return ExternalAgentPlan(AgentPlanState.valueOf(text(value,"state")),text(value,"planDigest"),text(value,"requestDigest"),
            text(value,"goal"),tools,constraints).checked(capture)
    }
    internal fun proposal(value:JsonObject):ExternalAgentProposal {
        fields(value,setOf("callId","sequence","state","tool","arguments","requestDigest","projectBinding","authorityRevision"),setOf("claimRevision"))
        val sequence=long(value,"sequence");require(sequence in 1..18)
        val state=AgentPhoneCallState.valueOf(text(value,"state"))
        val claimRevision=value["claimRevision"]?.let{long(value,"claimRevision")}
        require(if(state==AgentPhoneCallState.QUEUED)claimRevision==null else claimRevision!=null&&claimRevision>0)
        return ExternalAgentProposal(text(value,"callId"),sequence.toInt(),text(value,"tool"),value.getValue("arguments").jsonObject,
            text(value,"requestDigest"),text(value,"projectBinding"),long(value,"authorityRevision"),state,claimRevision)
    }
    /** Reject nested duplicate/escaped aliases before JsonObject could retain only the last key. */
    fun parseObject(raw:String):JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_WIRE_BYTES){"BRIDGE_WIRE_BOUND"}
        var at=0;var nodes=0
        fun space(){while(at<raw.length&&raw[at].isWhitespace())at++}
        fun string():String {
            val start=at;require(raw.getOrNull(at)=='"');at++;var escaped=false
            while(at<raw.length){
                val c=raw[at++]
                if(c=='"'&&!escaped)return Json.decodeFromString<String>(raw.substring(start,at))
                if(c=='\\'&&!escaped)escaped=true else escaped=false
            }
            error("BRIDGE_JSON_STRING")
        }
        lateinit var value:(Int)->Unit
        value={depth->
            require(depth<=16&&++nodes<=8192){"BRIDGE_JSON_BOUND"};space()
            when(raw.getOrNull(at)){
                '{'->{at++;space();val keys=mutableSetOf<String>()
                    if(raw.getOrNull(at)=='}')at++ else while(true){
                        space();require(keys.add(string())){"BRIDGE_DUPLICATE_KEY"};space();require(raw.getOrNull(at++)==':')
                        value(depth+1);space();val end=raw.getOrNull(at++);if(end=='}')break;require(end==',')
                    }}
                '['->{at++;space();if(raw.getOrNull(at)==']')at++ else while(true){
                    value(depth+1);space();val end=raw.getOrNull(at++);if(end==']')break;require(end==',')
                }}
                '"'->{string();Unit}
                else->{val start=at;while(at<raw.length&&!raw[at].isWhitespace()&&raw[at] !in ",]}")at++;require(at>start)}
            }
        }
        value(0);space();require(at==raw.length)
        return Json.parseToJsonElement(raw).jsonObject
    }
}
