package com.user.localagent

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.saveable.rememberSaveable
import com.user.localagent.models.ChosenInference
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import com.user.localagent.core.IntentPlanning
import com.user.localagent.core.IntentCapabilities
import com.user.localagent.core.IntentSource
import com.user.localagent.core.SourceReadiness
import com.user.localagent.core.ToolCallSchema
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.CapabilityGrant
import com.user.localagent.core.CapabilityPolicy
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.core.office.ZipHapRoom
import com.user.localagent.core.StepEvidence
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.TaskHarness
import com.user.localagent.core.HarnessDecision
import com.user.localagent.data.GrantRecord
import com.user.localagent.benchmark.BenchmarkRunner
import com.user.localagent.automation.ReflectionController
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID
import com.user.localagent.ui.*

private val launchIntroClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

private val Background = ZipHapColors.DeepNavy
private val Panel = ZipHapColors.NavySurface
private val Accent = ZipHapColors.Periwinkle
private val Mint = ZipHapColors.Mint
data class ChatLine(val id:String = UUID.randomUUID().toString(), val role:String, val text:String,val requestText:String?=null)
data class PendingAction(val call:ToolCall, val decision:CompletableDeferred<Boolean>,val remember:MutableStateFlow<Boolean> = MutableStateFlow(false),val runId:String="",val threadId:String="",val projectId:String?=null,val reviewText:String?=null,
    val recipientChoices:List<com.user.localagent.core.ContactCandidate> =emptyList(),val selectedRecipient:MutableStateFlow<String?> = MutableStateFlow(null))

class MainActivity : ComponentActivity() {
    private val app by lazy { (application as LocalAgentApplication).container }
    private val lines get()=app.runs.visibleLines
    private val status get()=app.runs.status
    private val busy get()=app.runs.busy
    private val approval get()=app.runs.approval

    private var attachmentJob:Job?=null
    private var attachmentJobThreadId:String?=null
    private var attachmentScope:Triple<String,Long,String>?=null
    private val attachmentPreparing=MutableStateFlow(false)

