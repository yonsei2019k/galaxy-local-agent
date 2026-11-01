package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Only the visible room's actual run may supply current-work facts. */
internal fun summaryActivity(activities:Collection<UiRunActivity>,threadId:String,activeRunId:String?,busy:Boolean):UiRunActivity? {
    val room=activities.filter{it.threadId==threadId&&it.runId.isNotBlank()}
    return if(busy) room.firstOrNull{it.runId==activeRunId}
    else room.maxByOrNull{it.updatedAt.takeIf{at->at>0}?:it.startedAt}
}

@Composable
fun ConversationWorkSummary(activity:UiRunActivity?,busy:Boolean,status:String,nextRequest:String?,queuedCount:Int,
    modifier:Modifier=Modifier,onDetails:(()->Unit)?=null,onQueue:(()->Unit)?=null){
    if(!busy&&activity==null&&queuedCount==0)return
    val latest=activity?.let{visibleActivityEvents(it).lastOrNull{event->event.kind!=UiWorkEventKind.CONTEXT}}
    val state=activity?.status
    val heading=when(state){
        "waiting_approval"->"사용자 확인이 필요합니다"
        "paused"->"작업이 일시 정지됐습니다"
        "failed"->"작업 중 오류가 발생했습니다"
        "interrupted"->"중단된 작업을 확인해주세요"
        "cancelled"->"작업을 중단했습니다"
        "completed"->"최근 응답이 끝났습니다"
        else->if(busy)"현재 작업"else"다음 작업"
    }
    val detail=latest?.label?.takeIf{it.isNotBlank()}
        ?:visibleActivityText(status,240).takeIf{busy&&it.isNotBlank()}
        ?:if(busy)"실행 상태를 확인하고 있습니다."else"실행 기록에서 결과와 확인 범위를 볼 수 있습니다."
    Surface(modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).testTag("conversation_work_summary"),
        color=ZipHapColors.Surface){
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(heading,style=MaterialTheme.typography.labelLarge,
                modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            Text(detail,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.testTag("current_work_detail"))
            if(queuedCount>0)Text("다음 · ${visibleActivityText(nextRequest.orEmpty(),240).ifBlank{"예약 요청"}} · 대기 ${queuedCount}개",
                maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.testTag("next_work_summary"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(activity!=null&&onDetails!=null)TextButton(onClick=onDetails,modifier=Modifier.testTag("summary_open_activity")){Text("실행 기록 보기")}
                if(queuedCount>0&&onQueue!=null)TextButton(onClick=onQueue,modifier=Modifier.testTag("summary_open_queue")){Text("예약 수정·순서·취소")}
            }
        }
    }
}
