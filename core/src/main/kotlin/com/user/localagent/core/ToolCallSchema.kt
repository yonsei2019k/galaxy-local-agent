package com.user.localagent.core

import kotlinx.serialization.json.*

/** Native decoding format only. It never grants permission or replaces host validation/approval. */
object ToolCallSchema {
    fun forTools(allowedTools:Set<String>,ownerRequest:String?=null,projectWrites:ProjectWriteSchemaConstraints?=null):String {
        require(allowedTools.isNotEmpty()&&allowedTools.size<=40){"구조화 출력에는 허용한 도구1–40개가 필요합니다."}
        require(allowedTools.all {it in ToolRegistry.definitions}){"알 수 없는 도구의 출력 스키마를 만들 수 없습니다."}
        val names=allowedTools.sorted()
        val definitions=Json.parseToJsonElement(ToolRegistry.schemaJson(names)).jsonArray.associate {item->
            val entry=item.jsonObject
            entry.getValue("name").jsonPrimitive.content to entry.getValue("parameters").jsonObject
        }
        return buildJsonObject {
            put("type","object")
            put("oneOf",buildJsonArray {names.forEach {name->add(buildJsonObject {
                put("type","object");put("additionalProperties",false)
                put("properties",buildJsonObject {
                    put("type",buildJsonObject {put("type","string");put("enum",buildJsonArray {add("tool_call")})})
                    put("tool",buildJsonObject {put("type","string");put("enum",buildJsonArray {add(name)})})
                    put("arguments",arguments(name,definitions.getValue(name),ownerRequest,projectWrites))
                    put("publicExplanation",buildJsonObject {put("type","string");put("maxLength",220)})
                })
                put("required",buildJsonArray {add("type");add("tool");add("arguments")})
            })}})
        }.toString().also {require(it.length<=65536){"도구 출력 스키마가 너무 큽니다."}}
    }

    private fun arguments(name:String,original:JsonObject,ownerRequest:String?,projectWrites:ProjectWriteSchemaConstraints?):JsonObject {
        val definition=ToolRegistry.definitions.getValue(name)
        val properties=original.getValue("properties").jsonObject.mapValues {(key,value)->
            val changes=value.jsonObject.toMutableMap()
            val spec=definition.arguments.getValue(key)
            // Only search_notes accepts an exactly empty optional collection filter as omitted.
            // This does not change host access scope or any required read/write target scope.
            if(spec.type=="string"&&(spec.required||key=="scope")){
                val explicitEmpty=(name=="patch_note"&&key=="newText"&&value.jsonObject["minLength"]?.jsonPrimitive?.intOrNull==0)||
                    (name=="search_notes"&&key=="scope"&&!spec.required)
                changes["minLength"]=JsonPrimitive(if(explicitEmpty)0 else maxOf(changes["minLength"]?.jsonPrimitive?.intOrNull?:0,1))
            }
            val enumeration=when {
                name=="create_document"&&key=="format"->listOf("docx","xlsx","pdf","txt","csv")
                name=="search_shopping"&&key=="store"->listOf("coupang","naver","danawa","11st","gmarket","amazon")
                name=="list_messenger_notifications"&&key=="packageName"->ToolRegistry.supportedMessengerPackages.sorted()
                else->emptyList()
            }
            if(enumeration.isNotEmpty())changes["enum"]=JsonArray(enumeration.map(::JsonPrimitive))
            when(key){
                "hour"->{changes["minimum"]=JsonPrimitive(0);changes["maximum"]=JsonPrimitive(23)}
                "minute"->{changes["minimum"]=JsonPrimitive(0);changes["maximum"]=JsonPrimitive(59)}
                "steps"->if(name=="generate_image"){changes["minimum"]=JsonPrimitive(1);changes["maximum"]=JsonPrimitive(50)}
                "seed"->if(name=="generate_image")changes["minimum"]=JsonPrimitive(0)
                "days"->if(name=="create_alarm"){
                    changes["items"]=buildJsonObject {put("type","integer");put("minimum",1);put("maximum",7)}
                    changes["maxItems"]=JsonPrimitive(7);changes["uniqueItems"]=JsonPrimitive(true)
                }
            }
            JsonObject(changes)
        }
        if(name=="write_project_file"&&projectWrites!=null)return projectWrites.arguments(
            JsonObject(original+mapOf("properties" to JsonObject(properties),"additionalProperties" to JsonPrimitive(false))))
        if(name=="patch_note"){
            val spans=ownerRequest?.let(OwnerTextSources::literalSpans).orEmpty()
            val oldRefs=spans.filter{it.text.isNotEmpty()&&it.text.length<=900}
            val newRefs=spans.filter{it.text.length<=2000}
            val payloads=setOf("oldText","oldTextQuoteId","newText","newTextQuoteId")
            val oldChoices=listOf("oldText")+if(oldRefs.isNotEmpty())listOf("oldTextQuoteId")else emptyList()
            val newChoices=listOf("newText")+if(newRefs.isNotEmpty())listOf("newTextQuoteId")else emptyList()
            fun variant(old:String,new:String)=buildJsonObject{
                put("type","object");put("additionalProperties",false)
                put("properties",JsonObject(properties.filterKeys{it !in payloads||it==old||it==new}.toMutableMap().apply{
                    for((key,refs) in listOf(old to oldRefs,new to newRefs))if(key.endsWith("QuoteId")){
                        put(key,JsonObject(getValue(key).jsonObject+mapOf("enum" to JsonArray(refs.map{JsonPrimitive(it.id)}))))
                    }
                }))
                put("required",buildJsonArray{
                    definition.arguments.filterValues{it.required}.keys.forEach{add(it)};add(old);add(new)
                })
            }
            val variants=oldChoices.flatMap{old->newChoices.map{new->variant(old,new)}}
            return if(variants.size==1)variants.single()else buildJsonObject{put("oneOf",JsonArray(variants))}
        }
        if(name=="save_note"){
            val quotes=ownerRequest?.let(OwnerTextSources::quotes).orEmpty()
            fun variant(payload:String)=buildJsonObject{
                put("type","object");put("additionalProperties",false)
                put("properties",JsonObject(properties.filterKeys{it !in setOf("content","contentQuoteId")||it==payload}.toMutableMap().apply {
                    if(payload=="contentQuoteId")put(payload,buildJsonObject{
                        put("type","string");put("enum",JsonArray(quotes.map{JsonPrimitive(it.id)}))
                        properties.getValue(payload).jsonObject["description"]?.let{put("description",it)}
                    })
                    else put(payload,JsonObject(getValue(payload).jsonObject+mapOf("minLength" to JsonPrimitive(1))))
                }))
                put("required",buildJsonArray{add(payload)})
            }
            return if(quotes.isEmpty())variant("content") else buildJsonObject{put("oneOf",buildJsonArray{add(variant("content"));add(variant("contentQuoteId"))})}
        }
        return JsonObject(original.toMutableMap().apply {put("properties",JsonObject(properties));put("additionalProperties",JsonPrimitive(false))})
    }
}
