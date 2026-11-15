package com.user.localagent.core

import kotlinx.serialization.json.*

/** Opt-in decoder protocol only. No branch grants tool authority or proves completion. */
object DirectNextActionProtocol {
    const val MAX_TOOLS=ToolArgumentCatalogSelection.MAX_FALLBACK_TOOLS
    const val MAX_RAW_CHARS=32768
    const val instruction="Return only a tool_call object, a clarification object, or {\"type\":\"answer\"}. Select answer only when current facts suffice for an ordinary reply; use an offered tool for needed evidence/action or ask an essential missing owner choice. The signal is not reply text or completion evidence; never claim an unexecuted action succeeded."

    sealed interface Decision {
        data class Tool(val call:ToolCall):Decision
        data class Clarification(val question:String):Decision
        /** Host may generate one ordinary reply; existing evidence/readback gates remain mandatory. */
        data object Answer:Decision
    }

    /** Root must also enforce the actual compact-catalog budget of its existing selection. */
    fun eligible(directLoop:Boolean,supportsStructuredOutput:Boolean,allowedTools:Set<String>,detailedTools:Set<String>):Boolean =
        directLoop&&supportsStructuredOutput&&allowedTools.size in 1..MAX_TOOLS&&
            allowedTools==detailedTools&&allowedTools.all{it in ToolRegistry.definitions}

    private fun allowedSnapshot(tools:Set<String>):Set<String> = tools.toSet().also{snapshot->
        require(snapshot.size in 1..MAX_TOOLS){"DIRECT_ACTION_TOOL_COUNT"}
        require(snapshot.all{it in ToolRegistry.definitions}){"DIRECT_ACTION_UNKNOWN_HOST_TOOL"}
    }

    fun schema(allowedTools:Set<String>,ownerRequest:String?=null,projectWrites:ProjectWriteSchemaConstraints?=null):String {
        val allowed=allowedSnapshot(allowedTools)
        val original=Json.parseToJsonElement(NextActionQuestion.schema(allowed,ownerRequest,projectWrites)).jsonObject
        val answer=buildJsonObject {
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject{
                put("type",buildJsonObject{put("type","string");put("enum",buildJsonArray{add("answer")})})
            })
            put("required",buildJsonArray{add("type")})
        }
        return JsonObject(original+mapOf("oneOf" to JsonArray(original.getValue("oneOf").jsonArray+answer)))
            .toString().also{require(it.length<=65536){"DIRECT_ACTION_SCHEMA_LIMIT"}}
    }

    /** Full JSON document only. Invalid/unknown output never falls through to an ordinary reply. */
    fun parse(raw:String,allowedTools:Set<String>):Decision {
        val allowed=allowedSnapshot(allowedTools)
        require(raw.isNotBlank()&&raw.length<=MAX_RAW_CHARS){"DIRECT_ACTION_INPUT_LIMIT"}
        rejectDuplicateKeys(raw)
        val value=Json.parseToJsonElement(raw) as? JsonObject?:error("DIRECT_ACTION_OBJECT_REQUIRED")
        val type=(value["type"] as? JsonPrimitive)?.takeIf{it.isString}?.content?:error("DIRECT_ACTION_STRING_DISCRIMINATOR_REQUIRED")
        return when(type){
            "tool_call"->Decision.Tool(ToolRegistry.validateForRequest(ToolRegistry.parseAndValidate(raw),allowed))
            "clarification"->Decision.Clarification(checkNotNull(NextActionQuestion.parse(raw)))
            "answer"->{require(value.keys==setOf("type")){"DIRECT_ACTION_ANSWER_FIELDS"};Decision.Answer}
            else->error("DIRECT_ACTION_UNKNOWN_TYPE")
        }
    }
}
