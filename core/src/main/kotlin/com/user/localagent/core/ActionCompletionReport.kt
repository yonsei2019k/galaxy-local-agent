package com.user.localagent.core

import kotlinx.serialization.json.*

/** These records are made by the host after execution; model prose is never execution evidence. */
data class ObservedToolOutcome(val call:ToolCall,val result:ToolResult)

/** One plain-word fact on a receipt card: [label] is a short noun, [value] the exact observed or requested value. */
data class ReceiptField(val label:String,val value:String)

/** prepared, sent/stored and verified stay distinguishable: the UI shows [status], never a host sentence. */
enum class ReceiptStatus(val id:String){VERIFIED("verified"),PREPARED("prepared"),UNVERIFIED("unverified"),FAILED("failed")}

/**
 * Structured receipt for one managed action. [title] is null when the UI should use its own tool label.
 * Only [ReceiptStatus.VERIFIED] means the host re-read the stored result.
 */
data class ActionReceipt(val tool:String,val title:String?,val status:ReceiptStatus,val fields:List<ReceiptField>,val link:String?=null){
    val verified get()=status==ReceiptStatus.VERIFIED
    fun json()=buildJsonObject {
        put("tool",tool);title?.let{put("title",it)};put("status",status.id);put("verified",verified)
        put("fields",buildJsonArray{fields.forEach{field->add(buildJsonObject{put("label",field.label);put("value",field.value)})}})
        link?.let{put("link",it)}
    }
}

/**
 * [receipts] go to the receipt card; [text] keeps only observed content that is itself the answer
 * (a memo body, a listing, a lookup). [complete] is false whenever the whole request is not proven.
 */
data class ComposedReport(val receipts:List<ActionReceipt>,val text:String,val complete:Boolean,val observed:Boolean){
    fun receiptJson()=buildJsonObject {
        put("complete",complete)
        put("items",buildJsonArray{receipts.forEach{add(it.json())}})
    }
}

object ActionCompletionReport {
    val managedTools=setOf("patch_note","read_note","create_google_calendar_event","save_note","update_note","delete_note","create_reminder","update_reminder","delete_reminder",
        KakaoShare.TOOL,"prepare_sms","prepare_email","prepare_calendar_event","create_alarm","get_current_datetime","search_contacts",
        "inspect_project_storage","find_project_duplicates","list_reminders","get_agent_capabilities")+
        com.user.localagent.core.connections.ConsumerAppCatalog.names+setOf("open_map","open_url")+setOf(ScheduledReadToolCatalog.CREATE, ScheduledReadToolCatalog.CANCEL)
    private val auxiliaryReads=setOf("read_note","get_current_datetime","search_contacts","list_reminders","get_agent_capabilities")
    private val observationTools=setOf("read_note","inspect_project_storage","find_project_duplicates","list_reminders","get_agent_capabilities")
    /** Card titles for receipts the host builds; other tools use the UI's plain tool label. */
    private val titles=mapOf("prepare_sms" to "문자 준비","prepare_email" to "메일 준비","prepare_calendar_event" to "일정 준비",
        "create_alarm" to "알람 준비","open_map" to "지도 열기","open_url" to "링크 열기","save_note" to "메모 저장","update_note" to "메모 고침",
        "create_reminder" to "알림 저장","update_reminder" to "알림 고침","create_google_calendar_event" to "구글 캘린더에 일정 넣기")
    private val handoffLabels=linkedMapOf("query" to "찾는 말","name" to "이름","label" to "이름표","url" to "주소")
    private val weekdays=mapOf(1L to "일",2L to "월",3L to "화",4L to "수",5L to "목",6L to "금",7L to "토")
    fun manages(required:Set<String>,outcomes:List<ObservedToolOutcome>)=required.any{it in managedTools}||outcomes.any{it.call.name in managedTools-auxiliaryReads}

