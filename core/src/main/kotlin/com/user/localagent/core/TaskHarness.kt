package com.user.localagent.core

import java.util.Collections
import java.util.Locale
import kotlinx.serialization.json.*

enum class VerificationState { VERIFIED, PREPARED, UNVERIFIED, FAILED }

/** Display/verification metadata, never a permission grant or a model-issued authority. */
class TaskGoal(ownerRequest:String,successCriteria:List<String>,val stepBudget:Int=6,requiredTools:Set<String> = emptySet()) {
    val ownerRequest:String=ownerRequest.also {require(it.isNotBlank()&&it.length<=6000)}
    val goal:String get()=ownerRequest
    val successCriteria:List<String> = Collections.unmodifiableList(successCriteria.map {it.also {criterion->require(criterion.isNotBlank()&&criterion.length<=1000)}})
    val requiredTools:Set<String> = Collections.unmodifiableSet(requiredTools.toSet())
    init {require(stepBudget in 1..6);require(this.successCriteria.isNotEmpty()&&this.successCriteria.size<=12);require(this.requiredTools.size<=12)}
}
data class StepEvidence(val tool:String,val state:VerificationState,val summary:String) {
    init {require(tool.isNotBlank()&&tool.length<=100);require(summary.length<=4000)}
    val verification:VerificationState get()=state
}
class HarnessStep(tool:String,arguments:JsonObject,val evidence:StepEvidence) {
    val tool:String=tool
    val arguments:JsonObject=Json.parseToJsonElement(arguments.toString()).jsonObject
    internal val fingerprint:String=tool+":"+canonical(this.arguments)
    init {require(tool==evidence.tool);require(this.arguments.toString().length<=32768)}
    constructor(call:ToolCall,evidence:StepEvidence):this(call.name,call.arguments,evidence)
}
sealed interface HarnessDecision {
    data class Advance(val reason:String):HarnessDecision
    data class Finish(val summary:String):HarnessDecision
    data class Stop(val reason:String,val pendingExternalCompletion:Boolean=false):HarnessDecision
}

/** Decisions consume host-generated verification only, never assertions from the model or source data. */
object TaskHarness {
    /** Inherit only capabilities that the host router recognizes for a short follow-up. */
    fun inferGoal(ownerRequest:String,previousUserRequests:List<String>,stepBudget:Int=6):TaskGoal {
        val current=inferGoal(ownerRequest,stepBudget)
        if(current.requiredTools.isNotEmpty())return current
        val direct=directText(ownerRequest)
        if(direct.isBlank())return current
        val previous=previousUserRequests.takeLast(2).map(::directText)
        if(previous.lastOrNull().isNullOrBlank())return current
        val routed=RequestToolRouter.select(direct,previous).toSet()
        if(routed==RequestToolRouter.select(direct).toSet())return current
        val previousGoal=previous.asReversed().firstOrNull {it.isNotBlank()}?.let {inferGoal(it,stepBudget)}?:return current
        val required=previousGoal.requiredTools.intersect(routed)
        if(required.isEmpty())return current
        return TaskGoal(current.ownerRequest,previousGoal.successCriteria,stepBudget,required)
    }

