package com.user.localagent.core

import kotlinx.serialization.json.*

/** A question pauses execution. It is never a registered tool, permission, or completion. */
object NextActionQuestion {
    const val PAUSE_CODE="awaiting_owner_input"
    fun schema(tools:Set<String>,ownerRequest:String?=null):String {
        val original=Json.parseToJsonElement(ToolCallSchema.forTools(tools,ownerRequest)).jsonObject
        return JsonObject(original+mapOf("oneOf" to JsonArray(original.getValue("oneOf").jsonArray+buildJsonObject {
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject {
                put("type",buildJsonObject {put("type","string");put("enum",buildJsonArray{add("clarification")})})
                put("question",buildJsonObject {put("type","string");put("minLength",1);put("maxLength",300)})
            })
            put("required",buildJsonArray{add("type");add("question")})
        }))).toString().also{require(it.length<=65536)}
    }

    /** Null means a different action; malformed questions fail closed before any execution. */
    fun parse(raw:String):String? {
        val value=runCatching{Json.parseToJsonElement(raw) as? JsonObject}.getOrNull()?:return null
        if((value["type"] as? JsonPrimitive)?.contentOrNull!="clarification")return null
        require(raw.length<=32768)
        rejectDuplicateKeys(raw)
        require(value.keys==setOf("type","question")){"질문 형식에 실행 인자나 추가 필드를 넣을 수 없습니다."}
        val primitive=requireNotNull(value["question"] as? JsonPrimitive){"질문은 문자열이어야 합니다."}
        require(primitive.isString){"질문은 문자열이어야 합니다."}
        val question=primitive.content.trim()
        require(question.isNotBlank()&&question.length<=300&&'\u0000' !in question){"질문은 1–300자여야 합니다."}
        require(!question.contains("<think",true)&&!question.contains("</think",true)){"내부 추론을 질문으로 표시할 수 없습니다."}
        return question
    }
}
