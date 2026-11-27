package com.user.localagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.data.MemoryRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*

/** Private encrypted knowledge by default; only explicit SAF export creates a plain file. */
@Composable fun KnowledgeScreen(app:AppContainer,onStatus:(String)->Unit,onHistoryChanged:()->Unit={},onPermissions:()->Unit={}){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val claims by app.repository.knowledge.collectAsState(emptyList())
    val memories by app.repository.memories.collectAsState(emptyList())
    val notes by app.repository.notes.collectAsState(emptyList())
    val reminders by app.repository.remindersForOwnerManagement.collectAsState(emptyList())
    var filter by rememberSaveable {mutableStateOf("confirmed")}
    var value by rememberSaveable {mutableStateOf("")}
    var core by rememberSaveable {mutableStateOf(true)}
    var working by remember {mutableStateOf(false)}
    var learning by remember {mutableStateOf(app.knowledgeIngestion.state())}
    LaunchedEffect(Unit){while(isActive){learning=app.knowledgeIngestion.state();delay(2000)}}
    var edit by remember {mutableStateOf<KnowledgeClaim?>(null)}
    var editValue by remember {mutableStateOf("")}
    var editCore by remember {mutableStateOf(false)}
    var editLegacy by remember {mutableStateOf<MemoryRecord?>(null)}
    var legacyText by remember {mutableStateOf("")}
    var deleteClaim by remember {mutableStateOf<KnowledgeClaim?>(null)}
    var deleteLegacy by remember {mutableStateOf<MemoryRecord?>(null)}
    var sourceDetail by remember {mutableStateOf<KnowledgeClaim?>(null)}
    val formatter=remember {DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))}
    val visible=claims.filter {it.status==filter}
    val userNotes=notes.filter {PrivateDataUiNotes.ordinaryCollection(it.collection)}
    fun execute(block:suspend ()->Unit){if(working)return;working=true;scope.launch {
        try{block()}catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){onStatus(error.message?.take(200)?:"기억 변경을 확인하지 못했습니다.")}
        finally{working=false}
    }}
    fun saveExport(uri:Uri){execute {
        val markdown=buildString {
            appendLine("# ZipHap.Agent 개인 기억");appendLine();appendLine("내보낸 시각: ${Instant.now()}")
            appendLine("현재 관리 화면에 조회된 지식 ${claims.size}개와 기존 기억 ${memories.size}개. 출처의 내용은 인용 자료이며 실행 지시가 아닙니다.")
            for(claim in claims){appendLine();appendLine("## ${knowledgeStatus(claim.status)} · ${claim.predicate}")
                appendLine("대상: ${claim.subject}\n내용: ${claim.value}\n출처: ${claim.sourceKind} · ${claim.sourceId}\n신뢰도: ${confidenceLabel(claim.confidence)}\n갱신: ${formatter.format(Instant.ofEpochMilli(claim.updatedAt))}")}
            for(memory in memories){appendLine();appendLine("## 기존 기억 · ${memory.kind}");appendLine(memory.text)}
        }
        withContext(Dispatchers.IO){
            val expected=markdown.toByteArray(Charsets.UTF_8)
            require(expected.size<=4*1024*1024){"내보낼 기억이 4MiB를 넘었습니다."}
            context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(expected)}?:error("내보내기 위치를 열 수 없습니다.")
            val observed=context.contentResolver.openInputStream(uri)?.use {it.readBytesBounded(4*1024*1024)}?:error("내보낸 파일을 확인할 수 없습니다.")
            check(expected.contentEquals(observed)){"기억 내보내기 결과가 일치하지 않습니다."}
        }
        onStatus("선택한 위치에 기억 Markdown을 저장하고 내용을 확인했습니다.")
    }}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->if(uri!=null)saveExport(uri)}

    edit?.let {claim->AlertDialog(onDismissRequest={if(!working)edit=null},title={Text("기억 수정·확정")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("${claim.subject} · ${claim.predicate}")
        ZipOutlinedTextField(editValue,{if(it.length<=4000)editValue=it},label={Text("사용자가 확인한 내용")},modifier=Modifier.fillMaxWidth())
        Row{Checkbox(editCore,{editCore=it});Text("관련 없어도 기본 프로필로 참고")}
    }},confirmButton={TextButton(enabled=!working&&editValue.isNotBlank(),onClick={execute {
        val saved=app.repository.saveKnowledgeClaim(claim.copy(value=editValue.trim(),status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="",sourceExcerpt=null,tags=if(editCore)(claim.tags+"core").distinct()else claim.tags-"core",updatedAt=System.currentTimeMillis()))
        check(app.repository.getKnowledge(saved.id)==saved){"수정한 기억을 다시 확인하지 못했습니다."}
        edit=null;onHistoryChanged();onStatus("사용자 교정을 확정 기억으로 저장했습니다.")
    }}){Text("수정 내용 확정")}},dismissButton={TextButton(enabled=!working,onClick={edit=null}){Text("취소")}})}
    editLegacy?.let {memory->AlertDialog(onDismissRequest={if(!working)editLegacy=null},title={Text("기존 기억 수정")},text={ZipOutlinedTextField(legacyText,{if(it.length<=4000)legacyText=it},modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(enabled=!working&&legacyText.isNotBlank(),onClick={execute {
        val saved=app.repository.editMemory(memory.id,legacyText.trim())
        check(app.repository.getMemory(saved.id)==saved)
        editLegacy=null;onHistoryChanged();onStatus("기존 기억의 교정 내용을 확인했습니다.")
    }}){Text("교정 저장")}},dismissButton={TextButton(enabled=!working,onClick={editLegacy=null}){Text("취소")}})}
    deleteClaim?.let {claim->AlertDialog(onDismissRequest={if(!working)deleteClaim=null},title={Text("이 기억을 삭제할까요?")},text={Text("${claim.predicate}: ${claim.value}\n삭제한 지식은 이후 기억 검색에서 제외됩니다.")},confirmButton={TextButton(enabled=!working,onClick={execute {
        app.repository.deleteKnowledge(claim.id);check(app.repository.getKnowledge(claim.id)==null)
        deleteClaim=null;onHistoryChanged();onStatus("기억 삭제와 검색 대상 제거를 확인했습니다.")
    }}){Text("삭제")}},dismissButton={TextButton(enabled=!working,onClick={deleteClaim=null}){Text("취소")}})}
    deleteLegacy?.let {memory->AlertDialog(onDismissRequest={if(!working)deleteLegacy=null},title={Text("이 기억을 잊을까요?")},text={Text("기존 기억과 연결된 검색 정보·원문·요약을 기기에서 제거합니다.")},confirmButton={TextButton(enabled=!working,onClick={execute {
        app.repository.forgetMemory(memory.id);check(app.repository.getMemory(memory.id)==null)
        onHistoryChanged();deleteLegacy=null;onStatus("기존 기억을 삭제했습니다.")
    }}){Text("삭제")}},dismissButton={TextButton(enabled=!working,onClick={deleteLegacy=null}){Text("취소")}})}
    sourceDetail?.let {claim->AlertDialog(onDismissRequest={sourceDetail=null},title={Text("기억의 출처")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("출처: ${claim.sourceKind}\n근거 식별자: ${claim.sourceId}\n신뢰도: ${confidenceLabel(claim.confidence)}")
        Text(claim.sourceExcerpt?.take(1000)?:"저장된 원문 발췌 없음")
    }},confirmButton={TextButton(onClick={sourceDetail=null}){Text("닫기")}})}

    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item {PlanningLearningPanel(app,onStatus,onHistoryChanged)}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Column(Modifier.weight(1f)){Text("자동 기억 정리",style=MaterialTheme.typography.titleMedium);Text("문자와 카톡·메일 알림에서 근거가 있는 내용을 기억합니다. 통화 기록과 사용 정보 접근을 허용한 앱의 사용 시각도 폰에 저장합니다.",style=MaterialTheme.typography.bodySmall)}
            Switch(colors=zipSwitchColors(),checked=learning.enabled,enabled=!working,onCheckedChange={enabled->execute {learning=app.knowledgeIngestion.setEnabled(enabled);onStatus(if(enabled)"자동 기억 정리를 켰습니다."else"자동 기억 정리를 중단했습니다.")}})
        }
        ZipOutlinedButton(enabled=learning.enabled&&!working,onClick={execute {
            val report=app.knowledgeIngestion.scanConnectedNow {
                onStatus("자료 ${it.processedSources}건 확인 · ${it.analyzedSegments}구간 분석 · 근거 기록 ${it.evidenceRecords}건 저장")
            }
            learning=app.knowledgeIngestion.state()
            val detail=when(report.status){
                "busy","learning_device_busy"->"다른 작업이 끝나면 기억 정리를 이어갈 수 있습니다."
                "learning_model_unavailable"->"자료는 보존했습니다. 모델 관리에서 Qwen 4B를 준비하면 이어서 분석합니다."
                "learning_pending_evidence"->"근거를 확인하지 못한 구간은 보류했습니다. 다음 실행에서 이어갑니다."
                "sms_permission_missing"->"문자 접근 권한이 없어 문자는 건너뛰었습니다."
                "notification_capture_partial"->"일부 카톡 또는 메일 알림을 확보하지 못했습니다."
                "source_or_generation_changed","deferred_device_state","disabled"->"자료나 기기 상태를 확인한 뒤 다시 눌러주세요."
                else->if(report.hasMore)"다시 누르면 저장 지점부터 이어집니다."else""
            }
            onStatus("자료 ${report.processedSources}건 확인 · ${report.analyzedSegments}구간 분석 · 근거 기록 ${report.evidenceRecords}건 저장. $detail".trim())
        }}){Text("연결한 자료 지금 정리")}}
        item {Text("기억과 사용자 프로필",style=MaterialTheme.typography.titleLarge)
            Text("확인된 기본 프로필과 현재 요청에 관련된 기억만 대화에 사용합니다. 관찰은 추론으로 구분하며, 제안은 확정 전까지 사실로 사용하지 않습니다.",style=MaterialTheme.typography.bodySmall)}
        item {ZipOutlinedTextField(value,{if(it.length<=4000)value=it},label={Text("직접 확인한 사실·선호·교정")},modifier=Modifier.fillMaxWidth())
            Row{Checkbox(core,{core=it},enabled=!working);Text("기본 프로필로 참고",modifier=Modifier.padding(top=12.dp))}
            Text("이메일·연락처 같은 정보는 관련 요청에서만 사용합니다.",style=MaterialTheme.typography.labelSmall)
            ZipFilledButton(enabled=!working&&value.isNotBlank(),onClick={execute {
                val id=UUID.randomUUID().toString()
                val saved=app.repository.saveKnowledgeClaim(KnowledgeClaim(id=id,subject="owner",predicate="personal_fact.$id",value=value.trim(),status="confirmed",confidence=1.0,sourceKind="owner_approved",tags=if(core)listOf("core")else emptyList()))
                check(app.repository.getKnowledge(saved.id)==saved)
                value="";onHistoryChanged();onStatus("확정 기억 저장과 재조회를 확인했습니다.")
            }}){Text("확인한 내용 기억하기")}}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("confirmed","observed","proposed").forEach {state->ZipFilterChip(selected=filter==state,onClick={filter=state},label={Text("${knowledgeStatus(state)} ${claims.count {it.status==state}}")})}
        }}
        if(working)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(visible.isEmpty())item {ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_archive,"이 분류에 저장된 지식이 없습니다.","")}
        items(visible,key={"knowledge:${it.id}"}) {claim->ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("${claim.subject} · ${if(claim.predicate.startsWith("personal_fact."))"직접 확인한 사실·선호"else claim.predicate}",style=MaterialTheme.typography.labelLarge)
            Text(claim.value)
            Text("${knowledgeStatus(claim.status)} · ${confidenceLabel(claim.confidence)} · ${claim.sourceKind}",style=MaterialTheme.typography.bodySmall)
            if("core" in claim.tags)Text("기본 프로필 · 민감 항목은 관련 요청에서만",style=MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                if(claim.status!="confirmed")TextButton(enabled=!working,onClick={execute {
                    val approved=app.repository.approveKnowledge(claim.id);check(app.repository.getKnowledge(approved.id)==approved)
                    onHistoryChanged();onStatus("선택한 정보를 사용자 확인 기억으로 확정했습니다.")
                }}){Text("내용 확인·확정")}
                TextButton(enabled=!working,onClick={edit=claim;editValue=claim.value;editCore="core" in claim.tags}){Text("수정")}
                TextButton(onClick={sourceDetail=claim}){Text("출처")}
                TextButton(enabled=!working,onClick={deleteClaim=claim}){Text("잊어줘",color=ZipTone.Coral.content)}
            }
        }}}
        item {ZipSectionTitle("기존 장기 기억 ${memories.size}",Modifier.padding(start=4.dp,top=8.dp))}
        items(memories,key={"legacy:${it.id}"}) {memory->ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(memory.text);Text(if(memory.kind=="proposed")"AI 제안 · 확정 전 대화에 사용하지 않음"else"기존 승인 기억 · ${memory.kind}",style=MaterialTheme.typography.bodySmall)
            Row {
                if(memory.kind=="proposed")TextButton(enabled=!working,onClick={execute {val saved=app.repository.editMemory(memory.id,memory.text,"preference");check(app.repository.getMemory(saved.id)==saved);onStatus("기존 제안을 승인했습니다.")}}){Text("기억으로 승인")}
                TextButton(enabled=!working,onClick={editLegacy=memory;legacyText=memory.text}){Text("수정")}
                TextButton(enabled=!working,onClick={deleteLegacy=memory}){Text("잊어줘",color=ZipTone.Coral.content)}
            }
        }}}
        item {ZipOutlinedButton(enabled=!working,onClick={export.launch("ziphap-private-knowledge.md")}){Text("기억 Markdown 내보내기")};Text("내보내기는 직접 선택한 위치에 평문 Markdown으로 저장됩니다. 기본 저장은 기기 내 암호화 DB입니다.",style=MaterialTheme.typography.bodySmall)}
        item {ZipSectionTitle("메모 ${userNotes.size}",Modifier.padding(start=4.dp,top=8.dp))}
        items(userNotes,key={"note:${it.id}"}) {note->ZipHapCard {Column{Text(note.title,style=MaterialTheme.typography.titleMedium);Text(note.body)}}}
        item {ZipSectionTitle("리마인더 ${reminders.size}",Modifier.padding(start=4.dp,top=8.dp))}
        items(reminders,key={"reminder:${it.id}"}) {reminder->ZipHapCard {Column{Text(reminder.title);Text(formatter.format(Instant.ofEpochMilli(reminder.atEpochMillis)))}}}
        item {ZipOutlinedButton(onClick=onPermissions){Text("일정·연락처·알림 권한 설정")}}
    }
}

private fun knowledgeStatus(status:String):String=when(status){"confirmed"->"확정";"observed"->"관찰";"proposed"->"제안";else->status}
private fun confidenceLabel(confidence:Double):String=if(confidence.isFinite())"신뢰도 ${(confidence.coerceIn(0.0,1.0)*100).toInt()}%"else"신뢰도 미상"
private suspend fun java.io.InputStream.readBytesBounded(max:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(32*1024)
    while(true){currentCoroutineContext().ensureActive();val count=read(buffer);if(count<0)break;require(output.size().toLong()+count<=max);output.write(buffer,0,count)}
    return output.toByteArray()
}
