package com.user.localagent.core

import kotlinx.serialization.json.*

/** These records are made by the host after execution; model prose is never execution evidence. */
data class ObservedToolOutcome(val call:ToolCall,val result:ToolResult)

object ActionCompletionReport {
    val managedTools=setOf("save_note","update_note","delete_note","create_reminder","update_reminder","delete_reminder",
        "prepare_sms","prepare_email","prepare_calendar_event","create_alarm","get_current_datetime","search_contacts",
        "inspect_project_storage","find_project_duplicates","list_reminders","get_agent_capabilities")+
        com.user.localagent.core.connections.ConsumerAppCatalog.names+setOf("open_map","open_url")
    private val auxiliaryReads=setOf("get_current_datetime","search_contacts","list_reminders","get_agent_capabilities")
    private val observationTools=setOf("inspect_project_storage","find_project_duplicates","list_reminders","get_agent_capabilities")
    fun manages(required:Set<String>,outcomes:List<ObservedToolOutcome>)=required.any{it in managedTools}||outcomes.any{it.call.name in managedTools-auxiliaryReads}
    fun render(required:Set<String>,outcomes:List<ObservedToolOutcome>,reason:String?=null):String=buildString {
        val managed=outcomes.filter{it.call.name in managedTools}
        var observationIncomplete=false
        if(managed.isEmpty())append("이번 실행에서 완료를 확인한 작업이 없습니다.")
        managed.forEachIndexed {index,observed->
            if(isNotEmpty())append("\n\n")
            val call=observed.call;val result=observed.result
            fun value(key:String)=call.arguments[key]?.jsonPrimitive?.content.orEmpty()
            if(!result.success||result.verification in setOf(VerificationState.FAILED,VerificationState.UNVERIFIED)){
                append("실행 결과 미확인 · ").append(result.content.take(1200));return@forEachIndexed
            }
            if(call.name in observationTools){
                if(result.verification!=VerificationState.VERIFIED){
                    append("조회 결과 미확인 · 실제 관찰 결과가 검증되지 않았습니다.")
                    observationIncomplete=true
                }else{
                    val report=observation(call.name,result.content)
                    append(report.text);observationIncomplete=observationIncomplete||report.incomplete
                }
            }else if(result.verification==VerificationState.PREPARED){
                append("관련 앱으로 요청을 전달했습니다. 실제 화면 표시·저장·발송·길 안내 시작은 확인되지 않았습니다.")
                when(call.name){
                    "prepare_sms"->append("\n수신 번호: ").append(value("phoneNumber")).append("\n문자 내용:\n").append(value("message"))
                    "prepare_email"->append("\n수신 주소: ").append(value("to")).append("\n제목: ").append(value("subject")).append("\n본문:\n").append(value("body"))
                    "prepare_calendar_event"->append("\n일정: ").append(value("title")).append("\n시작: ").append(value("start")).append("\n종료: ").append(value("end"))
                    else->listOf("query","name","label","url").forEach{key->if(value(key).isNotBlank())append("\n요청 값: ").append(value(key))}
                }
            }else when(call.name){
                "save_note","update_note"->{append("메모 ").append(if(call.name=="save_note")"저장"else"수정").append(" 후 재조회를 확인했습니다.");if(value("title").isNotEmpty())append("\n제목: ").append(value("title"));append("\n본문:\n").append(value("content"))}
                "create_reminder","update_reminder"->{
                    val receipt=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
                    val reminder=receipt?.get("reminder") as? JsonObject
                    val title=(reminder?.get("title") as? JsonPrimitive)?.takeIf{it.isString}?.content
                    val at=(reminder?.get("atEpochMillis") as? JsonPrimitive)?.longOrNull
                    if((receipt?.get("storedAndReadBack") as? JsonPrimitive)?.booleanOrNull==true&&(receipt?.get("queueVerified") as? JsonPrimitive)?.booleanOrNull==true&&title!=null&&at!=null){
                        append("알림 ").append(if(call.name=="create_reminder")"저장"else"수정").append("과 예약 큐를 확인했습니다.")
                        append("\n제목: ").append(title).append("\n시각: ").append(java.time.Instant.ofEpochMilli(at).atZone(ToolRegistry.defaultZone))
                        append("\n알림이 실제 표시됐는지는 아직 확인하지 않았습니다.")
                    }else append(result.content)
                }
                "get_current_datetime"->append("기기에서 조회한 시각: ").append(result.content)
                "search_contacts"->append("연락처 조회 결과:\n").append(result.content)
                else->append(result.content)
            }
        }
        val verified=outcomes.filter{it.result.success&&it.result.verification==VerificationState.VERIFIED}.map{it.call.name}.toSet()
        if((required-verified).isNotEmpty()||observationIncomplete)append("\n\n전체 요청의 완료는 확인되지 않았습니다.")
        reason?.takeIf{it.isNotBlank()}?.let{append("\n").append(it)}
    }

