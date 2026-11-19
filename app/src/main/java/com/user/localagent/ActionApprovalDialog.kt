package com.user.localagent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.core.CapabilityPolicy

@Composable internal fun ActionApprovalDialog(action:PendingAction,review:String){
    val remember by action.remember.collectAsState()
    val recipient by action.selectedRecipient.collectAsState()
    val title=if(action.recipientChoices.isNotEmpty())"문자를 받을 사람 선택" else when(action.call.name){
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
    AlertDialog(onDismissRequest={action.decision.complete(false)},title={Text(title)},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())){
            Text(review,style=MaterialTheme.typography.bodySmall)
            action.recipientChoices.forEach{candidate->Row(verticalAlignment=Alignment.CenterVertically){
                RadioButton(recipient==candidate.phoneNumber,onClick={action.selectedRecipient.value=candidate.phoneNumber})
                Text(candidate.name+"\n"+candidate.phoneNumber)
            }}
            Spacer(Modifier.height(8.dp));Text("확인한 이 작업만 진행해요.")
            if(action.recipientChoices.isEmpty()&&CapabilityPolicy.canPreapprove(action.call.name))Row{
                Checkbox(remember,onCheckedChange={action.remember.value=it});Text("같은 범위의 반복 생성·수정을 허용")
            }
        }
    },confirmButton={TextButton(enabled=action.recipientChoices.isEmpty()||recipient!=null,onClick={action.decision.complete(true)}){
        Text(if(action.recipientChoices.isEmpty())"실행"else"선택한 번호로 초안 준비")
    }},dismissButton={TextButton(onClick={action.decision.complete(false)}){Text("취소")}})
}
