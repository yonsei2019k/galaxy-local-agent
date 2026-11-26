package com.user.localagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.workspace.ProjectFile
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/** One private project by default; external scope comes only from the owner's SAF tree choice. */
@Composable fun ProjectWorkspacePanel(app:AppContainer,onStatus:(String)->Unit,onAsk:(String)->Unit){
    val projectId by app.runs.selectedProjectId.collectAsState()
    val ready by app.runs.selectionReady.collectAsState()
    var workspace by remember(projectId){mutableStateOf<ProjectWorkspace?>(null)}
    var binding by remember(projectId){mutableStateOf<String?>(null)}
    var revision by remember(projectId){mutableIntStateOf(0)}
    var scopeError by remember(projectId){mutableStateOf<String?>(null)}
    LaunchedEffect(projectId,revision){
        workspace=null;binding=null;scopeError=null
        try{
            check(projectId.isBlank()||app.repository.chat.project(projectId)!=null){"선택한 프로젝트가 없어 폴더를 열지 않았습니다."}
            val captured=app.workspaceFor(projectId.takeIf{it.isNotBlank()})
            val fingerprint=captured.bindingFingerprint()
            check(projectId.isBlank()||app.repository.chat.project(projectId)!=null){"선택한 프로젝트가 없어 폴더를 열지 않았습니다."}
            ensureActive();workspace=captured;binding=fingerprint
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){scopeError="프로젝트 폴더를 준비하지 못했습니다. 프로젝트를 다시 선택해주세요."}
    }
    val captured=workspace;val fingerprint=binding
    if(!ready||captured==null||fingerprint==null){Text(scopeError?:"프로젝트 폴더를 확인하는 중입니다.");return}
    key(projectId,fingerprint){ProjectWorkspaceEditor(app,captured,projectId,fingerprint,{revision++},onStatus,onAsk)}
}

