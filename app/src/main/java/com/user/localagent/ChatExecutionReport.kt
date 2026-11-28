package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.ui.toolLabel
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

/** A local display history. It is never a capability, a completion proof or provider context. */
internal object ChatExecutionReport {
    const val KEY = "hostExecutionReportV1"
    private const val LIMIT = 32
    private val browser = setOf("open_pc_browser_page","read_pc_browser_page","act_on_pc_browser_page",
        "open_app_browser_page","read_app_browser_page","app_browser_action")
    private val playback = mapOf("playing" to "재생 중", "paused" to "일시 정지", "ended" to "재생 종료",
        "buffering" to "버퍼링", "seeking" to "위치 이동 중", "checking" to "재생 여부 확인 중")
    private val className = Regex("[가-힣a-zA-Z0-9 ()·&._-]{1,80}")
    private val identifier = Regex("[0-9]{5,}|[A-Za-z0-9_-]{22,}")
    data class Line(val key:String,val text:String)
    data class History(val lines:List<Line> = emptyList(),val omitted:Int=0,val lastCall:String?=null,
        val checklist:List<String> = emptyList()) {
        fun append(line:Line):History {
            if(lines.lastOrNull()?.key==line.key) {
                return if(lines.last()==line)this else copy(lines=lines.dropLast(1)+line)
            }
            val next=lines+line
            return if(next.size<=LIMIT)copy(lines=next)
                else copy(lines=listOf(next.first())+next.takeLast(LIMIT-1),omitted=(omitted+1).coerceAtMost(999_999))
        }
        fun json()=buildJsonObject {
            put("omitted",omitted)
            lastCall?.let {put("lastCall",it)}
            put("lines",buildJsonArray {lines.forEach {add(buildJsonArray {add(it.key);add(it.text)})}})
            if(checklist.isNotEmpty())put("checklist",JsonArray(checklist.map(::JsonPrimitive)))
        }
    }
    fun read(context:JsonObject):History {
        val data=context[KEY] as? JsonObject?:return History()
        val lines=(data["lines"] as? JsonArray).orEmpty().take(LIMIT).mapNotNull { entry->
            val pair=entry as? JsonArray?:return@mapNotNull null
            val key=(pair.getOrNull(0) as? JsonPrimitive)?.takeIf {it.isString}?.content
            val text=(pair.getOrNull(1) as? JsonPrimitive)?.takeIf {it.isString}?.content
            if(key==null||key.length !in 1..180||text==null||text.length !in 1..240||text.any(Char::isISOControl))null else Line(key,text)
        }
        val checklist=(data["checklist"] as? JsonArray).orEmpty().take(6).mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf {it.isString}?.content
                ?.takeIf {it.length in 1..240 && it.none(Char::isISOControl)}
        }
        return History(lines,(data["omitted"] as? JsonPrimitive)?.intOrNull?.coerceIn(0,999_999)?:0,
            data.str("lastCall")?.takeIf {it.length==64 && it.all {c->c in '0'..'9'||c in 'a'..'f'}},checklist)
    }
    /** Long-answer checkpoints replace the request envelope while the same run is writing. */
    fun retain(previous:String,replacement:String):String {
        if(previous==replacement || KEY !in previous)return replacement
        val next=runCatching {Json.parseToJsonElement(replacement).jsonObject}.getOrNull()?:return replacement
        if(KEY in next)return replacement
        val prior=runCatching {Json.parseToJsonElement(previous).jsonObject}.getOrNull()?:return replacement
        val report=prior[KEY] as? JsonObject?:return replacement
        val retained=JsonObject(next+(KEY to report)).toString()
        return retained.takeIf {it.length<=2_000_000}?:replacement
    }
    suspend fun checkpoint(request:QueuedChatRequest, context:JsonObject, outcome:ObservedToolOutcome?,
        stepCount:Int=0, lookup:suspend (String)->ChatRunRecord?):History {
        var history=read(context)
        if(KEY !in context) {
            val parentId=request.goalSegment?.parentRunId?:request.browserAuthParentRunId?:request.goalOwnerReplyParentId
                ?:request.goal?.linkedRunIds?.let {ids->ids.getOrNull(ids.indexOf(request.runId)-1)}
            val parent=parentId?.takeIf {it!=request.runId}?.let {lookup(it)}
            if(parent!=null && parent.id==parentId && parent.threadId==request.threadId && parent.branchId==request.branchId &&
                parent.projectId==request.projectId && (request.goal==null || GoalPdfEmail.matchesScope(request,parent))) {
                val saved=runCatching {QueuedChatRequest.fromJson(parent.contextJson)}.getOrNull()
                if(saved!=null && saved.runId==parent.id && runCatching {requireSameGoalAuthority(saved,request)}.isSuccess) {
                    val source=Json.parseToJsonElement(parent.contextJson).jsonObject
                    history=read(source)
                    if(parent.status in setOf("paused","interrupted","waiting_approval"))
                        state(parent.status,parent.errorCode,source)?.let {history=history.append(Line("prior:"+parent.errorCode,it))}
                    wake(source)?.let {history=history.append(it)}
                }
            }
        }
        if(outcome==null)return history
        val callKey=goalDigest(request.runId+"|"+stepCount+"|"+outcome.call.name+"|"+outcome.call.id+"|"+outcome.result.verification)
        if(history.lastCall==callKey)return history
        return observe(history,outcome).copy(lastCall=callKey)
    }
    private fun JsonObject.str(key:String)=(this[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
    private fun safeClass(value:String?)=value?.takeIf {className.matches(it)&&!identifier.containsMatchIn(it)&&!ConversationPrivacy.sensitiveText(it)}
    private fun host(value:String?):String? {
        val url=value?.let {runCatching {URI(it)}.getOrNull()}?:return null
        if(url.scheme!="https"||url.rawUserInfo!=null)return null
        val host=url.host?.lowercase()?:return null
        val labels=host.split('.')
        if(labels.size<2||labels.last().length !in 2..24||labels.last().any {it !in 'a'..'z'})return null
        val count=if(labels.size>=3&&labels.last().length==2&&labels[labels.lastIndex-1] in setOf("ac","co","go","or","ne","re","com","org","gov","edu","net"))3 else 2
        return labels.takeLast(count).joinToString(".").takeIf {it.length<=70 && !identifier.containsMatchIn(it)}
    }
    private fun duration(ms:Long):String {
        val seconds=ms/1000
        return "${seconds/60}분 ${seconds%60}초"
    }
    fun observe(history:History, observed:ObservedToolOutcome):History {
        val (call,result)=observed
        if(result.id!=call.id||call.name !in ToolRegistry.definitions)return history
        val tool=call.name
        val label=toolLabel(tool)
        if(!result.success || result.effectNotApplied || result.verification!=VerificationState.VERIFIED){
            val auth=tool in browser && result.verificationSummary in setOf("PHONE_BROWSER_AUTH_REQUIRED","PC_BROWSER_AUTH_REQUIRED")
            val description=when {
                auth->"사이트가 본인 인증을 요청했어요."
                result.verification==VerificationState.PREPARED->"$label 준비. 실제 수행 결과는 아직 확인되지 않았어요."
                result.effectNotApplied->"$label 단계에서 멈췄고 변경은 적용되지 않았어요."
                result.verification==VerificationState.UNVERIFIED->"$label 결과를 확인하지 못했어요."
                else->"$label 실패. 실제 적용 여부는 다시 확인해야 해요."
            }
            return history.append(Line(if(auth)"auth" else "$tool:${result.verification}:${result.effectNotApplied}",description))
        }
        val data=if(tool in browser || tool in setOf("save_browser_pdf","send_gmail_message","create_google_calendar_event",GoalProgressToolCatalog.READ,GoalProgressToolCatalog.UPDATE))
            runCatching {Json.parseToJsonElement(result.content).jsonObject}.getOrNull()?:JsonObject(emptyMap()) else JsonObject(emptyMap())
        if(tool=="send_gmail_message" || tool=="create_google_calendar_event"){
            val proof=connectorGoalProof(call,data)
            val text=when(proof?.kind){
                GoalProofKind.GMAIL_SENT->"Gmail 보낸편지함에서 발송 결과를 확인했어요."
                GoalProofKind.CALENDAR_EVENT->"등록 요청과 일치하는 일정을 캘린더에서 다시 확인했어요."
                else->"$label 도구가 응답했어요. 요청과 일치하는 대상의 재조회 결과는 확인되지 않았어요."
            }
            return history.append(Line("$tool:${proof?.kind}:${goalDigest(call.id).take(16)}",text))
        }
        if(tool in browser){
            val site=host(data.str("url"))
            val where=site?.let {"$it · "}.orEmpty()
            if(data["loginRequired"]==JsonPrimitive(true))return history.append(Line("auth:$site",where+"사이트가 본인 인증을 요청했어요."))
            val media=data["playbackEvidence"] as? JsonObject
            val status=media?.str("playbackStatus")
            if(media!=null && status in playback){
                val position=(media["positionMs"] as? JsonPrimitive)?.longOrNull?.takeIf {it in 0..86_400_000L}
                val total=(media["durationMs"] as? JsonPrimitive)?.longOrNull?.takeIf {it in 1..86_400_000L && (position==null||position<=it)}
                val timing=listOfNotNull(position?.let {"관찰 위치 "+duration(it)},total?.let {"전체 "+duration(it)}).joinToString(", ")
                val player=media.str("playerSignature")?.takeIf {it.length<=1024}?.let {goalDigest(it).take(16)}.orEmpty()
                return history.append(Line("media:$site:$player:$status",where+"영상 "+playback.getValue(checkNotNull(status))+
                    timing.takeIf(String::isNotEmpty)?.let {" ($it)"}.orEmpty()))
            }
            if(media!=null){
                val observation=when(status){
                    "player_changed"->"다른 플레이어가 보여 같은 영상의 재생 상태를 확인하지 못했어요."
                    "observation_timeout"->"관찰 시간 안에 영상의 재생 상태를 확인하지 못했어요."
                    else->"현재 화면에서 영상의 재생 상태를 확인하지 못했어요."
                }
                return history.append(Line("media:$site:unknown",where+observation))
            }
            val completed=safeClass(data.str("verifiedCompletedCourseTitle"))
            if(completed!=null && tool==PcBrowserToolCatalog.READ && completed==call.arguments.str(PcBrowserToolCatalog.COURSE_TITLE) && runCatching {goalOutcomeProof(call,result)}.getOrNull()!=null)
                return history.append(Line("course:$completed",where+"사이트 수강완료 목록에서 「$completed」 확인"))
            if(site!=null && tool.startsWith("read_") && history.lines.lastOrNull()?.key?.startsWith("media:$site:")==true)
                return history
            if(result.verificationSummary==BrowserNavigationRule.VERIFIED_SUMMARY)
                return history.append(Line("navigation:$site",where+"대상 링크를 열고 도착한 페이지에서 새 확인 문구를 찾았어요."))
            if(data["loginRequired"]==JsonPrimitive(false) && history.lines.lastOrNull()?.key=="recheck:$site" && tool in setOf(PcBrowserToolCatalog.READ,"read_app_browser_page"))return history
            if(tool in setOf(PcBrowserToolCatalog.READ,"read_app_browser_page") && data["loginRequired"]==JsonPrimitive(false) &&
                history.lines.lastOrNull()?.let {it.key.startsWith("auth")||it.key=="wake:OWNER_AUTH_DONE"||it.key.startsWith("prior:waiting_browser_auth")}==true)
                return history.append(Line("recheck:$site",where+"사이트를 다시 읽었어요. 이 화면에서는 추가 로그인 요구가 보이지 않았어요."))
            if(data["pageChanged"]==JsonPrimitive(true))
                return history.append(Line("changed:$site",where+"사이트 조작 후 페이지 전환을 확인했어요."))
            if(data["pageChanged"]==JsonPrimitive(false))return history.append(Line("unchanged:$site",where+"사이트 조작 후 페이지 전환은 확인되지 않았어요."))
            return history.append(Line("$tool:$site",where+label+" 결과 확인"))
        }
        if(tool==GoalProgressToolCatalog.READ||tool==GoalProgressToolCatalog.UPDATE){
            val ledger=data["ledger"] as? JsonObject?:return history
            val entries=(ledger["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(GoalProgressLimits.MAX_ITEMS)
            val updated=history.copy(checklist=checklist(entries))
            val item=entries.lastOrNull {it.str("kind")=="lecture"&&it.str("status") in setOf("active","waiting")}?:return updated
            val course=safeClass(item.str("course"))?:return updated
            val unit=safeClass(item.str("unit"))?:return updated
            val line=Line("lesson:$course:$unit","작업 장부의 진행 대상 · $course / $unit")
            return if(updated.lines.lastOrNull {it.key.startsWith("lesson:")}==line)updated else updated.append(line)
        }
        if(tool=="save_browser_pdf"){
            val course=safeClass(data.str("certificateCourse"))
            val year=(data["certificateCompletionYear"] as? JsonPrimitive)?.intOrNull
            if(course!=null && year!=null && year in 1900..2200 && runCatching {goalOutcomeProof(call,result)}.getOrNull()!=null)
                return history.append(Line("certificate:$course:$year","${year}년 「$course」 이수증 PDF 저장 및 내용 확인"))
        }
        if(tool in setOf("get_goal","get_current_datetime","get_agent_capabilities","wait_for_goal_condition"))return history
        val key=if(ToolReadPolicy.isReadOnly(tool))tool else "$tool:${goalDigest(call.id).take(16)}"
        return history.append(Line(key,label+" 결과 확인"))
    }

    private fun checklist(entries:List<JsonObject>):List<String> {
        if(entries.isEmpty())return emptyList()
        fun status(item:JsonObject):String = item.str("status").orEmpty().let {
            if(it=="done" && (item["evidence"] as? JsonArray).isNullOrEmpty())"todo" else it
        }
        val done=entries.count {status(it)=="done"}
        val remaining=entries.size-done
        val states=mapOf("blocked" to "막힌 항목", "waiting" to "대기 중", "active" to "진행 중",
            "done" to "장부에서 확인한 항목", "todo" to "남은 항목")
        return buildList {
            add("작업 장부 %d개 항목 중 확인 %d개 · 남음 %d개".format(entries.size,done,remaining))
            states.forEach { (state,label)->
                val selected=entries.asReversed().firstNotNullOfOrNull {item->
                    if(status(item)!=state)return@firstNotNullOfOrNull null
                    val target=safeClass(item.str("course")) ?: return@firstNotNullOfOrNull null
                    val unit=safeClass(item.str("unit")) ?: return@firstNotNullOfOrNull null
                    if(target==unit)target else "$target / $unit"
                }
                if(selected!=null)add("$label · $selected")
            }
        }
    }

    private fun wake(context:JsonObject):Line? {
        val event=(context["hostGoalWake"] as? JsonObject)?.str("event")?:return null
        val text=when(event) {
            "MEDIA_ENDED"->"영상 종료를 확인했어요. 다음은 사이트의 진도 반영 여부를 확인할 차례예요."
            "MEDIA_STALLED"->"관찰한 영상 위치가 더 이상 늘지 않았어요. 재생 상태를 확인해야 해요."
            "PLAYER_GONE"->"같은 플레이어를 찾지 못했어요. 재생 상태를 다시 확인해야 해요."
            "LOGIN_EXPIRED"->"로그인이 만료됐어요. 사이트에 다시 로그인해야 해요."
            "MEDIA_LIMIT"->"재생 확인 시간이 끝났어요. 사이트 진도는 다시 확인해야 해요."
            "OWNER_AUTH_DONE"->"소유자 인증 완료 신호를 받았어요. 같은 작업에서 로그인 결과를 다시 확인해요."
            "OWNER_AUTH_TIMEOUT"->"시간 안에 인증 완료를 확인하지 못했어요."
            "TIME_REACHED","RESTORED_PAST_DUE"->"예약한 확인 시각이 지났어요."
            "CONDITIONS_UNMET"->"예약한 조건이 충족됐는지 확인하지 못했어요."
            else->return null
        }
        return Line("wake:$event",text)
    }
    fun goalResult(contextJson:String,goal:GoalDefinition):String {
        val context=runCatching {Json.parseToJsonElement(contextJson).jsonObject}.getOrNull() ?: return contextJson
        val verified=goal.criteria.count {it.status==GoalCriterionStatus.VERIFIED}
        val owner=goal.criteria.count {it.status==GoalCriterionStatus.USER_CONFIRMED}
        return JsonObject(context+("hostGoalCriteriaCounts" to buildJsonObject {
            put("verified",verified);put("owner",owner);put("total",goal.criteria.size)
        })).toString()
    }
    fun state(status:String,error:String?,context:JsonObject):String? {
        if(error=="goal_verified_complete")return "목표의 모든 완료 기준을 확인했어요."
        GoalAutopilotPauseReason.entries.find {it.code==error}?.let {return it.description}
        val due=(context["hostGoalWaitAt"] as? JsonPrimitive)?.longOrNull
            ?.takeIf {it in 946684800000L..7258118400000L}
            ?.let {Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))}
        return when {
        status=="waiting_approval"->"내용을 확인하고 승인해 주시면 이어갈게요."
        status=="paused"&&error=="waiting_goal_condition"&&context.str("hostGoalWaitKind")=="media"->"영상 종료를 기다리고 있어요. 종료 신호를 받으면 사이트 진도를 확인해요."
        status=="paused"&&(error=="waiting_goal_condition"&&(context.str("hostGoalWaitKind")=="auth" || "hostGoalOwnerLogin" in context) ||
            error in setOf("waiting_browser_auth","browser_auth_on_phone"))->"사이트에서 인증을 마쳐 주세요. 이어서 로그인됐는지 확인할게요."
        status=="paused"&&error=="goal_wait_cancelled"->"예약된 이어가기를 취소했어요. 확인한 내용은 작업 기록에 남겼어요."
        status=="paused"&&error=="goal_wait_deadline"->"예약된 작업의 기한이 지났어요. 확인한 내용은 작업 기록에 남겼어요."
        status=="paused"&&error=="goal_wait_auth_expired"->"사이트 인증 요청이 만료됐어요. 확인한 내용은 작업 기록에 남겼어요."
        status=="paused"&&error=="browser_auth_cancelled"->"로그인 대기를 취소했어요. 확인한 내용은 작업 기록에 남겼어요."
        status=="paused"&&error=="goal_wait_scope_changed"->"대기 중 작업 권한이나 대화 범위가 바뀌어 일시정지했어요."
        status=="paused"&&error=="goal_wait_checkpoint_unavailable"->"저장된 대기 지점을 열지 못했어요. 작업 기록에서 마지막 단계를 확인할 수 있어요."
        status=="paused"&&error==GOAL_OWNER_INPUT->"위 질문에 답해 주시면 이 대화에서 이어갈게요."
            status=="paused"&&error=="waiting_goal_condition"&&due!=null->"예약 대기 · $due 이후 다시 확인해요."
        status=="paused"&&error=="waiting_goal_condition"->"조건 대기 · 예약한 조건을 다시 확인한 뒤 이어가요."
        status=="paused"->"여기까지 확인했어요. 작업을 이어가면 마지막 상태부터 확인할게요."
        status=="interrupted"->"작업이 중간에 멈췄어요. 계속하려면 마지막 결과를 먼저 확인해야 해요."
        status=="cancelled"->"작업을 중단했어요."
        status=="failed"->"작업을 끝내지 못했어요. 마지막으로 확인한 내용은 작업 기록에 남겼어요."
        status=="completed"->"이 실행 구간이 끝났어요."
        else->null
        }
    }
    fun display(history:History,status:String,error:String?,context:JsonObject):List<String> {
        val ending=state(status,error,context)
        if(history.lines.isEmpty() && history.checklist.isEmpty() && "goalSegment" !in context && "hostGoalCriteriaCounts" !in context && (ending==null || status=="completed" || KEY !in context))return emptyList()
        return buildList {
            if(context[InterruptedGoalFileCheck.MATCHED]==JsonPrimitive(true))
                add("앱이 다시 열린 뒤 파일을 읽어 요청한 내용과 일치하는지 확인했어요. 이전 저장 동작이 끝났는지는 확인할 수 없어요.")
            history.lines.forEachIndexed {i,line->
                if(i==1&&history.omitted>0)add("중간 단계 ${history.omitted}개는 요약에서 생략했어요.")
                add(line.text)
            }
            val media=history.lines.indexOfLast {it.key.startsWith("media:")}
            if(media>=0 && history.lines.drop(media+1).none {it.key.startsWith("course:")||it.key.startsWith("certificate:")})
                add("위 재생 관찰만으로는 사이트의 진도율이나 이수 처리를 확인할 수 없어요.")
            val counts=context["hostGoalCriteriaCounts"] as? JsonObject
            if(counts!=null) {
                val verified=(counts["verified"] as? JsonPrimitive)?.intOrNull
                val owner=(counts["owner"] as? JsonPrimitive)?.intOrNull
                val total=(counts["total"] as? JsonPrimitive)?.intOrNull
                if(total!=null && total in 1..1000 && verified!=null && verified in 0..total && owner!=null && owner in 0..(total-verified))
                    add("목표 기준 ${total}개 중 실행 근거로 확인 ${verified}개, 소유자가 확인 ${owner}개, 미확인 ${total-verified-owner}개예요.")
            }
            val resumed=wake(context)
            if(resumed!=null && history.lines.lastOrNull()!=resumed)add(resumed.text)
            if(context[GoalPdfEmail.KEY] is JsonObject)add("첨부 PDF가 있는 메일 초안을 준비했어요. 발송 여부는 확인되지 않았어요. 아래 초안에서 내용을 확인할 수 있어요.")
            addAll(history.checklist)
            if(resumed==null || status!="paused")ending?.let {add(it)}
        }.let { lines -> if(lines.size<=40)lines else listOf(lines.first())+lines.takeLast(39) }
    }
}
