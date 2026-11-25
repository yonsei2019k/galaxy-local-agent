package com.user.localagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.data.MessageCursor
import com.user.localagent.data.MessageRecord
import com.user.localagent.diagnostics.ConversationDiagnostics
import com.user.localagent.diagnostics.ConversationExportFormat
import com.user.localagent.diagnostics.ConversationExportMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local stored chat browser. An explicit save first freezes its query and complete file locally. */
@Composable
fun ConversationHistoryPanel(app:AppContainer,searchOnly:Boolean=false,onStatus:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val diagnostics=remember(app){ConversationDiagnostics(app.repository)}
    val messages=remember{mutableStateListOf<MessageRecord>()}
    var queryDraft by rememberSaveable{mutableStateOf("")}
    val searchFocus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(searchOnly){
        if(searchOnly){
            delay(150)
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    var query by rememberSaveable{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var pageLoaded by remember(query,revision) { mutableStateOf(false) }
    var loadFailed by remember(query,revision) { mutableStateOf(false) }
    var total by remember{mutableLongStateOf(0)}
    var cursor by remember{mutableStateOf<MessageCursor?>(null)}
    var busy by remember{mutableStateOf(false)}
    var deleteTarget by remember{mutableStateOf<MessageRecord?>(null)}
    var exportFormat by remember{mutableStateOf(ConversationExportFormat.JSON)}
    val exports=remember(context){PendingFileExports.get(context)}
    val exportOwner="conversation-history"
    var exportToken by rememberSaveable{mutableStateOf(exports.activeToken(exportOwner))}
    val formatter=remember{DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))}

    fun submitSearch() {
        if (busy) return
        query = queryDraft.trim()
        revision++
        keyboard?.hide()
    }

    fun finishExport(uri:android.net.Uri?,format:ConversationExportFormat){
        val token=exportToken;val extension=if(format==ConversationExportFormat.JSON)"json"else"txt"
        if(uri==null){exports.cancel(token);exportToken=null;return}
        busy=true
        scope.launch {
            try {
                val snapshot=exports.request(token,exportOwner,extension)
                val saved=savePendingExport(context,exports,token,exportOwner,extension,uri){/* Full snapshot SHA readback is performed by the shared writer. */}
                if(saved){
                    val details=checkNotNull(snapshot?.details)
                    val count=checkNotNull(details.messageCount);val partial=checkNotNull(details.truncated)
                    onStatus("선택 당시 검색 범위의 대화 ${count}개를 ${if(format==ConversationExportFormat.JSON)"JSON"else"텍스트"}로 저장하고 파일 SHA-256 일치를 확인했습니다.${if(partial)" 크기·개수 제한으로 일부만 포함됩니다."else""}")
                }
            }catch(cancel:CancellationException){onStatus("내보내기를 중단했습니다. 선택한 파일이 일부만 저장됐을 수 있습니다.");throw cancel}
            catch(error:Exception){onStatus(error.message?.take(200)?:"대화를 내보내지 못했습니다. 저장을 다시 눌러주세요.")}
            finally{exportToken=null;busy=false}
        }
    }
    val jsonDestination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){finishExport(it,ConversationExportFormat.JSON)}
    val textDestination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){finishExport(it,ConversationExportFormat.TEXT)}
    fun prepareExport(){
        if(busy||exportToken!=null)return
        val sourceQuery=query;val format=exportFormat;val extension=if(format==ConversationExportFormat.JSON)"json"else"txt"
        busy=true
        scope.launch {
            try {
                var metadata:ConversationExportMetadata?=null
                val snapshot=exports.prepare(exportOwner,extension,details={
                    val record=checkNotNull(metadata)
                    PendingExportDetails(messageCount=record.messageCount,truncated=record.truncated,query=sourceQuery)
                }){file->metadata=file.outputStream().use{diagnostics.exportSnapshot(it,limit=10_000,query=sourceQuery,format=format)}}
                exportToken=snapshot.token
                onStatus("대화 ${metadata?.messageCount}개의 저장용 복사본을 준비했습니다.${if(metadata?.truncated==true)" 제한으로 일부만 포함됩니다."else""} 저장 위치를 선택해주세요.")
                try{if(format==ConversationExportFormat.JSON)jsonDestination.launch("ZipHap-conversations.json")else textDestination.launch("ZipHap-conversations.txt")}
                catch(failure:Exception){exports.cancel(snapshot.token);exportToken=null;throw failure}
            }catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){onStatus(error.message?.take(200)?:"대화 저장용 복사본을 준비하지 못했습니다.")}
            finally{busy=false}
        }
    }
    LaunchedEffect(app,query,revision){
        busy=true
        try {
            val page=app.repository.messagePage(limit=50,query=query)
            total=app.repository.messageCount(query)
            messages.clear()
            messages.addAll(page.messages)
            cursor=page.nextCursor
            pageLoaded=true
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){loadFailed=true}
        finally{busy=false}
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(!searchOnly)ZipHapSectionTitle("저장된 대화")
            OutlinedTextField(value=queryDraft,onValueChange={if(it.length<=500)queryDraft=it},label={Text("대화 내용 검색")},
                modifier=Modifier.fillMaxWidth().focusRequester(searchFocus),singleLine=true,shape=MaterialTheme.shapes.medium,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search), keyboardActions=KeyboardActions(onSearch={submitSearch()}))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(enabled=!busy,onClick=::submitSearch){Text("검색")}
                OutlinedButton(enabled=!busy,onClick={queryDraft="";query="";revision++}){Text("전체")}
            }
            if(pageLoaded)ZipHapStatusTag("기록 ${total}개 · ${messages.size}개 표시")
        }}}
        if(!searchOnly)item {SettingsDisclosure("대화 내보내기", "검색 범위의 기록을 파일로 저장해요.") {
            Text("최대 10,000개·20MiB까지 선택한 위치에 저장해요. 일부만 저장되면 파일에 표시해요.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=exportFormat==ConversationExportFormat.JSON,onClick={exportFormat=ConversationExportFormat.JSON},label={Text("JSON")})
                FilterChip(selected=exportFormat==ConversationExportFormat.TEXT,onClick={exportFormat=ConversationExportFormat.TEXT},label={Text("텍스트")})
            }
            OutlinedButton(enabled=pageLoaded&&!busy&&exportToken==null,onClick={prepareExport()}){Text("대화 파일 저장")}
        }}
        if(busy)item{LinearProgressIndicator(modifier=Modifier.fillMaxWidth())}
        if(loadFailed)item {
            Column {
                Text("대화 기록을 불러오지 못했어요.")
                TextButton(onClick={revision++}) { Text("다시 불러오기") }
            }
        } else if(pageLoaded && messages.isEmpty())item {
            Text(if(query.isEmpty()) "아직 저장된 대화가 없어요." else "이 단어를 포함한 대화가 없어요.")
        }
        items(if(pageLoaded) messages else emptyList(),key={it.id}) {message->
            var expanded by remember(message.id){mutableStateOf(false)}
            ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                ZipHapStatusTag("${if(message.role=="user")"나" else "집순이"} · ${formatter.format(Instant.ofEpochMilli(message.createdAt))}")
                SelectionContainer{Text(message.text,maxLines=if(expanded)Int.MAX_VALUE else 8,style=MaterialTheme.typography.bodyMedium)}
                Row{
                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"접기" else "전체 보기")}
                    TextButton(enabled=!busy,onClick={deleteTarget=message}){Text("이 기록 삭제")}
                }
            }}
        }
        if(pageLoaded && cursor!=null)item{OutlinedButton(enabled=!busy,onClick={if(!busy){val next=cursor;busy=true;scope.launch {
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
        if(!busy&&messages.isEmpty())item{
            ZipHapCard {ZipHapSectionTitle(if(query.isBlank())"저장된 대화가 없어요." else "검색 결과가 없어요.")}
        }
    }
    deleteTarget?.let {target->AlertDialog(onDismissRequest={deleteTarget=null},title={Text("이 대화 기록을 삭제할까요?")},text={Text("이 메시지와 연결된 기억을 삭제하고 대화 요약을 초기화합니다. 내보낸 파일은 별도로 관리해야 합니다.")},confirmButton={TextButton(onClick={scope.launch {
        busy=true
        try{app.repository.deleteMessage(target.id);deleteTarget=null;revision++;onStatus("선택한 대화 기록을 삭제했습니다.")}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("기록을 삭제하지 못했습니다.")}
        finally{busy=false}
    }}){Text("삭제")}},dismissButton={TextButton(onClick={deleteTarget=null}){Text("취소")}})}
}
