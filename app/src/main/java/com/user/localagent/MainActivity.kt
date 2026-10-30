package com.user.localagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.saveable.rememberSaveable
import com.user.localagent.models.ChosenInference
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.AssistantPolicy
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.CapabilityGrant
import com.user.localagent.core.CapabilityPolicy
import com.user.localagent.data.GrantRecord
import com.user.localagent.benchmark.BenchmarkRunner
import com.user.localagent.automation.ReflectionController
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID
import com.user.localagent.ui.*

private val Background = ZipHapColors.DeepNavy
private val Panel = ZipHapColors.NavySurface
private val Accent = ZipHapColors.Periwinkle
private val Mint = ZipHapColors.Mint
data class ChatLine(val id:String = UUID.randomUUID().toString(), val role:String, val text:String)
data class PendingAction(val call:ToolCall, val decision:CompletableDeferred<Boolean>,val remember:MutableStateFlow<Boolean> = MutableStateFlow(false))

class MainActivity : ComponentActivity() {
    private val app by lazy { (application as LocalAgentApplication).container }
    private val lines = MutableStateFlow<List<ChatLine>>(emptyList())
    private val status = MutableStateFlow("기기 안에서 실행")
    private val busy = MutableStateFlow(false)
    private val approval = MutableStateFlow<PendingAction?>(null)
    private var requestJob: Job? = null
    private val modelOptions=MutableStateFlow(InferenceOptions())
    private val preferredModelId=MutableStateFlow("auto")
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { status.value = "권한 설정을 반영했습니다." }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) lifecycleScope.launch {
            runCatching { app.models.importModel(uri, "가져온 로컬 모델") }
                .onSuccess { status.value = "모델을 가져왔습니다. 원본 SHA-256을 등록해 검증해주세요." }
                .onFailure { status.value = it.message ?: "모델 가져오기 실패" }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false}
        preferredModelId.value=getSharedPreferences("chat-preferences",MODE_PRIVATE).getString("model","auto")?:"auto"
        lifecycleScope.launch { runCatching { app.models.refresh() } }
        lifecycleScope.launch {runCatching {val saved=app.repository.messages.first().sortedBy {it.createdAt}.takeLast(60);if(saved.isNotEmpty())lines.value=saved.map {ChatLine(it.id,it.role,it.text)}}}
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){if(!busy.value && !RuntimeGate.benchmark.get())app.updates.checkAndApply();delay(60_000)}}}
        setContent { ZipHapTheme { AppScreen() } }
    }
    override fun onStart(){super.onStart();RuntimeGate.foreground.set(true)}
    override fun onStop(){RuntimeGate.foreground.set(false);app.scheduleIdleUnload();super.onStop()}

    private fun send(text:String):Boolean {
        if(text.isBlank())return false
        if(busy.value){status.value="답변을 만드는 중입니다. 중지한 뒤 다시 보낼 수 있습니다.";return false}
        if(RuntimeGate.benchmark.get()){status.value="성능 측정이 진행 중입니다. 측정이 끝난 뒤 보내주세요.";return false}
        if(text.length>6000){status.value="긴 문서는 문서 화면에서 일부를 선택해 분석해주세요.";return false}
        if(!RuntimeGate.inference.compareAndSet(false,true)){status.value="모델 작업을 정리 중입니다. 입력한 내용은 유지됩니다.";return false}
        busy.value=true
        requestJob=lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) {
            app.cancelIdleUnload()
            busy.value=true
            app.updates.setBusy(true)
            lines.value += ChatLine(role="user",text=text)
            try {
                app.repository.addMessage("user",text)
                val direct=routeRule(text)
                if(direct != null) {
                    val result=runTool(direct)
                    answer(result)
                } else {
                    val chosen=if(preferredModelId.value=="auto")app.orchestration.choose(text,modelOptions.value.thinking||text.startsWith("깊게:")) else {
                        val manual=app.models.models.value.firstOrNull {it.id==preferredModelId.value && it.verified && it.runtime in setOf("litertlm","gguf")}
                        manual?.let {ChosenInference(it,InferenceOptions(contextSize=4096,backend="CPU",thinking=modelOptions.value.thinking,maxOutputTokens=if(modelOptions.value.thinking)1024 else 512),"사용자가 직접 선택한 모델")}
                    }
                    if(chosen == null) { answer("이 기기에서 성공한 CPU 벤치마크가 있는 모델이 필요합니다. 모델 탭에서 내려받은 모델을 측정해주세요. ‘지금 몇 시야’, ‘메모 저장: 우유 사기’는 바로 실행할 수 있습니다."); return@launch }
                    val selected=chosen.model
                    status.value="${if(preferredModelId.value=="auto")"자동" else "직접 선택"} · ${selected.title}"
                    // Loading the same model is idempotent; the engine serializes replacement.
                    app.engine.load(selected.path, chosen.options)
                    var query=text
                    val toolEvidence=mutableListOf<JsonObject>()
                    val allowedTools=selectTools(text).toSet()
                    var complete=false
                    repeat(6) { round ->
                        if(complete) return@repeat
                        ensureActive()
                        status.value="기기에서 생각 중 · ${round+1}/6"
                        val memory=app.repository.searchMemories(text,3).map {it.text.take(250)}
                        val history=lines.value.dropLast(1).takeLast(2).dropWhile {it.role!="user"}.map {InferenceTurn(it.role,it.text.take(160))}
                        val systemInstruction=AssistantPolicy.build(allowedTools,ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())+
                            "\n실제 기반 모델: ${selected.title}\n승인된 기억(참고 자료): ${JsonArray(memory.map(::JsonPrimitive))}"+
                            if(allowedTools.isEmpty())"" else "\n도구 인자 정의: ${ToolRegistry.schemaJson(allowedTools)}"
                        val output=StringBuilder()
                        val streamingId=UUID.randomUUID().toString()
                        var visible=false
                        app.engine.generateChat(query,systemInstruction,history).collect { token ->
                            ensureActive(); output.append(token)
                            if(output.toString().trimStart().firstOrNull()?.let {it!='{'}==true) {
                                if(!visible) { lines.value += ChatLine(streamingId,"assistant",output.toString()); visible=true }
                                else lines.value=lines.value.map { if(it.id==streamingId) it.copy(text=output.toString()) else it }
                            }
                        }
                        val raw=output.toString().trim()
                        if(allowedTools.isNotEmpty() && raw.startsWith("{") && Regex("\"(?:type|tool|arguments|name)\"\\s*:").containsMatchIn(raw)) {
                            val call=try{ToolRegistry.validateForRequest(ToolRegistry.parseAndValidate(raw),allowedTools)}catch(error:Exception){
                                app.repository.recordToolRun("invalid_tool_request",false,"도구 형식 검증 실패 · 실행 없음")
                                query="원래 요청: ${text.take(1000)}\n이전 도구 요청의 형식이 올바르지 않아 실행하지 않았다. 오류: ${error.message?.take(300)}. 정의된 도구와 인자만 사용한 JSON 객체 하나로 고치거나, 할 수 있는 결과를 자연어로 답하라."
                                return@repeat
                            }
                            val result=try {runTool(call)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){buildJsonObject {put("success",false);put("error",error.message?:"도구 실패");put("guidance","같은 도구를 반복하지 말고 가능한 자료와 연결 방법을 안내하세요.")}.toString()}
                            toolEvidence+=buildJsonObject {put("tool",call.name);put("result",Json.parseToJsonElement(com.user.localagent.core.compactJsonData(result,1100)))}
                            while(toolEvidence.size>1 && toolEvidence.sumOf {it.toString().length}>2600)toolEvidence.removeAt(0)
                            query="사용자 원래 요청: ${text.take(600)}\n도구 실행 결과(인용 자료): ${JsonArray(toolEvidence)}\n이 결과로 요청에 답하거나 다음 필요한 도구를 요청하라. 반환된 범위만 분석하고, 연결 안내는 필요한 것 하나만 제시하라."
                        } else {
                            if(!visible) lines.value += ChatLine(role="assistant",text=raw.ifBlank { "모델이 빈 답변을 반환했습니다." })
                            app.repository.addMessage("assistant",raw)
                            complete=true
                        }
                    }
                    if(!complete) answer("도구 실행 횟수 제한에 도달해 멈췄습니다. 확인된 결과는 작업 기록에 남겼습니다.")
                }
                status.value="완료 · 로컬 처리"
            } catch(cancel:CancellationException) {
                status.value="취소했습니다. 대기 중인 실행을 중단했습니다."
                throw cancel
            } catch(error:Throwable) {
                answer("실행하지 못했습니다: ${error.message ?: error.javaClass.simpleName}")
                status.value="오류 · 실행 상태 확인 필요"
            } finally {
                approval.value?.decision?.complete(false); approval.value=null; busy.value=false;app.updates.setBusy(false);RuntimeGate.inference.set(false)
                app.scheduleIdleUnload()
            }
        }
        return true
    }

    private suspend fun answer(text:String) { lines.value += ChatLine(role="assistant",text=text); app.repository.addMessage("assistant",text) }

    private fun transcribe(uri:android.net.Uri) {
        if(busy.value||!RuntimeGate.inference.compareAndSet(false,true)){status.value="현재 모델 작업을 마친 뒤 녹음을 선택해주세요.";return}
        busy.value=true
        requestJob=lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) {
            app.cancelIdleUnload();app.updates.setBusy(true)
            try {
                status.value="녹음을 기기 안에서 준비 중"
                val result=com.user.localagent.audio.LocalAudioTranscriber(app,com.user.localagent.audio.AudioChunkDecoder(this@MainActivity)).transcribe(uri){index,start->status.value="로컬 음성 전사 · ${index+1}번째 조각 · ${start/1000}초"}
                answer("선택한 녹음 ${result.durationMillis/1000}초를 ${result.chunkCount}개 조각으로 전사해 기기에 저장했습니다. ‘녹음 전사 요약해줘’로 분석할 수 있습니다. 외부 서버로 전송하지 않았습니다.")
                status.value="녹음 전사 완료"
            }catch(cancel:CancellationException){status.value="전사를 중단했습니다. 이번 작업의 미완성 자료를 정리했습니다.";throw cancel}
            catch(error:Exception){answer("녹음 전사를 완료하지 못했습니다: ${error.message}");status.value="전사 오류"}
            finally {busy.value=false;app.updates.setBusy(false);RuntimeGate.inference.set(false);app.scheduleIdleUnload()}
        }
    }

    private fun chooseModel(id:String) {
        preferredModelId.value=id
        getSharedPreferences("chat-preferences",MODE_PRIVATE).edit().putString("model",id).apply()
    }
    private fun actionSummary(call:ToolCall):String {
        if(call.name!="reply_messenger_notification")return call.arguments.toString()
        val target=com.user.localagent.integrations.MessengerInbox.messages.value.firstOrNull {it.id==call.arguments["id"]?.jsonPrimitive?.content}
        val label=target?.let {com.user.localagent.integrations.MessengerInbox.supported[it.packageName]}?:"만료된 알림"
        return "$label\n대화: ${target?.conversation?:target?.title.orEmpty()}\n보낸 사람: ${target?.sender.orEmpty()}\n\n${call.arguments["message"]?.jsonPrimitive?.content.orEmpty()}\n\n확인하면 이 대화에 답장 전송을 요청합니다."
    }
    private fun routeRule(text:String):ToolCall? {
        val trimmed=text.trim()
        fun call(name:String,args:JsonObject)=ToolCall(UUID.randomUUID().toString(),name,args)
        if(trimmed.matches(Regex("^(지금\\s*)?(몇\\s*시(야|인가요|예요|죠)?|현재\\s*시간)[?.]?$")) || trimmed=="오늘 날짜") return call("get_current_datetime",buildJsonObject {})
        if(trimmed.startsWith("메모 저장:")) return call("save_note",buildJsonObject {put("content",trimmed.substringAfter(':').trim());put("scope","default")})
        if(trimmed.startsWith("메모 검색:")) return call("search_notes",buildJsonObject {put("query",trimmed.substringAfter(':').trim())})
        if(trimmed.startsWith("기억해:")) return call("save_memory",buildJsonObject {put("content",trimmed.substringAfter(':').trim())})
        if(trimmed.startsWith("교정:")) return call("save_memory",buildJsonObject {put("content",trimmed.substringAfter(':').trim());put("kind","correction")})
        return null
    }
    private fun selectTools(text:String):List<String> {
        if(text.startsWith("선택한 문서"))return emptyList()
        val tools=linkedSetOf<String>()
        fun has(vararg words:String)=words.any {text.contains(it)}
        if(has("메디통","카카오","카톡","텔레그램","메신저","메시지","대화 자료")){
            tools.addAll(listOf("list_imported_conversations","search_conversation_imports","list_messenger_notifications"))
            if(has("답장","회신","보내"))tools.add("reply_messenger_notification")
        }
        if(has("문자") || (has("모든","전체")&&has("메시지")))tools.add("get_sms_summary")
        if(has("통화","전화내역","전화 내역"))tools.add("get_call_log_summary")
        if(has("녹음","음성 파일","전사"))tools.addAll(listOf("list_audio_recordings","search_audio_transcripts"))
        if(has("일정","회의"))tools.addAll(listOf("get_current_datetime","list_calendar_events","prepare_calendar_event","create_reminder"))
        if(has("메모"))tools.addAll(listOf("save_note","search_notes"))
        if(has("기억","잊어"))tools.addAll(listOf("save_memory","search_memories","delete_memory"))
        if(has("문자"))tools.addAll(listOf("search_contacts","prepare_sms"))
        if(has("메일","이메일"))tools.add("prepare_email")
        if(has("지도","길찾기"))tools.add("open_map")
        if(has("알람","리마인더","알려줘"))tools.addAll(listOf("get_current_datetime","create_reminder","create_alarm"))
        if(has("웹","http","주소 열기"))tools.add("open_url")
        return tools.take(10)
    }
    private suspend fun runTool(call:ToolCall):String {
        ToolRegistry.validate(call)
        val grants=app.repository.listGrants().map {CapabilityGrant(it.extensionId,it.codeHash,it.toolName,it.scope)}
        val codeHash=withContext(Dispatchers.IO){app.hostCodeHash}
        val confirmation=CapabilityPolicy.requiresConfirmation(call,"builtin",codeHash,grants)||call.name in setOf("open_map","open_url")
        if(confirmation) {
            val deferred=CompletableDeferred<Boolean>()
            val action=PendingAction(call,deferred);approval.value=action
            val allowed=try { deferred.await() } finally { approval.value=null }
            if(!allowed) throw CancellationException("사용자가 실행을 취소했습니다.")
            if(action.remember.value && CapabilityPolicy.canPreapprove(call.name))app.repository.addGrant(GrantRecord("builtin",codeHash,call.name,call.arguments["scope"]?.jsonPrimitive?.content?:"default"))
        }
        currentCoroutineContext().ensureActive()
        val intent=app.tools.prepareIntent(call)
        if(intent != null) {
            withContext(Dispatchers.Main) { startActivity(intent) }
            app.repository.recordToolRun(call.name,true,"작성·확인 화면 열림");app.automation.recordOutcome(call.name,true)
            return "관련 앱의 작성·확인 화면을 열었습니다. 실제 저장·발송 완료는 확인되지 않았습니다."
        }
        val result=withTimeout(30_000) { app.tools.execute(call) }
        app.repository.recordToolRun(call.name,result.success,if(result.success) "성공" else "실패")
        app.automation.recordOutcome(call.name,result.success)
        if(!result.success) throw IllegalStateException(result.content)
        return result.content
    }

    @Composable private fun AppScreen() {
        var tab by rememberSaveable {mutableIntStateOf(0)}
        val state by status.collectAsState()
        val pending by approval.collectAsState()
        val working by busy.collectAsState()
        val messages by lines.collectAsState()
        val installed by app.models.models.collectAsState()
        val chosenId by preferredModelId.collectAsState()
        Scaffold(containerColor=Background,contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)) {padding->
            if(tab==0)ChatWorkspace(messages,working,state,installed,chosenId,::chooseModel,::send,
                {app.engine.cancel();requestJob?.cancel();approval.value?.decision?.complete(false)},
                {index->if(index==-1){lines.value=emptyList();status.value="새 대화";tab=0}else tab=index.coerceIn(0,5)},
                Modifier.fillMaxSize().padding(padding))
            else Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(horizontal=18.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={tab=0}){Text("‹ 대화")}
                    Text(listOf("","모델 관리","기억과 작업","문서 작업실","설정","자료 연결과 분석")[tab],style=MaterialTheme.typography.titleMedium)
                }
                Text(state,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
                when(tab){1->ModelsScreen();2->MemoryScreen();3->Box(Modifier.weight(1f)){DocumentSkillsScreen(app.skills,{status.value=it}){request->tab=0;send(request)}};4->ExtensionsScreen();5->Box(Modifier.weight(1f)){DataAnalysisPanel(app,{request->tab=0;send(request)},{uri->tab=0;transcribe(uri)})}}
            }
        }
        pending?.let { action -> val remember by action.remember.collectAsState();AlertDialog(onDismissRequest={action.decision.complete(false)},title={Text("실행 내용을 확인해주세요")},text={Column {Text(action.call.name);Text(actionSummary(action.call),style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(8.dp));Text("승인한 이 작업만 실행합니다.");if(CapabilityPolicy.canPreapprove(action.call.name))Row {Checkbox(remember,onCheckedChange={action.remember.value=it});Text("같은 범위의 반복 생성·수정을 허용")}}},confirmButton={TextButton(onClick={action.decision.complete(true)}){Text("실행")}},dismissButton={TextButton(onClick={action.decision.complete(false)}){Text("취소")}}) }
    }
    @Composable private fun ColumnScope.ModelsScreen() {
        val installed by app.models.models.collectAsState()
        val selected by app.models.selectedModel.collectAsState()
        val progress by app.models.progress.collectAsState()
        val scope=rememberCoroutineScope()
        val options by modelOptions.collectAsState()
        var endurance by remember { mutableStateOf(false) }
        var downloadJob by remember {mutableStateOf<Job?>(null)}
        var hashTarget by remember {mutableStateOf<String?>(null)}
        var expectedHash by remember {mutableStateOf("")}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { InfoCard("한 번에 하나의 모델","CPU를 기준으로 실측합니다. 모델 파일은 APK에 포함하지 않으며 Wi-Fi에서 별도로 내려받습니다.") }
            item {var routing by remember {mutableStateOf("실측 결과를 확인 중")};LaunchedEffect(installed){routing=app.orchestration.summary()};InfoCard("자동 오케스트레이션",routing)}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=options.backend=="CPU",onClick={modelOptions.value=options.copy(backend="CPU")},label={Text("CPU")});FilterChip(selected=options.backend=="GPU",onClick={modelOptions.value=options.copy(backend="GPU")},label={Text("GPU 실험")});FilterChip(selected=options.thinking,onClick={modelOptions.value=options.copy(thinking=!options.thinking,maxOutputTokens=if(options.thinking)512 else 1024)},label={Text("깊게 생각")})}}
            item {Row {FilterChip(selected=options.contextSize==4096,onClick={modelOptions.value=options.copy(contextSize=4096)},label={Text("4K 문맥")});Spacer(Modifier.width(8.dp));FilterChip(selected=options.contextSize==8192,onClick={modelOptions.value=options.copy(contextSize=8192)},label={Text("8K 문맥")})}}
            item {Row {Checkbox(endurance,onCheckedChange={endurance=it});Text("10분 연속 안정성 측정")}}
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={importModel.launch(arrayOf("application/octet-stream","*/*"))}) { Text("파일 가져오기") }
                OutlinedButton(onClick={scope.launch { app.engine.unload(); status.value="모델 메모리를 해제했습니다." }}) {Text("메모리 해제")}
            } }
            if(progress!=null) item {val p=progress!!;InfoCard(if(p.phase=="hash")"모델 무결성 검증" else "모델 다운로드","${p.title}\n${p.bytes/1024/1024} / ${p.total/1024/1024} MB");LinearProgressIndicator(progress={if(p.total>0)(p.bytes.toDouble()/p.total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());TextButton(onClick={downloadJob?.cancel()}){Text("중단 · 다음 다운로드에서 이어받기")}}
            items(app.models.candidates) { candidate -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(candidate.title,fontWeight=FontWeight.Bold)
                Text("공식 메타데이터에서 버전·해시를 확인한 뒤 다운로드",style=MaterialTheme.typography.bodySmall)
                Button(enabled=progress==null,onClick={downloadJob=scope.launch { status.value="모델 출처 확인 중";try{app.models.download(app.models.resolve(candidate));status.value="다운로드·해시 검증 완료"}catch(cancel:CancellationException){status.value="중단했습니다. 받은 부분은 이어받기에 사용합니다.";throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"} }}) {Text("다운로드")}
            } } }
            item {Text("설치한 모델",style=MaterialTheme.typography.titleMedium)}
            items(installed) { model -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) {Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(model.title,fontWeight=FontWeight.Bold)
                Text(if(selected?.id==model.id) "선택됨" else "선택 가능",color=Mint)
                if(!model.verified)TextButton(onClick={hashTarget=model.id;expectedHash=""}){Text("원본 SHA-256 등록")}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={scope.launch {app.engine.unload();app.models.select(model.id);chooseModel(model.id);status.value="선택한 모델을 다음 대화에 사용합니다."}}){Text("선택")}
                    TextButton(onClick={scope.launch {runBenchmark(model.path,if(endurance)10 else 0)}}){Text("벤치마크")}
                    TextButton(onClick={scope.launch {app.engine.unload();app.models.delete(model.id);status.value="모델을 삭제했습니다."}}){Text("삭제")}
                }
            } } }
            item {InfoCard("실측 전에는 성능을 단정하지 않습니다","Qwen은 llama.cpp CPU 엔진으로 실행합니다. 실제 속도와 정확도를 비교한 뒤 기본 모델을 결정합니다. 이미지 생성 엔진은 아직 연결되지 않았습니다.")}
        }
        hashTarget?.let {id->AlertDialog(onDismissRequest={hashTarget=null},title={Text("배포자의 SHA-256 확인")},text={Column{Text("내가 신뢰하는 원본 배포자가 제공한 64자리 해시를 입력하세요.");OutlinedTextField(value=expectedHash,onValueChange={expectedHash=it.trim()},label={Text("SHA-256")})}},confirmButton={TextButton(enabled=expectedHash.matches(Regex("[a-fA-F0-9]{64}")),onClick={scope.launch {runCatching {app.models.verifyImported(id,expectedHash)}.onSuccess{status.value="원본 해시가 일치합니다.";hashTarget=null}.onFailure{status.value=it.message?:"검증 실패"}}}){Text("파일 검증")}},dismissButton={TextButton(onClick={hashTarget=null}){Text("취소")}})}
    }
    private suspend fun runBenchmark(path:String,minutes:Int=0) {
        status.value="벤치마크 시작 · 다른 작업은 잠시 기다려주세요"
        try {
            app.updates.setBusy(true)
            val report=BenchmarkRunner(this,app.engine).run(path,modelOptions.value,minutes)
            status.value="측정 결과 저장: ${report.name}"
        } catch(cancel:CancellationException){throw cancel}catch(error:Throwable) {status.value="벤치마크 실패: ${error.message}"} finally {app.updates.setBusy(false)}
    }
    @Composable private fun ColumnScope.MemoryScreen() {
        val notes by app.repository.notes.collectAsState(emptyList())
        val memories by app.repository.memories.collectAsState(emptyList())
        val reminders by app.repository.reminders.collectAsState(emptyList())
        val scope=rememberCoroutineScope()
        var memory by remember { mutableStateOf("") }
        var deleteId by remember { mutableStateOf<String?>(null) }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {InfoCard("기억은 내가 선택합니다","명시적으로 저장한 정보만 장기 기억에 넣습니다. 삭제한 기억은 검색과 관련 원문·요약에서 제거합니다.")}
            item {OutlinedTextField(value=memory,onValueChange={memory=it},label={Text("기억할 내용")},modifier=Modifier.fillMaxWidth())}
            item {Button(onClick={val value=memory;memory="";scope.launch {app.repository.saveMemory(value);status.value="승인한 기억을 저장했습니다."}},enabled=memory.isNotBlank()){Text("이 내용을 기억하기")}}
            item {Text("장기 기억 ${memories.size}",style=MaterialTheme.typography.titleMedium)}
            items(memories) { record -> Surface(color=Panel,shape=RoundedCornerShape(14.dp)) {Column(Modifier.fillMaxWidth().padding(14.dp)) {if(record.kind=="proposed")Text("AI 제안 · 승인 전에는 대화에 사용하지 않음",color=Mint);Text(record.text);Row {if(record.kind=="proposed")TextButton(onClick={scope.launch {app.repository.editMemory(record.id,record.text,"preference");status.value="제안을 확인하고 기억으로 승인했습니다."}}){Text("기억으로 승인")};TextButton(onClick={deleteId=record.id}){Text("잊어줘")}}}} }
            item {Text("메모 ${notes.size}",style=MaterialTheme.typography.titleMedium)}
            items(notes) {InfoCard(it.title,it.body)}
            item {Text("리마인더 ${reminders.size}",style=MaterialTheme.typography.titleMedium)}
            items(reminders) {InfoCard(it.title,java.time.Instant.ofEpochMilli(it.atEpochMillis).atZone(ZoneId.of("Asia/Seoul")).toString())}
            item {OutlinedButton(onClick={permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.READ_CONTACTS,Manifest.permission.POST_NOTIFICATIONS))}) {Text("일정·연락처·알림 권한 설정")}}
        }
        deleteId?.let { id -> AlertDialog(onDismissRequest={deleteId=null},title={Text("이 기억을 잊을까요?")},text={Text("기억과 연결된 검색 정보·원문·요약을 기기에서 제거합니다.")},confirmButton={TextButton(onClick={scope.launch {app.repository.forgetMemory(id);lines.value=app.repository.messages.first().sortedBy {it.createdAt}.map {ChatLine(it.id,it.role,it.text)};deleteId=null;status.value="기억을 삭제했습니다."}}){Text("삭제")}},dismissButton={TextButton(onClick={deleteId=null}){Text("취소")}}) }
    }
    @Composable private fun ColumnScope.ExtensionsScreen() {
        var section by remember {mutableIntStateOf(0)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("자동화","앱 연결","패치·확장").forEachIndexed {index,label->FilterChip(section==index,onClick={section=index},label={Text(label)})}}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(section==0)item {AutomationPanel(this@MainActivity,app.automation,{startActivity(it)},{status.value=it},{app.repository.discardProposedMemories();Unit})}
            if(section==1)item {com.user.localagent.integrations.MessengerPanel(this@MainActivity){status.value=it}}
            if(section==1)item {com.user.localagent.integrations.ScreenBridgePanel(this@MainActivity,{startActivity(it)}){status.value=it}}
            if(section==2){
            item {com.user.localagent.updates.UpdatePanel(app.updates,{startActivity(it)},{status.value=it})}
            item {InfoCard("확장 보안 검사","해시와 고정된 Git 커밋을 검증하고 선언형 스킬·템플릿만 허용합니다. 실행 코드와 지원되지 않는 MCP 서버는 설치하지 않습니다. 설치 화면 연결과 앱별 어댑터는 개발 중입니다.")}
            item {val scope=rememberCoroutineScope();OutlinedButton(onClick={scope.launch {app.repository.revokeGrant("builtin");status.value="기본 도구의 반복 작업 허용을 해제했습니다."}}){Text("반복 작업 허용 초기화")}}
            }
            item {Text("버전 ${BuildConfig.VERSION_NAME} · ${Build.MODEL}",style=MaterialTheme.typography.bodySmall)}
        }
    }
    @Composable private fun InfoCard(title:String,body:String) {ZipHapCard {Text(title,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}}
}
