package com.user.localagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Concise owner-facing facts from actual host events; never model chain-of-thought. */
enum class UiWorkEventKind { RATIONALE, PLAN, TOOL, FILE, VERIFICATION, ANSWER, CONTEXT }
enum class UiWorkEvidence { NONE, PENDING, VERIFIED, PREPARED, UNVERIFIED, FAILED }
enum class UiFileChangeOperation { CREATED, UPDATED, DELETED }

data class UiRunFileChange(
    val path:String,val operation:UiFileChangeOperation,val beforeSha256:String?=null,val afterSha256:String?=null,
    /** Opaque reference to a real retained diff; null means no diff viewer is offered. */
    val diffRef:String?=null,
    /** Origin captured when the change occurred, never the mutable currently selected project. */
    val sourceProjectId:String?=null,val workspaceBinding:String?=null,
)
data class UiRunActivityEvent(
    val id:String,val kind:UiWorkEventKind,val label:String,val detail:String="",val at:Long=0,
    val evidence:UiWorkEvidence=UiWorkEvidence.NONE,val fileChanges:List<UiRunFileChange> = emptyList(),
)
data class UiRunActivity(
    val runId:String,val threadId:String,val assistantMessageId:String,val status:String,
    val mode:String="act",val startedAt:Long=0,val updatedAt:Long=0,val events:List<UiRunActivityEvent> = emptyList(),
    val totalEvents:Long=events.size.toLong(),val hasOlder:Boolean=false,val loadingOlder:Boolean=false,
)
internal data class UiWorkEvidenceCounts(val tools:Int,val verified:Int,val prepared:Int,val failed:Int)

private val internalReasoningMarkup=Regex("(?i)<\\s*/?\\s*(?:think|analysis|reasoning)\\b|\\[\\s*(?:analysis|chain.of.thought)\\s*]")
internal fun visibleActivityText(value:String,maxChars:Int=1600):String=when{
    internalReasoningMarkup.containsMatchIn(value)->""
    value.length>maxChars->"요약이 너무 길어 이 카드에 표시하지 않았습니다."
    else->value
}
internal fun activityMatchesMessage(activity:UiRunActivity?,threadId:String,messageId:String):Boolean =
    activity!=null&&activity.threadId==threadId&&activity.assistantMessageId==messageId&&activity.runId.isNotBlank()

internal fun visibleActivityEvents(activity:UiRunActivity):List<UiRunActivityEvent> {
    val byId=linkedMapOf<String,UiRunActivityEvent>()
    activity.events.forEach{event->
        if(event.id.isNotBlank()){
            val label=visibleActivityText(event.label,240);val detail=visibleActivityText(event.detail)
            val files=event.fileChanges.filter{it.path.isNotBlank()&&it.path.length<=512&&visibleActivityText(it.path).isNotBlank()}
            if(label.isNotBlank()||detail.isNotBlank()||files.isNotEmpty())byId[event.id]=event.copy(label=label,detail=detail,fileChanges=files)
            else byId.remove(event.id)
        }
    }
    return byId.values.toList()
}
internal fun workEvidenceCounts(events:List<UiRunActivityEvent>):UiWorkEvidenceCounts {
    // File/readback rows describe a tool result; counting them again would triple one action.
    val evidence=events.filter{it.kind==UiWorkEventKind.TOOL}
    return UiWorkEvidenceCounts(events.count{it.kind==UiWorkEventKind.TOOL},evidence.count{it.evidence==UiWorkEvidence.VERIFIED},
        evidence.count{it.evidence==UiWorkEvidence.PREPARED},evidence.count{it.evidence==UiWorkEvidence.FAILED})
}
internal fun displayedWorkEvidence(event:UiRunActivityEvent):UiWorkEvidence =
    if(event.kind in setOf(UiWorkEventKind.RATIONALE,UiWorkEventKind.PLAN,UiWorkEventKind.CONTEXT,UiWorkEventKind.ANSWER)&&
        event.evidence in setOf(UiWorkEvidence.VERIFIED,UiWorkEvidence.PREPARED))UiWorkEvidence.NONE else event.evidence
