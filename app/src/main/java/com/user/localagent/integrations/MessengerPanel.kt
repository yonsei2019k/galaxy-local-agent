package com.user.localagent.integrations

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.user.localagent.automation.AssistantNotificationListener

@Composable fun MessengerPanel(context:Context,report:(String)->Unit) {
    val messages by MessengerInbox.messages.collectAsState()
    val lifecycle=LocalLifecycleOwner.current
    var listenerGranted by remember {mutableStateOf(AssistantNotificationListener.granted(context))}
    DisposableEffect(lifecycle,context) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME){
                listenerGranted=AssistantNotificationListener.granted(context)
                if(!listenerGranted)MessengerInbox.clearSession()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    var revision by remember {mutableIntStateOf(0)}
    var replyTo by remember {mutableStateOf<InboxMessage?>(null)}
    var draft by remember {mutableStateOf("")}
    var rule by remember {mutableStateOf(false)}
    Text("앱별 메시지 연동",style=MaterialTheme.typography.titleLarge)
    Text("Android 알림 접근: ${if(listenerGranted)"허용됨" else "꺼짐"}",style=MaterialTheme.typography.titleMedium)
    Text("Android 알림 접근은 여러 앱의 알림 내용을 읽을 수 있는 시스템 권한이며 ZipHap의 알림 표시 권한과는 다릅니다. 아래에서 켠 지원 앱의 알림은 제목·본문, 메시지 알림이 제공하는 대화방·보낸 사람 이름을 기기 메모리에서 최대 10분 처리합니다. 요청하면 기기 안의 모델이 분석하며 결과는 대화 기록에 저장됩니다. 외부 AI나 서버로 전송하지 않습니다. 답장은 사용자가 직접 확인하거나 승인한 고정 문구만 해당 앱 알림의 답장 동작으로 보냅니다.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={context.startActivity(AssistantNotificationListener.permissionIntent(context))}) {Text("Android 알림 접근 설정")}
    MessengerInbox.supported.forEach {(pkg,label)->
        key(revision,pkg){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f));Switch(MessengerInbox.allowed(context,pkg),onCheckedChange={MessengerInbox.setAllowed(context,pkg,it);revision++})}}
    }
    Text("답장 버튼은 해당 앱 알림에 Android 답장 기능이 있을 때 표시됩니다. 앱 업데이트나 알림 설정에 따라 사용할 수 없을 수 있습니다.",style=MaterialTheme.typography.bodySmall)
    messages.take(10).forEach {message->
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text("${MessengerInbox.supported[message.packageName]} · ${message.conversation?:message.title}",style=MaterialTheme.typography.titleSmall)
            message.sender?.let {Text("보낸 사람 · $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text(message.text,maxLines=5)
            Row {TextButton(enabled=message.canReply,onClick={replyTo=message;draft="";rule=false}){Text("답장 작성")};if(message.canReply&&message.stableThread)TextButton(onClick={replyTo=message;draft="확인했습니다. 내용을 검토한 뒤 답변드리겠습니다.";rule=true}){Text("자동 답장 규칙")}}
        }}
    }
    OutlinedButton(onClick={MessengerInbox.clear(context);report("알림 세션과 자동 답장 규칙을 삭제했습니다.")}){Text("메시지·답장 규칙 지우기")}
    replyTo?.let {target->AlertDialog(onDismissRequest={replyTo=null},title={Text(if(rule)"이 대화방의 자동 답장 허용" else "답장 전송 확인")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("앱 · ${MessengerInbox.supported[target.packageName]}")
        Text("대화방 · ${target.conversation?:target.title}")
        target.sender?.let {Text("확인한 보낸 사람 · $it")}
        OutlinedTextField(value=draft,onValueChange={if(it.length<=if(rule)500 else 2000)draft=it},label={Text("전송할 문구")})
        Text(if(rule)"24시간 동안 이 대화방의 새 수신 메시지에 같은 문구를 자동 전송합니다. 그룹 대화라면 보낸 사람 구분 없이 적용됩니다. 시간당 최대 1건, 하루 최대 3건이며 AI가 문구를 바꾸지 않습니다." else "확인하면 해당 메신저의 알림 답장으로 전송을 요청합니다.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(enabled=draft.isNotBlank(),onClick={runCatching{if(rule)MessengerInbox.approveAcknowledgementRule(context,target.id,draft)else MessengerInbox.replyConfirmed(context,target.id,draft)}.onSuccess{report(if(rule)"지정한 대화방의 자동 답장 규칙을 저장했습니다." else "답장 전송을 요청했습니다. 수신 여부는 메신저에서 확인해주세요.");replyTo=null}.onFailure{report(it.message?:"답장을 실행하지 못했습니다.");replyTo=null}}){Text(if(rule)"이 범위 자동 허용" else "전송")}},dismissButton={TextButton(onClick={replyTo=null}){Text("취소")}})}
}
