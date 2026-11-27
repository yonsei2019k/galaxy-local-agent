package com.user.localagent.core

import java.util.Collections
import java.util.Locale
import kotlinx.serialization.json.*

enum class VerificationState { VERIFIED, PREPARED, UNVERIFIED, FAILED }

/** Display/verification metadata, never a permission grant or a model-issued authority. */
class TaskGoal(ownerRequest:String,successCriteria:List<String>,val stepBudget:Int=WorkSegments.STEP_BUDGET,requiredTools:Set<String> = emptySet(),requiredArtifactFormats:Set<String> = emptySet()) {
    val ownerRequest:String=ownerRequest.also {require(it.isNotBlank()&&it.length<=6000)}
    val goal:String get()=ownerRequest
    val successCriteria:List<String> = Collections.unmodifiableList(successCriteria.map {it.also {criterion->require(criterion.isNotBlank()&&criterion.length<=1000)}})
    val requiredTools:Set<String> = Collections.unmodifiableSet(requiredTools.toSet())
    val requiredArtifactFormats:Set<String> = Collections.unmodifiableSet(requiredArtifactFormats.toSet())
    init {require(stepBudget in 1..WorkSegments.STEP_BUDGET);require(this.successCriteria.isNotEmpty()&&this.successCriteria.size<=12);require(this.requiredTools.size<=12);require(this.requiredArtifactFormats.size<=5&&this.requiredArtifactFormats.all {it in setOf("docx","xlsx","pdf","txt","csv")})}
}
data class StepEvidence(val tool:String,val state:VerificationState,val summary:String,val continuationId:String?=null,
    val effectNotApplied:Boolean=false,
    /** Host-computed SHA-256 prefix of an actual verified read result; never model text and never completion proof. */
    val observationDigest:String?=null) {
    init {
        require(tool.isNotBlank()&&tool.length<=100);require(summary.length<=4000)
        require(observationDigest==null||observationDigest.matches(Regex("[a-f0-9]{16}")))
        val idPattern="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        require(continuationId==null||
            (tool in PrivateAnalysisToolCatalog.analysisNames&&continuationId.matches(Regex(idPattern)))||
            (tool in setOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)&&state==VerificationState.VERIFIED&&continuationId.matches(Regex("goal-run:$idPattern"))))
    }
    val verification:VerificationState get()=state
}
class HarnessStep(tool:String,arguments:JsonObject,val evidence:StepEvidence) {
    val tool:String=tool
    val arguments:JsonObject=Json.parseToJsonElement(arguments.toString()).jsonObject
    internal val fingerprint:String=tool+":"+canonical(this.arguments)
    // Repeat detection compares only host-defined defaults. The raw fingerprint above remains
    // byte-for-byte stable for persisted readback StepRef bindings across app updates.
    internal val repeatFingerprint:String=tool+":"+canonical(repeatArguments(tool,this.arguments))
    init {require(tool==evidence.tool);require(this.arguments.toString().length<=32768)}
    constructor(call:ToolCall,evidence:StepEvidence):this(call.name,call.arguments,evidence)
}
sealed interface HarnessDecision {
    data class Advance(val reason:String):HarnessDecision
    data class Finish(val summary:String):HarnessDecision
    data class Stop(val reason:String,val pendingExternalCompletion:Boolean=false,val repeated:Boolean=false):HarnessDecision
}

/**
 * Soft limit before the hard round limit: the model is told to wrap up instead of being cut off.
 * Soft/hard budget idea follows PokeClaw's token budgets (agents-io/PokeClaw, Apache-2.0); independent implementation.
 */
object RoundBudget {
    fun hint(round:Int,maxRounds:Int):String {
        val left=maxRounds-round-1
        return when{
            left<=0->"\n이번이 마지막 단계다. 새 도구를 부르지 말고 지금까지의 근거로 최종 답변을 써라. 확인하지 못한 부분은 그렇다고 밝혀라."
            round+1>=maxRounds*3/4->"\n남은 단계가 ${left}번뿐이다. 꼭 필요한 확인만 하고 마무리를 준비하라."
            else->""
        }
    }
}