    private val modelOptions get()=app.runs.modelOptions
    private val preferredModelId get()=app.runs.preferredModelId
    private val requestedTab=MutableStateFlow<Int?>(null)
    private var folderProjectId:String?=null
    private val projectFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        val projectId=folderProjectId
        folderProjectId=null
        if(uri!=null&&projectId!=null)lifecycleScope.launch {
            try {
                check(app.repository.chat.project(projectId)?.archived==false){"프로젝트를 다시 확인해주세요."}
                app.workspaceFor(projectId).select(uri)
                status.value="선택한 프로젝트의 폴더를 연결했습니다. 작성 중인 설정은 유지됩니다."
            }catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){status.value=error.message?:"프로젝트를 연결하지 못했습니다."}
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result->
        val actual=result.keys.associateWith{androidx.core.content.ContextCompat.checkSelfPermission(this,it)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        status.value=permissionResultMessage(actual)
    }
    private val attachFiles=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val origin=attachmentScope
        attachmentScope=null
        if(uris.isNotEmpty()&&!attachmentPreparing.value){attachmentJobThreadId=origin?.first;attachmentPreparing.value=true;attachmentJob=lifecycleScope.launch {
            try{
                check(origin!=null){"첨부할 원래 대화를 확인하지 못했습니다. 해당 방에서 다시 선택해주세요."}
                val room=app.repository.chat.thread(origin.first)
                check(room!=null&&room.revision==origin.second&&room.activeBranchId==origin.third){"자료를 선택하는 동안 원래 대화가 변경되었습니다. 다시 선택해주세요."}
                status.value="첨부 파일을 기기에 준비 중";app.chatAttachments.add(uris,origin.first);status.value="원래 대화에 파일을 첨부했습니다. 해당 방에서 요청을 입력해주세요."
            }
            catch(cancel:CancellationException){status.value="파일 첨부를 취소했어요.";throw cancel}
            catch(error:Exception){status.value=error.message?:"첨부 파일을 준비하지 못했습니다."}
            finally{attachmentJob=null;attachmentJobThreadId=null;attachmentPreparing.value=false}
        }}
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) lifecycleScope.launch {
            runCatching { app.models.importModel(uri, "가져온 로컬 모델") }
                .onSuccess { status.value = "파일을 가져왔어요. 배포처의 확인 코드를 입력해 주세요." }
                .onFailure { status.value = it.message ?: "모델 가져오기 실패" }
        }
    }

    private var launchIntroPending by mutableStateOf(false)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        folderProjectId=savedInstanceState?.getString("folder-project-id")
        savedInstanceState?.let {saved->saved.getString("attachment-thread-id")?.let {id->saved.getString("attachment-branch-id")?.let {branch->attachmentScope=Triple(id,saved.getLong("attachment-thread-revision"),branch)}}}
        if(intent.hasExtra("open_tab"))requestedTab.value=intent.getIntExtra("open_tab",0).coerceIn(0,10)
        launchIntroPending = savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) && requestedTab.value == 0 &&
            launchIntroClaimed.compareAndSet(false,true)
        val lightBars=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(lightBars,lightBars)
        WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
        lifecycleScope.launch { runCatching { app.models.refresh() } }
        app.runs.refreshVisibleHistory()
        openCallReport(intent)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){if(!app.runs.hasActiveRuns.value && !RuntimeGate.benchmark.get()&&!app.smsAnalysis.state.value.running&&!app.webResearch.state.value.running&&!app.imageStudio.state.value.running)app.updates.checkAndApply();delay(60_000)}}}
        setContent { ZipHapTheme { AppScreen() } }
    }
    override fun onStart(){super.onStart();RuntimeGate.foreground.set(true)}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putString("folder-project-id",folderProjectId)
        attachmentScope?.let {(id,revision,branch)->outState.putString("attachment-thread-id",id);outState.putLong("attachment-thread-revision",revision);outState.putString("attachment-branch-id",branch)}
        super.onSaveInstanceState(outState)
    }
    override fun onStop(){app.phoneMedia.cancelListening();RuntimeGate.foreground.set(false);app.scheduleIdleUnload();super.onStop()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.hasExtra("open_tab"))requestedTab.value=intent.getIntExtra("open_tab",0).coerceIn(0,10);openCallReport(intent)}

    private fun openCallReport(intent:Intent) {
        val threadId=intent.getStringExtra("open_thread_id")?:return
        if(threadId.length !in 1..160)return
        lifecycleScope.launch {
            app.runs.selectionReady.first {it}
            val thread=app.repository.chat.thread(threadId)?:return@launch
            if(thread.archived)return@launch
            val project=thread.projectId?.let {app.repository.chat.project(it)}
            if(project?.archived==true)return@launch
            app.runs.selectThread(threadId);requestedTab.value=0
        }
    }

    private fun send(text:String):Boolean {if(attachmentPreparing.value){status.value="첨부 준비가 끝난 뒤 보내주세요.";return false};return app.runs.send(text)}
    private fun sendToThread(text:String,threadId:String):Boolean {if(attachmentPreparing.value){status.value="첨부 준비가 끝난 뒤 보내주세요.";return false};return app.runs.send(text,threadId)}
    private fun chooseModel(id:String)=app.runs.chooseModel(id)
    private val externalAgentConsentThread=mutableStateOf<Pair<String,String>?>(null)
    private fun chooseConversationModel(id:String,threadId:String){
        if(id==com.user.localagent.externalagent.ExternalAgentSettings.DISABLE_ID){
            com.user.localagent.externalagent.ExternalAgentSettings.get(this,app.runs.preferredModelId.value).setEnabled(false)
            app.runs.chooseModel("auto",threadId);status.value="클라우드 도구 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다.";return
        }
        // Preference only: the coordinator retains the model/authority captured by active requests.
        if(!app.runs.selectionReady.value||app.runs.selectedThreadId.value!=threadId){
            status.value="선택한 대화를 불러온 뒤 모델을 바꿔주세요.";return
        }
        if(com.user.localagent.externalagent.ExternalAgentSettings.isCloud(id)){
            if(!com.user.localagent.compute.PcComputeSettings.get(this).state.value.paired){status.value="설정에서 개인 PC를 먼저 연결해주세요.";return}
            if(!com.user.localagent.externalagent.ExternalAgentSettings.get(this,id).capture().allowsPersonalData){externalAgentConsentThread.value=threadId to id;return}
            app.runs.chooseModel(id,threadId);status.value="다음 요청에서 클라우드 모델이 승인된 휴대폰 도구를 사용합니다.";return
        }
        if(id!="auto" && id!=com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION){status.value="일반 업무는 Codex, 개인 자료는 개인정보 · 로컬을 선택해주세요.";return}
        // A preference change is synchronous. The next serialized request owns runtime loading.
        app.runs.chooseModel(id,threadId)
        status.value="이 대화의 다음 요청에 선택한 모델을 사용합니다."
    }
    private fun transcribe(uri:android.net.Uri,origin:com.user.localagent.audio.PrivateDataPickerOrigin)=app.runs.transcribe(uri,origin)
    private fun refreshVisibleHistory()=app.runs.refreshVisibleHistory()
    private fun invalidateKnowledgeContext()=app.runs.invalidateKnowledgeContext()
    private fun actionSummary(action:ToolCall)=app.runs.actionSummary(action)
    @Composable private fun AppScreen() {
        externalAgentConsentThread.value?.let {(threadId,modelId)->AlertDialog(
            onDismissRequest={externalAgentConsentThread.value=null},title={Text("클라우드 도구 연결")},
            text={Text(com.user.localagent.externalagent.ExternalAgentSettings.consentText(modelId))},
            confirmButton={TextButton(onClick={
                if(app.runs.selectionReady.value&&app.runs.selectedThreadId.value==threadId){
                    com.user.localagent.externalagent.ExternalAgentSettings.get(this,modelId).setEnabled(true)
                    app.runs.chooseModel(modelId,threadId)
                }
                externalAgentConsentThread.value=null
            }){Text("연결하고 선택")}},dismissButton={TextButton(onClick={externalAgentConsentThread.value=null}){Text("취소")}})}
        var tab by rememberSaveable {mutableIntStateOf(0)}
        var settingsPage by rememberSaveable {mutableStateOf(SettingsPage.HOME)}
        var panelFromSettings by rememberSaveable {mutableStateOf(false)}
        var manualSettings by rememberSaveable {mutableStateOf(false)}
        fun openPanel(destination:Int,fromSettings:Boolean=false){
            settingsPage=SettingsPage.HOME
            panelFromSettings=fromSettings
            manualSettings=fromSettings
            tab=destination
        }
        var chatScreenAttempt by remember {mutableIntStateOf(0)}
        var chatScreenUnavailable by remember {mutableStateOf(false)}
        var chatManagerOpen by rememberSaveable {mutableStateOf(false)}
        var composerEpoch by remember {mutableLongStateOf(0)}
        val collaboration=remember{com.user.localagent.collab.CollaborationRoom.get(applicationContext)}
        val collaborationState by collaboration.state.collectAsState()
        var collaborationVisible by remember{mutableStateOf(false)}
        LaunchedEffect(collaborationVisible){
            if(collaborationVisible)lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
                while(isActive){collaboration.refresh();delay(10_000)}
            }
        }
        val submittedTokens=remember {linkedSetOf<String>()}
        var imageStudioStatus by rememberSaveable {mutableStateOf("")}
        val requested by requestedTab.collectAsState()
        LaunchedEffect(requested){requested?.let {openPanel(it);requestedTab.value=null}}
        val state by status.collectAsState()
        val chat=app.runs
        val selectedThreadId by chat.selectedThreadId.collectAsState()
        // Freeze the value, not the delegated State getter, in callbacks retained by an old IME.
        val composerThreadId=selectedThreadId
        var officeProfileEntries by remember{mutableStateOf<Map<String,com.user.localagent.core.office.OfficePrivateProfile.Entry>>(emptyMap())}
        var officeProfileRevision by remember{mutableIntStateOf(0)}
        val officeOpen=composerThreadId==ZipHapRoom.OFFICE.threadId
        LaunchedEffect(officeOpen,officeProfileRevision){
            officeProfileEntries=if(officeOpen)withContext(Dispatchers.IO){runCatching{app.officeProfile.read()?.entries}.getOrNull()}.orEmpty() else emptyMap()
        }
        val roomDraft by chat.draft.collectAsState()
        val queuedRequests by chat.queued.collectAsState()
        val activeExecutions by chat.activeExecutions.collectAsState()
        val auxiliaryExecution by chat.auxiliaryExecution.collectAsState()
        val preparingAttachments by attachmentPreparing.collectAsState()
        val roomGoal by chat.goalState.collectAsState()
        val composerReady by chat.selectionReady.collectAsState()
        val roomActivities by chat.workActivities.collectAsState()
        var queueOpen by rememberSaveable{mutableStateOf(false)}
        var goalEditorThreadId by rememberSaveable{mutableStateOf<String?>(null)}
        var skillLibraryOpen by rememberSaveable{mutableStateOf(false)}
        var skillCapabilities by remember{mutableStateOf<List<com.user.localagent.core.ToolCapability>>(emptyList())}
        LaunchedEffect(skillLibraryOpen){
            if(skillLibraryOpen)lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
                try{skillCapabilities=app.capabilitySnapshot()}
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){skillCapabilities=emptyList()}
                awaitCancellation()
            }
        }
        LaunchedEffect(selectedThreadId){if(goalEditorThreadId!=selectedThreadId)goalEditorThreadId=null}
        val roomThreads by app.repository.chat.threads.collectAsState(initial=emptyList())
        val menuThreads by app.repository.chat.menuThreads.collectAsState(initial=emptyList())
        LaunchedEffect(Unit){
            try{app.repository.chat.openFixedOfficeRooms()}
            catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){FailureLog.record(this@MainActivity,"office_room_setup",error)}
        }
        val currentBranchId=roomThreads.firstOrNull{it.id==selectedThreadId}?.activeBranchId.orEmpty()
        var messageMenuId by remember(composerThreadId,currentBranchId){mutableStateOf<String?>(null)}
        var messageEdit by remember(composerThreadId,currentBranchId){mutableStateOf<String?>(null)}
        val answerStates by chat.answerStates.collectAsState()
        var roomBranches by remember(selectedThreadId){mutableStateOf<List<UiChatBranch>>(emptyList())}
        LaunchedEffect(selectedThreadId,currentBranchId){
            roomBranches=emptyList()
            if(selectedThreadId.isNotBlank())try{
                roomBranches=app.repository.chat.branches(selectedThreadId).map{UiChatBranch(it.id,it.title)}
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){status.value="대화 버전 목록을 불러오지 못했습니다."}
        }
        val pending by approval.collectAsState()
        val messages by lines.collectAsState()
        val previewMessageIds = messages.takeLast(160).filter { it.role == "assistant" }.map { it.id }.toSet()
        val resultPreviews = key(composerThreadId, currentBranchId) {
            val result by produceState(kotlinx.serialization.json.JsonArray(emptyList()), previewMessageIds, activeExecutions.filter { it.threadId == composerThreadId }.map { it.runId }) {
                value = com.user.localagent.ui.ChatResultPreviews.load(
                    this@MainActivity, app, composerThreadId, currentBranchId, previewMessageIds)
            }
            result
        }
        val installed by app.models.models.collectAsState()
        val chosenId by preferredModelId.collectAsState()
        val attachments by app.chatAttachments.state.collectAsState()
        val steerRunId=chat.activeRunId
        val steerThreadId=chat.activeThreadId
        Scaffold(containerColor=Background,contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)) {padding->
            if(tab==0&&chatScreenUnavailable){
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                    Text("대화를 열지 못했어요.",style=MaterialTheme.typography.titleMedium)
                    Text("다시 열어 보고, 계속 안 되면 앱을 종료한 뒤 다시 실행해 주세요.")
                    TextButton(onClick={chatScreenAttempt++;chatScreenUnavailable=false}){Text("다시 열기")}
                    TextButton(onClick={openPanel(4)}){Text("설정")}
                }
            }else if(tab==0){
                val runForRoom=steerRunId?.takeIf{steerThreadId==composerThreadId}
                val roomAuxiliary=auxiliaryExecution?.takeIf{it.threadId==composerThreadId}
                val approvalThread=activeExecutions.firstOrNull{it.threadId!=composerThreadId&&it.waitingForApproval}?.threadId
                val epoch=(runForRoom.orEmpty()+currentBranchId).hashCode().toLong().and(0x7fffffff)
                val menuById=menuThreads.associateBy{it.id}
                val staffRooms=ZipHapRoom.entries.filter{it.agent!=null}.sortedBy{checkNotNull(it.agent).ordinal}
                    .mapNotNull{seat->menuById[seat.threadId]?.takeUnless{it.archived}}
                fun threadJson(id:String,title:String)=buildJsonObject{
                    put("id",id);put("title",title);put("selected",id==composerThreadId)
                    put("working",activeExecutions.any{it.threadId==id&&!it.waitingForApproval&&it.waitingForResource==null})
                }
                val modelChoices=listOf("auto" to "자동",com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID to "Codex",com.user.localagent.externalagent.ExternalAgentSettings.CLAUDE_MODEL_ID to "Claude",
                    com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION to com.user.localagent.core.ConversationPrivacy.LOCAL_LABEL)
                val snapshot=buildJsonObject{
                    put("threadId",composerThreadId);put("branchId",currentBranchId);put("ready",composerReady)
                    put("draft",roomDraft);put("composerEpoch",composerEpoch);put("busy",runForRoom!=null)
                    put("canCancelPreparation",preparingAttachments&&attachmentJobThreadId==composerThreadId||roomAuxiliary!=null)
                    put("otherApprovalPending",approvalThread!=null)
                    put("status",if(runForRoom!=null||activeExecutions.isEmpty())state else "다른 대화에서 작업 중")
                    put("modelId",chosenId);put("modelLabel",modelChoices.find{it.first==chosenId}?.second?:"선택한 모델")
                    put("activeRunId",runForRoom);put("actionEpoch",epoch);put("canSteer",runForRoom!=null&&!chat.officeTeamRun(runForRoom)&&!preparingAttachments&&attachments.isEmpty())
                    put("resultPreviews",resultPreviews)
                    put("messages",buildJsonArray{messages.takeLast(160).forEach{message->add(buildJsonObject{
                        val author = officeAuthor(message.role).takeIf { composerThreadId == officeRoomId }
                        put("id",message.id);put("role",if (author == null) message.role else "assistant")
                        val text = if (message.role == "assistant" || author != null)
                            com.user.localagent.externalagent.CloudAgentFinals.visibleAnswer(message.text) else message.text
                        put("text",text.take(32000))
                        author?.let { put("authorLabel", it.label) }
                    })}})
                    put("officeSelected",officeOpen)
                    if(officeOpen)put("officeProfile",buildJsonObject{officeProfileEntries.forEach{(seat,entry)->put(seat,buildJsonObject{
                        entry.birthDate?.let{put("birthDate",it)};entry.schoolGrade?.let{put("schoolGrade",it)}
                    })}})
                    put("staffRooms",buildJsonArray{staffRooms.forEach{thread->add(threadJson(thread.id,thread.title))}})
                    put("threads",buildJsonArray{
                        staffRooms.forEach{thread->add(threadJson(thread.id,thread.title))}
                        menuById[composerThreadId]?.takeUnless{thread->staffRooms.any{it.id==thread.id}}?.let{add(threadJson(it.id,it.title))}
                    })
                    put("models",buildJsonArray{modelChoices.forEach{(id,label)->add(buildJsonObject{put("id",id);put("label",label)})}})
                    put("attachments",buildJsonArray{attachments.forEach{add(buildJsonObject{put("id",it.id);put("name",it.name)})}})
                    put("collab",collaborationState)
                    put("tasks",buildJsonArray{activeExecutions.filter{it.threadId==composerThreadId}.forEach{run->add(buildJsonObject{
                        put("id",run.runId);put("phase",if(run.waitingForApproval)"approval"else "running")
                        put("label",if(run.waitingForApproval)"승인 확인 필요"else "작업 진행 중");put("detail",run.status)
                        put("canCancel",run.runId==runForRoom)
                        put("evidence",buildJsonArray{publicActivityWindow(roomActivities.values.firstOrNull{it.runId==run.runId&&it.threadId==composerThreadId}?.events.orEmpty()).forEach{event->add(buildJsonObject{
                            put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                        })}})
                    })}
                        roomActivities.values.filter{it.threadId==composerThreadId&&it.assistantMessageId in messages.map{m->m.id}&&it.status !in setOf("queued","running","waiting_approval")&&activeExecutions.none{run->run.runId==it.runId}}
                            .distinctBy{it.runId}.sortedBy{it.updatedAt}.takeLast(12).forEach{activity->add(buildJsonObject{
                                put("id",activity.runId);put("phase",activity.status)
                                put("label",when(activity.status){"completed"->"응답 종료";"paused"->"확인 후 이어가기";"cancelled"->"중단됨";"failed"->"실행 실패";else->"실행 종료"})
                                put("detail","");put("canCancel",false)
                                put("evidence",buildJsonArray{publicActivityWindow(activity.events).forEach{event->add(buildJsonObject{
                                    put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                                })}})
                            })}
                    })
                }.toString()
                Box(Modifier.fillMaxSize().padding(padding).imePadding()){
                key(chatScreenAttempt){ReactChatHost(snapshot,onUnavailable={launchIntroPending=false;chatScreenUnavailable=true},
                    onFirstReady={launchIntroPending=false},modifier=Modifier.fillMaxSize(),onCommand={command->
                    val sameRoom=command.threadId==chat.selectedThreadId.value&&command.threadId==composerThreadId&&command.branchId==currentBranchId&&chat.selectionReady.value
                    if(sameRoom){
                        val payload=command.payload
                        when(command.action){
                            "draft"->chat.updateDraft(payload.getValue("text").jsonPrimitive.content,composerThreadId)
                            "send"->{
                                val token=payload.getValue("submitToken").jsonPrimitive.content
                                val text=payload.getValue("text").jsonPrimitive.content
                                if(attachmentPreparing.value){status.value="첨부 자료를 준비하고 있습니다. 준비가 끝난 뒤 보내주세요."}
                                else if(token !in submittedTokens&&chat.sendFromReact(text,composerThreadId,currentBranchId,token)){
                                    submittedTokens+=token;while(submittedTokens.size>128)submittedTokens.remove(submittedTokens.first())
                                    if(chat.draft.value==text)chat.updateDraft("",composerThreadId)
                                }
                                composerEpoch++
                            }
                            "stop","steer"->{
                                val id=payload.getValue("runId").jsonPrimitive.content
                                if(id==runForRoom&&id==chat.activeRunId&&payload.getValue("actionEpoch").jsonPrimitive.long==epoch){
                                    if(command.action=="stop")chat.stopRun(id,composerThreadId)
                                    else if(!attachmentPreparing.value&&app.chatAttachments.state.value.isEmpty())chat.steer(payload.getValue("text").jsonPrimitive.content,id,composerThreadId)
                                    else status.value="첨부 자료를 준비 중이거나 선택한 상태에서는 추가 지시를 적용할 수 없습니다. 입력은 유지했습니다."
                                }else status.value="작업이 바뀌어 이전 화면의 지시를 적용하지 않았습니다."
                                if(command.action!="stop")composerEpoch++
                            }
                            "selectThread"->payload.getValue("id").jsonPrimitive.content.takeIf{id->roomThreads.any{it.id==id&&!it.archived&&it.deletedAt==null}}?.let(chat::selectThread)
                            "renameThread"->chat.renameThread(composerThreadId,payload.getValue("title").jsonPrimitive.content)
                            "openOffice"->chat.openFixedOfficeRooms()
                            "saveOfficeProfile","clearOfficeProfile"->if(officeOpen){
                                val saving=command.action=="saveOfficeProfile"
                                val entries=if(saving)runCatching{payload.getValue("entries").jsonObject.mapValues{(_,value)->
                                    val o=value.jsonObject
                                    com.user.localagent.core.office.OfficePrivateProfile.Entry(
                                        o["birthDate"]?.jsonPrimitive?.content?.takeIf{it.isNotEmpty()},o["schoolGrade"]?.jsonPrimitive?.content?.takeIf{it.isNotEmpty()})
                                }.filterValues{it.birthDate!=null||it.schoolGrade!=null}}.getOrNull() else emptyMap()
                                this@MainActivity.lifecycleScope.launch{
                                    val ok=entries!=null&&withContext(Dispatchers.IO){runCatching{
                                        if(saving&&entries.isNotEmpty())app.officeProfile.write(com.user.localagent.core.office.OfficePrivateProfile(entries))
                                        else app.officeProfile.clear()
                                    }.isSuccess}
                                    officeProfileRevision++
                                    status.value=if(!ok)"직원 정보를 저장하지 못했습니다. 입력값을 확인해 주세요."
                                        else if(saving&&entries!!.isNotEmpty())"직원 정보를 이 휴대폰에 암호화해 저장했습니다." else "직원 정보를 지웠습니다."
                                }
                            }
                            "selectModel"->payload.getValue("id").jsonPrimitive.content.takeIf{id->modelChoices.any{it.first==id}}?.let{id->chooseConversationModel(id,composerThreadId)}
                            "attach"->{val room=roomThreads.firstOrNull{it.id==composerThreadId};if(room!=null&&!attachmentPreparing.value){attachmentScope=Triple(room.id,room.revision,room.activeBranchId);attachFiles.launch(arrayOf("*/*"))}}
                            "navigate"->{val panel=payload.getValue("panel").jsonPrimitive.content;collaborationVisible=panel=="collab";if(collaborationVisible)collaboration.refresh()else openPanel(when(panel){"chat"->0;"images"->7;"tools"->3;"projects"->10;"history"->6;else->4})}
                            "collabRefresh"->collaboration.refresh(payload.getValue("bindingEpoch").jsonPrimitive.long)
                            "collabSend"->collaboration.post(payload.getValue("clientMessageId").jsonPrimitive.content,payload.getValue("text").jsonPrimitive.content,payload.getValue("recipient").jsonPrimitive.content,payload.getValue("bindingEpoch").jsonPrimitive.long)
                            "copy"->{val id=payload.getValue("messageId").jsonPrimitive.content;if(messages.any{it.id==id&&(it.role=="assistant"||officeAuthor(it.role)!=null)})chat.copyMessage(id)}
                            "messageMenu"->{
                                val id=payload.getValue("messageId").jsonPrimitive.content
                                if(messages.any{it.id==id&&(it.role in setOf("user","assistant")||officeAuthor(it.role)!=null)})messageMenuId=id
                            }
                            "manageThreads"->chatManagerOpen=true
                            "removeAttachment"->{
                                val id=payload.getValue("id").jsonPrimitive.content
                                if(app.chatAttachments.state.value.any{it.id==id})app.chatAttachments.remove(id)
                            }
                            "cancelPreparation"->{
                                if(attachmentJobThreadId==composerThreadId)attachmentJob?.cancel()
                                else chat.auxiliaryExecution.value?.takeIf{it.threadId==composerThreadId}?.let{chat.stopAuxiliary(it.operationId,it.threadId)}
                            }
                            "openWaitingApproval"->approvalThread?.takeIf{id->chat.activeExecutions.value.any{it.threadId==id&&it.waitingForApproval}}?.let(chat::selectThread)
                        }
                    }else if(command.action in setOf("send","steer","attach","removeAttachment")){
                        status.value="대화가 바뀌어 방금 요청을 적용하지 않았어요. 다시 확인해 주세요."
                    }
                })}
                if(launchIntroPending) LaunchIntro(Modifier.matchParentSize())
                }
            }
            else Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(horizontal=18.dp)) {
                val returnsToSettings = panelFromSettings || (tab == 4 && manualSettings)
                fun showManual(link:SettingsLink) {
                    if(link==SettingsLink.VOICE) {openPanel(1,fromSettings=true);return}
                    manualSettings = true
                    settingsPage = when(link) {
                        SettingsLink.CONNECTIONS -> SettingsPage.CONNECTIONS
                        SettingsLink.PRIVACY -> SettingsPage.PRIVACY
                        SettingsLink.AUTOMATION -> SettingsPage.AUTOMATION
                        SettingsLink.UPDATES -> SettingsPage.UPDATES
                        else -> SettingsPage.HOME
                    }
                }
                fun returnToParent(){
                    when {
                        panelFromSettings -> {openPanel(4);showManual(SettingsLink.MANUAL)}
                        tab==4 && settingsPage!=SettingsPage.HOME -> showManual(SettingsLink.MANUAL)
                        tab==4 && manualSettings -> openPanel(4)
                        else -> {openPanel(0);refreshVisibleHistory()}
                    }
                }
                androidx.activity.compose.BackHandler {returnToParent()}
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=::returnToParent){Text(if(returnsToSettings)"‹ 설정"else"‹ 대화")}
                    val title=if(tab==4)settingsPage.title
                        else listOf("","음성을 글로 바꾸기","기억과 할 일","파일 작업","설정","연결된 자료","대화 기록","이미지 작업실","문자와 일정","웹·쇼핑 검색","프로젝트")[tab]
                    Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    if(tab==4 && !manualSettings) TextButton(onClick={showManual(SettingsLink.MANUAL)}){Text("수동 설정")}
                }
                val panelStatus=if(tab==7)imageStudioStatus else state
                if(panelStatus.isNotBlank() && (tab!=4 || manualSettings))Text(panelStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
                when(tab){1->VoiceSettingsScreen();2->MemoryScreen();3->Box(Modifier.weight(1f)){ToolShelfPanel(app,{status.value=it}){request->tab=0;send(request)}};4->if(manualSettings) key(settingsPage){
                    SettingsScreen(settingsPage,chatScreenUnavailable,{settingsPage=it}){openPanel(it,fromSettings=true)}
                }else SettingsAssistantPanel(app,::showManual,moveToChat={request->
                    if(!chat.selectionReady.value||chat.selectedThreadId.value!=composerThreadId||
                        composerThreadId.isBlank()||chat.draft.value.isNotEmpty())false
                    else {
                        chat.updateDraft(request,composerThreadId)
                        openPanel(0)
                        true
                    }
                },modifier=Modifier.weight(1f));5->Box(Modifier.weight(1f)){DataAnalysisPanel(app,{request,threadId->tab=0;sendToThread(request,threadId)},{uri,origin->tab=0;transcribe(uri,origin)})};6->Box(Modifier.weight(1f)){ConversationHistoryPanel(app){status.value=it}};7->Box(Modifier.weight(1f)){ImageStudioPanel(app,{imageStudioStatus=it})};8->Box(Modifier.weight(1f)){SmsAnalysisPanel(app){status.value=it}};9->Box(Modifier.weight(1f)){WebResearchPanel(app){status.value=it}};10->Box(Modifier.weight(1f)){ProjectWorkspacePanel(app,{status.value=it}){request->tab=0;send(request)}}}
            }
        }
        if(chatManagerOpen){
            val projects by app.repository.chat.projects.collectAsState(initial=emptyList())
            val selectedProjectId by chat.selectedProjectId.collectAsState()
            val navigation=ChatNavigation(
                projects=projects.filterNot{it.archived}.map{project->
                    val settings=project.settings
                    UiProject(project.id,project.title,instructions=settings.instructions,modelId=settings.modelId,
                        thinking=settings.thinking,contextSize=settings.contextSize,memoryMode=settings.memoryMode,
                        useOwnerMemory=settings.useOwnerMemory,learnOwnerFacts=settings.learnOwnerFacts,
                        revision=project.revision,disabledTools=settings.disabledTools)
                },
                threads=menuThreads.filterNot{room->ZipHapRoom.entries.any{it.threadId==room.id}}.map{room->UiThread(room.id,room.projectId.orEmpty(),room.title,room.pinned,
                    room.archived,room.deletedAt!=null,room.updatedAt)},
                selectedProjectId=selectedProjectId,selectedThreadId=selectedThreadId,supportsTrash=false,
                modelChoices=listOf(UiModelChoice("auto","자동"),UiModelChoice(com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID,"Codex"),UiModelChoice(com.user.localagent.externalagent.ExternalAgentSettings.CLAUDE_MODEL_ID,"Claude"),
                    UiModelChoice(com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION,com.user.localagent.core.ConversationPrivacy.LOCAL_LABEL)),
                actions=ChatNavigationActions(onSelectThread=chat::selectThread,onNewThread=chat::newThread,
                    onSelectProject=chat::selectProject,onSaveProject=chat::saveProject,onDeleteProject=chat::deleteProject,
                    onChooseProjectFolder={id->folderProjectId=id;projectFolder.launch(null)},onRenameThread=chat::renameThread,
                    onPinThread=chat::pinThread,onArchiveThread=chat::archiveThread,onClearThread=chat::clearThread,
                    onDeleteThread=chat::deleteThread,onDuplicateThread=chat::duplicateThread,onMoveThread=chat::moveThread,
                    onExportThread=chat::exportThread,onSearchThreads=chat::searchThreads),
            )
            androidx.compose.ui.window.Dialog(onDismissRequest={chatManagerOpen=false}){
                Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxHeight(0.9f)){
                    ChatNavigator(navigation,onDismiss={chatManagerOpen=false},
                        onOpenImages={chatManagerOpen=false;openPanel(7)},onOpenSettings={chatManagerOpen=false;openPanel(4)},
                        conversationActions={
                            TextButton(onClick={chatManagerOpen=false;openPanel(6)}){Text("대화 기록")}
                            TextButton(onClick={chatManagerOpen=false;queueOpen=true}){Text("대기 중인 요청")}
                            TextButton(onClick={chatManagerOpen=false;goalEditorThreadId=composerThreadId}){Text("이 대화의 목표")}
                            TextButton(onClick={chatManagerOpen=false;skillLibraryOpen=true}){Text("도움 기능")}
                            if(roomBranches.size>1)roomBranches.forEach{branch->
                                TextButton(enabled=branch.id!=currentBranchId,onClick={
                                    chatManagerOpen=false;chat.selectBranch(composerThreadId,branch.id)
                                }){Text(branch.title)}
                            }
                        })
                }
            }
        }
        messages.firstOrNull{it.id==messageMenuId}?.let {message->
            fun closeMenu(){messageMenuId=null;messageEdit=null}
            AlertDialog(onDismissRequest=::closeMenu,title={Text(if(message.role=="assistant")"답변 메뉴"else"메시지 메뉴")},text={
                val editing=messageEdit
                if(editing!=null)OutlinedTextField(editing,{messageEdit=it},label={Text("메시지")},maxLines=6)
                else Column {
                    if(message.role=="user")TextButton(onClick={messageEdit=message.text}){Text("수정해서 보내기")}
                    else {
                        GeneratedImageChatPreview(message.text)
                        if(answerStates[message.id]?.canContinue==true)TextButton(onClick={closeMenu();chat.continueAnswer(message.id)}){Text("답변 이어받기")}
                        TextButton(onClick={closeMenu();chat.regenerate(message.id)}){Text("다시 답변 받기")}
                        TextButton(onClick={closeMenu();chat.exportMessage(message.id)}){Text("답변 저장")}
                    }
                    TextButton(onClick={closeMenu();chat.forkMessage(message.id)}){Text("여기서 새 대화 시작")}
                }
            },confirmButton={
                val editing=messageEdit
                if(editing==null)TextButton(onClick=::closeMenu){Text("닫기")}
                else TextButton(enabled=editing.isNotBlank()&&editing.length<=12000,onClick={closeMenu();chat.editMessage(message.id,editing)}){Text("보내기")}
            },dismissButton={if(messageEdit!=null)TextButton(onClick={messageEdit=null}){Text("취소")}})
        }
        if(queueOpen)RunQueueDialog(
            items=queuedRequests.map{request->UiQueuedRun(request.runId,request.threadId,request.text.lineSequence().firstOrNull().orEmpty().take(100),request.text,
                threadLabel=roomThreads.firstOrNull{it.id==request.threadId}?.title?:"대화",modelLabel=if(request.modelId=="auto")"모델 자동"else installed.firstOrNull{it.id==request.modelId}?.title?:"선택한 모델",
                modeLabel=com.user.localagent.core.AgentWorkMode.entries.firstOrNull{it.wireName==request.mode}?.label?:request.mode,goalLinked=request.goal!=null,continuation=request.continuation!=null)},
            onDismiss={queueOpen=false},onEdit=chat::editQueued,onMove=chat::reorderQueued,onCancel=chat::cancelQueued,
            onManageGoal={threadId->queueOpen=false;chat.selectThread(threadId)},
        )
        if(skillLibraryOpen)SkillLibraryDialog(chat.bundledSkills,toolStatuses=skillCapabilities,onDismiss={skillLibraryOpen=false})
        goalEditorThreadId?.takeIf{it==selectedThreadId&&it.isNotBlank()}?.let {goalThread->
            GoalEditorDialog(goal=roomGoal?.takeIf{it.threadId==goalThread},threadId=goalThread,
                projectId=roomThreads.firstOrNull{it.id==goalThread}?.projectId,
                onSave={goal->chat.saveGoal(goal){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onStart={goal->chat.startGoal(goal){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onDelete={chat.deleteGoal(goalThread){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onDismiss={goalEditorThreadId=null},feedback=state,
                onDeleteVersioned={snapshot->chat.deleteGoal(goalThread,snapshot){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}})
        }
        pending?.takeIf{it.threadId==composerThreadId}?.let { action -> ActionApprovalDialog(action,action.reviewText?:actionSummary(action.call)) }
    }
    @Composable private fun ColumnScope.VoiceSettingsScreen() {
        val installed by app.models.models.collectAsState()
        val selectedId by preferredModelId.collectAsState()
        val media by app.models.mediaSelection.collectAsState()
        val asr by app.models.asrSelection.collectAsState()
        val progress by app.models.progress.collectAsState()
        val scope=rememberCoroutineScope()
        val options by modelOptions.collectAsState()
        val thinkingThreadId=app.runs.selectedThreadId.collectAsState().value
        val thinkingReady by app.runs.selectionReady.collectAsState()
        var advancedModels by rememberSaveable {mutableStateOf(false)}
        var endurance by remember { mutableStateOf(false) }
        var downloadJob by remember {mutableStateOf<Job?>(null)}
        var hashTarget by remember {mutableStateOf<String?>(null)}
        var expectedHash by remember {mutableStateOf("")}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(progress!=null) item {val p=progress!!;InfoCard(if(p.phase=="hash")"모델 무결성 검증" else "모델 다운로드","${p.title}\n${p.bytes/1024/1024} / ${p.total/1024/1024} MB");LinearProgressIndicator(progress={if(p.total>0)(p.bytes.toDouble()/p.total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());TextButton(onClick={downloadJob?.cancel()}){Text("중단 · 다음 다운로드에서 이어받기")}}
            item {InfoCard("녹음에서 글 만들기", "이 휴대폰에서 음성을 글로 바꿀 때 쓸 언어와 인식 방법을 고를 수 있어요.")}
            item {Text("녹음 언어",style=MaterialTheme.typography.titleSmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("ko" to "한국어", "en" to "영어", "auto" to "자동 감지").forEach {(code,label)->
                    FilterChip(selected=asr.language==code,enabled=code!="auto"||asr.modelId!=com.user.localagent.models.AsrCatalog.DEVICE_MODEL,onClick={scope.launch {app.models.selectAsrLanguage(code)}},label={Text(label)})
                }
            }}
            item {
                val deviceAsr by app.models.deviceAsrStatus.collectAsState()
                Surface(color=Panel,shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    Text("휴대폰 기본 음성 인식",fontWeight=FontWeight.Bold)
                    Text("한국어와 영어를 지원해요. 이 휴대폰에서 사용할 수 있는지 확인하세요.",style=MaterialTheme.typography.bodySmall)
                    Text(deviceAsr.message,style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.prepareDeviceAsr(deviceAsr.downloadable)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"기기 내 AI 확인 실패"}}}){Text(if(deviceAsr.downloadable)"음성 인식 준비"else"지원 확인")}
                        FilterChip(selected=asr.modelId==com.user.localagent.models.AsrCatalog.DEVICE_MODEL,enabled=deviceAsr.available&&asr.language!="auto",onClick={scope.launch {app.models.selectAsr(com.user.localagent.models.AsrCatalog.DEVICE_MODEL)}},label={Text("사용하기")})
                    }
                }}
            }
            items(app.models.candidates.filter {it.id in com.user.localagent.models.AsrCatalog.modelIds}) {candidate->
                Surface(color=Panel,shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    val accuracyFirst=candidate.id=="asr-qwen-17b"
                    Text(if(accuracyFirst)"정확도 우선"else"저장 공간 절약",fontWeight=FontWeight.Bold)
                    Text(if(accuracyFirst)"약 2.8GB 다운로드"else"약 1.2GB 다운로드",style=MaterialTheme.typography.bodySmall)
                    Text("휴대폰에서 처리 · 공식 배포 파일",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.downloadBundle(candidate);status.value="음성 인식 파일 검증 완료"}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"}}}){Text("다운로드")}
                        FilterChip(selected=asr.modelId==candidate.id,enabled=app.models.asrReady(candidate.id),onClick={scope.launch {app.models.selectAsr(candidate.id)}},label={Text("사용하기")})
                    }
                }}
            }
            item {TextButton(onClick={advancedModels=!advancedModels}){Text(if(advancedModels)"모델·성능 설정 접기"else"모델·성능 설정 더 보기")}}
            if(advancedModels){
                item { InfoCard("PC와 휴대폰에서 나누어 계산","연결한 PC가 계산 중이면 다른 대화의 작업 하나를 휴대폰에서 진행할 수 있습니다. 휴대폰에는 모델 하나씩만 올립니다. 모델 파일은 APK와 별도로 내려받습니다.") }
                item {var routing by remember {mutableStateOf("실측 결과를 확인 중")};LaunchedEffect(installed){routing=app.orchestration.summary()};InfoCard("자동 오케스트레이션",routing)}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=options.backend=="CPU",onClick={modelOptions.value=options.copy(backend="CPU")},label={Text("CPU 측정")});FilterChip(selected=options.backend=="GPU",onClick={modelOptions.value=options.copy(backend="GPU")},label={Text("GPU 실험 측정")});FilterChip(selected=options.thinking,onClick={app.runs.setThinking(!options.thinking,thinkingThreadId)},enabled=thinkingReady,label={Text("이 대화 깊게 생각")})}}
                item {Row {FilterChip(selected=options.contextSize==4096,onClick={modelOptions.value=options.copy(contextSize=4096)},label={Text("4K 문맥")});Spacer(Modifier.width(8.dp));FilterChip(selected=options.contextSize==8192,onClick={modelOptions.value=options.copy(contextSize=8192)},label={Text("8K 문맥")})}}
                item {Text("CPU/GPU와 4K/8K는 아래 성능 측정에 사용합니다. 깊게 생각 요청은 현재 대화에 저장되며, 실제 모델·설정은 기기의 검증 결과와 자원 상태에 따라 선택됩니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                item {Row {Checkbox(endurance,onCheckedChange={endurance=it});Text("10분 연속 안정성 측정")}}
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={importModel.launch(arrayOf("application/octet-stream","*/*"))}) { Text("파일 가져오기") }
                    OutlinedButton(onClick={scope.launch {maintainModelFiles {status.value="휴대폰 모델 메모리를 해제했습니다."}}}) {Text("메모리 해제")}
                } }
                items(app.models.candidates.filterNot {it.id=="gemma-e2b"||com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { candidate -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(candidate.title,fontWeight=FontWeight.Bold)
                    Text("공식 메타데이터에서 버전·해시를 확인한 뒤 다운로드",style=MaterialTheme.typography.bodySmall)
                    Button(enabled=progress==null,onClick={downloadJob=scope.launch { status.value="모델 출처 확인 중";try{app.models.downloadBundle(candidate);status.value="다운로드·해시 검증 완료"}catch(cancel:CancellationException){status.value="중단했습니다. 받은 부분은 이어받기에 사용합니다.";throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"} }}) {Text("다운로드")}
                } } }
                item {Text("설치한 모델",style=MaterialTheme.typography.titleMedium)}
                items(installed.filterNot {it.id=="gemma-e2b"||com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { model -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) {Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(model.title,fontWeight=FontWeight.Bold)
                    val imageOnly=com.user.localagent.models.GemmaMediaCatalog.imageOnly(model.id,model.sha256)
                    Text(if(imageOnly) "이미지 전용" else if(selectedId==model.id) "이 대화에서 선택됨" else "선택 가능",color=Mint)
                    if(model.id in com.user.localagent.models.GemmaMediaCatalog.modelIds) {
                        val ready=app.models.mediaReady(model)
                        Text(if(ready)"사진 분석 지원"else"사진 분석 지원 파일 다운로드 필요",style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(!imageOnly)FilterChip(selected=selectedId==model.id,enabled=model.verified&&thinkingReady,onClick={chooseConversationModel(model.id,thinkingThreadId)},label={Text("이 대화에서 사용")})
                            FilterChip(selected=media.vision==model.id,enabled=ready,onClick={scope.launch {app.models.selectMedia("vision",model.id)}},label={Text("사진용")})
                        }
                        if(model.id=="gemma-e4b")Text("사진은 CPU 2스레드·입력량 제한으로 부하를 낮춥니다. 메모리 부족·과열 시 작업을 보류합니다.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(!model.verified)TextButton(onClick={hashTarget=model.id;expectedHash=""}){Text("확인 코드 입력")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(!imageOnly&&model.id !in com.user.localagent.models.GemmaMediaCatalog.modelIds)TextButton(enabled=model.verified&&thinkingReady,onClick={chooseConversationModel(model.id,thinkingThreadId)}){Text("이 대화에서 사용")}
                        if(!imageOnly)TextButton(onClick={scope.launch {runBenchmark(model.path,if(endurance)10 else 0)}}){Text("성능 측정")}
                        TextButton(onClick={scope.launch {maintainModelFiles {app.models.delete(model.id);status.value="모델을 삭제했습니다."}}}){Text("삭제")}
                    }
                } } }
            }
        }
        hashTarget?.let {id->AlertDialog(onDismissRequest={hashTarget=null},title={Text("파일 확인")},text={Column{Text("배포처가 알려준 64자리 확인 코드를 입력해 주세요.");OutlinedTextField(value=expectedHash,onValueChange={expectedHash=it.trim()},label={Text("확인 코드")})}},confirmButton={TextButton(enabled=expectedHash.matches(Regex("[a-fA-F0-9]{64}")),onClick={scope.launch {runCatching {app.models.verifyImported(id,expectedHash)}.onSuccess{status.value="파일이 배포처의 원본과 일치해요.";hashTarget=null}.onFailure{status.value=it.message?:"검증 실패"}}}){Text("파일 검증")}},dismissButton={TextButton(onClick={hashTarget=null}){Text("취소")}})}
    }
    private suspend fun runBenchmark(path:String,minutes:Int=0) {
        status.value="벤치마크 시작 · 다른 작업은 잠시 기다려주세요"
        try {
            app.updates.setBusy(true)
            val report=BenchmarkRunner(this,app.engine).run(path,modelOptions.value,minutes)
            status.value="측정 결과 저장: ${report.name}"
        } catch(cancel:CancellationException){throw cancel}catch(error:Throwable) {status.value="벤치마크 실패: ${error.message}"} finally {app.updates.setBusy(false)}
    }
    /** Do not delete a model captured by either room, or clear an unrelated remote backend. */
    private suspend fun maintainModelFiles(action:suspend()->Unit){
        try {app.runs.queueMutation.withLock {
            check(!app.runs.hasActiveRuns.value&&!RuntimeGate.benchmark.get()&&app.models.progress.value==null){"실행·다운로드 중인 작업이 끝난 뒤 모델 파일을 관리해주세요."}
            val owns=RuntimeGate.inference.compareAndSet(false,true)
            check(owns){"휴대폰 모델 작업이 끝난 뒤 다시 시도해주세요."}
            var busyLease:java.io.Closeable?=null
            var nativeCleanupFailed=false
            try {
                busyLease=app.updates.acquireBusy("model-maintenance:${UUID.randomUUID()}")
                try {app.engine.unloadPhoneResidency()}catch(error:Throwable){nativeCleanupFailed=true;throw error}
                action()
            }finally{
                try {busyLease?.close()}finally{
                    if(!nativeCleanupFailed)RuntimeGate.inference.set(false)
                    app.scheduleIdleUnload()
                }
            }
        }}catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){status.value=error.message?:"모델 파일 변경을 확인하지 못했습니다."}
    }
    @Composable private fun ColumnScope.MemoryScreen() {
        Box(Modifier.weight(1f)) {
            KnowledgeScreen(app,{status.value=it},::invalidateKnowledgeContext) {
                permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR,Manifest.permission.READ_CONTACTS,Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }
    @Composable private fun ColumnScope.SettingsScreen(
        page:SettingsPage,
        showChatRecoveryLinks:Boolean,
        onPage:(SettingsPage)->Unit,
        onOpen:(Int)->Unit,
    ) {
        val assistantBusy by app.runs.busy.collectAsState()
        val imageWork by app.imageStudio.state.collectAsState()
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when(page){
                SettingsPage.HOME->{
                    item {SettingsEntry("앱과 컴퓨터 연결","메일·문서·메신저 또는 내 컴퓨터를 연결해요."){onPage(SettingsPage.CONNECTIONS)}}
                    item {SettingsEntry("음성을 글로 바꾸기","녹음에 쓸 언어와 휴대폰의 음성 인식을 설정해요."){onOpen(1)}}
                    item {SettingsEntry("개인정보와 권한","화면 접근이나 전에 허용한 작업을 확인해요."){onPage(SettingsPage.PRIVACY)}}
                    item {SettingsEntry("자동으로 도와주기","통화 뒤 정리하거나 사용 중에 도움을 받도록 설정해요."){onPage(SettingsPage.AUTOMATION)}}
                    item {SettingsEntry("앱 업데이트","새 버전을 확인하고 설치해요."){onPage(SettingsPage.UPDATES)}}
                    if(showChatRecoveryLinks){
                        item {SettingsEntry("대화 기록","저장한 대화를 찾아요."){onOpen(6)}}
                    }
                    item {
                        SettingsDisclosure("저장한 자료와 기록","기억, 문자, 일정, 검색 자료를 찾아요."){
                            TextButton(onClick={onOpen(3)}){Text("파일 작업")}
                            TextButton(onClick={onOpen(2)}){Text("기억과 할 일")}
                            TextButton(onClick={onOpen(5)}){Text("연결된 자료")}
                            TextButton(onClick={onOpen(8)}){Text("문자와 일정")}
                            TextButton(onClick={onOpen(9)}){Text("검색 기록")}
                        }
                    }
                }
                SettingsPage.CONNECTIONS->{
                    item {SettingsEntry("메일·일정·문서 연결","Google 계정으로 사용할 기능을 선택해요."){
                        startActivity(Intent(this@MainActivity,com.user.localagent.connections.ConnectionsActivity::class.java))
                    }}
                    item {SettingsDisclosure("내 컴퓨터","컴퓨터를 추가하거나 연결 상태를 확인해요."){
                        PcComputePanel(assistantBusy||imageWork.running||RuntimeGate.benchmark.get(),
                            loadQaLessons=app.repository.chat::remoteQaLessons){status.value=it}
                    }}
                    item {SettingsDisclosure("메신저","카카오톡 등에서 받아볼 내용을 정해요."){
                        com.user.localagent.integrations.MessengerPanel(this@MainActivity,onAsk={request->onOpen(0);send(request)}){status.value=it}
                    }}
                }
                SettingsPage.AUTOMATION->{
                    item {SettingsDisclosure("통화 뒤 정리","통화 녹음으로 할 일과 메모를 제안받아요."){
                        CallFollowUpPanel(app,{status.value=it}){threadId->app.runs.selectThread(threadId);onOpen(0)}
                    }}
                    item {SettingsDisclosure("사용 기록으로 도움 받기","사용 시간과 알림 횟수로 제안을 받아요."){
                        AutomationPanel(this@MainActivity,app.automation,{startActivity(it)},{status.value=it},{app.repository.discardProposedMemories();Unit})
                    }}
                }
                SettingsPage.PRIVACY->{
                    item {SettingsDisclosure("다른 앱 화면 접근","화면을 읽거나 조작할 수 있는 범위를 정해요."){
                        com.user.localagent.integrations.ScreenBridgePanel(this@MainActivity,{startActivity(it)}){status.value=it}
                    }}
                    item {SettingsDisclosure("이전에 허용한 작업","반복 작업에 저장한 허용을 해제할 수 있어요."){
                        val scope=rememberCoroutineScope()
                        OutlinedButton(onClick={scope.launch {app.repository.revokeGrant("builtin");status.value="기본 도구의 반복 작업 허용을 해제했습니다."}}){Text("반복 작업 허용 해제")}
                    }}
                    item {SettingsDisclosure("Codex 연결","이 앱이 Codex를 사용하도록 허용한 상태를 관리해요."){
                        TextButton(onClick={
                            com.user.localagent.externalagent.ExternalAgentSettings.get(this@MainActivity).setEnabled(false)
                            status.value="Codex 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다."
                        }){Text("Codex 연결 해제")}
                    }}
                    item {SettingsDisclosure("Claude 연결","이 앱이 Claude를 사용하도록 허용한 상태를 관리해요."){
                        TextButton(onClick={
                            com.user.localagent.externalagent.ExternalAgentSettings.get(this@MainActivity,com.user.localagent.externalagent.ExternalAgentSettings.CLAUDE_MODEL_ID).setEnabled(false)
                            status.value="Claude 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다."
                        }){Text("Claude 연결 해제")}
                    }}
                }
                SettingsPage.UPDATES->{
                    item {com.user.localagent.updates.UpdatePanel(app.updates,{startActivity(it)},{status.value=it})}
                    item {SettingsDisclosure("확장 기능 검사","직접 추가할 확장 파일의 정보를 확인해요."){
                        ExtensionInspectionPanel{status.value=it}
                    }}
                    item {Text("버전 ${com.user.localagent.runtime.RuntimePackageMetadata(this@MainActivity).versionName}",style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
    @Composable private fun InfoCard(title:String,body:String) {ZipHapCard {Text(title,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}}
}
