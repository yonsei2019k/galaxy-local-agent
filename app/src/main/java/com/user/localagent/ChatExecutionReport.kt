package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
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
        val label=ToolReportLabels.label(tool)
        if(!result.success || result.effectNotApplied || result.verification!=VerificationState.VERIFIED){
            val auth=tool in browser && result.verificationSummary in setOf("PHONE_BROWSER_AUTH_REQUIRED","PC_BROWSER_AUTH_REQUIRED")
            val description=when {
                auth->"사이트 · 로그인 필요"
                result.verification==VerificationState.PREPARED->"$label · 준비만 함 · 결과는 아직 모름"
                result.effectNotApplied->"$label · 멈춤 · 바뀐 것 없음"
                result.verification==VerificationState.UNVERIFIED->"$label · 결과 못 봄"
                else->"$label · 안 됨 · 바뀌었는지 확인 필요"
            }
            return history.append(Line(if(auth)"auth" else "$tool:${result.verification}:${result.effectNotApplied}",description))
        }
        val data=if(tool in browser || tool in setOf("save_browser_pdf","send_gmail_message","create_google_calendar_event",GoalProgressToolCatalog.READ,GoalProgressToolCatalog.UPDATE))
            runCatching {Json.parseToJsonElement(result.content).jsonObject}.getOrNull()?:JsonObject(emptyMap()) else JsonObject(emptyMap())
        if(tool=="send_gmail_message" || tool=="create_google_calendar_event"){
            val proof=connectorGoalProof(call,data)
            val text=when(proof?.kind){
                GoalProofKind.GMAIL_SENT->"Gmail 보낸편지함에서 보낸 메일 확인함"
                GoalProofKind.CALENDAR_EVENT->"캘린더에서 새 일정 다시 확인함"
                else->"$label · 응답 받음 · 결과는 아직 못 봄"
            }
            return history.append(Line("$tool:${proof?.kind}:${goalDigest(call.id).take(16)}",text))
        }
        if(tool in browser){
            val site=host(data.str("url"))
            val where=site?.let {"$it · "}.orEmpty()
            if(data["loginRequired"]==JsonPrimitive(true))return history.append(Line("auth:$site",where+"로그인 필요"))
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
                    "player_changed"->"영상 상태 못 봄 · 다른 플레이어가 보임"
                    "observation_timeout"->"영상 상태 못 봄 · 볼 시간이 끝남"
                    else->"영상 상태 못 봄"
                }
                return history.append(Line("media:$site:unknown",where+observation))
            }
            val completed=safeClass(data.str("verifiedCompletedCourseTitle"))
            if(completed!=null && tool==PcBrowserToolCatalog.READ && completed==call.arguments.str(PcBrowserToolCatalog.COURSE_TITLE) && runCatching {goalOutcomeProof(call,result)}.getOrNull()!=null)
                return history.append(Line("course:$completed",where+"수강완료 목록에서 「$completed」 확인함"))
            if(site!=null && tool.startsWith("read_") && history.lines.lastOrNull()?.key?.startsWith("media:$site:")==true)
                return history
            if(result.verificationSummary==BrowserNavigationRule.VERIFIED_SUMMARY)
                return history.append(Line("navigation:$site",where+"링크를 열고 새 페이지 확인함"))
            if(data["loginRequired"]==JsonPrimitive(false) && history.lines.lastOrNull()?.key=="recheck:$site" && tool in setOf(PcBrowserToolCatalog.READ,"read_app_browser_page"))return history
            if(tool in setOf(PcBrowserToolCatalog.READ,"read_app_browser_page") && data["loginRequired"]==JsonPrimitive(false) &&
                history.lines.lastOrNull()?.let {it.key.startsWith("auth")||it.key=="wake:OWNER_AUTH_DONE"||it.key.startsWith("prior:waiting_browser_auth")}==true)
                return history.append(Line("recheck:$site",where+"사이트 다시 읽음 · 추가 로그인 요구 없음"))
            if(data["pageChanged"]==JsonPrimitive(true))
                return history.append(Line("changed:$site",where+"누른 뒤 페이지 바뀜 확인함"))
            if(data["pageChanged"]==JsonPrimitive(false))return history.append(Line("unchanged:$site",where+"누른 뒤 페이지 그대로"))
            return history.append(Line("$tool:$site",where+label+" · 확인함"))
        }
        if(tool==GoalProgressToolCatalog.READ||tool==GoalProgressToolCatalog.UPDATE){
            val ledger=data["ledger"] as? JsonObject?:return history
            val entries=(ledger["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(GoalProgressLimits.MAX_ITEMS)
            val updated=history.copy(checklist=checklist(entries))
            val item=entries.lastOrNull {it.str("kind")=="lecture"&&it.str("status") in setOf("active","waiting")}?:return updated
            val course=safeClass(item.str("course"))?:return updated
            val unit=safeClass(item.str("unit"))?:return updated
            val line=Line("lesson:$course:$unit","할 일 장부 · 지금 할 것 · $course / $unit")
            return if(updated.lines.lastOrNull {it.key.startsWith("lesson:")}==line)updated else updated.append(line)
        }
        if(tool=="save_browser_pdf"){
            val course=safeClass(data.str("certificateCourse"))
            val year=(data["certificateCompletionYear"] as? JsonPrimitive)?.intOrNull
            if(course!=null && year!=null && year in 1900..2200 && runCatching {goalOutcomeProof(call,result)}.getOrNull()!=null)
                return history.append(Line("certificate:$course:$year","${year}년 「$course」 이수증 PDF 저장하고 내용 확인함"))
        }
        if(tool in setOf("get_goal","get_current_datetime","get_agent_capabilities","wait_for_goal_condition"))return history
        val key=if(ToolReadPolicy.isReadOnly(tool))tool else "$tool:${goalDigest(call.id).take(16)}"
        return history.append(Line(key,label+" · 확인함"))
    }

    private fun checklist(entries:List<JsonObject>):List<String> {
        if(entries.isEmpty())return emptyList()
        fun status(item:JsonObject):String = item.str("status").orEmpty().let {
            if(it=="done" && (item["evidence"] as? JsonArray).isNullOrEmpty())"todo" else it
        }
        val done=entries.count {status(it)=="done"}
        val remaining=entries.size-done
        val states=mapOf("blocked" to "막힘", "waiting" to "기다리는 중", "active" to "하는 중",
            "done" to "확인함", "todo" to "남음")
        return buildList {
            add("할 일 %d개 · 확인 %d개 · 남음 %d개".format(entries.size,done,remaining))
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
            "MEDIA_ENDED"->"영상 끝남 확인 · 다음은 진도 들어갔는지 보기"
            "MEDIA_STALLED"->"영상이 더 안 나감 · 재생 모습 확인 필요"
            "PLAYER_GONE"->"영상 플레이어 못 찾음 · 재생 모습 확인 필요"
            "LOGIN_EXPIRED"->"로그인 풀림 · 다시 로그인 필요"
            "MEDIA_LIMIT"->"영상 볼 시간 끝 · 진도 확인 필요"
            "OWNER_AUTH_DONE"->"로그인 끝 신호 받음 · 로그인 결과를 다시 확인"
            "OWNER_AUTH_TIMEOUT"->"시간 안에 로그인 확인 못 함"
            "TIME_REACHED","RESTORED_PAST_DUE"->"다시 볼 시간이 됨"
            "CONDITIONS_UNMET"->"정한 조건이 맞는지 못 봄"
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
        if(error=="goal_verified_complete")return "목표 기준 모두 확인함"
        GoalAutopilotPauseReason.entries.find {it.code==error}?.let {return it.label}
        val notice=RunStatusLabels.read(context)?.reason
        val due=(context["hostGoalWaitAt"] as? JsonPrimitive)?.longOrNull
            ?.takeIf {it in 946684800000L..7258118400000L}
            ?.let {Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))}
        return when {
        status=="waiting_approval"->"허락 기다리는 중"
        status=="paused"&&context.str("hostGoalWaitKind")=="media"->"영상 끝나기 기다리는 중 · 끝나면 진도 확인"
        status=="paused"&&(context.str("hostGoalWaitKind")=="auth" || "hostGoalOwnerLogin" in context || error in setOf("waiting_browser_auth","browser_auth_on_phone"))->"로그인 기다리는 중 · 공식 로그인 뒤 결과 다시 확인"
        status=="paused"&&error==GOAL_OWNER_INPUT->"답 기다리는 중"
            status=="paused"&&error=="waiting_goal_condition"&&due!=null->"$due 이후 다시 보기"
        status=="paused"&&error=="waiting_goal_condition"->"정한 조건 기다리는 중"
        status=="paused"->listOfNotNull("잠깐 멈춤",notice).joinToString(" · ")
        status=="interrupted"->listOfNotNull("중간에 끊김",notice?:"마지막 결과 확인 필요").joinToString(" · ")
        status=="cancelled"->"그만둠"
        status=="failed"->listOfNotNull("끝내지 못함",notice?:"확인한 데까지 남겨 둠").joinToString(" · ")
        status=="completed"->listOfNotNull("끝남",notice).joinToString(" · ")
