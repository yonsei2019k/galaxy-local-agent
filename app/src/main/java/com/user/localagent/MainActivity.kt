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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
            catch(error:Exception){status.value=error.message?:"프로젝트 폴더를 연결하지 못했습니다."}
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result->
        val actual=result.keys.associateWith{androidx.core.content.ContextCompat.checkSelfPermission(this,it)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        status.value=permissionResultMessage(actual)
    }
    private val attachFiles=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val origin=attachmentScope
        attachmentScope=null
        if(uris.isNotEmpty()&&!attachmentPreparing.value){attachmentPreparing.value=true;attachmentJob=lifecycleScope.launch {
            try{
                check(origin!=null){"첨부할 원래 대화를 확인하지 못했습니다. 해당 방에서 다시 선택해주세요."}
                val room=app.repository.chat.thread(origin.first)
                check(room!=null&&room.revision==origin.second&&room.activeBranchId==origin.third){"자료를 선택하는 동안 원래 대화가 변경되었습니다. 다시 선택해주세요."}
                status.value="첨부 파일을 기기에 준비 중";app.chatAttachments.add(uris,origin.first);status.value="원래 대화에 파일을 첨부했습니다. 해당 방에서 요청을 입력해주세요."
            }
            catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){status.value=error.message?:"첨부 파일을 준비하지 못했습니다."}
            finally{attachmentJob=null;attachmentPreparing.value=false}
        }}
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) lifecycleScope.launch {
            runCatching { app.models.importModel(uri, "가져온 로컬 모델") }
                .onSuccess { status.value = "모델을 가져왔습니다. 원본 SHA-256을 등록해 검증해주세요." }
                .onFailure { status.value = it.message ?: "모델 가져오기 실패" }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        folderProjectId=savedInstanceState?.getString("folder-project-id")
        savedInstanceState?.let {saved->saved.getString("attachment-thread-id")?.let {id->saved.getString("attachment-branch-id")?.let {branch->attachmentScope=Triple(id,saved.getLong("attachment-thread-revision"),branch)}}}
        if(intent.hasExtra("open_tab"))requestedTab.value=intent.getIntExtra("open_tab",0).coerceIn(0,10)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false}
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
    private val externalAgentConsentThread=mutableStateOf<String?>(null)
    private fun chooseConversationModel(id:String,threadId:String){
        if(id==com.user.localagent.externalagent.ExternalAgentSettings.DISABLE_ID){
            com.user.localagent.externalagent.ExternalAgentSettings.get(this).setEnabled(false)
            app.runs.chooseModel("auto",threadId);status.value="클라우드 도구 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다.";return
        }
        // Preference only: the coordinator retains the model/authority captured by active requests.
        if(!app.runs.selectionReady.value||app.runs.selectedThreadId.value!=threadId){
            status.value="선택한 대화를 불러온 뒤 모델을 바꿔주세요.";return
        }
        val pairedText=com.user.localagent.models.PairedPcTextProfileDescriptor.forExplicitSelection(id)!=null&&com.user.localagent.compute.PcComputeSettings.get(this).state.value.let{it.paired&&!it.claudeText}
        if(id==com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID){
            if(!com.user.localagent.compute.PcComputeSettings.get(this).state.value.paired){status.value="설정에서 개인 PC를 먼저 연결해주세요.";return}
            if(!com.user.localagent.externalagent.ExternalAgentSettings.get(this).capture().enabled){externalAgentConsentThread.value=threadId;return}
            app.runs.chooseModel(id,threadId);status.value="다음 요청에서 클라우드 모델이 승인된 휴대폰 도구를 사용합니다.";return
        }
        if(id!="auto"&&!pairedText&&app.models.models.value.none{it.id==id&&it.verified&&it.runtime in setOf("litertlm","gguf")}){status.value="설치된 로컬 모델 또는 등록한 PC 텍스트 모델을 선택해주세요.";return}
        // A preference change is synchronous. The next serialized request owns runtime loading.
        app.runs.chooseModel(id,threadId)
        status.value="이 대화의 다음 요청에 선택한 모델을 사용합니다."
    }
    private fun transcribe(uri:android.net.Uri,origin:com.user.localagent.audio.PrivateDataPickerOrigin)=app.runs.transcribe(uri,origin)
    private fun refreshVisibleHistory()=app.runs.refreshVisibleHistory()
    private fun invalidateKnowledgeContext()=app.runs.invalidateKnowledgeContext()
    private fun actionSummary(action:ToolCall)=app.runs.actionSummary(action)
    @Composable private fun AppScreen() {
        externalAgentConsentThread.value?.let {threadId->AlertDialog(
            onDismissRequest={externalAgentConsentThread.value=null},title={Text("클라우드 도구 연결")},
            text={Text(com.user.localagent.externalagent.ExternalAgentSettings.CONSENT_TEXT)},
            confirmButton={TextButton(onClick={
                if(app.runs.selectionReady.value&&app.runs.selectedThreadId.value==threadId){
                    com.user.localagent.externalagent.ExternalAgentSettings.get(this).setEnabled(true)
                    app.runs.chooseModel(com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID,threadId)
                }
                externalAgentConsentThread.value=null
            }){Text("연결하고 선택")}},dismissButton={TextButton(onClick={externalAgentConsentThread.value=null}){Text("취소")}})}
        var tab by rememberSaveable {mutableIntStateOf(0)}
        var nativeChat by rememberSaveable {mutableStateOf(false)}
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
        LaunchedEffect(requested){requested?.let {tab=it;requestedTab.value=null}}
        val state by status.collectAsState()
        val chat=app.runs
        val selectedThreadId by chat.selectedThreadId.collectAsState()
        // Freeze the value, not the delegated State getter, in callbacks retained by an old IME.
        val composerThreadId=selectedThreadId
        val selectedProjectId by chat.selectedProjectId.collectAsState()
        val roomDraft by chat.draft.collectAsState()
        val answerStates by chat.answerStates.collectAsState()
        val queuedRequests by chat.queued.collectAsState()
        val activeExecutions by chat.activeExecutions.collectAsState()
        val auxiliaryExecution by chat.auxiliaryExecution.collectAsState()
        val runMode by chat.runMode.collectAsState()
        val roomGoal by chat.goalState.collectAsState()
        val roomViewport by chat.viewportState.collectAsState()
        val composerReady by chat.selectionReady.collectAsState()
        val roomHasEarlier by chat.hasEarlier.collectAsState()
        val roomActivities by chat.workActivities.collectAsState()
        val runEvents by chat.runTimeline.collectAsState()
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
        val roomProjects by app.repository.chat.projects.collectAsState(initial=emptyList())
        val roomThreads by app.repository.chat.threads.collectAsState(initial=emptyList())
        val currentBranchId=roomThreads.firstOrNull{it.id==selectedThreadId}?.activeBranchId.orEmpty()
        var roomBranches by remember(selectedThreadId){mutableStateOf<List<UiChatBranch>>(emptyList())}
        LaunchedEffect(selectedThreadId,currentBranchId){
            roomBranches=emptyList()
            if(selectedThreadId.isNotBlank())try{
                roomBranches=app.repository.chat.branches(selectedThreadId).map{UiChatBranch(it.id,it.title)}
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){status.value="대화 버전 목록을 불러오지 못했습니다."}
        }
        val pending by approval.collectAsState()
        val working by busy.collectAsState()
        val messages by lines.collectAsState()
        val installed by app.models.models.collectAsState()
        val pairedTextSettings by com.user.localagent.compute.PcComputeSettings.get(this@MainActivity).state.collectAsState()
        val chosenId by preferredModelId.collectAsState()
        val imageWork by app.imageStudio.state.collectAsState()
        val webWork by app.webResearch.state.collectAsState()
        val project by app.projectWorkspace.state.collectAsState()
        val projectFolders by produceState<Map<String,String>>(emptyMap(),roomProjects.map{it.id}) {
            val ids=roomProjects.map{it.id}
            if(ids.isEmpty())value=emptyMap()
            else combine(ids.map{app.workspaceFor(it).state}){states->ids.zip(states.map{it.name}).toMap()}.collect{value=it}
        }
        val attachments by app.chatAttachments.state.collectAsState()
        val preparingAttachments by attachmentPreparing.collectAsState()
        val steerRunId=chat.activeRunId
        val steerThreadId=chat.activeThreadId
        val roomAuxiliary=auxiliaryExecution?.takeIf {it.threadId==composerThreadId}
        val navigation=ChatNavigation(
            projects=roomProjects.filterNot{it.archived}.map{UiProject(it.id,it.title,folderLabel=projectFolders[it.id]?:"프로젝트 전용 폴더",instructions=it.settings.instructions,modelId=it.settings.modelId,thinking=it.settings.thinking,contextSize=it.settings.contextSize,memoryMode=it.settings.memoryMode,useOwnerMemory=it.settings.useOwnerMemory,learnOwnerFacts=it.settings.learnOwnerFacts,revision=it.revision,disabledTools=it.settings.disabledTools)},
            threads=roomThreads.map{thread->UiThread(thread.id,thread.projectId.orEmpty(),thread.title,thread.pinned,thread.archived,thread.deletedAt!=null,thread.updatedAt,
                runStatus=activeExecutions.firstOrNull {it.threadId==thread.id}?.let {run->
                    when {run.waitingForApproval->"승인 대기";run.waitingForResource=="PHONE"->"휴대폰 계산 대기";run.waitingForResource=="PC"->"PC 계산 대기";run.computeLocation=="PC"->"PC에서 실행 중";run.computeLocation=="PHONE"->"휴대폰에서 실행 중";else->"작업 준비 중"}
                }?:queuedRequests.count{it.threadId==thread.id}.takeIf{it>0}?.let{"대기 ${it}개"}.orEmpty())},
            selectedProjectId=selectedProjectId,selectedThreadId=selectedThreadId,supportsTrash=false,
            actions=ChatNavigationActions(
                onSelectThread=chat::selectThread,onNewThread=chat::newThread,onSelectProject=chat::selectProject,
                onSaveProject=chat::saveProject,onDeleteProject=chat::deleteProject,onChooseProjectFolder={id->folderProjectId=id;projectFolder.launch(null)},
                onRenameThread=chat::renameThread,onPinThread=chat::pinThread,onArchiveThread=chat::archiveThread,
                onClearThread=chat::clearThread,onDeleteThread=chat::deleteThread,onDuplicateThread=chat::duplicateThread,
                onMoveThread=chat::moveThread,onExportThread=chat::exportThread,
                onSearchThreads=chat::searchThreads,
            ),
        )
        Scaffold(containerColor=Background,contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)) {padding->
            if(tab==0&&!nativeChat){
                val runForRoom=steerRunId?.takeIf{steerThreadId==composerThreadId}
                val epoch=(runForRoom.orEmpty()+currentBranchId).hashCode().toLong().and(0x7fffffff)
                val modelChoices=listOf("auto" to "자동 · 구독 우선",com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID to "구독 Codex")+
                    installed.filter{it.verified&&it.runtime in setOf("litertlm","gguf")}.map{it.id to it.title}
                val snapshot=buildJsonObject{
                    put("threadId",composerThreadId);put("branchId",currentBranchId);put("ready",composerReady)
                    put("draft",roomDraft);put("composerEpoch",composerEpoch);put("busy",runForRoom!=null)
                    put("status",if(runForRoom!=null||activeExecutions.isEmpty())state else "다른 대화에서 작업 중")
                    put("modelId",chosenId);put("modelLabel",modelChoices.find{it.first==chosenId}?.second?:"선택한 모델")
                    put("activeRunId",runForRoom);put("actionEpoch",epoch);put("canSteer",runForRoom!=null&&attachments.isEmpty())
                    put("messages",buildJsonArray{messages.takeLast(160).forEach{message->add(buildJsonObject{
                        put("id",message.id);put("role",message.role);put("text",message.text.take(32000))
                    })}})
                    put("threads",buildJsonArray{roomThreads.filter{!it.archived&&it.deletedAt==null}.take(100).forEach{thread->add(buildJsonObject{
                        put("id",thread.id);put("title",thread.title);put("selected",thread.id==composerThreadId)
                    })}})
                    put("models",buildJsonArray{modelChoices.forEach{(id,label)->add(buildJsonObject{put("id",id);put("label",label)})}})
                    put("attachments",buildJsonArray{attachments.forEach{add(buildJsonObject{put("id",it.id);put("name",it.name)})}})
                    put("collab",collaborationState)
                    put("tasks",buildJsonArray{activeExecutions.filter{it.threadId==composerThreadId}.forEach{run->add(buildJsonObject{
                        put("id",run.runId);put("phase",if(run.waitingForApproval)"approval"else "running")
                        put("label",if(run.waitingForApproval)"승인 확인 필요"else "작업 진행 중");put("detail",run.status)
                        put("canCancel",run.runId==runForRoom)
                        put("evidence",buildJsonArray{roomActivities.values.firstOrNull{it.runId==run.runId&&it.threadId==composerThreadId}?.events.orEmpty().takeLast(8).forEach{event->add(buildJsonObject{
                            put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                        })}})
                    })}
                        roomActivities.values.filter{it.threadId==composerThreadId&&it.assistantMessageId in messages.map{m->m.id}&&it.status !in setOf("queued","running","waiting_approval")&&activeExecutions.none{run->run.runId==it.runId}}
                            .distinctBy{it.runId}.sortedBy{it.updatedAt}.takeLast(12).forEach{activity->add(buildJsonObject{
                                put("id",activity.runId);put("phase",activity.status)
                                put("label",when(activity.status){"completed"->"응답 종료";"paused"->"확인 후 이어가기";"cancelled"->"중단됨";"failed"->"실행 실패";else->"실행 종료"})
                                put("detail","실제 실행 기록을 확인할 수 있습니다.");put("canCancel",false)
                                put("evidence",buildJsonArray{activity.events.takeLast(8).forEach{event->add(buildJsonObject{
                                    put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                                })}})
                            })}
                    })
                }.toString()
                ReactChatHost(snapshot,onUnavailable={nativeChat=true},modifier=Modifier.fillMaxSize().padding(padding).imePadding(),onCommand={command->
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
                            "newThread"->chat.newThread()
                            "selectModel"->payload.getValue("id").jsonPrimitive.content.takeIf{id->modelChoices.any{it.first==id}}?.let{id->chooseConversationModel(id,composerThreadId)}
                            "attach"->{val room=roomThreads.firstOrNull{it.id==composerThreadId};if(room!=null&&!attachmentPreparing.value){attachmentScope=Triple(room.id,room.revision,room.activeBranchId);attachFiles.launch(arrayOf("*/*"))}}
                            "navigate"->{val panel=payload.getValue("panel").jsonPrimitive.content;collaborationVisible=panel=="collab";if(collaborationVisible)collaboration.refresh()else tab=when(panel){"chat"->0;"images"->7;"tools"->3;"projects"->10;"history"->6;else->4}}
                            "collabRefresh"->collaboration.refresh(payload.getValue("bindingEpoch").jsonPrimitive.long)
                            "collabSend"->collaboration.post(payload.getValue("clientMessageId").jsonPrimitive.content,payload.getValue("text").jsonPrimitive.content,payload.getValue("recipient").jsonPrimitive.content,payload.getValue("bindingEpoch").jsonPrimitive.long)
                            "copy"->{val id=payload.getValue("messageId").jsonPrimitive.content;if(messages.any{it.id==id&&it.role=="assistant"})chat.copyMessage(id)}
                            "nativeDetails"->nativeChat=true
                        }
                    }
                })
            }
            else if(tab==0)ChatWorkspace(messages,working||preparingAttachments,state,installed.filterNot {com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)},chosenId,{id->chooseConversationModel(id,composerThreadId)},{text->sendToThread(text,composerThreadId)},
                {if(steerRunId!=null)chat.stopRun(steerRunId,composerThreadId)else if(preparingAttachments)attachmentJob?.cancel()else roomAuxiliary?.let {chat.stopAuxiliary(it.operationId,it.threadId)}},
                {index->if(index==-1){chat.newThread();tab=0}else {tab=index.coerceIn(0,10);if(tab==0)refreshVisibleHistory()}},
                Modifier.fillMaxSize().padding(padding),projectName=project.name,onProjectClick={tab=10},onAttach={
                    val room=roomThreads.firstOrNull{it.id==composerThreadId}
                    if(chat.selectionReady.value&&chat.selectedThreadId.value==composerThreadId&&room!=null&&!attachmentPreparing.value){attachmentScope=Triple(room.id,room.revision,room.activeBranchId);attachFiles.launch(arrayOf("*/*"))}
                },
                attachments=attachments.map {ChatAttachmentChip(it.id,it.name,when{it.mimeType.startsWith("image/")->"사진";it.mimeType.startsWith("video/")->"영상";else->"파일"})},
                onRemoveAttachment={if(chat.selectionReady.value&&chat.selectedThreadId.value==composerThreadId)app.chatAttachments.remove(it)},navigation=navigation,composerKey=composerThreadId,
                draftValue=roomDraft,onDraftChange={value->chat.updateDraft(value,composerThreadId)},
                messageActions=UiMessageActions(onEdit=chat::editMessage,onRegenerate=chat::regenerate,onFork=chat::forkMessage,onCopy=chat::copyMessage,onExport=chat::exportMessage),
                answerStates=answerStates,onContinue=chat::continueAnswer,queuedCount=queuedRequests.size,queuedPreview=queuedRequests.take(3).map{request->(roomThreads.firstOrNull{it.id==request.threadId}?.title?:"대화")+" · "+request.text.lineSequence().firstOrNull().orEmpty().ifBlank{"첨부 자료 분석"}.take(100)},onQueue={text->sendToThread(text,composerThreadId)},
                branches=roomBranches,currentBranchId=currentBranchId,onSelectBranch={branchId->chat.selectBranch(selectedThreadId,branchId)},
                onShowQueue={queueOpen=true},
                onManageGoal={if(chat.selectionReady.value&&chat.selectedThreadId.value==composerThreadId)goalEditorThreadId=composerThreadId},onShowSkills={skillLibraryOpen=true},
                runTimeline=runEvents.filter{it.threadId.isBlank()||it.threadId==selectedThreadId},
                onSteer=if(working&&steerRunId!=null&&steerThreadId==selectedThreadId)({text->chat.steer(text,steerRunId,steerThreadId)})else null,
                activeRunKey=steerRunId,viewport=roomViewport,onViewportChange=chat::updateViewport,hasEarlier=roomHasEarlier,onLoadEarlier=chat::loadEarlier,composerReady=composerReady,pairedPcTextAvailable=pairedTextSettings.paired&&!pairedTextSettings.claudeText,
                activityByMessageId=roomActivities,onLoadOlderActivity=chat.workActivityController::loadOlder,
                activeGoalLabel=roomGoal?.takeIf {it.threadId==composerThreadId&&it.projectId.orEmpty()==selectedProjectId}?.outcome,
                backgroundWork={Column {
                    TextButton(onClick={nativeChat=false}){Text("새 대화 화면으로 돌아가기")}
                    TextButton(onClick={nativeChat=false;collaborationVisible=true;collaboration.open()}){Text("Codex · Claude 협업방")}
                    if(imageWork.running)TextButton(onClick={tab=7}){Text("이미지 작업 진행 중 · ${imageWork.phase} · 열기")}
                    CallFollowUpConversationStatus(app,composerThreadId)
                    activeExecutions.filter {it.threadId!=composerThreadId}.forEach {run->
                        TextButton(onClick={chat.selectThread(run.threadId)}){
                            Text(if(run.waitingForApproval)"다른 대화에 확인할 작업이 있습니다 · 열기"else if(run.computeLocation=="PC")"다른 대화에서 PC 계산 중 · 열기"else"다른 대화에서 작업 중 · 열기")
                        }
                    }
                }})
            else Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(horizontal=18.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={if(tab in setOf(4,7,10)){tab=0;refreshVisibleHistory()}else tab=4}){Text(if(tab in setOf(4,7,10))"‹ 대화"else"‹ 설정·참고")}
                    Text(listOf("","모델 관리","기억과 작업","문서 작업실","설정·참고","자료 연결과 분석","대화 기록","이미지 작업실","문자·일정 분석","웹·쇼핑 검색","프로젝트 폴더")[tab],style=MaterialTheme.typography.titleMedium)
                }
                val panelStatus=if(tab==7)imageStudioStatus else state
                if(panelStatus.isNotBlank())Text(panelStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
                when(tab){1->ModelsScreen();2->MemoryScreen();3->Box(Modifier.weight(1f)){ToolShelfPanel(app,{status.value=it}){request->tab=0;send(request)}};4->ExtensionsScreen{tab=it};5->Box(Modifier.weight(1f)){DataAnalysisPanel(app,{request,threadId->tab=0;sendToThread(request,threadId)},{uri,origin->tab=0;transcribe(uri,origin)})};6->Box(Modifier.weight(1f)){ConversationHistoryPanel(app){status.value=it}};7->Box(Modifier.weight(1f)){ImageStudioPanel(app,{imageStudioStatus=it})};8->Box(Modifier.weight(1f)){SmsAnalysisPanel(app){status.value=it}};9->Box(Modifier.weight(1f)){WebResearchPanel(app){status.value=it}};10->Box(Modifier.weight(1f)){ProjectWorkspacePanel(app,{status.value=it}){request->tab=0;send(request)}}}
            }
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
        pending?.let { action -> ActionApprovalDialog(action,action.reviewText?:actionSummary(action.call)) }
    }
    @Composable private fun ColumnScope.ModelsScreen() {
        val installed by app.models.models.collectAsState()
        val selectedId by preferredModelId.collectAsState()
        val media by app.models.mediaSelection.collectAsState()
        val asr by app.models.asrSelection.collectAsState()
        val progress by app.models.progress.collectAsState()
        val scope=rememberCoroutineScope()
        val options by modelOptions.collectAsState()
        val thinkingThreadId=app.runs.selectedThreadId.collectAsState().value
        val thinkingReady by app.runs.selectionReady.collectAsState()
        var endurance by remember { mutableStateOf(false) }
        var downloadJob by remember {mutableStateOf<Job?>(null)}
        var hashTarget by remember {mutableStateOf<String?>(null)}
        var expectedHash by remember {mutableStateOf("")}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {TextButton(onClick={
                com.user.localagent.externalagent.ExternalAgentSettings.get(this@MainActivity).setEnabled(false)
                status.value="클라우드 도구 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다."
            }){Text("클라우드 도구 연결 해제")}}
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
            if(progress!=null) item {val p=progress!!;InfoCard(if(p.phase=="hash")"모델 무결성 검증" else "모델 다운로드","${p.title}\n${p.bytes/1024/1024} / ${p.total/1024/1024} MB");LinearProgressIndicator(progress={if(p.total>0)(p.bytes.toDouble()/p.total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());TextButton(onClick={downloadJob?.cancel()}){Text("중단 · 다음 다운로드에서 이어받기")}}
            items(app.models.candidates.filterNot {com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { candidate -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(candidate.title,fontWeight=FontWeight.Bold)
                Text("공식 메타데이터에서 버전·해시를 확인한 뒤 다운로드",style=MaterialTheme.typography.bodySmall)
                Button(enabled=progress==null,onClick={downloadJob=scope.launch { status.value="모델 출처 확인 중";try{app.models.downloadBundle(candidate);status.value="다운로드·해시 검증 완료"}catch(cancel:CancellationException){status.value="중단했습니다. 받은 부분은 이어받기에 사용합니다.";throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"} }}) {Text("다운로드")}
            } } }
            item {Text("설치한 모델",style=MaterialTheme.typography.titleMedium)}
            items(installed.filterNot {com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { model -> Surface(color=Panel,shape=RoundedCornerShape(16.dp)) {Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(model.title,fontWeight=FontWeight.Bold)
                Text(if(selectedId==model.id) "이 대화에서 선택됨" else "선택 가능",color=Mint)
                if(model.id in com.user.localagent.models.GemmaMediaCatalog.modelIds) {
                    val ready=app.models.mediaReady(model)
                    Text(if(ready)"음성 내용 분석·사진 분석 지원"else"사진 분석 지원 파일 다운로드 필요",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=selectedId==model.id,enabled=model.verified&&thinkingReady,onClick={chooseConversationModel(model.id,thinkingThreadId)},label={Text("이 대화에서 사용")})
                        FilterChip(selected=media.vision==model.id,enabled=ready,onClick={scope.launch {app.models.selectMedia("vision",model.id)}},label={Text("사진용")})
                    }
                    if(model.id=="gemma-e4b")Text("음성·사진은 CPU 2스레드·입력량 제한으로 부하를 낮춥니다. 메모리 부족·과열 시 작업을 보류합니다.",style=MaterialTheme.typography.bodySmall)
                }
                if(!model.verified)TextButton(onClick={hashTarget=model.id;expectedHash=""}){Text("원본 SHA-256 등록")}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled=model.verified&&thinkingReady,onClick={chooseConversationModel(model.id,thinkingThreadId)}){Text("선택")}
                    TextButton(onClick={scope.launch {runBenchmark(model.path,if(endurance)10 else 0)}}){Text("벤치마크")}
                    TextButton(onClick={scope.launch {maintainModelFiles {app.models.delete(model.id);status.value="모델을 삭제했습니다."}}}){Text("삭제")}
                }
            } } }
            item {InfoCard("전용 음성 인식", "녹음 전사는 전용 ASR이, 전사 내용의 요약·분석은 선택한 대화 모델이 담당합니다. 모델은 순서대로 메모리에 올립니다.")}
            item {
                val deviceAsr by app.models.deviceAsrStatus.collectAsState()
                Surface(color=Panel,shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    Text("기기 내 음성 AI · ML Kit",fontWeight=FontWeight.Bold)
                    Text("S26의 시스템 AI 활용 후보 · 한국어·영어 · 기기·언어별 지원 확인",style=MaterialTheme.typography.bodySmall)
                    Text(deviceAsr.message,style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.prepareDeviceAsr(deviceAsr.downloadable)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"기기 내 AI 확인 실패"}}}){Text(if(deviceAsr.downloadable)"시스템 AI 준비"else"지원 확인")}
                        FilterChip(selected=asr.modelId==com.user.localagent.models.AsrCatalog.DEVICE_MODEL,enabled=deviceAsr.available&&asr.language!="auto",onClick={scope.launch {app.models.selectAsr(com.user.localagent.models.AsrCatalog.DEVICE_MODEL)}},label={Text("전사용")})
                    }
                }}
            }
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("ko" to "한국어", "en" to "영어", "auto" to "자동 감지").forEach {(code,label)->
                    FilterChip(selected=asr.language==code,enabled=code!="auto"||asr.modelId!=com.user.localagent.models.AsrCatalog.DEVICE_MODEL,onClick={scope.launch {app.models.selectAsrLanguage(code)}},label={Text(label)})
                }
            }}
            items(app.models.candidates.filter {it.id in com.user.localagent.models.AsrCatalog.modelIds}) {candidate->
                Surface(color=Panel,shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    Text(candidate.title,fontWeight=FontWeight.Bold)
                    Text(if(candidate.id=="asr-qwen-17b")"정확도 우선 · 지원 파일 포함 약 2.8GB"else"가벼운 모드 · 지원 파일 포함 약 1.2GB",style=MaterialTheme.typography.bodySmall)
                    Text("기기 내 처리 · Apache 2.0 · 공식 배포 파일·SHA-256 확인",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.downloadBundle(candidate);status.value="음성 인식 파일 검증 완료"}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"}}}){Text("다운로드")}
                        FilterChip(selected=asr.modelId==candidate.id,enabled=app.models.asrReady(candidate.id),onClick={scope.launch {app.models.selectAsr(candidate.id)}},label={Text("전사용")})
                    }
                }}
            }
            item {InfoCard("모델 역할을 나눕니다","E2B는 빠른 대화, E4B·Qwen은 수동 비교용입니다. 이미지 작업실에서는 별도 로컬 이미지 모델을 사용하며, 대화 모델을 먼저 메모리에서 내립니다.")}
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
    @Composable private fun ColumnScope.ExtensionsScreen(onOpen:(Int)->Unit) {
        var section by rememberSaveable {mutableIntStateOf(0)}
        val assistantBusy by app.runs.busy.collectAsState()
        val imageWork by app.imageStudio.state.collectAsState()
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("참고·도구","자동화","앱 연결","패치·확장").forEachIndexed {index,label->FilterChip(section==index,onClick={section=index},label={Text(label)})}}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(section==0){
                item {Text("대화에서 파일·사진·영상을 첨부하고 문서·쇼핑·작업을 요청하세요. 아래 화면은 설정과 결과 확인용입니다.",style=MaterialTheme.typography.bodySmall)}
                items(listOf("프로젝트 폴더" to 10,"모델 관리" to 1,"기억과 할 일" to 2,"내장 도구·권한 상태" to 3,"대화 기록" to 6,"자료 연결·분석" to 5,"문자·일정 후보 관리" to 8,"웹·쇼핑 출처 기록" to 9)){(label,destination)->OutlinedButton(onClick={onOpen(destination)},modifier=Modifier.fillMaxWidth()){Text(label)}}
            }
            if(section==1)item {AutomationPanel(this@MainActivity,app.automation,{startActivity(it)},{status.value=it},{app.repository.discardProposedMemories();Unit})}
            if(section==1)item {CallFollowUpPanel(app,{status.value=it}){threadId->app.runs.selectThread(threadId);onOpen(0)}}
            if(section==2)item {com.user.localagent.integrations.MessengerPanel(this@MainActivity,onAsk={request->onOpen(0);send(request)}){status.value=it}}
            if(section==2)item {com.user.localagent.integrations.ScreenBridgePanel(this@MainActivity,{startActivity(it)}){status.value=it}}
            if(section==2)item {OutlinedButton(onClick={startActivity(Intent(this@MainActivity,com.user.localagent.connections.ConnectionsActivity::class.java))},modifier=Modifier.fillMaxWidth()){Text("Google·메일·문서 앱 연결 설정")}}
            if(section==2)item {PcComputePanel(assistantBusy||imageWork.running||RuntimeGate.benchmark.get()){status.value=it}}
            if(section==3){
            item {com.user.localagent.updates.UpdatePanel(app.updates,{startActivity(it)},{status.value=it})}
            item {ExtensionInspectionPanel{status.value=it}}
            item {val scope=rememberCoroutineScope();OutlinedButton(onClick={scope.launch {app.repository.revokeGrant("builtin");status.value="기본 도구의 반복 작업 허용을 해제했습니다."}}){Text("반복 작업 허용 초기화")}}
            }
            item {Text("버전 ${BuildConfig.VERSION_NAME} · ${Build.MODEL}",style=MaterialTheme.typography.bodySmall)}
        }
    }
    @Composable private fun InfoCard(title:String,body:String) {ZipHapCard {Text(title,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}}
}