    /**
     * One string for callers outside the chat card (finalization policies, records). Receipts are not in it:
     * the chat shows them through [compose] as a card.
     */
    fun render(required:Set<String>,outcomes:List<ObservedToolOutcome>,reason:String?=null):String {
        val report=compose(required,outcomes)
        return buildString {
            if(!report.observed)append("확인한 일 없음")
            append(report.text)
            if(!report.complete){if(isNotEmpty())append("\n\n");append("부탁한 일이 다 끝났는지는 아직 모름")}
            reason?.takeIf{it.isNotBlank()}?.let{append("\n").append(it)}
        }
    }

    fun compose(required:Set<String>,outcomes:List<ObservedToolOutcome>):ComposedReport {
        val managed=outcomes.filter{it.call.name in managedTools}
        var observationIncomplete=false
        val noteReadPages=NoteReadbackPages()
        val noteReadIncomplete=linkedMapOf<RequiredReadbackPolicy.Resource,Boolean>()
        val receipts=mutableListOf<ActionReceipt>()
        val text=StringBuilder()
        fun section():StringBuilder {if(text.isNotEmpty())text.append("\n\n");return text}
        managed.forEach {observed->
            val call=observed.call;val result=observed.result
            fun value(key:String)=call.arguments[key]?.jsonPrimitive?.content.orEmpty()
            fun receipt(status:ReceiptStatus,vararg fields:Pair<String,String?>,link:String?=null){
                receipts.add(ActionReceipt(call.name,titles[call.name],status,
                    fields.mapNotNull{(label,value)->value?.let{ReceiptField(label,it)}},link))
            }
            if(!result.success||result.verification in setOf(VerificationState.FAILED,VerificationState.UNVERIFIED)){
                receipt(if(!result.success||result.verification==VerificationState.FAILED)ReceiptStatus.FAILED else ReceiptStatus.UNVERIFIED,
                    "받은 답" to result.content.take(1200).ifBlank{null});return@forEach
            }
            if(call.name == "prepare_email" && PdfMailAttachments.FIELD in call.arguments && result.verification in setOf(VerificationState.PREPARED,VerificationState.VERIFIED)) {
                section().append(result.content)
                return@forEach
            }
            if(call.name == KakaoShare.TOOL) {
                section().append(if (result.verification == VerificationState.PREPARED) KakaoShare.HANDOFF else "카카오톡 · 보냈는지 못 봄")
                return@forEach
            }
            if(call.name in observationTools){
                if(result.verification!=VerificationState.VERIFIED){
                    receipt(ReceiptStatus.UNVERIFIED)
                    observationIncomplete=true
                }else{
                    val report=observation(call.name,result.content)
                    section().append(report.text)
                    if(call.name=="read_note"){
                        val raw=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
                        val proof=noteReadPages.observe(call,raw);noteReadIncomplete[proof.target]=proof.snapshot==null
                    }else observationIncomplete=observationIncomplete||report.incomplete
                }
            }else if(result.verification==VerificationState.PREPARED){
                when(call.name){
                    "prepare_sms"->receipt(ReceiptStatus.PREPARED,"받는 번호" to value("phoneNumber"),"문자 내용" to value("message"))
                    "prepare_email"->receipt(ReceiptStatus.PREPARED,"받는 주소" to value("to"),"제목" to value("subject"),"본문" to value("body"))
                    "prepare_calendar_event"->receipt(ReceiptStatus.PREPARED,"일정" to value("title"),
                        "시작" to value("start").takeIf{it.isNotBlank()}?.let(::time),"끝" to value("end").takeIf{it.isNotBlank()}?.let(::time))
                    "create_alarm"->receipt(ReceiptStatus.PREPARED,"시각" to alarmTime(call.arguments),
                        "이름표" to value("label").ifBlank{null},"요일" to days(call.arguments["days"]))
                    else->receipt(ReceiptStatus.PREPARED,*handoffLabels.map{(key,label)->label to value(key).ifBlank{null}}.toTypedArray())
                }
            }else when(call.name){
                ScheduledReadToolCatalog.CREATE, ScheduledReadToolCatalog.CANCEL -> {
                    val report = renderScheduledRead(call, result)
                    if (report == null) {
                        receipt(ReceiptStatus.UNVERIFIED)
                        observationIncomplete = true
                    } else section().append(report)
                }
                "patch_note"->{
                    val receipt=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
                    if(receipt?.flag("storedAndReadBack")==true&&receipt.flag("readbackComplete")==true&&receipt.flag("unreadTextPreserved")==true&&
                        receipt.text("id")==value("id")&&receipt.text("scope")==value("scope")&&receipt.text("beforeUpdatedAt")==value("expectedUpdatedAt")){
                        section().append(if(receipt.flag("changed")==true)"메모 일부 고침 · 저장 뒤 전체 다시 봐서 확인함"else"메모 그대로 · 본문·버전 안 바뀜 · 다시 봐서 확인함")
                        text.append("\n제목: ").append(receipt.text("title"));text.append("\n메모 버전: ").append(receipt.text("updatedAt"))
                        text.append("\n나머지 글 · 그대로 둠")
                        noteReadPages.invalidate(value("id"))
                        if("read_note" in required)noteReadIncomplete[RequiredReadbackPolicy.Resource("note_body_v1",value("id"))]=true
                    }else{section().append("메모 일부 고침 · 저장됐는지 못 봄");observationIncomplete=true}
                }
                "create_google_calendar_event"->{
                    val privateEvent=call.arguments["private"]==JsonPrimitive(true)
                    val receipt=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
                    val event=receipt?.get("event") as? JsonObject
                    val proven=runCatching{
                        require(receipt?.text("service")=="calendar"&&receipt.flag("verified")==true&&receipt.flag("eventActive")==true)
                        require(receipt.flag("attendeeDeliveryConfirmed")==false&&receipt.flag("attendeeAcceptanceConfirmed")==false)
                        require(receipt.text("calendarSelector")==value("calendarId").ifEmpty{"primary"}&&receipt.text("accountBindingSha256")?.matches(Regex("[a-f0-9]{64}"))==true)
                        require(event!=null&&event.text("id")==receipt.text("remoteId")&&event.text("id")?.matches(Regex("[a-f0-9]{64}"))==true)
                        require(event.text("status") in setOf("confirmed","tentative")&&event.text("title")==value("title"))
                        require(!privateEvent||event.text("visibility")=="private")
                        require(java.time.OffsetDateTime.parse(event.text("start")).toInstant()==java.time.OffsetDateTime.parse(value("start")).toInstant()&&
                            java.time.OffsetDateTime.parse(event.text("end")).toInstant()==java.time.OffsetDateTime.parse(value("end")).toInstant())
                    }.isSuccess
                    if(proven){
                        receipt(ReceiptStatus.VERIFIED,"일정" to event?.text("title"),"시작" to event?.text("start")?.let(::time),"끝" to event?.text("end")?.let(::time),
                            "초대" to if(privateEvent)"비공개 · 초대 없음" else "받았는지·수락했는지 모름",
                            link=com.user.localagent.core.connections.CalendarTargetIntegrity.eventLink(event?.text("htmlLink")))
                    }else{receipt(ReceiptStatus.UNVERIFIED,"일정" to value("title").ifBlank{null});observationIncomplete=true}
                }
                "save_note","update_note"->receipt(ReceiptStatus.VERIFIED,"제목" to value("title").ifEmpty{null},"내용" to value("content"))
                "create_reminder","update_reminder"->{
                    val receipt=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
                    val reminder=receipt?.get("reminder") as? JsonObject
                    val title=(reminder?.get("title") as? JsonPrimitive)?.takeIf{it.isString}?.content
                    val at=(reminder?.get("atEpochMillis") as? JsonPrimitive)?.longOrNull
                    if((receipt?.get("storedAndReadBack") as? JsonPrimitive)?.booleanOrNull==true&&(receipt?.get("queueVerified") as? JsonPrimitive)?.booleanOrNull==true&&title!=null&&at!=null){
                        receipt(ReceiptStatus.VERIFIED,"제목" to title,"시각" to time(java.time.Instant.ofEpochMilli(at).atZone(ToolRegistry.defaultZone)),
                            "알림이 떴는지" to "아직 모름")
                    }else section().append(result.content)
                }
                "get_current_datetime"->section().append("기기에서 조회한 시각: ").append(result.content)
                "search_contacts"->section().append("연락처 조회 결과:\n").append(result.content)
                else->section().append(result.content)
            }
        }
        val verified=outcomes.filter{it.result.success&&it.result.verification==VerificationState.VERIFIED}.map{it.call.name}.toSet()
        val complete=(required-verified).isEmpty()&&!observationIncomplete&&noteReadIncomplete.values.none{it}
        return ComposedReport(receipts,text.toString(),complete,managed.isNotEmpty())
    }

