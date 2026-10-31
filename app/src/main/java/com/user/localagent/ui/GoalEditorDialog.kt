package com.user.localagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.user.localagent.core.GoalCriterion
import com.user.localagent.core.GoalCriterionStatus
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus

/** The host closes only after a verified save/start; failed storage leaves all edits here intact. */
@Composable
fun GoalEditorDialog(
    goal:GoalDefinition?,threadId:String,projectId:String?,
    onSave:(GoalDefinition)->Unit,onStart:(GoalDefinition)->Unit,onDelete:()->Unit,onDismiss:()->Unit,
    feedback:String="",
    onDeleteVersioned:((GoalDefinition)->Unit)?=null,
) {
    var edited by remember(goal?.id,threadId,projectId){mutableStateOf(goal?:GoalDefinition(threadId=threadId,projectId=projectId,outcome="",criteria=listOf(GoalCriterion(text=""))))}
    var conditions by remember(goal?.id,threadId,projectId){mutableStateOf(goal?.constraints?.joinToString("\n").orEmpty())}
    var error by remember{mutableStateOf("")}
    var statusMenu by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    val stale=goal!=null&&goal.id==edited.id&&goal.updatedAt!=edited.updatedAt

    fun invalidate(updated:GoalDefinition):GoalDefinition=updated.copy(status=if(edited.status==GoalStatus.COMPLETE)GoalStatus.DRAFT else updated.status)
    fun clearEvidence(criteria:List<GoalCriterion>)=criteria.map{it.copy(status=GoalCriterionStatus.PENDING,evidence=emptyList())}
    fun submit(start:Boolean){
        try{
            check(!stale){"목표 진행 상태가 바뀌었습니다. 최신 상태를 불러온 뒤 반영해주세요."}
            check(edited.threadId==threadId&&edited.projectId==projectId){"대화·프로젝트 범위가 바뀌었습니다. 목표 창을 다시 열어주세요."}
            val candidate=edited.copy(constraints=conditions.lineSequence().filter{it.isNotBlank()}.toList(),status=if(start)GoalStatus.ACTIVE else edited.status).validate()
            require(candidate.toJson().toString().length<=12000){"목표·조건·근거를 합한 저장 내용이 12,000자를 넘었습니다. 입력은 유지했으니 내용을 나누어주세요."}
            error=""
            if(start)onStart(candidate)else onSave(candidate)
        }catch(problem:IllegalArgumentException){error=problem.message?:"목표 내용을 확인해주세요."}
        catch(problem:IllegalStateException){error=problem.message?:"목표 상태를 확인해주세요."}
    }

    AlertDialog(onDismissRequest=onDismiss,title={Text(if(goal==null)"목표 설정"else"목표와 완료 기준")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(stale){
                Text("진행 중인 작업이 목표 상태를 변경했습니다. 작성 중인 내용은 그대로 유지했습니다.",color=ZipHapColors.Error,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={goal?.let{edited=it;conditions=it.constraints.joinToString("\n");error=""}}){Text("최신 목표로 다시 불러오기")}
            }
            OutlinedTextField(edited.outcome,{text->edited=invalidate(edited.copy(outcome=text,criteria=clearEvidence(edited.criteria)))},
                modifier=Modifier.fillMaxWidth().testTag("goal_outcome"),label={Text("원하는 결과")},minLines=2,maxLines=5)
            Text("완료 기준",style=MaterialTheme.typography.titleSmall)
            Text("직접 확인한 기준과 실행 기록으로 검증된 기준을 구분합니다. 항목을 모두 확인해도 목표 상태를 자동으로 완료 처리하지 않습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
            edited.criteria.forEachIndexed{index,criterion->
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Row(verticalAlignment=Alignment.Top){
                        OutlinedTextField(criterion.text,{text->edited=invalidate(edited.copy(criteria=edited.criteria.map{if(it.id==criterion.id)it.copy(text=text,status=GoalCriterionStatus.PENDING,evidence=emptyList())else it}))},
                            modifier=Modifier.weight(1f).testTag("goal_criterion_${criterion.id}"),label={Text("기준 ${index+1}")},minLines=1,maxLines=4)
                        TextButton(onClick={edited=invalidate(edited.copy(criteria=edited.criteria.filterNot{it.id==criterion.id}))}){Text("제거")}
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Checkbox(criterion.status==GoalCriterionStatus.USER_CONFIRMED,onCheckedChange={checked->
                            edited=invalidate(edited.copy(criteria=edited.criteria.map{if(it.id==criterion.id)it.copy(status=if(checked)GoalCriterionStatus.USER_CONFIRMED else GoalCriterionStatus.PENDING,evidence=emptyList())else it}))
                        },enabled=criterion.status!=GoalCriterionStatus.VERIFIED,modifier=Modifier.testTag("goal_confirm_${criterion.id}"))
                        Text(when(criterion.status){
                            GoalCriterionStatus.VERIFIED->"실행 기록으로 검증됨 · 근거 ${criterion.evidence.size}건"
                            GoalCriterionStatus.USER_CONFIRMED->"사용자가 직접 확인함"
                            GoalCriterionStatus.FAILED->"검증 실패 · 직접 확인했다면 선택"
                            GoalCriterionStatus.PENDING->"내가 직접 확인한 기준"
                        },style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                    }
                }
            }
            TextButton(onClick={edited=invalidate(edited.copy(criteria=edited.criteria+GoalCriterion(text="")))},modifier=Modifier.testTag("goal_add_criterion")){Text("＋ 완료 기준 추가")}
            OutlinedTextField(conditions,{text->conditions=text;edited=invalidate(edited.copy(criteria=clearEvidence(edited.criteria)))},
                modifier=Modifier.fillMaxWidth().testTag("goal_constraints"),label={Text("조건 · 한 줄에 하나")},minLines=2,maxLines=5)
            HorizontalDivider()
            Column{
                TextButton(onClick={statusMenu=true},modifier=Modifier.testTag("goal_status")){Text("상태 · ${goalStatusLabel(edited.status)}")}
                DropdownMenu(expanded=statusMenu,onDismissRequest={statusMenu=false}){GoalStatus.entries.forEach{status->
                    DropdownMenuItem(text={Text(goalStatusLabel(status))},enabled=status!=GoalStatus.COMPLETE||edited.completionReady,
                        onClick={edited=edited.copy(status=status);statusMenu=false})
                }}
            }
            if(edited.status==GoalStatus.COMPLETE)Text("확인된 기준을 바탕으로 사용자가 완료 상태를 선택했습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
            if(feedback.isNotBlank())Text(feedback,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("goal_feedback"))
            if(error.isNotBlank())Text(error,color=ZipHapColors.Error,style=MaterialTheme.typography.bodySmall)
            if(goal!=null)TextButton(onClick={deleting=true}){Text("이 목표 삭제",color=ZipHapColors.Error)}
        }
    },confirmButton={Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
        TextButton(onClick={submit(false)},enabled=!stale,modifier=Modifier.testTag("goal_save")){Text("저장")}
        TextButton(onClick={submit(true)},enabled=!stale,modifier=Modifier.testTag("goal_start")){Text("목표 추진")}
    }},dismissButton={TextButton(onClick=onDismiss){Text("닫기")}})
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("목표 삭제")},text={Text("이 대화의 목표와 완료 기준을 삭제합니다. 이미 생성한 파일·일정 등 실행 결과는 유지됩니다.")},
        confirmButton={TextButton(onClick={deleting=false;if(stale)error="목표 상태가 바뀌었습니다. 최신 상태를 불러온 뒤 삭제해주세요." else if(onDeleteVersioned!=null)onDeleteVersioned(edited)else onDelete()}){Text("목표 삭제",color=ZipHapColors.Error)}},dismissButton={TextButton(onClick={deleting=false}){Text("취소")}})
}

private fun goalStatusLabel(status:GoalStatus)=when(status){GoalStatus.DRAFT->"작성 중";GoalStatus.ACTIVE->"추진 중";GoalStatus.PAUSED->"일시 정지";GoalStatus.COMPLETE->"완료 · 직접 선택"}
