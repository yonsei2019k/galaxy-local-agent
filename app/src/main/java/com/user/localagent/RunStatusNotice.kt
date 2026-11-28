package com.user.localagent

import kotlinx.serialization.json.*

/**
 * Facts the host shows about a run. The host never writes conversation sentences: model text is the
 * only prose in the chat, and these short labels are rendered as a status card by the UI.
 * [reason] and [instruction] are a few plain words; [instruction] keeps exact menu names when the
 * owner must find a phone setting.
 */
internal data class RunNotice(val reason:String,val action:String?=null,val instruction:String?=null) {
    init {
        require(reason.isNotBlank()&&reason.length<=120&&reason.none(Char::isISOControl))
        require(action==null||action in RunStatusLabels.actions)
        require(instruction==null||instruction.isNotBlank()&&instruction.length<=240&&instruction.none(Char::isISOControl))
    }
    fun json()=buildJsonObject {
        put("reason",reason)
        action?.let {put("action",it)}
        instruction?.let {put("instruction",it)}
    }
}

/** Structured status for one run, sent to the web UI as `runStatus` on the run's assistant message. */
internal data class RunStatusCard(val phase:String,val label:String,val reason:String?=null,
    val action:String?=null,val actionLabel:String?=null,val instruction:String?=null) {
    fun json()=buildJsonObject {
        put("phase",phase);put("label",label)
        reason?.let {put("reason",it)}
        action?.let {put("action",it)}
        actionLabel?.let {put("actionLabel",it)}
        instruction?.let {put("instruction",it)}
    }
}

internal object RunStatusLabels {
    const val KEY="hostRunNoticeV1"

    /** Action ids the UI maps to its existing buttons; the label is the plain button text. */
    val actions=mapOf(
        "open_screen_setup" to "설정 열기",
        "open_login" to "로그인하기",
        "reply" to "답하기",
        "open_pc_connection" to "PC 연결 보기",
        "open_calendar_permission" to "일정 허락하기",
        "new_request" to "다시 부탁하기",
        "choose_model" to "모델 고르기",
    )

    /** Plain labels per phase id. */
    val phases=linkedMapOf(
        "queued" to "기다리는 중",
        "running" to "하는 중",
        "waiting_approval" to "허락 기다리는 중",
        "waiting_reply" to "답 기다리는 중",
        "waiting_login" to "로그인 기다리는 중",
        "waiting_time" to "다시 볼 때까지 기다리는 중",
        "paused" to "잠깐 멈춤",
        "failed" to "끝내지 못함",
        "interrupted" to "중간에 끊김",
        "cancelled" to "그만둠",
        "completed" to "끝남",
    )

    private val replyCodes=setOf(com.user.localagent.core.NextActionQuestion.PAUSE_CODE,
        com.user.localagent.core.NextActionQuestion.CLOUD_PAUSE_CODE,GOAL_OWNER_INPUT)
    private val loginCodes=setOf("waiting_browser_auth","browser_auth_on_phone","remote_test_owner_login_required",
        "browser_auth_resume_expired","browser_auth_resume_unavailable")

    /** Reason labels for runs saved before the host stored a [RunNotice]. Unknown codes show the phase only. */
    private val legacyReasons=mapOf(
        "screen_setup_system" to RunNotice("화면 연결 설정 필요","open_screen_setup"),
        "screen_setup_app" to RunNotice("화면 연결 설정 필요","open_screen_setup"),
        "screen_setup_reconnect" to RunNotice("화면 연결 다시 켜기 필요","open_screen_setup"),
        com.user.localagent.tools.PhoneCalendarReadEvidence.PAUSE_CODE to RunNotice("일정 보기 허락 필요","open_calendar_permission"),
        com.user.localagent.core.WebsiteExecutionPolicy.RECONNECT_CODE to RunNotice("PC 연결 기다리는 중","open_pc_connection"),
        "waiting_browser_auth" to RunNotice("사이트 로그인 필요","open_login"),
        "browser_auth_on_phone" to RunNotice("사이트 로그인 필요","open_login"),
        "browser_auth_resume_expired" to RunNotice("로그인 확인 시간 지남","open_login"),
        "browser_auth_resume_unavailable" to RunNotice("로그인 뒤 못 이어감"),
        "goal_recovery_scope_changed" to RunNotice("이전에 받은 권한 변경"),
        "goal_wait_scope_changed" to RunNotice("대기 중 작업 권한 변경"),
        "goal_wait_checkpoint_unavailable" to RunNotice("이전 작업 기록 확인 필요"),
        "wait_deadline" to RunNotice("작업 마감 시간 지남"),
        "wait_auth_expired" to RunNotice("로그인 확인 시간 지남"),
        "wait_cancelled" to RunNotice("요청하신 대기 취소"),
    )

    fun phaseId(status:String,errorCode:String?):String = when {
        status=="paused"&&errorCode in replyCodes->"waiting_reply"
        status=="paused"&&errorCode in loginCodes->"waiting_login"
        status=="paused"&&errorCode=="waiting_goal_condition"->"waiting_time"
        status in phases->status
        else->"paused"
    }

    fun read(context:JsonObject):RunNotice? {
        val value=context[KEY] as? JsonObject?:return null
        fun text(key:String)=(value[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
        return runCatching {RunNotice(checkNotNull(text("reason")),text("action"),text("instruction"))}.getOrNull()
    }

    fun write(context:JsonObject,notice:RunNotice):JsonObject = JsonObject(context+(KEY to notice.json()))

    fun card(status:String,errorCode:String?,context:JsonObject):RunStatusCard {
        val phase=phaseId(status,errorCode)
        val notice=read(context)?:legacyReasons[errorCode]
            ?:GoalAutopilotPauseReason.entries.find {it.code==errorCode}?.let {RunNotice(it.label)}
        val action=notice?.action?:if(phase=="waiting_reply")"reply" else null
        return RunStatusCard(phase,phases.getValue(phase),notice?.reason,action,action?.let(actions::get),notice?.instruction)
    }
}

/**
 * Deterministic output check for model-written status text. Intent is never classified here: the text
 * is only rejected when a sentence claims a finished or applied result that the run state does not show.
 */
internal object ModelStatusText {
    private val claim=Regex("완료|끝냈|끝났|마쳤|성공|저장했|저장해 ?(뒀|두었|놨|놓았)|보냈|발송했|전송했|등록했|예약했|신청했|결제했|주문했|" +
        "바꿨|바꾸었|변경했|적용했|처리했|삭제했|지웠|만들었|체크인했|출석했|해 ?(뒀|두었|놨|놓았)어|됐어요|되었어요|되었습니다|했습니다|done|completed|success",
        RegexOption.IGNORE_CASE)
    private val negation=Regex("못|않|안 |안했|아직|실패|모르|확인 ?(이 )?필요|확인하지|없어|없었|불확실|미확인|멈췄|중단")

    fun acceptable(text:String?,completed:Boolean):Boolean {
        val body=text?.trim().orEmpty()
        if(body.isBlank()||body.length>4000)return false
        if(completed)return true
        return body.split(Regex("[.!?。\n]+")).none {sentence->claim.containsMatchIn(sentence)&&!negation.containsMatchIn(sentence)}
    }

    /** Model text to post for a run that ends with [completed]; null means the status card alone is shown. */
    fun choose(modelText:String?,completed:Boolean):String? = modelText?.trim()?.takeIf {acceptable(it,completed)}
}