    fun inferGoal(ownerRequest:String,stepBudget:Int=6):TaskGoal {
        val direct=directText(ownerRequest).take(6000)
        val text=direct.lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
        val required=linkedSetOf<String>()
        val criteria=mutableListOf<String>()
        fun add(tool:String,criterion:String){required+=tool;criteria+=criterion}
        fun has(vararg words:String)=words.any {it in text}
        val read=has("분석","요약","조회","찾아","검색","정리","읽어","읽고","보여","확인","알려줘")
        val write=has("저장","적어","남겨","기록","등록","추가","만들","생성","넣어","옮겨","그려")
        val remove=has("삭제","지워","잊어","없애")
        val update=has("수정","변경","고쳐","바꿔")
        val explaining=has("방법","원리","번역","설명해","뜻이야","기능이야")
        val negated=Regex("(?:저장|삭제|수정|변경|분석|조회|전사|읽)(?:은|는)?(?:하지마|하지말|지마|지말)").containsMatchIn(text)
        if(explaining||negated)return TaskGoal(direct.ifBlank {"사용자의 직접 요청 확인"},listOf("설명·중단 요청을 실행 권한으로 해석하지 않는다."),stepBudget)
        val messenger=has("카카오","카톡","텔레그램","메디통","메신저")
        val sms=has("문자","sms")||has("메시지","메세지")&&!messenger
        if(sms&&read&&!has("문자열","유니코드","코드"))
            add("get_sms_summary","요청한 범위의 문자 조회·분석 결과를 확인한다.")
        if(messenger&&read)
            add("list_messenger_notifications","요청한 메신저 자료의 실제 조회 범위와 결과를 확인한다.")
        if(has("메모"))when {remove->add("delete_note","지정한 메모가 삭제됐는지 확인한다.");update->add("update_note","요청한 메모 수정 결과를 다시 읽어 확인한다.");write->add("save_note","메모가 실제 저장됐는지 다시 읽어 확인한다.");read->add("search_notes","요청한 메모 검색 결과를 확인한다.")}
        if(has("기억"))when {remove->add("delete_memory","지정한 기억이 삭제됐는지 확인한다.");write||has("기억해")->add("save_memory","승인한 기억이 실제 저장됐는지 확인한다.");read->add("search_memories","요청한 기억 검색 결과를 확인한다.")}
        if(text.startsWith("교정:")||text.startsWith("교정："))add("save_memory","사용자가 명시한 교정 내용의 승인·저장 결과를 확인한다.")
        if(has("통화내역","통화기록","전화내역","전화기록")&&read)add("get_call_log_summary","요청한 통화 내역의 조회 범위와 결과를 확인한다.")
        if(has("녹음","음성파일","전사")&&read){
            if(has("전사","요약","분석"))add("search_audio_transcripts","존재하는 녹음 전사문의 조회 범위와 결과를 확인한다. 원본 오디오 분석 완료를 주장하지 않는다.")
            else add("list_audio_recordings","녹음 파일 메타데이터의 조회 결과를 확인한다.")
        }
        if(has("우리대화","너와나의대화","이앱대화","비서대화","대화기록")&&read&&!messenger&&!sms)add("search_conversation_history","이 비서 앱의 대화 기록 조회 결과를 확인한다.")
        if(has("연락처","전화번호")&&read)add("search_contacts","요청한 연락처 조회 결과를 확인한다.")
        val calendar=has("일정","캘린더","스케줄","스케쥴","약속","회의")
        if(calendar&&write&&!remove)
            add("prepare_calendar_event","일정의 실제 저장 여부를 확인한다. 작성 화면 준비만으로 저장 완료를 주장하지 않는다.")
        else if(calendar&&read&&!has("문자","sms","메시지","카카오","카톡"))
            add("list_calendar_events","요청한 기간의 일정 조회 결과를 확인한다.")
        val timedNotice=has("알려줘","알림","리마인더")&&Regex("(?:\\d+(?:분|시간|일|초)(?:뒤|후)|내일|모레|오늘|매일|매주|\\d+시)").containsMatchIn(text)&&!has("날씨","기온","뜻","방법")
        if(has("리마인더","알림")||timedNotice)when {
            remove->add("delete_reminder","지정한 알림 삭제와 예약 취소를 확인한다.")
            update->add("update_reminder","알림 수정과 예약 상태를 확인한다.")
            write||timedNotice->add("create_reminder","알림 저장과 예약 상태를 확인한다.")
        }
        if(has("알람")&&(write||has("맞춰","설정")))add("create_alarm","알람의 실제 설정 여부를 확인한다. 설정 화면 전달은 별도 상태다.")
        val image=has("이미지","사진","그림","일러스트","포스터","썸네일","배경화면","로고","아이콘")
        if(image&&has("만들","생성","그려","그리기")||has("그려","그리기"))add("generate_image","실제로 생성된 이미지 파일을 확인한다.")
        if(has("메일","이메일")&&has("초안","작성","보내"))add("prepare_email","이메일 초안 준비와 실제 발송을 구분한다.")
        if(has("문자","sms")&&has("보내","초안","작성"))add("prepare_sms","문자 초안 준비와 실제 발송을 구분한다.")
        if(has("지도","길찾기")&&has("열어","찾아","보여"))add("open_map","지도에 전달한 요청의 실행 결과를 확인한다.")
        if(has("웹","http","주소")&&has("열어","접속"))add("open_url","요청한 웹페이지 열기 결과를 확인한다.")
        if(required.isEmpty()&&has("몇시","현재시간","오늘날짜"))add("get_current_datetime","현재 시각 조회 결과를 확인한다.")
        return TaskGoal(direct.ifBlank {"사용자의 직접 요청 확인"},criteria.ifEmpty {listOf("직접 요청한 작업과 검증 기준이 확인되기 전 실행 완료를 주장하지 않는다.")},stepBudget,required)
    }