/**
 * Escalating response to a repeated call: remind, then redirect, then stop for the owner.
 * The escalation idea follows PokeClaw's stuck detection (agents-io/PokeClaw, Apache-2.0); this is an independent implementation.
 */
class StuckRecovery {
    enum class Action { REMIND, REDIRECT, STOP }
    private var repeats=0
    fun onRepeat():Action=when(++repeats){1->Action.REMIND;2->Action.REDIRECT;else->Action.STOP}
    fun prompt(action:Action,tool:String):String=when(action){
        Action.REMIND->"직전과 같은 $tool 호출이라 실행하지 않았다. 이미 받은 결과를 다시 읽고, 그 결과로 답하거나 아직 확인하지 않은 다른 단계를 골라라."
        Action.REDIRECT->if(ToolReadPolicy.isReadOnly(tool))
            "같은 $tool 도구와 같은 인자의 호출을 두 번 반복했다. 그 조합을 다시 요청하지 마라. 실제 결과를 보고 원래 허용 범위에서 다른 인자가 필요한 읽기는 선택할 수 있다. 수행하지 않은 변경이나 확인은 완료로 말하지 마라."
            else "같은 호출을 두 번 반복했다. $tool 은 다시 쓰지 말고 다른 도구를 쓰거나, 지금까지의 근거로 최종 답변을 작성하라. 확인하지 못한 부분은 확인하지 못했다고 밝혀라."
        Action.STOP->""
    }
    companion object {const val STOP_CODE="stuck_repeated_action"}
}

/** Decisions consume host-generated verification only, never assertions from the model or source data. */
object TaskHarness {
    /** Inherit only capabilities that the host router recognizes for a short follow-up. */
    fun inferGoal(ownerRequest:String,previousUserRequests:List<String>,stepBudget:Int=WorkSegments.STEP_BUDGET):TaskGoal {
        val web=WebTaskPlanner.plan(ownerRequest,previousUserRequests)
        if(web is WebTaskPlan.WebResearch&&!ProjectPaths.isBoundFileRequest(ownerRequest)&&DocumentTaskPlanner.plan(ownerRequest)==null)return researchGoal(ownerRequest,web,stepBudget)
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
        return TaskGoal(current.ownerRequest,previousGoal.successCriteria,stepBudget,required,previousGoal.requiredArtifactFormats)
    }