@Composable private fun ProjectWorkspaceEditor(app:AppContainer,workspace:ProjectWorkspace,projectId:String,binding:String,onBindingChanged:()->Unit,onStatus:(String)->Unit,onAsk:(String)->Unit){
    val selection by workspace.state.collectAsState()
    val scope=rememberCoroutineScope()
    var directory by rememberSaveable {mutableStateOf("")}
    var files by remember {mutableStateOf<List<ProjectFile>>(emptyList())}
    var path by rememberSaveable {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    var loadedPath by remember {mutableStateOf<String?>(null)}
    var loadedHash by remember {mutableStateOf<String?>(null)}
    var working by remember {mutableStateOf(false)}
    var pickerBinding by rememberSaveable{mutableStateOf<String?>(null)}
    fun current()=app.runs.selectionReady.value&&app.runs.selectedProjectId.value==projectId
    suspend fun ensureCurrent(){
        currentCoroutineContext().ensureActive();check(current()){"프로젝트가 바뀌어 이전 화면의 작업을 적용하지 않았습니다."}
        if(workspace.bindingFingerprint()!=binding){onBindingChanged();error("프로젝트 폴더가 바뀌었습니다. 파일을 다시 열어주세요.")}
        currentCoroutineContext().ensureActive();check(current()){"프로젝트가 바뀌어 이전 화면의 작업을 적용하지 않았습니다."}
    }
    fun execute(changesBinding:Boolean=false,block:suspend ()->Unit){if(working||!current())return;working=true;scope.launch {
        var lease:java.io.Closeable?=null
        try{if(!changesBinding)lease=workspace.acquireLease();ensureCurrent();block()}
        catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){if(current())onStatus(error.message?.take(200)?:"프로젝트 파일 작업 실패")}
        finally{lease?.close();working=false}
    }}
    suspend fun refresh(){val loaded=workspace.list(directory);ensureCurrent();files=loaded}
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        val origin=pickerBinding;pickerBinding=null
        if(uri!=null&&origin==binding&&current())execute(changesBinding=true){
            workspace.select(uri)
            if(current()){onStatus("선택한 프로젝트 폴더에 연결했습니다.");onBindingChanged()}
        }
    }
    LaunchedEffect(workspace,binding,selection){
        if(workspace.bindingFingerprint()!=binding){onBindingChanged();return@LaunchedEffect}
        directory="";files=emptyList()
        try{refresh()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){if(current())onStatus("프로젝트 폴더 목록을 확인하지 못했습니다.")}
    }
    LaunchedEffect(workspace,binding){while(isActive){
        delay(1000)
        if(workspace.bindingFingerprint()!=binding){if(current())onBindingChanged();return@LaunchedEffect}
    }}
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item {Text(selection.name,style=MaterialTheme.typography.titleLarge)
            Text(if(selection.external)"사용자가 선택한 휴대전화 폴더 · ${if(selection.writable)"읽기·쓰기"else"읽기"}"else"앱 전용 로컬 프로젝트 · PC 연결 없이 읽고 작성",style=MaterialTheme.typography.bodySmall)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipOutlinedButton(enabled=!working,onClick={if(current()){pickerBinding=binding;choose.launch(null)}}){Text("휴대전화 폴더 선택")}
            if(selection.external)TextButton(enabled=!working,onClick={execute(changesBinding=true){workspace.usePrivateProject();if(current()){onStatus("앱 전용 프로젝트로 전환했습니다.");onBindingChanged()}}}){Text("기본 프로젝트")}
        }}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(directory.ifBlank {"프로젝트 루트"},Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            if(directory.isNotEmpty())TextButton(enabled=!working,onClick={execute {directory=directory.substringBeforeLast('/',"");refresh()}}){Text("상위 폴더")}
            TextButton(enabled=!working,onClick={execute {refresh()}}){Text("새로고침")}
        }}
        items(files,key={it.path}) {file->ZipRow(title=file.path.substringAfterLast('/')+(file.bytes?.let {" · $it B"}?:""),
            modifier=Modifier.clip(RoundedCornerShape(ZipHapTokens.RadiusControl)).background(ZipHapColors.Surface),
            icon=if(file.directory)com.user.localagent.runtime.R.drawable.zi_folder else com.user.localagent.runtime.R.drawable.zi_file,iconTone=if(file.directory)ZipTone.Sun else ZipTone.Cobalt,
            showChevron=file.directory,enabled=!working,onClick={execute {
            if(file.directory){directory=file.path;refresh()}else{
                val actual=workspace.read(file.path);ensureCurrent()
                path=file.path;text=actual;loadedPath=file.path;loadedHash=ProjectWorkspace.sha256(actual)
                onStatus("프로젝트 텍스트를 읽고 수정 기준 해시를 기록했습니다.")
            }
        }})}
        item {ZipSectionTitle("파일 작성·수정",Modifier.padding(start=4.dp,top=6.dp,bottom=4.dp))
            ZipOutlinedTextField(path,{if(it.length<=240)path=it},label={Text("프로젝트 안의 상대 경로 · 예: notes/plan.md")},modifier=Modifier.fillMaxWidth(),enabled=!working)
        }
        item {if(text.length>12_000){Text("이 파일은 편집 한도보다 커 읽기 전용으로 일부를 표시합니다.",style=MaterialTheme.typography.bodySmall);Text(text.take(12_000))}
            else ZipOutlinedTextField(text,{if(it.length<=12_000)text=it},label={Text("UTF-8 파일 내용")},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),enabled=!working)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipFilledButton(enabled=!working&&selection.writable&&path.isNotBlank()&&text.length<=12_000,onClick={
                val target=path;val content=text;val expected=if(path==loadedPath)loadedHash else null
                execute {
                workspace.write(target,content,expected)
                val actual=workspace.read(target);ensureCurrent();check(actual==content)
                loadedPath=target;loadedHash=ProjectWorkspace.sha256(actual);refresh()
                onStatus("프로젝트 파일 저장과 재읽기 일치를 확인했습니다.")
            }}){Text("파일 저장·검증")}
            ZipOutlinedButton(enabled=!working&&loadedPath!=null,onClick={val source=loadedPath;execute{ensureCurrent();onAsk("현재 프로젝트의 ${JsonPrimitive(source.orEmpty())} 파일을 읽고 개선할 점과 수정안을 제시해줘.")}}){Text("대화에서 작업")}
        }}
        item {Text("기존 파일은 읽을 때 확인한 해시가 일치해야 수정됩니다. 파일 저장은 코드를 실행하거나 앱을 설치하는 동작이 아닙니다.",style=MaterialTheme.typography.bodySmall)}
        if(working)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
    }
}