    /** Exact wall time in plain Korean; seconds and a non-default offset stay visible when present. */
    internal fun time(value:java.time.ZonedDateTime):String {
        val base=value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm"))
        val seconds=when {
            value.nano!=0->":%02d.%s".format(value.second,"%09d".format(value.nano).trimEnd('0'))
            value.second!=0->":%02d".format(value.second)
            else->""
        }
        val zone=if(value.offset==ToolRegistry.defaultZone.rules.getOffset(value.toInstant()))"" else " (UTC${value.offset.id.takeUnless{it=="Z"}.orEmpty()})"
        return base+seconds+zone
    }
    private fun time(value:String):String=runCatching{time(java.time.OffsetDateTime.parse(value).toZonedDateTime())}.getOrNull()
        ?:runCatching{time(java.time.LocalDateTime.parse(value).atZone(ToolRegistry.defaultZone))}.getOrNull()?:value
    private fun alarmTime(arguments:JsonObject):String? {
        val hour=(arguments["hour"] as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?:return null
        val minute=(arguments["minute"] as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?:return null
        return "%02d:%02d".format(hour,minute)
    }
    private fun days(value:JsonElement?):String? {
        val list=value as? JsonArray?:return null
        return list.map{item->(item as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?.let(weekdays::get)?:item.toString()}
            .joinToString(", ").ifBlank{null}
    }

    private data class Observation(val text:String,val incomplete:Boolean)
    private fun observation(tool:String,body:String):Observation {
        val data=if(body.length<=65536)runCatching{rejectDuplicateKeys(body);Json.parseToJsonElement(body) as? JsonObject}.getOrNull()else null
        if(data==null)return Observation("조회 결과 못 읽음 · 본 내용과 바뀐 것은 모름",true)
        return when(tool){
            "read_note"->{
                val complete=data.flag("completeBody")==true&&data.flag("partial")==false&&data.flag("hasMore")==false
                Observation((if(complete)"메모 전체 조회"else"메모 일부 조회 · 안 읽은 부분 있음")+"\n"+data.text("text").orEmpty(),!complete)
            }
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
            if(partial==true||enumerated==false)append("\n빠진 범위 있음 · 전체 결과 아님")
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
                    append("\n경로 표시 · 최대 5개 그룹·그룹당 3개 · 나머지 생략")
                listOf("duplicateGroupsOmitted" to "결과에서 생략한 중복 그룹", "duplicatePathsOmitted" to "결과에서 생략한 중복 경로").forEach{(key,label)->
                    data.count(key)?.takeIf{it>0}?.let{append("\n").append(label).append(": ").append(countLabel(it))}
                }
                append("\n중복을 찾은 것뿐 · 지울 허락이나 늘어난 저장 공간 아님")
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
                append("\n읽기만 하는 도구인데 바뀐 것이 보고됨 · 결과 확인 필요")
        }
        return Observation(text,incomplete)
    }

    private fun capabilities(data:JsonObject):Observation {
        val tools=data["tools"] as? JsonArray
        val paired=data["pairedPc"] as? JsonObject
        val models=data["models"] as? JsonObject
        val workspace=data["workspace"] as? JsonObject
        val text=buildString {
            append("도구와 실행 조건 조회")
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
            append("\n등록·설정·허용은 일이 된다는 근거 아님 · 권한과 범위는 실행 직전에 다시 확인")
        }
        return Observation(text,data.text("status")!="ok"||data.count("registeredToolCount")==null||data.count("allowedToolCount")==null||tools==null||data.flag("partial")!=false)
    }

    private fun reminders(data:JsonObject):Observation {
        val rows=data["reminders"] as? JsonArray
        val truncated=data.flag("truncated")
        var incomplete=data.text("status")!="ok"||data.text("source")!="current_request_reminders"||rows==null||truncated!=false
        val text=buildString {
            append("리마인더 기록 조회")
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
            if((rows?.size?:0)>20)append("\n표시 한도 · 나머지 ").append(rows!!.size-20).append("개 생략")
            if(truncated==true)append("\n조회 한도 밖 알림 · 이 결과에 없음")
            append("\n목록 기록만 봄 · 알림이 실제로 떴는지·받았는지는 모름")
        }
        return Observation(text,incomplete)
    }

    private fun renderScheduledRead(call: ToolCall, result: ToolResult): String? {
        val receipt = runCatching { Json.parseToJsonElement(result.content).jsonObject }.getOrNull()
        val id = receipt.text("scheduleId")?.takeIf(ScheduledReadPolicy::isId) ?: return null
        if (result.id != call.id || receipt.flag("runResultVerified") != false) return null
        val status = receipt.text("status")
        if (call.name == ScheduledReadToolCatalog.CREATE) {
            return when (status) {
                ScheduledReadPolicy.SCHEDULED ->
                    "조회 예약 넣음 · 결과는 원래 대화에\n예약 번호 $id"
                "queued", "running", "waiting_approval", "completed", "cancelled", "failed", "interrupted", "paused" ->
                    "같은 예약 이미 있음 · 상태와 결과는 원래 대화에\n예약 번호 $id"
                else -> null
            }
        }
        if (id != (call.arguments["scheduleId"] as? JsonPrimitive)?.takeIf { it.isString }?.content) return null
        val message = when (status) {
            "stop_requested" -> "하던 일에 멈춤 요청 보냄 · 마지막 상태는 원래 대화에"
            "cancelled" -> "예약 취소된 것 확인함"
            "completed", "failed", "interrupted", "paused" -> "이미 끝난 예약 · 결과와 상태 그대로"
            else -> return null
        }
        val cleanup = receipt.text("systemCleanup")
        return if (status in setOf("stop_requested", "cancelled") && cleanup != "confirmed") {
            "$message · Android 예약 정리는 아직 못 봄"
        } else message
    }

    private fun JsonObject?.text(key:String):String?=(this?.get(key) as? JsonPrimitive)?.takeIf{it.isString}?.content
    private fun JsonObject?.flag(key:String):Boolean?=(this?.get(key) as? JsonPrimitive)?.takeUnless{it.isString}?.booleanOrNull
    private fun JsonObject?.count(key:String):Long?=(this?.get(key) as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?.takeIf{it>=0}
    private fun countLabel(value:Long?):String=value?.let{"${it}개"}?:"미확인"
    private fun flagLabel(value:Boolean?):String=when(value){true->"예";false->"아니오";null->"미확인"}
    /** Source names/paths stay visibly quoted data, with controls escaped and bounded display. */
    private fun quoted(value:String):String=JsonPrimitive(value.take(160)).toString()+(if(value.length>160)"…"else"")
}