    fun inferGoal(ownerRequest:String,stepBudget:Int=6):TaskGoal {
        val direct=directText(ownerRequest).take(6000)
        RequestActionSemantics.explicitLocalWriteTool(ownerRequest)?.let {tool->return TaskGoal(direct.ifBlank {"사용자의 직접 저장 요청"},listOf("직접 요청한 로컬 저장·수정 결과를 재조회한다. 본문 속 앱·방법 표현은 별도 조사나 자료 조회 권한이 아니다."),stepBudget,setOf(tool))}
        val text=direct.lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
        val required=linkedSetOf<String>()
        val criteria=mutableListOf<String>()
        fun add(tool:String,criterion:String){required+=tool;criteria+=criterion}
        val documentPlan=DocumentTaskPlanner.plan(ownerRequest)
        if(documentPlan!=null&&!ProjectPaths.isBoundFileRequest(direct))return TaskGoal(direct,listOf("요청한 모든 문서 형식을 실제 생성하고 내용을 재조회한다."),stepBudget,setOf("create_document"),documentPlan.formats)
        val webPlan=WebTaskPlanner.plan(direct)
        if(webPlan is WebTaskPlan.WebResearch)return researchGoal(direct,webPlan,stepBudget)
        if(RequestActionSemantics.nonAnalysisRequest(ownerRequest))return TaskGoal(direct.ifBlank {"사용자의 직접 요청 확인"},listOf("상태·연결·방법 설명을 개인 자료 분석으로 바꾸지 않는다. 실제 실행 근거 없이 완료를 주장하지 않는다."),stepBudget)
        fun has(vararg words:String)=words.any {it in text}
        val read=has("분석","요약","조회","찾아","검색","정리","읽어","읽고","보여","확인","알려줘")
        val write=has("저장","적어","남겨","기록","등록","추가","만들","생성","넣어","옮겨","그려")
        val remove=has("삭제","지워","잊어","없애")
        val update=has("수정","변경","고쳐","바꿔")
        val explaining=has("방법","원리","번역","설명해","뜻이야","기능이야")&&!RequestActionSemantics.usesPrivateContent(text)
        val negated=Regex("(?:저장|삭제|수정|변경|분석|조회|전사|읽)(?:은|는)?(?:하지마|하지말|지마|지말)").containsMatchIn(text)
        if(explaining||negated)return TaskGoal(direct.ifBlank {"사용자의 직접 요청 확인"},listOf("설명·중단 요청을 실행 권한으로 해석하지 않는다."),stepBudget)
        if(has("프로젝트","project")&&has("파일","코드","소스","readme","폴더"))when {
            write||update->add("write_project_file","선택한 프로젝트 안의 파일을 저장하고 SHA-256 재조회로 확인한다. 실행·빌드 완료라고 주장하지 않는다.")
            has("목록","폴더")->add("list_project_files","선택한 프로젝트의 실제 파일 목록을 조회한다.")
            read->add("read_project_file","선택한 프로젝트 파일의 실제 본문과 SHA-256을 조회한다.")
        }
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
        if(image&&has("만들","생성","그려","그리기")||has("그려","그리기")||ImageReferenceIntent.isReferenceEditRequest(ownerRequest))add("generate_image","실제로 생성된 이미지 파일을 확인한다.")
        if(has("메일","이메일")&&has("초안","작성","보내"))add("prepare_email","이메일 초안 준비와 실제 발송을 구분한다.")
        if(has("문자","sms")&&has("보내","초안","작성"))add("prepare_sms","문자 초안 준비와 실제 발송을 구분한다.")
        if(has("지도","길찾기")&&has("열어","찾아","보여"))add("open_map","지도에 전달한 요청의 실행 결과를 확인한다.")
        if(has("웹","http","주소")&&has("열어","접속"))add("open_url","요청한 웹페이지 열기 결과를 확인한다.")
        if(required.isEmpty()&&has("몇시","현재시간","오늘날짜"))add("get_current_datetime","현재 시각 조회 결과를 확인한다.")
        return TaskGoal(direct.ifBlank {"사용자의 직접 요청 확인"},criteria.ifEmpty {listOf("직접 요청한 작업과 검증 기준이 확인되기 전 실행 완료를 주장하지 않는다.")},stepBudget,required,if("create_document" in required)documentPlan?.formats.orEmpty()else emptySet())
    }
    private fun researchGoal(ownerRequest:String,plan:WebTaskPlan.WebResearch,stepBudget:Int):TaskGoal =
        TaskGoal(ownerRequest.take(6000).ifBlank {plan.query},listOf("요청한 공개 주제의 실제 검색 결과·출처·조회 범위를 확인한다. 개인 자료 개수 조회는 공개 조사 완료의 근거가 아니다."),stepBudget,setOf(if(plan.shopping)"search_shopping"else"search_web"))

