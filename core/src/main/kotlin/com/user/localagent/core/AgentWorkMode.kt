package com.user.localagent.core

import com.user.localagent.core.connections.ConnectionCatalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.Locale

/** Owner-selected workflow. A mode never grants Android/account/tool permissions. */
@Serializable
enum class AgentWorkMode(
    val wireName:String,val label:String,val description:String,val promptHint:String,val defaultSkillId:String?,
) {
    @SerialName("act") ACT("act","실행","요청을 수행하고 실제 결과를 확인합니다.","필요한 도구를 사용해 요청을 수행하고 실행 결과를 확인한다. 기존 권한·승인·범위·취소 검사는 유지한다.",null),
    @SerialName("plan") PLAN("plan","계획","필요한 자료를 읽고 실행할 단계를 정리합니다.","허용된 로컬·계정 자료를 읽어 실행 순서와 확인 기준을 계획한다. 파일·일정·외부 상태를 변경하거나 개인 자료를 공개 검색어로 전송하지 않는다.","plan"),
    @SerialName("goal") GOAL("goal","목표","완료 기준을 정하고 목표의 진행을 관리합니다.","사용자가 명시한 목표·범위·완료 기준을 구체화하고 남은 일을 정리한다. 자료 읽기는 허용되며 실제 변경 작업은 사용자의 목표 추진 선택과 실행 모드에 연결한다. 근거 없이 기준 달성이나 전체 완료를 선언하지 않는다.","goal"),
    @SerialName("debug") DEBUG("debug","디버그","문제를 진단하고 수정한 뒤 결과를 검증합니다.","문제를 재현하거나 증상을 확인하고 원인 가설·필요한 읽기·승인된 수정·실제 검증을 순서대로 수행한다. 파일 저장 확인과 프로그램 실행·테스트 통과를 구분하며 사용할 수 없는 검증을 수행했다고 말하지 않는다.","debug"),
    @SerialName("review") REVIEW("review","검토","자료를 읽고 문제점과 개선안을 확인합니다.","허용된 자료를 읽어 오류·누락·위험·개선안을 근거와 함께 검토한다. 원본과 외부 상태를 변경하거나 개인 자료를 공개 검색어로 전송하지 않는다.","review"),
    @SerialName("research") RESEARCH("research","조사","공개 자료를 조사하고 출처를 확인합니다.","현재 요청의 공개 주제를 공개 웹 자료로 조사하고 관찰한 출처와 한계를 구분한다. 개인 기억·대화·계정·파일 내용을 조회하거나 공개 질의로 옮기지 않는다.","web-research");

    companion object {
        fun parse(value:String):AgentWorkMode=entries.firstOrNull{it.wireName==value.trim().lowercase(Locale.ROOT)}
            ?:throw IllegalArgumentException("지원하지 않는 작업 모드입니다.")
    }
}

/** Explicit effect declarations, independent of risk labels and natural-language keywords. */
object AgentWorkModePolicy {
    val contextReadTools:Set<String> = setOf("list_skills","read_skill")
    private val publicReadTools=setOf("search_web","search_shopping","read_webpage","get_current_datetime","search_youtube_videos")+EvidenceToolCatalog.names+OfficeToolCatalog.names
    private val localReadTools=setOf(
        "get_current_datetime","get_agent_capabilities","list_reminders","list_project_files","read_project_file","inspect_project_storage","find_project_duplicates","search_conversation_history",
        "list_imported_conversations","search_conversation_imports","get_sms_summary","get_call_log_summary",
        "list_audio_recordings","search_audio_transcripts","list_calendar_events","search_notes","read_note","search_contacts",
        "search_memories","list_messenger_notifications","analyze_selected_data","query_selected_data","list_notebooklm_notebooks",
    )+GoalToolCatalog.contextReadNames
    fun allows(mode:AgentWorkMode,toolName:String):Boolean {
        if(toolName in WebsiteExecutionPolicy.phoneWebTools)return false
        if(toolName in OfficeToolCatalog.names)return toolName in ToolRegistry.definitions
        if(toolName in contextReadTools)return true // Context only; ToolRegistry still validates actual registration.
        if(toolName !in ToolRegistry.definitions)return false
        // The sub-agent itself only reads; its inner tools are filtered with this same policy by the host.
        if(toolName in LocalSubagentToolCatalog.names)return true
        if(mode==AgentWorkMode.ACT||mode==AgentWorkMode.DEBUG)return true
        if(mode==AgentWorkMode.RESEARCH)return toolName in publicReadTools
        if(toolName in localReadTools||toolName in PrivateAnalysisToolCatalog.privateReadNames)return true
        if(toolName in publicReadTools)return false
        // Remote read operations are declared by their connector; no prefix or Risk.LOW inference.
        return toolName in ConnectionCatalog.privateReadNames
    }
    fun filterTools(mode:AgentWorkMode,availableTools:Set<String>):Set<String> = availableTools.filterTo(linkedSetOf()){allows(mode,it)}
}

