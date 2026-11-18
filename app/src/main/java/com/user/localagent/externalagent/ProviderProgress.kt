package com.user.localagent.externalagent

import kotlinx.serialization.json.*

/** Display-only bounded metadata. Never an effect receipt or a model reasoning transcript. */
data class ProviderProgress(val sequence:Long,val stage:String,val elapsedMs:Long,val calls:Int,val toolName:String?=null) {
    val label:String get()=when(stage){
        "starting"->"클라우드 실행 준비 중"
        "planning"->"클라우드가 다음 작업을 계획 중"
        "tool_running"->"도구 실행 중"
        "tool_returned"->"도구 응답 확인 중"
        "verifying"->"실행 결과 검증 중"
        else->"답변 작성 중"
    }
    companion object {
        private val stages=setOf("starting","planning","tool_running","tool_returned","verifying","answering")
        fun parse(value:JsonElement?):ProviderProgress?=runCatching {
            if(value==null||value==JsonNull)return null
            val o=value.jsonObject
            require(o.keys.all{it in setOf("sequence","stage","elapsedMs","calls","toolName")})
            fun number(key:String)=o.getValue(key).jsonPrimitive.also{require(!it.isString)}.long
            val stage=o.getValue("stage").jsonPrimitive.also{require(it.isString)}.content
            val sequence=number("sequence");val elapsed=number("elapsedMs");val calls=number("calls")
            require(stage in stages&&sequence in 1..1_000_000&&elapsed in 0..3_600_000&&calls in 0..1000)
            val tool=o["toolName"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.also{require(it.isString)}?.content
            require(tool==null||tool.matches(Regex("[a-z][a-z0-9_]{0,79}")))
            ProviderProgress(sequence,stage,elapsed,calls.toInt(),tool)
        }.getOrNull()
    }
}
