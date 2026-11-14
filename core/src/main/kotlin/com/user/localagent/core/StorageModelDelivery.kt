package com.user.localagent.core

import kotlinx.serialization.json.*

/** Model-context projection for the TWO trusted host storage tools only; no execution or permission. */
object StorageModelDelivery {
    const val MAX_MODEL_CHARS=4_096
    private const val MAX_RAW_CHARS=131_072
    fun modelResult(raw:String,maxChars:Int=MAX_MODEL_CHARS):String {
        require(maxChars in 512..MAX_MODEL_CHARS)
        val original=if(raw.length>MAX_RAW_CHARS)null else try{
            rejectDuplicateKeys(raw);Json.parseToJsonElement(raw) as? JsonObject
        }catch(_:Exception){null}
        if(original==null||original.string("status")!="ok"||original.string("scope")!="selected_project_only"||
            original.flag("readOnly")!=true||original.flag("filesChanged")!=false||
            original.count("deletedFiles")!=0L||original.count("movedFiles")!=0L)
            return wrap(JsonNull,true,true).toString()
        val exact=wrap(original,false,sourcePartial(original))
        if(exact.toString().length<=maxChars)return exact.toString()
        // Reorder only. Never derive counts/bytes from clipped groups or insert an expected fixture value.
        val prioritized=linkedMapOf<String,JsonElement>()
        priority.forEach{key->original[key]?.let{prioritized[key]=it}}
        original.forEach{(key,value)->if(key !in prioritized)prioritized[key]=value}
        val compacted=Json.parseToJsonElement(compactJsonData(JsonObject(prioritized).toString(),maxChars)).jsonObject
        // Entire receipt did not fit, even if compactor's first pass happened to remove only noncritical fields.
        return wrap(compacted["data"]?:JsonNull,true,true).toString().also{check(it.length<=maxChars)}
    }
    private fun wrap(data:JsonElement,truncated:Boolean,sourcePartial:Boolean)=buildJsonObject {
        put("data",data);put("truncated",truncated);put("sourceScopePartial",sourcePartial);put("dataOnly",true)
    }
    private fun sourcePartial(data:JsonElement,depth:Int=0):Boolean=if(depth>16)true else when(data){
        is JsonObject->data.any{(key,value)->
            when {
                key in partialFlags->(value as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull!=false
                key=="enumerationComplete"->(value as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull!=true
                key in omittedCounts->(value as? JsonPrimitive)?.takeIf{!it.isString}?.longOrNull?.let{it!=0L}?:true
                key=="issues"->value !is JsonArray||value.isNotEmpty()
                else->sourcePartial(value,depth+1)
            }
        }||("status" in data&&data.flag("partial")==null)
        is JsonArray->data.any{sourcePartial(it,depth+1)}
        else->false
    }
    private val partialFlags=setOf("partial","truncated","sourceScopePartial","hasMore","has_more","sizeTotalCapped")
    private val omittedCounts=setOf("skippedHashCandidates","unknownSizeFiles","duplicateGroupsOmitted","duplicatePathsOmitted","pathsOmitted","extensionGroupsOmitted")
    private val priority=listOf("status","scope","readOnly","partial","enumerationComplete","observedFiles",
        "observedDuplicateGroups","potentialDuplicateBytes","observedKnownBytes","duplicateGroups",
        "skippedHashCandidates","unknownSizeFiles","sizeTotalCapped","duplicateGroupsOmitted","duplicatePathsOmitted",
        "issues","limits","filesChanged","deletedFiles","movedFiles","comparison","hashedFiles","verifiedHashBytes",
        "hashBudgetChargedBytes","extensionGroupsOmitted","largestFiles","extensionGroups","path")
    private fun JsonObject.string(key:String)=(this[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
    private fun JsonObject.flag(key:String)=(this[key] as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull
    private fun JsonObject.count(key:String)=(this[key] as? JsonPrimitive)?.takeIf{!it.isString}?.longOrNull
}