@Serializable
enum class GoalCriterionStatus {
    @SerialName("pending") PENDING,
    @SerialName("verified") VERIFIED,
    @SerialName("failed") FAILED,
    @SerialName("user_confirmed") USER_CONFIRMED,
}
@Serializable
enum class GoalStatus {
    @SerialName("draft") DRAFT,
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
    @SerialName("waiting") WAITING,
    @SerialName("complete") COMPLETE,
}

@Serializable
enum class GoalProofKind { PUBLIC_PDF_SOURCE, PROJECT_FILE, KAIRB_COMPLETED_COURSE, BROWSER_PDF_SOURCE, PDF_EMAIL_DRAFT, GMAIL_SENT, CALENDAR_EVENT, KAIRB_CERTIFICATE }

@Serializable
data class GoalProofTarget(val kind:GoalProofKind,val target:String) {
    fun browserPdfCriterion():String="브라우저에서 「$target」 PDF 원본 저장"
    fun courseListCriterion():String="KAIRB 수강완료 목록에서 「$target」 확인"
    /** Exact readback facts produced only after the connector completed its approved operation. */
    fun connectorFields():List<String> {
        val values=kotlinx.serialization.json.Json.parseToJsonElement(target) as? JsonArray
            ?:error("대상 필드 배열이 필요합니다.")
        require(values.toString()==target)
        return values.map {field->
            require(field is JsonPrimitive && field.isString)
            field.content.also {require(it.isNotBlank()&&it==it.trim()&&it.none(Char::isISOControl))}
        }
    }
    fun connectorCriterion():String {
        val fields=connectorFields()
        return when(kind){
            GoalProofKind.PDF_EMAIL_DRAFT->"「${fields[0]}」에게 보낼 「${fields[1]}」 메일에 지정한 PDF를 모두 첨부해 초안까지만 준비"
            GoalProofKind.GMAIL_SENT->"보낸편지함에서 「${fields[0]}」에게 보낸 「${fields[1]}」 확인"
            GoalProofKind.CALENDAR_EVENT->"캘린더 「${fields[0]}」에서 「${fields[1]}」 일정 확인 (${fields[2]} ~ ${fields[3]})"
            GoalProofKind.KAIRB_CERTIFICATE->"KAIRB ${fields[0]}년 「${fields[1]}」 이수증 PDF 확인"
            else->error("연결 서비스 근거가 아닙니다.")
        }
    }
    val isPdfSource:Boolean get()=kind in setOf(GoalProofKind.PUBLIC_PDF_SOURCE,GoalProofKind.BROWSER_PDF_SOURCE,GoalProofKind.KAIRB_CERTIFICATE)