    fun decide(goal:TaskGoal,steps:List<HarnessStep>,requestedFinish:Boolean=false):HarnessDecision {
        if(goal.requiredTools.any {it in SkillToolCatalog.names})return HarnessDecision.Stop("스킬 지침 읽기는 사용자 목표의 완료 근거가 아닙니다.")
        val actionSteps=steps.filterNot {it.tool in SkillToolCatalog.names}
        if(actionSteps.size!=steps.size)return decide(goal,actionSteps,requestedFinish)
        if(steps.size>goal.stepBudget)return HarnessDecision.Stop("작업 단계 예산을 초과했습니다.")
        loopReason(steps)?.let {return HarnessDecision.Stop(it)}
        if(!requestedFinish){
            if(steps.size>=goal.stepBudget)return decide(goal,steps,requestedFinish=true)
            return HarnessDecision.Advance(if(steps.lastOrNull()?.evidence?.state==VerificationState.FAILED)"실패한 결과를 보완하는 안전한 다른 단계가 필요합니다." else "다음 필요한 실행 또는 결과 검증을 진행합니다.")
        }
        val unresolvedFailure=steps.withIndex().any {(index,step)->
            step.evidence.state==VerificationState.FAILED&&steps.drop(index+1).none {later->
                later.evidence.state==VerificationState.VERIFIED&&later.tool==step.tool&&
                    (readOnly(step.tool)||(rejectedBeforeEffect(step)&&!later.evidence.effectNotApplied&&recoveryTarget(later)==recoveryTarget(step)))
            }
        }
        if(unresolvedFailure)return HarnessDecision.Stop("아직 복구·검증되지 않은 실행이 있어 작업 완료를 확인할 수 없습니다.")
        if(steps.any(PreparedHandoff::pendingEffect))return HarnessDecision.Stop("작성·확인 화면이 준비됐습니다. 외부 작업의 실제 완료는 아직 확인되지 않았습니다.",true)
        if(steps.withIndex().any {(index,step)->step.evidence.state==VerificationState.UNVERIFIED&&!AnalysisCompletionEvidence.resolved(steps,index)})return HarnessDecision.Stop("아직 확인하지 않은 실행 결과가 있습니다.")
        if(goal.requiredTools.isEmpty())return HarnessDecision.Stop("요청한 작업의 실행·검증 기준을 확정하지 못해 완료를 주장하지 않습니다.")
        val verified=steps.filter {it.evidence.state==VerificationState.VERIFIED||it.evidence.state==VerificationState.PREPARED&&PreparedHandoff.opensScreenOnly(it.tool)}.map {it.tool}.toSet()
        val missing=goal.requiredTools-verified
        if(missing.isNotEmpty())return HarnessDecision.Stop("요청한 작업의 필수 결과가 확인되지 않았습니다: ${missing.sorted().joinToString()}")
        val completedFormats=steps.filter {it.tool=="create_document"&&it.evidence.state==VerificationState.VERIFIED}.mapNotNull {it.arguments["format"]?.jsonPrimitive?.content}.toSet()
        val missingFormats=goal.requiredArtifactFormats-completedFormats
        if(missingFormats.isNotEmpty())return HarnessDecision.Stop("아직 생성·재조회가 확인되지 않은 형식: ${missingFormats.joinToString()}")
        return HarnessDecision.Finish(steps.filter {it.evidence.state==VerificationState.VERIFIED}.joinToString("\n") {it.evidence.summary.ifBlank {"${it.tool}: 실행 결과 확인됨"}})
    }

