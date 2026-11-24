package com.user.localagent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.core.CapabilityPolicy

private fun approvalTitle(action:PendingAction):String =
    if(action.recipientChoices.isNotEmpty())"문자를 받을 사람 선택" else when(action.call.name){
        "prepare_sms"->"문자 초안 확인"
        "prepare_email"->"메일 초안 확인"
        "send_gmail_message","reply_gmail_message"->"보낼 메일 확인"
        "reply_messenger_notification"->"보낼 답장 확인"
        "prepare_calendar_event","create_google_calendar_event"->"일정 확인"
        "create_reminder","update_reminder","delete_reminder"->"알림 확인"
        "create_alarm"->"알람 확인"
        "save_note","update_note","patch_note","delete_note"->"메모 변경 확인"
        "save_memory","delete_memory"->"기억 변경 확인"
        "write_project_file"->"파일 변경 확인"
        else->"작업 내용 확인"
    }

@Composable
private fun ApprovalContents(action:PendingAction,review:String,modifier:Modifier) {
    val remember by action.remember.collectAsState()
    val recipient by action.selectedRecipient.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(review,style=MaterialTheme.typography.bodySmall)
        action.recipientChoices.forEach { candidate->
            Row(verticalAlignment=Alignment.CenterVertically) {
                RadioButton(recipient==candidate.phoneNumber,onClick={action.selectedRecipient.value=candidate.phoneNumber})
                Text(candidate.name+"\n"+candidate.phoneNumber)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("확인한 이 작업만 진행해요.",style=MaterialTheme.typography.bodySmall)
        if(action.recipientChoices.isEmpty()&&CapabilityPolicy.canPreapprove(action.call.name)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(remember,onCheckedChange={action.remember.value=it})
                Text("같은 범위의 반복 생성·수정을 허용",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ApprovalConfirm(action:PendingAction,onDecision:(Boolean)->Unit) {
    val recipient by action.selectedRecipient.collectAsState()
    TextButton(
        enabled=!action.decision.isCompleted&&(action.recipientChoices.isEmpty()||recipient!=null),
        onClick={onDecision(true)}
    ) { Text(if(action.recipientChoices.isEmpty())"확인하고 실행" else "선택한 번호로 초안 준비") }
}

@Composable
internal fun ActionApprovalDialog(
    action:PendingAction,review:String,onDecision:(Boolean)->Unit={action.decision.complete(it)}
) {
    AlertDialog(
        onDismissRequest={onDecision(false)},
        title={Text(approvalTitle(action))},
        text={ApprovalContents(action,review,Modifier.heightIn(max=460.dp))},
        confirmButton={ApprovalConfirm(action,onDecision)},
        dismissButton={TextButton(onClick={onDecision(false)}){Text("취소")}}
    )
}

@Composable
internal fun InlineActionApproval(action:PendingAction,review:String,onDecision:(Boolean)->Unit) {
    Card(
        modifier=Modifier.fillMaxSize(),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=8.dp)) {
            Text(approvalTitle(action),style=MaterialTheme.typography.titleSmall, maxLines=2)
            Spacer(Modifier.height(6.dp))
            ApprovalContents(action,review,Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick={onDecision(false)}){Text("취소")}
                ApprovalConfirm(action,onDecision)
            }
        }
    }
}