    /** The full owner declaration names the draft and every source. Additional instructions cannot be silently discarded. */
    fun matchesOwnerDraftScope(instruction:String,sources:List<GoalProofTarget>):Boolean {
        if(kind!=GoalProofKind.PDF_EMAIL_DRAFT || sources.size !in 1..16 || sources.distinct().size!=sources.size || sources.any {!it.isPdfSource})return false
        val sourceLines=sources.map {if(it.kind==GoalProofKind.KAIRB_CERTIFICATE)it.connectorCriterion() else it.target}
        if(sourceLines.distinct().size!=sourceLines.size)return false
        val lines=instruction.lines().map(String::trim).filter(String::isNotEmpty)
        return lines.size==sourceLines.size+1 && lines.toSet()==(sourceLines+connectorCriterion()).toSet()
    }
    /** Host signature of the full work the owner reviewed. A model can submit only recipient and subject. */
    fun reviewedDraft(goal:GoalDefinition):GoalProofTarget {
        require(kind==GoalProofKind.PDF_EMAIL_DRAFT)
        val fields=connectorFields().take(2)
        val work=buildJsonArray {
            add(JsonPrimitive(goal.id))
            add(JsonPrimitive(goal.outcome))
            add(JsonArray(goal.constraints.map(::JsonPrimitive)))
            for(criterion in goal.criteria)add(buildJsonArray {
                add(JsonPrimitive(criterion.id))
                add(JsonPrimitive(criterion.text))
                criterion.proofTarget?.let {proof->
                    add(JsonPrimitive(proof.kind.name))
                    val declared=if(proof.kind==GoalProofKind.PDF_EMAIL_DRAFT)proof.connectorFields().take(2) else listOf(proof.target)
                    declared.forEach {add(JsonPrimitive(it))}
                }
            })
        }
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(work.toString().toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it)}
        return copy(target=JsonArray((fields+hash).map {JsonPrimitive(it)}).toString()).validate()
    }

    fun matchesOwnerConnectorFields(instruction: String): Boolean {
        val fields = connectorFields()
        if (!fields.all { it in instruction }) return false
        return when (kind) {
            GoalProofKind.PDF_EMAIL_DRAFT,GoalProofKind.GMAIL_SENT -> {
                val mailbox = Regex.escape(fields.first())
                val localChars = "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~@-]"
                Regex("(?<!$localChars)$mailbox(?![A-Za-z0-9@-]|\\.[A-Za-z0-9])").containsMatchIn(instruction)
            }
            GoalProofKind.KAIRB_CERTIFICATE ->
                Regex("(?<![0-9])" + Regex.escape(fields.first()) + "(?![0-9])").containsMatchIn(instruction)
            GoalProofKind.CALENDAR_EVENT -> true
            else -> false
        }
    }
    fun validate():GoalProofTarget {
        when(kind) {
            GoalProofKind.KAIRB_CERTIFICATE->{
                val fields=connectorFields()
                require(fields.size==2 && fields[0].matches(Regex("[0-9]{4}")) && fields[1].length<=240)
            }
            GoalProofKind.PDF_EMAIL_DRAFT,GoalProofKind.GMAIL_SENT->{
                val fields=connectorFields()
                require(fields.size==2 || kind==GoalProofKind.PDF_EMAIL_DRAFT && fields.size==3 && fields.last().matches(Regex("[a-f0-9]{64}")))
                com.user.localagent.core.connections.GmailSendContract.mailbox(fields[0])
            }
            GoalProofKind.CALENDAR_EVENT->{
                val fields=connectorFields()
                require(fields.size==4)
                com.user.localagent.core.connections.ConnectionRequests.key(fields[0])
                require(java.time.OffsetDateTime.parse(fields[3]).isAfter(java.time.OffsetDateTime.parse(fields[2])))
            }
            GoalProofKind.PUBLIC_PDF_SOURCE,GoalProofKind.BROWSER_PDF_SOURCE->require(WebAgentPolicy.urlAllowed(target)&&target.startsWith("https://")&&'#' !in target)
            GoalProofKind.PROJECT_FILE->require(ProjectPaths.segments(target).joinToString("/")==target)
            GoalProofKind.KAIRB_COMPLETED_COURSE->require(target.length in 1..240&&target==target.trim()&&target.none(Char::isISOControl))
        }
        val maxTarget=if(kind==GoalProofKind.PDF_EMAIL_DRAFT && connectorFields().size==3)570 else 500
        require(target.length in 1..maxTarget)
        return this
    }
}