    private data class Observation(val text:String,val incomplete:Boolean)
    private fun observation(tool:String,body:String):Observation {
        val data=if(body.length<=65536)runCatching{rejectDuplicateKeys(body);Json.parseToJsonElement(body) as? JsonObject}.getOrNull()else null
        if(data==null)return Observation("조회 결과를 해석하지 못했습니다. 관찰 내용과 변경 여부는 미확인입니다.",true)
        return when(tool){
            "inspect_project_storage","find_project_duplicates"->storage(tool,data)
            "get_agent_capabilities"->capabilities(data)
            else->reminders(data)
        }
    }

    private fun storage(tool:String,data:JsonObject):Observation {
        val partial=data.flag("partial");val enumerated=data.flag("enumerationComplete")
        val changed=data.flag("filesChanged");val deleted=data.count("deletedFiles");val moved=data.count("movedFiles")
        val scope=data.text("scope")=="selected_project_only"
        var incomplete=data.text("status")!="ok"||!scope||data.flag("readOnly")!=true||partial!=false||enumerated!=true||
            data.count("observedFiles")==null||changed!=false||deleted!=0L||moved!=0L
        val text=buildString {
            append(if(tool=="find_project_duplicates")"파일 중복 관찰 결과"else"저장소 관찰 결과")
            append("\n조회 범위: ").append(if(scope)"현재 선택한 프로젝트의 탐색 한도 안"else"미확인")
            append("\n조회 상태: ").append(if(data.text("status")=="ok")"결과 반환"else"미확인")
            append("\n읽기 전용 보고: ").append(flagLabel(data.flag("readOnly")))
            append("\n관찰한 파일: ").append(countLabel(data.count("observedFiles")))
            append("\n부분 조회: ").append(flagLabel(partial))
            append("\n파일 목록 순회 완료: ").append(flagLabel(enumerated))
            if(partial==true||enumerated==false)append("\n누락·제외된 범위가 있어 전체 결과로 판단할 수 없습니다.")
            if(tool=="find_project_duplicates"){
                val total=data.count("observedDuplicateGroups")
                append("\n관찰한 바이트 동일 중복 그룹: ").append(countLabel(total))
                append("\n해시를 확인한 파일: ").append(countLabel(data.count("hashedFiles")))
                append("\n해시 비교에서 제외한 파일: ").append(countLabel(data.count("skippedHashCandidates")))
                if(total==null)incomplete=true
                val groups=data["duplicateGroups"] as? JsonArray
                groups?.take(5)?.forEachIndexed {index,item->
                    val group=item as? JsonObject?:return@forEachIndexed
                    append("\n중복 그룹 ").append(index+1).append(": 관찰 사본 ").append(countLabel(group.count("observedCopies")))
                    val paths=group["paths"] as? JsonArray
                    paths?.take(3)?.forEach {path->
                        (path as? JsonPrimitive)?.takeIf{it.isString}?.let{append("\n  경로: ").append(quoted(it.content))}
                    }
                }
                if(groups==null){append("\n중복 경로: 미확인");incomplete=true}
                if((groups?.size?:0)>5||groups?.any{((it as? JsonObject)?.get("paths") as? JsonArray)?.size?.let{size->size>3}==true}==true)
                    append("\n경로 표시는 최대 5개 그룹·그룹당 3개이며 나머지는 생략했습니다.")
                listOf("duplicateGroupsOmitted" to "결과에서 생략한 중복 그룹", "duplicatePathsOmitted" to "결과에서 생략한 중복 경로").forEach{(key,label)->
                    data.count(key)?.takeIf{it>0}?.let{append("\n").append(label).append(": ").append(countLabel(it))}
                }
                append("\n중복 관찰은 파일 삭제 권한이나 확보한 저장 공간을 뜻하지 않습니다.")
            }else{
                append("\n확인된 크기 합계: ").append(data.count("observedKnownBytes")?.let{"${it}바이트"}?:"미확인")
                if(data.flag("sizeTotalCapped")==true)append(" (합계 상한에 도달한 값)")
                append("\n크기가 미확인인 파일: ").append(countLabel(data.count("unknownSizeFiles")))
                (data["largestFiles"] as? JsonArray)?.take(5)?.forEach {item->
                    val file=item as? JsonObject?:return@forEach
                    file.text("path")?.let{append("\n큰 파일: ").append(quoted(it)).append(" · ").append(file.count("bytes")?.let{bytes->"${bytes}바이트"}?:"크기 미확인")}
                }
            }
            append("\n파일 변경 보고: ").append(flagLabel(changed))
            append("\n삭제: ").append(countLabel(deleted)).append(" · 이동: ").append(countLabel(moved))
            if(data.flag("readOnly")==false||changed==true||(deleted?:0)>0||(moved?:0)>0)
                append("\n읽기 전용 도구의 예상과 다른 변경 보고가 있어 결과를 확인해야 합니다.")
        }
        return Observation(text,incomplete)
    }

