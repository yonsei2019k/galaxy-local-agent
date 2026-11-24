package com.user.localagent.core

import kotlinx.serialization.json.*

/** A question pauses execution. It is never a registered tool, permission, or completion. */
object NextActionQuestion {
    const val PAUSE_CODE="awaiting_owner_input"
    const val CLOUD_PAUSE_CODE="cloud_clarification_read_only"
    const val CHOICES_KEY="ownerReplyChoices"
    data class Choice(val label:String,val recommended:Boolean) {
        fun toJson()=buildJsonObject {put("label",label);put("recommended",recommended)}
    }
    data class Prompt(val question:String,val choices:List<Choice>)

    fun readChoices(value:JsonElement?):List<Choice> {
        val list=value as? JsonArray ?: return emptyList()
        if(list.size !in 2..4)return emptyList()
        val choices=list.map { item->
            val row=item as? JsonObject ?: return emptyList()
            if(row.keys!=setOf("label","recommended"))return emptyList()
            val label=(row["label"] as? JsonPrimitive)?.takeIf{it.isString}?.content?.trim() ?: return emptyList()
            val recommendation=(row["recommended"] as? JsonPrimitive)?.takeUnless{it.isString}?.booleanOrNull ?: return emptyList()
            if(label.isBlank()||label.length>40||label.startsWith("/")||label.any{it.isISOControl()}||
                '<' in label||'>' in label)return emptyList()
            Choice(label,recommendation)
        }
        return choices.takeIf{it.count(Choice::recommended)==1&&it.map{choice->choice.label}.toSet().size==it.size}.orEmpty()
    }
    fun schema(tools:Set<String>,ownerRequest:String?=null,projectWrites:ProjectWriteSchemaConstraints?=null):String {
        return extend(ToolCallSchema.forTools(tools,ownerRequest,projectWrites))
    }
    fun extend(schema:String):String {
        val original=Json.parseToJsonElement(schema).jsonObject
        return JsonObject(original+mapOf("oneOf" to JsonArray(original.getValue("oneOf").jsonArray+buildJsonObject {
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject {
                put("type",buildJsonObject {put("type","string");put("enum",buildJsonArray{add("clarification")})})
                put("question",buildJsonObject {put("type","string");put("minLength",1);put("maxLength",300)})
                put("choices",buildJsonObject {
                    put("type","array");put("minItems",2);put("maxItems",4)
                    put("description","Optional short answers when the owner has a finite choice. Mark exactly one recommended. Omit for open questions or protected approvals.")
                    put("items",buildJsonObject {
                        put("type","object");put("additionalProperties",false)
                        put("properties",buildJsonObject {
                            put("label",buildJsonObject {put("type","string");put("minLength",1);put("maxLength",40)})
                            put("recommended",buildJsonObject {put("type","boolean")})
                        })
                        put("required",buildJsonArray{add("label");add("recommended")})
                    })
                })
            })
            put("required",buildJsonArray{add("type");add("question")})
        }))).toString().also{require(it.length<=65536)}
    }

    /** Null means a different action; malformed questions fail closed before any execution. */
    fun parse(raw:String):String? = parsePrompt(raw)?.question

    fun parsePrompt(raw:String):Prompt? {
        val value=runCatching{Json.parseToJsonElement(raw) as? JsonObject}.getOrNull()?:return null
        if((value["type"] as? JsonPrimitive)?.contentOrNull!="clarification")return null
        require(raw.length<=32768)
        rejectDuplicateKeys(raw)
        require(value.keys in setOf(setOf("type","question"),setOf("type","question","choices"))){"질문 형식에 실행 인자나 추가 필드를 넣을 수 없습니다."}
        val primitive=requireNotNull(value["question"] as? JsonPrimitive){"질문은 문자열이어야 합니다."}
        require(primitive.isString){"질문은 문자열이어야 합니다."}
        val question=primitive.content.trim()
        require(question.isNotBlank()&&question.length<=300&&'\u0000' !in question){"질문은 1–300자여야 합니다."}
        require(!question.contains("<think",true)&&!question.contains("</think",true)){"내부 추론을 질문으로 표시할 수 없습니다."}
        return Prompt(question,readChoices(value["choices"]))
    }
}
