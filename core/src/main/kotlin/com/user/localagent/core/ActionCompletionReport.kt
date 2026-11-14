package com.user.localagent.core

import kotlinx.serialization.json.*

/** These records are made by the host after execution; model prose is never execution evidence. */
data class ObservedToolOutcome(val call:ToolCall,val result:ToolResult)

object ActionCompletionReport {
    val managedTools=setOf("save_note","update_note","delete_note","create_reminder","update_reminder","delete_reminder",
        "prepare_sms","prepare_email","prepare_calendar_event","create_alarm","get_current_datetime","search_contacts")+
        com.user.localagent.core.connections.ConsumerAppCatalog.names+setOf("open_map","open_url")
    fun manages(required:Set<String>,outcomes:List<ObservedToolOutcome>)=required.any{it in managedTools}||outcomes.any{it.call.name in managedTools-setOf("get_current_datetime","search_contacts")}
    fun render(required:Set<String>,outcomes:List<ObservedToolOutcome>,reason:String?=null):String=buildString {
        val managed=outcomes.filter{it.call.name in managedTools}
        if(managed.isEmpty())append("이번 실행에서 완료를 확인한 작업이 없습니다.")
        managed.forEachIndexed {index,observed->
            if(isNotEmpty())append("\n\n")
            val call=observed.call;val result=observed.result
            fun value(key:String)=call.arguments[key]?.jsonPrimitive?.content.orEmpty()
            if(!result.success||result.verification in setOf(VerificationState.FAILED,VerificationState.UNVERIFIED)){
                append("실행 결과 미확인 · ").append(result.content.take(1200));return@forEachIndexed
            }
            if(result.verification==VerificationState.PREPARED){
                append("관련 앱으로 요청을 전달했습니다. 실제 화면 표시·저장·발송·길 안내 시작은 확인되지 않았습니다.")
                when(call.name){
                    "prepare_sms"->append("\n수신 번호: ").append(value("phoneNumber")).append("\n문자 내용:\n").append(value("message"))
                    "prepare_email"->append("\n수신 주소: ").append(value("to")).append("\n제목: ").append(value("subject")).append("\n본문:\n").append(value("body"))
                    "prepare_calendar_event"->append("\n일정: ").append(value("title")).append("\n시작: ").append(value("start")).append("\n종료: ").append(value("end"))
                    else->listOf("query","name","label","url").forEach{key->if(value(key).isNotBlank())append("\n요청 값: ").append(value(key))}
                }
            }else when(call.name){
                "save_note","update_note"->{append("메모 ").append(if(call.name=="save_note")"저장"else"수정").append(" 후 재조회를 확인했습니다.");if(value("title").isNotEmpty())append("\n제목: ").append(value("title"));append("\n본문:\n").append(value("content"))}
                "get_current_datetime"->append("기기에서 조회한 시각: ").append(result.content)
                "search_contacts"->append("연락처 조회 결과:\n").append(result.content)
                else->append(result.content)
            }
        }
        val verified=outcomes.filter{it.result.success&&it.result.verification==VerificationState.VERIFIED}.map{it.call.name}.toSet()
        if((required-verified).isNotEmpty())append("\n\n전체 요청의 완료는 확인되지 않았습니다.")
        reason?.takeIf{it.isNotBlank()}?.let{append("\n").append(it)}
    }
}