internal fun activityStatusLabel(status:String):String=when(status){
    "queued"->"대기 중";"running"->"진행 중";"waiting_approval"->"승인 대기";"paused"->"일시 정지"
    "completed"->"응답 종료";"cancelled"->"중단됨";"interrupted"->"재시작으로 중단됨";"failed"->"오류";else->"상태 확인 필요"
}
internal fun workEvidenceLabel(evidence:UiWorkEvidence):String=when(evidence){
    UiWorkEvidence.NONE->"기록";UiWorkEvidence.PENDING->"진행 중";UiWorkEvidence.VERIFIED->"검증됨"
    UiWorkEvidence.PREPARED->"준비됨 · 외부 완료 미확인";UiWorkEvidence.UNVERIFIED->"확인 필요";UiWorkEvidence.FAILED->"실패"
}
private fun workKindLabel(kind:UiWorkEventKind):String=when(kind){
    UiWorkEventKind.RATIONALE->"진행 기준";UiWorkEventKind.PLAN->"계획";UiWorkEventKind.TOOL->"도구 실행"
    UiWorkEventKind.FILE->"파일 변경";UiWorkEventKind.VERIFICATION->"검증";UiWorkEventKind.ANSWER->"답변";UiWorkEventKind.CONTEXT->"참고 지침"
}
private fun activityTime(at:Long):String=if(at<=0)"시각 미기록"else runCatching{
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
}.getOrDefault("시각 미기록")
private fun hashLabel(hash:String?):String=hash?.takeIf{Regex("[a-fA-F0-9]{64}").matches(it)}?.let{it.take(12)+"…"}?:"해시 미기록"

internal fun activityInitiallyExpanded(status:String):Boolean = status in setOf("queued","running","waiting_approval","paused","failed","interrupted")

private const val THOUGHT_PREFIX="판단 과정: "

/** True when Chinese characters outnumber Hangul, so the owner never sees an unreadable thought. */
internal fun mostlyHan(text:String):Boolean {
    val han=text.count {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN}
    val hangul=text.count {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HANGUL}
    return han>10&&han>hangul
}

/** Rows worth showing in a transcript-style log: thoughts, decisions, tools, files, checks and failures. */
internal fun transcriptEvents(events:List<UiRunActivityEvent>):List<UiRunActivityEvent> = events.filter {event->
    event.evidence==UiWorkEvidence.FAILED||event.kind in setOf(UiWorkEventKind.PLAN,UiWorkEventKind.TOOL,UiWorkEventKind.FILE,UiWorkEventKind.VERIFICATION)
}

/** One transcript row: a marker and title, then an indented "⎿" result line, like a coding-assistant chat. */
@Composable
private fun TranscriptRow(event:UiRunActivityEvent){
    val thought=THOUGHT_PREFIX in event.label||THOUGHT_PREFIX in event.detail
    var open by rememberSaveable(event.id){mutableStateOf(false)}
    val evidence=displayedWorkEvidence(event)
    val accent=when(evidence){UiWorkEvidence.FAILED->ZipHapColors.Error;UiWorkEvidence.VERIFIED->ZipHapColors.Mint;UiWorkEvidence.PREPARED->Color(0xFFE1B96D);else->ZipHapColors.TextSecondary}
    Column(Modifier.fillMaxWidth().testTag("activity_event_${event.id}"),verticalArrangement=Arrangement.spacedBy(2.dp)){
        if(thought){
            val raw=(if(THOUGHT_PREFIX in event.label)event.label else event.detail).substringAfter(THOUGHT_PREFIX)
            val body=if(mostlyHan(raw))"모델이 한국어가 아닌 언어로 생각해 표시하지 않았습니다." else raw
            TextButton(onClick={open=!open},contentPadding=androidx.compose.foundation.layout.PaddingValues(0.dp)){
                Text("✻ 생각 ${if(open)"▾" else "▸"}",style=MaterialTheme.typography.labelLarge,color=ZipHapColors.TextSecondary)
            }
            Text(body,maxLines=if(open)Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,
                style=MaterialTheme.typography.bodySmall.copy(fontStyle=androidx.compose.ui.text.font.FontStyle.Italic),
                color=ZipHapColors.TextSecondary,modifier=Modifier.padding(start=14.dp))
            return@Column
        }
        Text("● ${event.label}",style=MaterialTheme.typography.bodyMedium,color=if(evidence==UiWorkEvidence.FAILED)ZipHapColors.Error else ZipHapColors.TextPrimary)
        val result=listOfNotNull(event.detail.takeIf{it.isNotBlank()},
            workEvidenceLabel(evidence).takeIf{event.kind==UiWorkEventKind.TOOL||evidence==UiWorkEvidence.FAILED}).joinToString(" · ")
        if(result.isNotBlank())Text("└ $result",style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace),
            color=accent,modifier=Modifier.padding(start=14.dp))
        event.fileChanges.forEach{file->
            val operation=when(file.operation){UiFileChangeOperation.CREATED->"생성";UiFileChangeOperation.UPDATED->"수정";UiFileChangeOperation.DELETED->"삭제"}
            Text("└ $operation ${file.path}",style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace),
                color=ZipHapColors.TextSecondary,modifier=Modifier.padding(start=14.dp))
        }
    }
}

