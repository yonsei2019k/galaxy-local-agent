package com.user.localagent.core

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Decoder metadata only. Neither this value nor a matching hash authorizes a file write. */
data class ProjectWriteReadBinding(val path:String,val sha256:String) {
    override fun toString()="ProjectWriteReadBinding(private host metadata)"
}

/** One immutable generation-local hint. No serializer, queue state or tool-call field accepts this object. */
class ProjectWriteSchemaConstraints private constructor(
    private val querySha256:String,private val bindings:List<ProjectWriteReadBinding>,private val usable:Boolean,
) {
    companion object {
        const val MAX_BINDINGS=WorkSegments.STEP_BUDGET
        const val MAX_SCHEMA_CHARS=65536
        private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
        private fun path(value:String)=ProjectPaths.segments(value).joinToString("/")

        /** Caller supplies only accepted current-request host reads. This factory itself grants no authority. */
        fun fromHostReadSnapshot(actualModelQuery:String,accepted:List<ProjectWriteReadBinding>):ProjectWriteSchemaConstraints {
            val queryHash=sha(actualModelQuery)
            if(accepted.size>MAX_BINDINGS)return ProjectWriteSchemaConstraints(queryHash,emptyList(),false)
            val valid=runCatching{accepted.all{path(it.path)==it.path&&it.sha256.matches(Regex("[a-f0-9]{64}"))}}.getOrDefault(false)
            if(!valid||accepted.groupBy{it.path}.any{(_,rows)->rows.map{it.sha256}.distinct().size!=1})
                return ProjectWriteSchemaConstraints(queryHash,emptyList(),false)
            return ProjectWriteSchemaConstraints(queryHash,accepted.distinct().sortedBy{it.path}.toList(),true)
        }

        /** Negative filter only: an actual later failed/partial read cannot revive an earlier good hint. */
        fun latestHostReadTokens(outcomes:List<ObservedToolOutcome>):Map<String,String?> {
            if(outcomes.size>WorkSegments.STEP_BUDGET)return emptyMap()
            val latest=linkedMapOf<String,String?>()
            outcomes.filter{it.call.name=="read_project_file"}.forEach{observed->
                val target=runCatching{
                    val p=observed.call.arguments.getValue("path").jsonPrimitive;require(p.isString);path(p.content)
                }.getOrNull()?:return@forEach
                val token=runCatching{
                    val r=observed.result
                    require(r.id==observed.call.id&&r.success&&r.verification==VerificationState.VERIFIED&&!r.effectNotApplied&&r.content.length<=65536)
                    val data=Json.parseToJsonElement(r.content).jsonObject
                    fun text(key:String)=data.getValue(key).jsonPrimitive.also{require(it.isString)}.content
                    fun flag(key:String)=data.getValue(key).jsonPrimitive.also{require(!it.isString)}.boolean
                    val body=text("text");require(path(text("path"))==target&&body.length<=8000&&!flag("truncated")&&flag("untrustedSourceData"))
                    require(text("sha256")==sha(body))
                    text("readToken").also{require(it.matches(Regex("[a-f0-9-]{36}")))}
                }.getOrNull()
                latest[target]=token
            }
            return latest.toMap()
        }

        /** Select the final whole schema BEFORE token counting. Oversize/invalid hints retain the original safe schema. */
        fun schemaOrOriginal(actualModelQuery:String,hint:ProjectWriteSchemaConstraints?,build:(ProjectWriteSchemaConstraints?)->String):String {
            val original=build(null);require(original.length<=MAX_SCHEMA_CHARS)
            if(hint==null||!hint.usable||hint.querySha256!=sha(actualModelQuery))return original
            val constrained=try{build(hint)}catch(_:IllegalArgumentException){return original}
            return constrained.takeIf{it.length<=MAX_SCHEMA_CHARS}?:original
        }
    }

    /** Existing-file branches couple one path and its original SHA. New files remain a separate hash-absent branch. */
    fun arguments(original:JsonObject):JsonObject {
        require(usable)
        val properties=original.getValue("properties").jsonObject
        require(properties.keys.containsAll(setOf("path","content","expectedSha256")))
        val required=original.getValue("required").jsonArray.map{it.jsonPrimitive.content}
        fun branch(binding:ProjectWriteReadBinding?):JsonObject {
            val fields=properties.toMutableMap()
            if(binding==null)fields.remove("expectedSha256")else {
                fields["path"]=JsonObject(properties.getValue("path").jsonObject+("enum" to buildJsonArray{add(binding.path)}))
                fields["expectedSha256"]=JsonObject(properties.getValue("expectedSha256").jsonObject+("enum" to buildJsonArray{add(binding.sha256)}))
            }
            return JsonObject(original+mapOf(
                "properties" to JsonObject(fields),"additionalProperties" to JsonPrimitive(false),
                "required" to JsonArray((required.filter{it!="expectedSha256"}+if(binding==null)emptyList()else listOf("expectedSha256")).distinct().map(::JsonPrimitive)),
            ))
        }
        val variants=bindings.map{branch(it)}+branch(null)
        return if(variants.size==1)variants.single()else buildJsonObject{put("oneOf",JsonArray(variants))}
    }
    override fun toString()="ProjectWriteSchemaConstraints(generation-local decoder metadata; no write authority)"
}
