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

/** Inside the first assistant list block: no extra LazyList index and no answer-text copies. */
@Composable
fun InlineRunActivityCard(activity:UiRunActivity,modifier:Modifier=Modifier,onOpenFileChange:((UiRunFileChange)->Unit)?=null,onLoadOlder:((String)->Unit)?=null,onExpandedChange:((Boolean)->Unit)?=null){
    var expanded by rememberSaveable(activity.runId){mutableStateOf(false)}
    val events=remember(activity.events){visibleActivityEvents(activity)}
    val counts=remember(events){workEvidenceCounts(events)}
    val status=activityStatusLabel(activity.status)
    Surface(modifier.fillMaxWidth().padding(bottom=12.dp).testTag("inline_activity_${activity.runId}"),
        shape=RoundedCornerShape(16.dp),color=ZipHapColors.Surface,border=BorderStroke(1.dp,Color(0xFF30383A))){
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Column(Modifier.weight(1f)){
                    Text("작업 과정 · $status",style=MaterialTheme.typography.labelLarge,color=if(activity.status=="failed")ZipHapColors.Error else ZipHapColors.TextPrimary)
                    Text("${activityTime(activity.startedAt)} · 도구 ${counts.tools} · 검증 ${counts.verified} · 준비 ${counts.prepared} · 실패 ${counts.failed}",style=MaterialTheme.typography.labelSmall,color=ZipHapColors.TextSecondary)
                }
                TextButton(onClick={expanded=!expanded;onExpandedChange?.invoke(expanded)},modifier=Modifier.testTag("toggle_activity_${activity.runId}")){Text(if(expanded)"접기"else"자세히")}
            }
            if(!expanded){
                val latest=events.lastOrNull{it.kind!=UiWorkEventKind.CONTEXT}?:events.lastOrNull()
                Text(latest?.label?.takeIf{it.isNotBlank()}?:if(events.isEmpty())"표시할 실행 기록이 아직 없습니다."else"참고 기록을 확인했습니다.",
                    maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
            }else{
                Text("간단한 작업 기준과 실제 실행 기록입니다. 응답 종료는 모든 외부 작업의 성공을 뜻하지 않습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                if(activity.hasOlder){
                    Text("최근 ${activity.events.size}개 기록 표시 · 전체 ${activity.totalEvents}개",style=MaterialTheme.typography.labelSmall,color=ZipHapColors.TextSecondary)
                    TextButton(enabled=onLoadOlder!=null&&!activity.loadingOlder,onClick={onLoadOlder?.invoke(activity.runId)},modifier=Modifier.testTag("load_older_activity_${activity.runId}")){Text(if(activity.loadingOlder)"이전 기록 불러오는 중"else"이전 작업 기록 더 보기")}
                }
                val groups=events.groupBy{it.kind}
                UiWorkEventKind.entries.forEach{kind->groups[kind]?.let{group->
                    HorizontalDivider(color=Color(0xFF30383A))
                    Text(workKindLabel(kind),style=MaterialTheme.typography.labelLarge,color=ZipHapColors.TextPrimary)
                    group.forEach{event->
                        Column(Modifier.fillMaxWidth().testTag("activity_event_${event.id}"),verticalArrangement=Arrangement.spacedBy(3.dp)){
                            Text("${activityTime(event.at)} · ${event.label}",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextPrimary)
                            val displayedEvidence=displayedWorkEvidence(event)
                            Text(workEvidenceLabel(displayedEvidence),style=MaterialTheme.typography.labelSmall,color=when(displayedEvidence){
                                UiWorkEvidence.FAILED->ZipHapColors.Error;UiWorkEvidence.VERIFIED->ZipHapColors.Mint;UiWorkEvidence.PREPARED->Color(0xFFE1B96D);else->ZipHapColors.TextSecondary})
                            if(event.detail.isNotBlank())Text(event.detail,style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                            event.fileChanges.forEach{file->
                                val operation=when(file.operation){UiFileChangeOperation.CREATED->"생성";UiFileChangeOperation.UPDATED->"수정";UiFileChangeOperation.DELETED->"삭제"}
                                Text("$operation · ${file.path}",style=MaterialTheme.typography.bodySmall)
                                Text("${hashLabel(file.beforeSha256)} → ${hashLabel(file.afterSha256)}",style=MaterialTheme.typography.labelSmall,color=ZipHapColors.TextSecondary)
                                if(!file.diffRef.isNullOrBlank()&&onOpenFileChange!=null)TextButton(onClick={onOpenFileChange(file)}){Text("실제 변경 내용 보기")}
                            }
                        }
                    }
                }}
            }
        }
    }
}