/** VERIFIED evidence entries are tool-run audit IDs, not model-written claims of success. */
@Serializable
data class GoalCriterion(
    val id:String=UUID.randomUUID().toString(),val text:String,
    val status:GoalCriterionStatus=GoalCriterionStatus.PENDING,val evidence:List<String> = emptyList(),
    val proofTarget:GoalProofTarget?=null,
)

/** updatedAt=0 means unsaved. Saving a loaded goal preserves its timestamp for conflict checks. */
@Serializable
data class GoalDefinition(
    val id:String=UUID.randomUUID().toString(),val threadId:String,val projectId:String?=null,
    val outcome:String,val criteria:List<GoalCriterion>,val constraints:List<String> = emptyList(),
    val status:GoalStatus=GoalStatus.DRAFT,val linkedRunIds:List<String> = emptyList(),val updatedAt:Long=0,
) {
    val completionReady:Boolean get()=criteria.isNotEmpty()&&criteria.all{it.status in setOf(GoalCriterionStatus.VERIFIED,GoalCriterionStatus.USER_CONFIRMED)}

    fun validate():GoalDefinition {
        fun validId(value:String)=value.isNotBlank()&&value.length<=100&&value.none{it.code<32}
        require(validId(id)&&validId(threadId)&&(projectId==null||validId(projectId))){"목표의 대화·프로젝트 식별자가 올바르지 않습니다."}
        require(outcome.isNotBlank()&&outcome.length<=12000&&'\u0000' !in outcome){"목표 내용을 입력하고 12,000자 이내로 작성해주세요."}
        require(criteria.isNotEmpty()&&criteria.map{it.id}.distinct().size==criteria.size){"완료 기준을 하나 이상 입력하고 중복 식별자를 제거해주세요."}
        criteria.forEach{criterion->
            criterion.proofTarget?.validate()
            require(validId(criterion.id)&&criterion.text.isNotBlank()&&criterion.text.length<=12000&&'\u0000' !in criterion.text){"완료 기준 내용이 올바르지 않습니다."}
            require(criterion.evidence.distinct().size==criterion.evidence.size&&criterion.evidence.all{it.isNotBlank()&&it.length<=1000&&'\u0000' !in it}){"완료 기준의 근거가 올바르지 않습니다."}
            require(criterion.status!=GoalCriterionStatus.VERIFIED||criterion.evidence.isNotEmpty()){"검증됨 상태에는 실제 실행 근거가 필요합니다."}
        }
        require(constraints.all{it.isNotBlank()&&it.length<=12000&&'\u0000' !in it}){"목표 조건에 빈 내용 또는 잘못된 문자가 있습니다."}
        require(linkedRunIds.distinct().size==linkedRunIds.size&&linkedRunIds.all(::validId)){"목표에 연결된 실행 식별자가 올바르지 않습니다."}
        require(updatedAt>=0){"목표 저장 시각이 올바르지 않습니다."}
        require(status!=GoalStatus.COMPLETE||completionReady){"모든 완료 기준을 검증하거나 직접 확인한 뒤 완료로 표시해주세요."}
        return this
    }
    fun toJson():JsonObject=Json.parseToJsonElement(GOAL_JSON.encodeToString(validate())).jsonObject
    companion object {
        private val GOAL_JSON=Json{ignoreUnknownKeys=true;encodeDefaults=true}
        fun parse(text:String):GoalDefinition=GOAL_JSON.decodeFromString<GoalDefinition>(text).validate()
        fun parse(value:JsonObject):GoalDefinition=parse(value.toString())
    }
}
