package com.user.localagent.workspace

import com.user.localagent.core.ProjectPaths
import kotlinx.serialization.json.*

data class ProjectToolRead(val path:String,val text:String,val sha256:String,val token:String)
data class DeliveredProjectRead(val path:String,val text:String,val sha256:String,val token:String)

/** Only complete, intact host tool evidence present in the actual model query can authorize a rewrite. */
object ProjectReadDelivery {
    /** Preserve this one source verbatim; tokenizer fitting may reject it but must not replace it with an excerpt. */
    fun modelResult(rawResult:String):String=runCatching {
        val data=Json.parseToJsonElement(rawResult).jsonObject
        require(data.keys==setOf("path","text","truncated","sha256","readToken","untrustedSourceData"))
        fun text(key:String)=data.getValue(key).jsonPrimitive.also{require(it.isString)}.content
        val body=text("text");require(body.length<=8000)
        ProjectPaths.segments(text("path"));require(text("sha256").matches(Regex("[a-f0-9]{64}")))
        require(text("readToken").matches(Regex("[a-f0-9-]{36}")))
        val flag=data.getValue("truncated").jsonPrimitive;require(!flag.isString&&flag.booleanOrNull!=null)
        val untrusted=data.getValue("untrustedSourceData").jsonPrimitive;require(!untrusted.isString&&untrusted.booleanOrNull==true)
        buildJsonObject {put("data",data);put("truncated",flag.boolean);put("sourceScopePartial",flag.boolean);put("dataOnly",true)}.toString()
    }.getOrElse{com.user.localagent.core.compactJsonData(rawResult,1100)}
    fun delivered(evidence:List<JsonObject>,actualModelQuery:String):List<DeliveredProjectRead> {
        if(evidence.isEmpty()||!actualModelQuery.contains(JsonArray(evidence).toString()))return emptyList()
        fun bool(o:JsonObject,key:String,expected:Boolean):Boolean=(o[key] as? JsonPrimitive)?.let{!it.isString&&it.booleanOrNull==expected}==true
        fun text(o:JsonObject,key:String):String?=(o[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
        return evidence.mapNotNull{item->runCatching{
            if(text(item,"tool")!="read_project_file"||!bool(item,"success",true)||text(item,"verification")!="VERIFIED")return@runCatching null
            val wrapped=item["result"] as? JsonObject?:return@runCatching null
            if(!bool(wrapped,"truncated",false)||!bool(wrapped,"sourceScopePartial",false)||!bool(wrapped,"dataOnly",true))return@runCatching null
            val data=wrapped["data"] as? JsonObject?:return@runCatching null
            if(!bool(data,"truncated",false))return@runCatching null
            val path=text(data,"path")?:return@runCatching null
            ProjectPaths.segments(path)
            val body=text(data,"text")?:return@runCatching null
            val hash=text(data,"sha256")?.takeIf{it.matches(Regex("[a-f0-9]{64}"))}?:return@runCatching null
            val token=text(data,"readToken")?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}?:return@runCatching null
            DeliveredProjectRead(path,body,hash,token)
        }.getOrNull()}
    }
}
