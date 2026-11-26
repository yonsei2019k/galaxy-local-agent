@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.user.localagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.user.localagent.core.AgentWorkMode
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

data class UiQueuedRun(
    val id:String,
    val threadId:String,
    val title:String,
    val text:String,
    val status:String="대기 중",
    val threadLabel:String="",
    val modelLabel:String="",
    val modeLabel:String="",
    val goalLinked:Boolean=false,
    val continuation:Boolean=false,
)
data class UiRunEvent(val id:String,val label:String,val detail:String="",val phase:String="plan",val threadId:String="")

/** Legacy standalone surface; the conversation composer does not expose workflow modes. */
@Deprecated("Conversation input selects the needed work without a persistent mode menu.")
@Composable
fun RunModeSelector(mode:String,onModeChange:(String)->Unit,modifier:Modifier=Modifier){
    var expanded by remember {mutableStateOf(false)}
    val selected=AgentWorkMode.entries.firstOrNull {it.wireName==mode}?:AgentWorkMode.ACT
    Box(modifier){
        ZipOutlinedButton(onClick={expanded=true},modifier=Modifier.testTag("run_mode_selector")){Text(selected.label);Spacer(Modifier.width(6.dp));ZipIcon(com.user.localagent.runtime.R.drawable.zi_down,null,size=18.dp)}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=420.dp)){
            AgentWorkMode.entries.forEach {item->
                DropdownMenuItem(text={Column{
                    Text(item.label,style=MaterialTheme.typography.titleSmall)
                    Text(item.description,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                }},onClick={expanded=false;onModeChange(item.wireName)},modifier=Modifier.testTag("run_mode_${item.wireName}"))
            }
        }
    }
}

@Composable
fun RunQueueDialog(items:List<UiQueuedRun>,onDismiss:()->Unit,onEdit:(String,String)->Unit,onMove:(String,Int)->Unit,onCancel:(String)->Unit,onManageGoal:((String)->Unit)?=null){
    var editing by remember{mutableStateOf<UiQueuedRun?>(null)}
    var editedText by remember{mutableStateOf("")}
    ZipSheet(onDismissRequest=onDismiss,title="대기 요청 · ${items.size}개",scrollable=false,
        actions={ZipSecondaryButton("닫기",onDismiss)}){
        if(items.isEmpty())ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_empty_chat,"대기 중인 요청이 없습니다.","")else LazyColumn(Modifier.fillMaxWidth().heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(items,key={it.id}){item->
                val position=items.indexOfFirst{it.id==item.id}
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZipHapTokens.RadiusControl)).background(ZipHapColors.SurfaceSunken)
                    .padding(12.dp).testTag("queued_run_${item.id}"),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("${position+1}. ${item.title.ifBlank{"첨부 자료 분석"}}",style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Text(listOf(item.status,item.threadLabel,item.modelLabel,item.modeLabel).filter{it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.labelMedium,color=ZipHapColors.TextSecondary)
                    Text("요청 ${item.id.takeLast(8)}",style=MaterialTheme.typography.labelSmall,color=ZipHapColors.TextSecondary)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){
                        TextButton(onClick={onMove(item.id,-1)},enabled=position>0,modifier=Modifier.testTag("queue_up_${item.id}")){Text("위로")}
                        TextButton(onClick={onMove(item.id,1)},enabled=position<items.lastIndex,modifier=Modifier.testTag("queue_down_${item.id}")){Text("아래로")}
                        TextButton(onClick={editing=item;editedText=item.text},enabled=!item.goalLinked&&!item.continuation,modifier=Modifier.testTag("queue_edit_${item.id}")){Text("수정")}
                        TextButton(onClick={onCancel(item.id)},modifier=Modifier.testTag("queue_cancel_${item.id}")){Text("취소",color=ZipHapColors.Error)}
                    }
                    if(item.goalLinked)Text("고정된 목표 요청입니다. 취소 후 채팅의 목표 관리에서 수정하고 다시 추진할 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                    if(item.goalLinked&&onManageGoal!=null)TextButton(onClick={onManageGoal(item.threadId)},modifier=Modifier.testTag("queue_goal_${item.id}")){Text("목표가 있는 대화 열기")}
                    if(item.continuation)Text("같은 답변에 이어 쓰는 요청입니다. 기존 요청과 출력은 유지됩니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                }
            }
        }
    }
    editing?.let{target->val stillQueued=items.any{it.id==target.id}
        AlertDialog(onDismissRequest={editing=null},title={Text("대기 요청 수정")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            ZipOutlinedTextField(editedText,{if(it.length<=12000)editedText=it},modifier=Modifier.fillMaxWidth().heightIn(max=280.dp),minLines=3,maxLines=9)
            Text(if(stillQueued)"변경한 내용은 이 대기 요청에 적용됩니다."else"이 요청이 이미 시작되거나 취소되었습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
        }},confirmButton={TextButton(onClick={onEdit(target.id,editedText.trim());editing=null},enabled=stillQueued&&editedText.isNotBlank()){Text("수정 적용")}},dismissButton={TextButton(onClick={editing=null}){Text("돌아가기")}})
    }
}

@Composable
fun RunSteerDialog(onDismiss:()->Unit,onSubmit:(String)->Unit){
    var instruction by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("진행 중 요청에 추가 지시")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        ZipOutlinedTextField(instruction,{if(it.length<=4000)instruction=it},modifier=Modifier.fillMaxWidth().heightIn(max=280.dp),minLines=3,maxLines=8,placeholder={Text("예: 비교 기준을 가격보다 소음에 맞춰줘")})
        Text("현재 실행을 안전하게 정리한 뒤 추가 지시를 이어서 처리합니다. 이미 완료한 작업의 결과는 유지됩니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
    }},confirmButton={TextButton(onClick={onSubmit(instruction.trim())},enabled=instruction.isNotBlank()){Text("지시 보내기")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}

@Composable
fun RunTimeline(events:List<UiRunEvent>,modifier:Modifier=Modifier){
    if(events.isEmpty())return
    var expanded by remember{mutableStateOf(false)}
    Column(modifier.fillMaxWidth()){
        TextButton(onClick={expanded=true},modifier=Modifier.testTag("run_timeline")){Text("진행 기록 · ${events.size}개",color=ZipHapColors.TextSecondary);ZipIcon(com.user.localagent.runtime.R.drawable.zi_chevron_right,null,tint=ZipHapColors.TextSecondary,size=18.dp)}
        events.takeLast(2).forEach{event->
            Text("${phaseLabel(event.phase)} · ${event.label}",Modifier.padding(horizontal=12.dp,vertical=2.dp),style=MaterialTheme.typography.labelMedium,color=ZipHapColors.TextSecondary,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
    if(expanded)ZipSheet(onDismissRequest={expanded=false},title="실행 진행 기록",scrollable=false,
        actions={ZipSecondaryButton("닫기",{expanded=false})}){
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(events,key={it.id}){event->Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                if(event!==events.first())ZipInsetDivider(inset=0.dp)
                Text("${phaseLabel(event.phase)} · ${event.label}",style=MaterialTheme.typography.titleSmall)
                if(event.detail.isNotBlank())Text(event.detail,style=MaterialTheme.typography.bodySmall)
            }}
        }
    }
}

private fun phaseLabel(phase:String)=when(phase){"tool"->"도구";"result","verification","verified"->"확인";"prepared"->"준비";"unverified"->"확인 필요";"error","failed"->"오류";"plan"->"계획";else->"진행"}