/** Inside the first assistant list block: no extra LazyList index and no answer-text copies. */
@Composable
fun InlineRunActivityCard(activity:UiRunActivity,modifier:Modifier=Modifier,onOpenFileChange:((UiRunFileChange)->Unit)?=null,onLoadOlder:((String)->Unit)?=null,onExpandedChange:((Boolean)->Unit)?=null){
    var technicalDetails by rememberSaveable(activity.runId){mutableStateOf(false)}
    var chosenExpansion by rememberSaveable(activity.runId){mutableStateOf<Boolean?>(null)}
    val expanded=chosenExpansion?:activityInitiallyExpanded(activity.status)
    val all=remember(activity.events){visibleActivityEvents(activity)}
    val events=remember(all){transcriptEvents(all)}
    val counts=remember(all){workEvidenceCounts(all)}
    val status=activityStatusLabel(activity.status)
    val elapsedSeconds by produceState(0L,activity.runId,activity.status,activity.startedAt){
        value=0L
        while(activity.status=="running"&&activity.startedAt>0){
            value=(System.currentTimeMillis()-activity.startedAt).coerceAtLeast(0)/1000
            delay(1000)
        }
    }
    val elapsed=if(elapsedSeconds>0)" · ${elapsedSeconds/60}분 ${elapsedSeconds%60}초"else""
    // Transcript style: no card border, a quiet header, then the thought/action lines in order.
    Column(modifier.fillMaxWidth().padding(bottom=10.dp).testTag("inline_activity_${activity.runId}"),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("${if(activity.status=="running")"◐" else "●"} $status$elapsed · 도구 ${counts.tools}"+(if(counts.failed>0)" · 실패 ${counts.failed}" else ""),
                style=MaterialTheme.typography.labelMedium,color=if(activity.status=="failed")ZipHapColors.Error else ZipHapColors.TextSecondary,modifier=Modifier.weight(1f))
            if(events.isNotEmpty())TextButton(onClick={chosenExpansion=!expanded;onExpandedChange?.invoke(!expanded)},modifier=Modifier.testTag("toggle_activity_${activity.runId}")){Text(if(expanded)"접기"else"펼치기")}
        }
        if(activity.hasOlder&&expanded)TextButton(enabled=onLoadOlder!=null&&!activity.loadingOlder,onClick={onLoadOlder?.invoke(activity.runId)},modifier=Modifier.testTag("load_older_activity_${activity.runId}")){Text(if(activity.loadingOlder)"이전 기록 불러오는 중"else"이전 과정 더 보기")}
        val shown=if(expanded)events else events.takeLast(1)
        shown.forEach{event->TranscriptRow(event)}
        if(expanded)events.flatMap{it.fileChanges}.filter{!it.diffRef.isNullOrBlank()}.forEach{file->
            if(onOpenFileChange!=null)TextButton(onClick={onOpenFileChange(file)}){Text("변경 내용 보기 · ${file.path}")}
            if(technicalDetails)Text("${hashLabel(file.beforeSha256)} → ${hashLabel(file.afterSha256)}",style=MaterialTheme.typography.labelSmall,color=ZipHapColors.TextSecondary)
        }
        if(expanded&&events.any{it.fileChanges.isNotEmpty()})TextButton(onClick={technicalDetails=!technicalDetails},modifier=Modifier.testTag("activity_technical_details_${activity.runId}")){
            Text(if(technicalDetails)"파일 무결성 정보 접기"else"파일 무결성 정보 보기")
        }
    }
}
