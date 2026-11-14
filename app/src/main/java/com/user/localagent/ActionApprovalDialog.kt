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
    AlertDialog(onDismissRequest={action.decision.complete(false)},title={Text(if(action.recipientChoices.isEmpty())"실행 내용을 확인해주세요"else"문자를 준비할 연락처를 선택해주세요")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())){
            if(action.recipientChoices.isEmpty())Text(action.call.name)
            Text(review,style=MaterialTheme.typography.bodySmall)
            action.recipientChoices.forEach{candidate->Row(verticalAlignment=Alignment.CenterVertically){
                RadioButton(recipient==candidate.phoneNumber,onClick={action.selectedRecipient.value=candidate.phoneNumber})
                Text(candidate.name+"\n"+candidate.phoneNumber)
            }}
            Spacer(Modifier.height(8.dp));Text("승인한 이 작업만 실행합니다.")
            if(action.recipientChoices.isEmpty()&&CapabilityPolicy.canPreapprove(action.call.name))Row{
                Checkbox(remember,onCheckedChange={action.remember.value=it});Text("같은 범위의 반복 생성·수정을 허용")
            }
        }
    },confirmButton={TextButton(enabled=action.recipientChoices.isEmpty()||recipient!=null,onClick={action.decision.complete(true)}){
        Text(if(action.recipientChoices.isEmpty())"실행"else"선택한 번호로 초안 준비")
    }},dismissButton={TextButton(onClick={action.decision.complete(false)}){Text("취소")}})
}
