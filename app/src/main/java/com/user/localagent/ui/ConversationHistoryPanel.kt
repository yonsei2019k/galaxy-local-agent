package com.user.localagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.data.MessageCursor
import com.user.localagent.data.MessageRecord
import com.user.localagent.diagnostics.ConversationDiagnostics
import com.user.localagent.diagnostics.ConversationExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local stored chat browser. Exports run only after the user picks a destination document. */
@Composable
fun ConversationHistoryPanel(app:AppContainer,onStatus:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val diagnostics=remember(app){ConversationDiagnostics(app.repository)}
    val messages=remember{mutableStateListOf<MessageRecord>()}
    var queryDraft by remember{mutableStateOf("")}
    var query by remember{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var total by remember{mutableLongStateOf(0)}
    var cursor by remember{mutableStateOf<MessageCursor?>(null)}
    var busy by remember{mutableStateOf(false)}
    var deleteTarget by remember{mutableStateOf<MessageRecord?>(null)}
    var exportFormat by remember{mutableStateOf(ConversationExportFormat.JSON)}
    val formatter=remember{DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))}

    val jsonDestination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)scope.launch {
            busy=true
            try {
                val result=context.contentResolver.openOutputStream(uri,"wt")?.use {diagnostics.exportSnapshot(it,limit=10_000,query=query)}?:error("선택한 파일을 열 수 없습니다.")
                onStatus("대화 ${result.messageCount}개를 JSON으로 내보냈습니다.${if(result.truncated)" 크기·개수 제한으로 일부만 포함됩니다." else ""}")
            }catch(cancel:CancellationException){onStatus("내보내기를 중단했습니다. 선택한 파일이 일부만 저장됐을 수 있습니다.");throw cancel}
            catch(_:Exception){onStatus("대화를 내보내지 못했습니다. 저장 위치를 확인해주세요.")}
            finally{busy=false}
        }
    }
    val textDestination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)scope.launch {
            busy=true
            try {
                val result=context.contentResolver.openOutputStream(uri,"wt")?.use {diagnostics.exportSnapshot(it,limit=10_000,query=query,format=ConversationExportFormat.TEXT)}?:error("선택한 파일을 열 수 없습니다.")
                onStatus("대화 ${result.messageCount}개를 텍스트로 내보냈습니다.${if(result.truncated)" 크기·개수 제한으로 일부만 포함됩니다." else ""}")
            }catch(cancel:CancellationException){onStatus("내보내기를 중단했습니다. 선택한 파일이 일부만 저장됐을 수 있습니다.");throw cancel}
            catch(_:Exception){onStatus("대화를 내보내지 못했습니다. 저장 위치를 확인해주세요.")}
            finally{busy=false}
        }
    }
    LaunchedEffect(app,query,revision){
        busy=true
        try {
            val page=app.repository.messagePage(limit=50,query=query)
            messages.clear();messages.addAll(page.messages);cursor=page.nextCursor;total=app.repository.messageCount(query)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("대화 기록을 불러오지 못했습니다.")}
        finally{busy=false}
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("내 대화 기록",style=MaterialTheme.typography.titleLarge)
            Text("ZipHap.Agent에 저장된 대화를 최신순으로 봅니다. 최근 200개보다 오래된 기록도 불러올 수 있습니다. 다른 앱의 대화나 입력 중인 초안은 포함하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=queryDraft,onValueChange={if(it.length<=500)queryDraft=it},label={Text("대화 내용 검색")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(enabled=!busy,onClick={query=queryDraft.trim();revision++}){Text("검색")}
                OutlinedButton(enabled=!busy,onClick={queryDraft="";query="";revision++}){Text("전체")}
            }
            Text("일치하는 기록 ${total}개 · 현재 ${messages.size}개 표시",style=MaterialTheme.typography.labelMedium)
        }}}
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("진단용 대화 내보내기",style=MaterialTheme.typography.titleMedium)
            Text("현재 검색 범위의 대화 본문을 선택한 위치에 저장합니다. 최대 10,000개·20MiB이며 일부인 경우 파일에 표시됩니다. 개발자에게 검토할 기록만 직접 공유할 수 있고, GitHub에 자동 업로드하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=exportFormat==ConversationExportFormat.JSON,onClick={exportFormat=ConversationExportFormat.JSON},label={Text("JSON")})
                FilterChip(selected=exportFormat==ConversationExportFormat.TEXT,onClick={exportFormat=ConversationExportFormat.TEXT},label={Text("텍스트")})
            }
            OutlinedButton(enabled=!busy,onClick={if(exportFormat==ConversationExportFormat.JSON)jsonDestination.launch("ZipHap-conversations.json")else textDestination.launch("ZipHap-conversations.txt")}){Text("대화 파일 저장")}
        }}}
        if(busy)item{LinearProgressIndicator(modifier=Modifier.fillMaxWidth())}
        items(messages,key={it.id}) {message->
            var expanded by remember(message.id){mutableStateOf(false)}
            ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("${if(message.role=="user")"나" else "ZipHap.Agent"} · ${formatter.format(Instant.ofEpochMilli(message.createdAt))}",style=MaterialTheme.typography.labelMedium)
                SelectionContainer{Text(message.text,maxLines=if(expanded)Int.MAX_VALUE else 8,style=MaterialTheme.typography.bodyMedium)}
                Row{
                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"접기" else "전체 보기")}
                    TextButton(enabled=!busy,onClick={deleteTarget=message}){Text("이 기록 삭제")}
                }
            }}
        }
        if(cursor!=null)item{OutlinedButton(enabled=!busy,onClick={if(!busy){val next=cursor;busy=true;scope.launch {
            try {
                val page=app.repository.messagePage(next,limit=50,query=query)
                val visibleIds=messages.mapTo(mutableSetOf()){it.id}
                messages.addAll(page.messages.filter {visibleIds.add(it.id)})
                // Retain a bounded browsing window while the cursor can continue through all history.
                if(messages.size>400)repeat(messages.size-400){messages.removeAt(0)}
                cursor=page.nextCursor
            }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){onStatus("이전 기록을 불러오지 못했습니다.")}
            finally{busy=false}
        }}},modifier=Modifier.fillMaxWidth()){Text("이전 기록 더 보기")}}
        if(!busy&&messages.isEmpty())item{Text("저장된 대화가 없거나 검색 결과가 없습니다.",style=MaterialTheme.typography.bodyMedium)}
    }
    deleteTarget?.let {target->AlertDialog(onDismissRequest={deleteTarget=null},title={Text("이 대화 기록을 삭제할까요?")},text={Text("이 메시지와 연결된 기억을 삭제하고 대화 요약을 초기화합니다. 내보낸 파일은 별도로 관리해야 합니다.")},confirmButton={TextButton(onClick={scope.launch {
        busy=true
        try{app.repository.deleteMessage(target.id);deleteTarget=null;revision++;onStatus("선택한 대화 기록을 삭제했습니다.")}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("기록을 삭제하지 못했습니다.")}
        finally{busy=false}
    }}){Text("삭제")}},dismissButton={TextButton(onClick={deleteTarget=null}){Text("취소")}})}
}
