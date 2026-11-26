package com.user.localagent.briefing

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.user.localagent.ui.ZipOutlinedButton
import com.user.localagent.ui.ZipFilledButton

@Composable fun BriefingPanel(app:AppContainer) {
    val context=LocalContext.current;val archive=remember {BriefingArchive(context)};val scope=rememberCoroutineScope()
    var status by remember {mutableStateOf(archive.status())};var confirm by remember {mutableStateOf(false)}
    val notes by app.repository.notes.collectAsState(emptyList())
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("PC에서 새 자료 정리",style=MaterialTheme.typography.titleMedium)
            Text("허용한 문자·통화기록과 카톡·메디통·메일 등의 새 알림을 암호화 보관합니다. 연결된 개발 PC의 Codex가 전체 문맥을 분석하며 OpenAI로 전달됩니다. 알림은 전체 대화가 아니고 통화기록에는 통화 내용이 없습니다.",style=MaterialTheme.typography.bodySmall)
            Text("${if(status["enabled"]?.jsonPrimitive?.boolean==true)"수집 켜짐"else"수집 꺼짐"} · 미분석 ${status["pending"]} · 처리 ${status["completed"]}")
            Text("30분마다 AI를 호출하지 않습니다. 새 자료를 모아서 분석하며 메시지나 서류를 전송하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            Text("처음에는 최근 30일 문자·통화기록부터 확인합니다. 과거 전체 이력을 분석한 것으로 표시하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            ZipFilledButton(onClick={context.startActivity(android.content.Intent(context,BriefingReviewActivity::class.java))}){Text("일정·업무 확인 및 캘린더 선택")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(status["enabled"]?.jsonPrimitive?.boolean==true)ZipOutlinedButton(onClick={archive.setEnabled(false);status=archive.status()}){Text("수집 중지")}
                else ZipFilledButton(onClick={confirm=true}){Text("수집·Codex 전달 허용")}
                TextButton(onClick={status=archive.status()}){Text("새로고침")}
                TextButton(onClick={archive.clear();status=archive.status()}){Text("보관함 삭제·중지")}
            }
            status["error"]?.takeIf {it!=JsonNull}?.let {Text("보관 한도에 도달했습니다. 아직 처리되지 않은 자료가 있습니다.")}
            if(context.getSharedPreferences("briefing-capture-status",android.content.Context.MODE_PRIVATE).getBoolean("captureIncomplete",false))Text("일부 알림을 보관하지 못한 이력이 있습니다. 전체 대화를 확보한 상태가 아닙니다.")
            notes.filter {it.collection=="codex-briefings"}.sortedByDescending {it.updatedAt}.take(3).forEach {
                Text(it.title,style=MaterialTheme.typography.titleSmall);Text(it.body.substringAfter("\n\n").substringBefore("\n\n구조화 결과:"),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("전체 문맥을 Codex에 전달할까요?")},text={Text("기존 Android 권한 범위 안에서 문자·통화기록과 지원 앱의 새 알림을 누적합니다. OpenAI Codex 분석은 로컬 추론과 별개입니다. 중지하면 새 수집·전달을 막고, 삭제하면 원문 보관함과 처리 이력을 지웁니다. 이미 저장된 분석 노트와 원본 앱의 자료는 유지됩니다.")},
        confirmButton={TextButton(onClick={confirm=false;scope.launch {withContext(Dispatchers.IO){archive.setEnabled(true)};status=archive.status()}}){Text("허용")}},
        dismissButton={TextButton(onClick={confirm=false}){Text("취소")}})
}