    fun decide(goal:TaskGoal,steps:List<HarnessStep>,requestedFinish:Boolean=false):HarnessDecision {
        if(steps.size>goal.stepBudget)return HarnessDecision.Stop("작업 단계 예산을 초과했습니다.")
        loopReason(steps)?.let {return HarnessDecision.Stop(it)}
        if(!requestedFinish){
            if(steps.size>=goal.stepBudget)return decide(goal,steps,requestedFinish=true)
            return HarnessDecision.Advance(if(steps.lastOrNull()?.evidence?.state==VerificationState.FAILED)"실패한 결과를 보완하는 안전한 다른 단계가 필요합니다." else "다음 필요한 실행 또는 결과 검증을 진행합니다.")
        }
        if(steps.any {it.evidence.state==VerificationState.FAILED})return HarnessDecision.Stop("실패한 실행 또는 결과 검증이 있어 작업 완료를 확인할 수 없습니다.")
        if(steps.any {it.evidence.state==VerificationState.PREPARED})return HarnessDecision.Stop("작성·확인 화면이 준비됐습니다. 외부 작업의 실제 완료는 아직 확인되지 않았습니다.",true)
        if(steps.any {it.evidence.state==VerificationState.UNVERIFIED})return HarnessDecision.Stop("아직 확인하지 않은 실행 결과가 있습니다.")
        if(goal.requiredTools.isEmpty())return HarnessDecision.Stop("요청한 작업의 실행·검증 기준을 확정하지 못해 완료를 주장하지 않습니다.")
        val verified=steps.filter {it.evidence.state==VerificationState.VERIFIED}.map {it.tool}.toSet()
        val missing=goal.requiredTools-verified
        if(missing.isNotEmpty())return HarnessDecision.Stop("요청한 작업의 필수 결과가 확인되지 않았습니다: ${missing.sorted().joinToString()}")
        return HarnessDecision.Finish(steps.filter {it.evidence.state==VerificationState.VERIFIED}.joinToString("\n") {it.evidence.summary.ifBlank {"${it.tool}: 실행 결과 확인됨"}})
    }

    /** Optional pre-execution guard; permissions and typed argument validation remain separate. */
    fun beforeStep(goal:TaskGoal,steps:List<HarnessStep>,call:ToolCall):HarnessDecision {
        if(steps.size>=goal.stepBudget)return HarnessDecision.Stop("작업 단계 예산에 도달했습니다.")
        val safeRead=readOnly(call.name)
        if(goal.requiredTools.isEmpty()&&!safeRead)return HarnessDecision.Stop("확인된 사용자 작업 목표에 포함되지 않은 변경·외부 행동입니다.")
        if(call.name !in goal.requiredTools&&!safeRead)return HarnessDecision.Stop("요청한 목표에 포함되지 않은 변경·외부 행동입니다.")
        val proposed=HarnessStep(call,StepEvidence(call.name,VerificationState.UNVERIFIED,"실행 전 검사"))
        loopReason(steps+proposed)?.let {return HarnessDecision.Stop(it)}
        return HarnessDecision.Advance("목표에 필요한 단계이며 실행 결과는 이후 별도로 검증합니다.")
    }
    private fun loopReason(steps:List<HarnessStep>):String? {
        if(steps.zipWithNext().any {(a,b)->a.fingerprint==b.fingerprint})return "같은 도구와 인자의 연속 실행을 중단했습니다."
        for(group in steps.groupBy {it.fingerprint}.values){
            if(group.size>=3||group.size>=2&&!readOnly(group.first().tool))return "같은 요청의 반복 또는 중복 변경을 중단했습니다."
        }
        return null
    }
    private fun readOnly(name:String)=ToolRegistry.definitions[name]?.risk==Risk.LOW&&(name.startsWith("get_")||name.startsWith("list_")||name.startsWith("search_"))
    private fun directText(raw:String):String {
        var text=raw.take(6000).trim()
        if(Regex("^(선택한|가져온)\\s*(문서|파일|텍스트)").containsMatchIn(text))return ""
        listOf("```","\"\"\"","<document>","<untrusted>").forEach {marker->val at=text.indexOf(marker);if(at>=0)text=text.substring(0,at)}
        text=text.lineSequence().filterNot {it.trimStart().startsWith('>')}.joinToString(" ")
        text=text.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`"),"[인용 자료]")
        Regex("(?:아래|다음)(?:의)?\\s*(문장|텍스트|문서|내용|인용|로그)(?:를|을|는|은|:|\\s)").find(text)?.range?.first?.let {text=text.substring(0,it)}
        return text.trim()
    }
}
private fun canonical(value:JsonElement):String=when(value){
    is JsonObject->value.keys.sorted().joinToString(",","{","}"){JsonPrimitive(it).toString()+":"+canonical(value.getValue(it))}
    is JsonArray->value.joinToString(",","[","]"){canonical(it)}
    else->value.toString()
}