    /** Optional pre-execution guard; permissions and typed argument validation remain separate. */
    fun beforeStep(goal:TaskGoal,steps:List<HarnessStep>,call:ToolCall):HarnessDecision {
        if(steps.size>=goal.stepBudget)return HarnessDecision.Stop("작업 단계 예산에 도달했습니다.")
        val safeRead=readOnly(call.name)
        if(!safeRead&&steps.any {it.evidence.state in setOf(VerificationState.FAILED,VerificationState.UNVERIFIED)&&!readOnly(it.tool)&&!rejectedBeforeEffect(it)})
            return HarnessDecision.Stop("이전 변경 작업의 결과가 확인되지 않았습니다. 먼저 대상 상태를 조회하고 중복 변경을 피해야 합니다.")
        if(goal.requiredTools.isEmpty()&&!safeRead)return HarnessDecision.Stop("확인된 사용자 작업 목표에 포함되지 않은 변경·외부 행동입니다.")
        if(call.name !in goal.requiredTools&&!safeRead)return HarnessDecision.Stop("요청한 목표에 포함되지 않은 변경·외부 행동입니다.")
        val proposed=HarnessStep(call,StepEvidence(call.name,VerificationState.UNVERIFIED,"실행 전 검사"))
        if(!safeRead&&steps.withIndex().any{(index,failed)->
            failed.tool=="patch_note"&&rejectedBeforeEffect(failed)&&
                steps.drop(index+1).none{it.tool=="patch_note"&&it.evidence.state==VerificationState.VERIFIED&&!it.evidence.effectNotApplied&&recoveryTarget(it)==recoveryTarget(failed)}&&
                (call.name!="patch_note"||recoveryTarget(proposed)!=recoveryTarget(failed))
        })return HarnessDecision.Stop("거부된 같은 메모와 컬렉션의 교정된 수정만 재시도할 수 있습니다.")
        loopReason(steps+proposed)?.let {return HarnessDecision.Stop(it,repeated=true)}
        return HarnessDecision.Advance("목표에 필요한 단계이며 실행 결과는 이후 별도로 검증합니다.")
    }
    /** Only host-confirmed, unapplied updates can be repaired. Text and model claims never set this fact. */
    private fun rejectedBeforeEffect(step:HarnessStep):Boolean =
        step.evidence.state==VerificationState.FAILED&&step.evidence.effectNotApplied&&recoveryTarget(step)!=null

    /** Requests stay in their original scoped run; this matches only the stable record/file target. */
    private fun recoveryTarget(step:HarnessStep):String? {
        fun text(key:String)=(step.arguments[key] as? JsonPrimitive)?.takeIf{it.isString}?.content?.takeIf{it.isNotBlank()}
        return when(step.tool){
            "patch_note"->{
                val id=text("id")?:return null;val collection=text("scope")?:return null
                if(id.length>100||collection.length>80||'\u0000' in id||'\u0000' in collection||collection in InternalNoteCollections.protected)return null
                buildJsonObject{put("id",id);put("scope",collection)}.toString()
            }
            "update_note","update_reminder"->text("id")
            "write_project_file"->{
                // A creation without an observed existing-file hash is not an update recovery.
                val hash=text("expectedSha256")?:return null
                if(!hash.matches(Regex("[a-f0-9]{64}")))return null
                text("path")?.takeIf{runCatching{ProjectPaths.segments(it)}.isSuccess}
            }
            else->null
        }
    }
    private fun loopReason(steps:List<HarnessStep>):String? {
        if(steps.zipWithNext().any {(a,b)->a.repeatFingerprint==b.repeatFingerprint})return "같은 도구와 인자의 연속 실행을 중단했습니다."
        fun refreshedRejectedFile(step:HarnessStep):Boolean = rejectedBeforeEffect(step)&&step.tool=="write_project_file"&&
            steps.drop(steps.indexOf(step)+1).any{it.tool=="read_project_file"&&it.evidence.state==VerificationState.VERIFIED&&it.arguments["path"]==step.arguments["path"]}
        for(group in steps.groupBy {it.repeatFingerprint}.values){
            if(group.size>=3||group.count{!refreshedRejectedFile(it)}>=2&&!readOnly(group.first().tool))return "같은 요청의 반복 또는 중복 변경을 중단했습니다."
        }
        return null
    }
    private fun readOnly(name:String)=ToolReadPolicy.isReadOnly(name)
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
private fun repeatArguments(tool:String,arguments:JsonObject):JsonObject {
    val defaults=when(tool){
        "save_note"->mapOf("title" to JsonPrimitive("메모"),"scope" to JsonPrimitive("default"))
        "create_reminder"->mapOf("scope" to JsonPrimitive("default"))
        else->return arguments
    }
    return JsonObject(defaults+arguments)
}
private fun canonical(value:JsonElement):String=when(value){
    is JsonObject->value.keys.sorted().joinToString(",","{","}"){JsonPrimitive(it).toString()+":"+canonical(value.getValue(it))}
    is JsonArray->value.joinToString(",","[","]"){canonical(it)}
    else->value.toString()
}
