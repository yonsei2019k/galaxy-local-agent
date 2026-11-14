package com.user.localagent

import com.user.localagent.core.RequiredReadbackObligations as Ledger
import com.user.localagent.core.RequiredReadbackPolicy as Proof
import kotlinx.serialization.json.*

/** Typed encrypted-context metadata only. Never accept this object from model/tool/message JSON. */
internal object ChatReadbackObligationCodec {
    const val MAX_JSON_CHARS=131_072
    fun encode(state:Ledger.State):JsonObject=buildJsonObject {
        put("version",1)
        put("context",buildJsonObject {
            put("runId",state.context.runId);put("threadId",state.context.threadId);put("projectId",state.context.projectId)
            put("scopeKey",state.context.scopeKey);put("workspaceBinding",state.context.workspaceBinding)
        })
        put("originRunId",state.originRunId);put("invalidHostProof",state.invalidHostProof)
        put("selectedPairs",buildJsonArray { state.selected.sortedBy { it.name }.forEach { add(JsonPrimitive(it.name)) } })
        put("applied",buildJsonArray { state.applied.forEach { a->add(buildJsonObject {
            put("source",ref(a.source));put("after",snapshot(a.after));put("latestRead",a.latestRead?.let { r->buildJsonObject {
                put("source",ref(r.source));put("returned",r.returned?.let(::snapshot)?:JsonNull)
                put("complete",r.complete);put("associated",r.associated);put("querySha256",r.querySha256)
            } }?:JsonNull)
            put("coverage",buildJsonArray {a.coverage.forEach {c->add(buildJsonObject {put("source",ref(c.source));put("querySha256",c.querySha256)})}})
        }) } })
    }.also { require(it.toString().length<=MAX_JSON_CHARS) }

    /** Present-but-invalid is an error, never the legacy absent/default state. */
    fun decode(element:JsonElement):Ledger.State {
        require(element.toString().length<=MAX_JSON_CHARS)
        val o=obj(element,setOf("version","context","originRunId","invalidHostProof","selectedPairs","applied"))
        require(number(o,"version")==1)
        val c=obj(o.getValue("context"),setOf("runId","threadId","projectId","scopeKey","workspaceBinding"))
        val context=Ledger.Context(string(c,"runId"),string(c,"threadId"),nullableString(c,"projectId"),string(c,"scopeKey"),nullableString(c,"workspaceBinding"))
        val pairs=o.getValue("selectedPairs").jsonArray.also { require(it.size in 1..Ledger.Pair.entries.size) }
            .map { require(it is JsonPrimitive&&it.isString);Ledger.Pair.valueOf(it.content) }.also { require(it.distinct().size==it.size) }.toSet()
        val applied=o.getValue("applied").jsonArray.also { require(it.size<=Ledger.MAX_STEPS) }.map { value->
            val a=obj(value,setOf("source","after","latestRead","coverage"))
            val read=a.getValue("latestRead").takeUnless { it is JsonNull }?.let { valueRead->
                val r=obj(valueRead,setOf("source","returned","complete","associated","querySha256"))
                Ledger.Observation(parseRef(r.getValue("source")),r.getValue("returned").takeUnless { it is JsonNull }?.let(::parseSnapshot),bool(r,"complete"),bool(r,"associated"),nullableString(r,"querySha256"))
            }
            val coverage=a.getValue("coverage").jsonArray.also {require(it.size<=Ledger.MAX_STEPS)}.map {valueCoverage->
                val c=obj(valueCoverage,setOf("source","querySha256"));Ledger.QueryAssociation(parseRef(c.getValue("source")),nullableString(c,"querySha256"))
            }
            Ledger.Applied(parseRef(a.getValue("source")),parseSnapshot(a.getValue("after")),read,coverage)
        }
        return Ledger.State(context,string(o,"originRunId"),pairs,applied,bool(o,"invalidHostProof"))
    }
    private fun ref(r:Ledger.StepRef)=buildJsonObject { put("index",r.index);put("tool",r.tool);put("callSha256",r.callSha256);put("sourceRunId",r.sourceRunId) }
    private fun snapshot(s:Proof.Snapshot)=buildJsonObject { put("kind",s.resource.kind);put("id",s.resource.stableId);put("revision",s.revision);put("stateSha256",s.stateSha256) }
    private fun parseRef(value:JsonElement):Ledger.StepRef {
        val r=obj(value,setOf("index","tool","callSha256","sourceRunId"))
        return Ledger.StepRef(number(r,"index"),string(r,"tool"),string(r,"callSha256"),string(r,"sourceRunId"))
    }
    private fun parseSnapshot(value:JsonElement):Proof.Snapshot {
        val s=obj(value,setOf("kind","id","revision","stateSha256"))
        return Proof.Snapshot(Proof.Resource(string(s,"kind"),string(s,"id")),string(s,"revision"),string(s,"stateSha256"))
    }
    private fun obj(value:JsonElement,keys:Set<String>)=value.jsonObject.also { require(it.keys==keys) }
    private fun string(o:JsonObject,key:String)=o.getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private fun nullableString(o:JsonObject,key:String)=o.getValue(key).takeUnless { it is JsonNull }?.let { require(it is JsonPrimitive&&it.isString);it.content }
    private fun bool(o:JsonObject,key:String)=o.getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
    private fun number(o:JsonObject,key:String)=o.getValue(key).jsonPrimitive.also { require(!it.isString) }.int
}