    private fun capabilities(data:JsonObject):Observation {
        val tools=data["tools"] as? JsonArray
        val paired=data["pairedPc"] as? JsonObject
        val models=data["models"] as? JsonObject
        val workspace=data["workspace"] as? JsonObject
        val text=buildString {
            append("도구와 실행 전제조건 조회 결과입니다.")
            append("\n조회 상태: ").append(if(data.text("status")=="ok")"결과 반환"else"미확인")
            append("\n등록 도구: ").append(countLabel(data.count("registeredToolCount")))
            append("\n현재 요청에서 허용된 도구: ").append(countLabel(data.count("allowedToolCount")))
            append("\n부분 조회: ").append(flagLabel(data.flag("partial")))
            if(tools==null)append("\n도구별 준비 상태: 미확인")else{
                val labels=linkedMapOf("INSTALLED" to "구현 등록", "REQUIRES_PERMISSION" to "권한 필요", "REQUIRES_MODEL" to "모델 필요", "NOT_IMPLEMENTED" to "미구현", "UNVALIDATED" to "미검증")
                labels.forEach{(state,label)->append("\n").append(label).append(": ").append(tools.count{(it as? JsonObject).text("availability")==state}).append("개")}
                val unknown=tools.count{(it as? JsonObject).text("availability") !in labels}
                if(unknown>0)append("\n준비 상태 미확인: ").append(unknown).append("개")
            }
            append("\nPC 연결 설정: ").append(when(paired.flag("configurationRegistered")){true->"등록됨";false->"등록되지 않음";null->"미확인"})
            append("\nPC 연결 가능 여부: ").append(if(paired.flag("reachabilityVerified")==true)"조회에서 확인됨"else"미확인")
            append("\n모델 실행 준비: ").append(if(models.flag("runtimeReadyVerified")==true)"조회에서 확인됨"else"미확인")
            append("\n프로젝트 접근 재확인: ").append(if(workspace.flag("accessRevalidated")==true)"확인됨"else"미확인")
            append("\n등록·설정·허용 여부는 실제 작업 성공의 근거가 아닙니다. 권한과 범위는 실행 직전에 다시 확인합니다.")
        }
        return Observation(text,data.text("status")!="ok"||data.count("registeredToolCount")==null||data.count("allowedToolCount")==null||tools==null||data.flag("partial")!=false)
    }

    private fun reminders(data:JsonObject):Observation {
        val rows=data["reminders"] as? JsonArray
        val truncated=data.flag("truncated")
        var incomplete=data.text("status")!="ok"||data.text("source")!="current_request_reminders"||rows==null||truncated!=false
        val text=buildString {
            append("리마인더 기록 조회 결과입니다.")
            append("\n조회 범위: ").append(if(data.text("source")=="current_request_reminders")"현재 요청의 대화·프로젝트"else"미확인")
            append("\n조회 상태: ").append(if(data.text("status")=="ok")"결과 반환"else"미확인")
            append("\n반환된 알림: ").append(countLabel(rows?.size?.toLong()))
            append("\n일부만 반환됨: ").append(flagLabel(truncated))
            rows?.take(20)?.forEach {item->
                val row=item as? JsonObject
                if(row==null){append("\n알림 항목: 미확인");incomplete=true;return@forEach}
                val title=row.text("title");val datetime=row.text("datetime");val state=row.text("status")
                append("\n알림: ").append(title?.let(::quoted)?:"미확인")
                append("\n  알림 그룹: ").append(row.text("scope")?.let(::quoted)?:"미확인")
                append("\n  시각: ").append(datetime?.let(::quoted)?:"미확인")
                append("\n  기록 상태: ").append(when(state){"pending"->"대기";"delivering"->"전달 처리 중";"delivered"->"전달 기록 있음";else->"미확인"})
                if(title==null||datetime==null||state !in setOf("pending","delivering","delivered"))incomplete=true
            }
            if((rows?.size?:0)>20)append("\n표시 한도로 나머지 ").append(rows!!.size-20).append("개는 생략했습니다.")
            if(truncated==true)append("\n조회 한도 밖의 알림은 이 결과에 포함되지 않았습니다.")
            append("\n목록 기록만으로 실제 알림 표시·수신을 확인한 것은 아닙니다.")
        }
        return Observation(text,incomplete)
    }

    private fun JsonObject?.text(key:String):String?=(this?.get(key) as? JsonPrimitive)?.takeIf{it.isString}?.content
    private fun JsonObject?.flag(key:String):Boolean?=(this?.get(key) as? JsonPrimitive)?.takeUnless{it.isString}?.booleanOrNull
    private fun JsonObject?.count(key:String):Long?=(this?.get(key) as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?.takeIf{it>=0}
    private fun countLabel(value:Long?):String=value?.let{"${it}개"}?:"미확인"
    private fun flagLabel(value:Boolean?):String=when(value){true->"예";false->"아니오";null->"미확인"}
    /** Source names/paths stay visibly quoted data, with controls escaped and bounded display. */
    private fun quoted(value:String):String=JsonPrimitive(value.take(160)).toString()+(if(value.length>160)"…"else"")
}
