@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
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
import java.io.File
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.user.localagent.ui.*

private val launchIntroClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

private const val CONTACTS_TAB = 11
private const val DISCOVER_TAB = 12
private const val HISTORY_SEARCH_TAB = 13
private val Background = ZipHapColors.DeepNavy
private val Panel = ZipHapColors.NavySurface
private val Accent = ZipHapColors.Periwinkle
private val Mint = ZipHapColors.Mint
data class ChatLine(val id:String = UUID.randomUUID().toString(), val role:String, val text:String,val requestText:String?=null,val createdAt:Long?=null)
data class PendingAction(val call:ToolCall, val decision:CompletableDeferred<Boolean>,val remember:MutableStateFlow<Boolean> = MutableStateFlow(false),val runId:String="",val threadId:String="",val projectId:String?=null,val reviewText:String?=null,
    val recipientChoices:List<com.user.localagent.core.ContactCandidate> =emptyList(),val selectedRecipient:MutableStateFlow<String?> = MutableStateFlow(null),val branchId:String=""){
    val presentationId:String=UUID.randomUUID().toString()
}

class MainActivity : ComponentActivity() {
    private val app by lazy { (application as LocalAgentApplication).container }
    private val lines get()=app.runs.visibleLines
    private val status get()=app.runs.status
    private val busy get()=app.runs.busy
    private val approval get()=app.runs.approval

    private var attachmentJob:Job?=null
    private var attachmentJobThreadId:String?=null
    private var attachmentScope:Triple<String,Long,String>?=null
    private var cameraAttachmentName:String?=null

    private fun cameraAttachmentFile(name:String?)=name?.takeIf {
        it.matches(Regex("camera-[0-9]+[.]jpg"))
    }?.let {File(File(cacheDir,"camera_attachments"),it)}

    private fun cameraAttachmentUri(file:File)=FileProvider.getUriForFile(this,"$packageName.files",file)
    private val attachmentPreparing=MutableStateFlow(false)
    private class VoiceTalk(val threadId:String,val branchId:String) {
        var stage by mutableStateOf("idle")
        var transcript by mutableStateOf("")
        var reply by mutableStateOf("")
    }

    private class VoicePrompt(val threadId:String,val branchId:String,val talk:VoiceTalk?) {
        var stage by mutableStateOf("permission")
    }

    private var voiceTalk by mutableStateOf<VoiceTalk?>(null)
    private var voiceTurn:Job?=null
    private var replySpeech:Job?=null
    private var spokenMessageId by mutableStateOf<String?>(null)

    private fun currentVoiceRoom(threadId:String,branchId:String):Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&app.runs.selectionReady.value&&
            app.runs.selectedThreadId.value==threadId&&app.runs.viewportState.value?.branchId==branchId

    private fun stopReplySpeech(){
        spokenMessageId=null
        replySpeech?.cancel()
        replySpeech=null
    }

    private fun closeVoiceTalk(){
        voiceTalk=null
        voiceTurn?.cancel()
        voiceTurn=null
        cancelVoicePrompt()
        stopReplySpeech()
    }

    private fun voiceInputBlocked():Boolean {
        val browser=com.user.localagent.integrations.AppWebAgent.get(this)
        return approval.value!=null||browser.ownerLoginCard.value!=null||browser.ownerWebsiteLogin.value!=null
    }

    private fun sendVoiceTalk(talk:VoiceTalk){
        val chat=app.runs
        if(voiceTalk!==talk||talk.stage!="review"||!currentVoiceRoom(talk.threadId,talk.branchId)||
            voiceInputBlocked()||chat.draft.value.isNotBlank()||app.chatAttachments.state.value.isNotEmpty()||
            attachmentPreparing.value||chat.activeExecutions.value.any{it.threadId==talk.threadId})return
        val runId=UUID.randomUUID().toString()
        if(!chat.sendFromReact(talk.transcript,talk.threadId,talk.branchId,runId))return
        talk.reply=""
        talk.stage="thinking"
        voiceTurn=lifecycleScope.launch {
            try {
                val store=app.repository.chat
                val run=withTimeoutOrNull(12_000){store.observeChatRun(runId).filterNotNull().first()}
                if(run==null){
                    if(voiceTalk===talk){
                        talk.stage="idle"
                        talk.reply="전송 상태는 채팅에서 확인해 주세요."
                    }
                    return@launch
                }
                val ended=store.observeChatRun(runId).first {
                    it==null||it.status !in setOf("queued","running","waiting_approval")
                }
                if(voiceTalk!==talk||!currentVoiceRoom(talk.threadId,talk.branchId))return@launch
                if(ended==null||ended.threadId!=talk.threadId||ended.branchId!=talk.branchId)return@launch
                val message=store.assistantForRun(ended.id)
                val text=message?.let {ChatSpeechText.prepare(store.fullMessageText(it.id).take(32_000),900)}.orEmpty()
                if(voiceTalk!==talk||!currentVoiceRoom(talk.threadId,talk.branchId))return@launch
                talk.transcript=""
                talk.reply=text.ifBlank { "작업 상태는 채팅에서 확인해 주세요." }
                if(text.isNotBlank()&&!voiceInputBlocked()&&ended.status in setOf("completed","paused")){
                    talk.stage="preparing"
                    speakChatText(text){
                        if(voiceTalk===talk&&talk.stage=="preparing")talk.stage="speaking"
                    }
                }
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){if(voiceTalk===talk)talk.reply="답변을 불러오지 못했어요. 채팅에서 확인해 주세요."}
            finally {if(voiceTalk===talk&&talk.stage in setOf("thinking","preparing","speaking"))talk.stage="idle"}
        }
    }

    private suspend fun speakChatText(text:String,onStarted:()->Unit={}){
        try {
            if(withTimeoutOrNull(120_000){app.phoneMedia.speak(text,preferSamsung=true,onStarted=onStarted)}==null)
                status.value="답변 읽기 시간이 끝났어요."
        }catch(_:TimeoutCancellationException){status.value="휴대폰의 한국어 음성을 시작하지 못했어요."}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){status.value="읽어주기를 시작하지 못했어요. 휴대폰의 한국어 음성 설정을 확인해 주세요."}
    }

    private fun toggleChatSpeech(message:ChatLine,threadId:String,branchId:String){
        if(spokenMessageId==message.id){stopReplySpeech();return}
        if(voiceTalk!=null||voicePrompt!=null||voiceInputBlocked()||!currentVoiceRoom(threadId,branchId))return
        val text=ChatSpeechText.prepare(message.text,2600)
        if(text.isBlank()){status.value="읽어줄 글이 없어요.";return}
        stopReplySpeech()
        spokenMessageId=message.id
        replySpeech=lifecycleScope.launch {
            try {speakChatText(text)}
            finally {if(spokenMessageId==message.id)spokenMessageId=null}
        }
    }

    private var voicePrompt by mutableStateOf<VoicePrompt?>(null)
    private var voicePermissionTarget:VoicePrompt?=null
    private var voiceDraftEpoch by mutableLongStateOf(0)
    private val voicePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        val target=voicePermissionTarget
        voicePermissionTarget=null
        if(target!=null&&voicePrompt===target){
            if(granted) lifecycleScope.launch { lifecycle.withResumed { listenForPrompt(target) } }
            else {
                voicePrompt=null
                val notice="마이크를 허용하면 음성으로 입력할 수 있어요."
                target.talk?.let{it.stage="idle";it.reply=notice}
                status.value=notice
            }
        }
    }

    private fun startVoicePrompt(threadId:String,branchId:String,talk:VoiceTalk?=null){
        if(voicePrompt!=null||voicePermissionTarget!=null||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        if(voiceInputBlocked())return
        stopReplySpeech()
        val target=VoicePrompt(threadId,branchId,talk)
        voicePrompt=target
        talk?.stage="permission"
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            listenForPrompt(target)
        else {
            voicePermissionTarget=target
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listenForPrompt(target:VoicePrompt){
        if(voicePrompt!==target)return
        val chat=app.runs
        fun currentRoom()=chat.selectionReady.value&&chat.selectedThreadId.value==target.threadId&&
            chat.viewportState.value?.branchId==target.branchId
        if(!currentRoom()||voiceInputBlocked()){
            voicePrompt=null
            target.talk?.stage="idle"
            return
        }
        target.stage="listening"
        target.talk?.stage="listening"
        app.phoneMedia.startListening(onText={text->
            if(voicePrompt===target){
                voicePrompt=null
                if(currentRoom()&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){
                    if(target.talk!=null){
                        if(voiceTalk===target.talk&&!voiceInputBlocked()){
                            target.talk.transcript=text.trim()
                            target.talk.stage="review"
                        }
                        return@startListening
                    }
                    val draft=chat.draft.value
                    val separator=if(draft.isEmpty()||draft.last().isWhitespace())"" else " "
                    val combined=draft+separator+text.trim()
                    if(combined.length<=12_000){
                        chat.updateDraft(combined,target.threadId)
                        voiceDraftEpoch++
                    }else status.value="음성 내용을 더하면 12,000자를 넘어요. 기존 입력은 유지했어요."
                }
            }
        },onError={message->
            if(voicePrompt===target){
                voicePrompt=null
                target.talk?.let{it.stage="idle";it.reply=message}
                if(currentRoom())status.value=message
            }
        })
    }

    private fun cancelVoicePrompt(){
        val target=voicePrompt?:return
        voicePrompt=null
        target.talk?.stage="idle"
        if(target.stage!="permission")app.phoneMedia.cancelListening()
    }

    private val officeCalendarReadGranted=MutableStateFlow(false)
    private val officeCalendarPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        officeCalendarReadGranted.value=granted
        status.value=if(granted)"휴대폰 일정을 읽을 수 있어요."else"일정 접근을 허용하지 않았어요."
    }

    private val modelOptions get()=app.runs.modelOptions
    private val preferredModelId get()=app.runs.preferredModelId
    private val requestedTab=MutableStateFlow<Int?>(null)
    private val attachFiles=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        prepareAttachmentUris(uris)
    }
    private val attachPhotos=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)){uris->
        prepareAttachmentUris(uris)
    }
    private val attachCamera=registerForActivityResult(ActivityResultContracts.TakePicture()){captured->
        val file=cameraAttachmentFile(cameraAttachmentName)
        cameraAttachmentName=null
        val uri=if(captured&&file?.isFile==true) runCatching{cameraAttachmentUri(file)}.getOrNull() else null
        if(captured&&uri==null)status.value="찍은 사진을 읽지 못했어요."
        prepareAttachmentUris(listOfNotNull(uri),file)
    }

    private fun prepareAttachmentUris(uris:List<android.net.Uri>,temporaryFile:File?=null){
        val origin=attachmentScope
        attachmentScope=null
        if(uris.isEmpty()||attachmentPreparing.value){temporaryFile?.delete();return}
        attachmentJobThreadId=origin?.first
        attachmentPreparing.value=true
        attachmentJob=lifecycleScope.launch {
            try{
                check(origin!=null){"첨부할 원래 대화를 확인하지 못했습니다. 해당 방에서 다시 선택해주세요."}
                val room=app.repository.chat.thread(origin.first)
                check(room!=null&&room.revision==origin.second&&room.activeBranchId==origin.third){"자료를 선택하는 동안 원래 대화가 변경되었습니다. 다시 선택해주세요."}
                status.value="첨부 파일을 기기에 준비 중";app.chatAttachments.add(uris,origin.first);status.value="원래 대화에 파일을 첨부했습니다. 해당 방에서 요청을 입력해주세요."
            }
            catch(cancel:CancellationException){status.value="파일 첨부를 취소했어요.";throw cancel}
            catch(error:Exception){status.value=error.message?:"첨부 파일을 준비하지 못했습니다."}
            finally{
                temporaryFile?.delete()
                attachmentJob=null;attachmentJobThreadId=null;attachmentPreparing.value=false
            }
        }
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) lifecycleScope.launch {
            runCatching { app.models.importModel(uri, "가져온 로컬 모델") }
                .onSuccess { status.value = "파일을 가져왔어요. 배포처의 확인 코드를 입력해 주세요." }
                .onFailure { status.value = it.message ?: "모델 가져오기 실패" }
        }
    }

    private var launchIntroPending by mutableStateOf(false)
    private var launchIntroChatReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAttachmentName=savedInstanceState?.getString("camera-attachment-name")
        savedInstanceState?.let {saved->saved.getString("attachment-thread-id")?.let {id->saved.getString("attachment-branch-id")?.let {branch->attachmentScope=Triple(id,saved.getLong("attachment-thread-revision"),branch)}}}
        if(intent.hasExtra("open_tab"))requestedTab.value=intent.getIntExtra("open_tab",0).coerceIn(0,10)
        launchIntroPending = shouldShowLaunchIntro(
            restoring = savedInstanceState != null,
            fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER),
            requestedTab = requestedTab.value,
        ) && launchIntroClaimed.compareAndSet(false,true)
        val lightBars=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(lightBars,lightBars)
        WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
        lifecycleScope.launch { runCatching { app.models.refresh() } }
        app.runs.refreshVisibleHistory()
        openCallReport(intent)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){if(!app.runs.hasActiveRuns.value && !RuntimeGate.benchmark.get()&&!app.smsAnalysis.state.value.running&&!app.webResearch.state.value.running&&!app.imageStudio.state.value.running)app.updates.checkAndApply();delay(60_000)}}}
        setContent {
            ZipHapTheme {
                AppScreen()
                com.user.localagent.integrations.AppBrowserSurface(this@MainActivity)
            }
        }
    }
    override fun onStart(){
        super.onStart()
        RuntimeGate.foreground.set(true)
        officeCalendarReadGranted.value=checkSelfPermission(Manifest.permission.READ_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    override fun onSaveInstanceState(outState:Bundle){
        attachmentScope?.let {(id,revision,branch)->outState.putString("attachment-thread-id",id);outState.putLong("attachment-thread-revision",revision);outState.putString("attachment-branch-id",branch)}
        cameraAttachmentName?.let{outState.putString("camera-attachment-name",it)}
        super.onSaveInstanceState(outState)
    }
    override fun onPause(){
        if(voicePrompt?.stage!="permission")closeVoiceTalk()
        super.onPause()
    }
    override fun onStop(){if(voicePrompt?.stage!="permission")closeVoiceTalk();app.phoneMedia.cancelListening();RuntimeGate.foreground.set(false);app.scheduleIdleUnload();super.onStop()}
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
            requestedTab.value=if(threadId==app.runs.selectedThreadId.value)0 else 6
            val authRun=intent.getStringExtra(com.user.localagent.voice.AgentRunService.RESUME_BROWSER_AUTH_RUN)
                ?.takeIf {it.length in 1..128}?:return@launch
            lifecycle.withResumed { Unit }
            try {app.goalWaits.resumeConfirmedBrowserAuth(threadId,authRun)}
            catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){FailureLog.record(this@MainActivity,"browser_auth_resume_visible",error)}
        }
    }

    private fun send(text:String):Boolean {if(attachmentPreparing.value){status.value="첨부 준비가 끝난 뒤 보내주세요.";return false};return app.runs.send(text)}
    private fun openDefaultMap() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("geo:0,0")))
        } catch (_:android.content.ActivityNotFoundException) {
            status.value="휴대폰에서 지도 앱을 열 수 없어요. 채팅에 장소 이름을 알려주셔도 찾아드릴게요."
        }
    }
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
    private fun refreshVisibleHistory()=app.runs.refreshVisibleHistory()
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
        var showServiceCatalog by rememberSaveable {mutableStateOf(false)}
        fun openPanel(destination:Int,fromSettings:Boolean=false){
            settingsPage=SettingsPage.HOME
            panelFromSettings=fromSettings
            manualSettings=fromSettings
            showServiceCatalog=false
            tab=when(destination){2,5,8->0;else->destination}
        }
        var chatScreenAttempt by remember {mutableIntStateOf(0)}
        var chatScreenUnavailable by remember {mutableStateOf(false)}
        var chatManagerOpen by rememberSaveable {mutableStateOf(false)}
        val webAgent=remember {com.user.localagent.integrations.AppWebAgent.get(this)}
        val loginCard by webAgent.ownerLoginCard.collectAsState()
        val websiteLogin by webAgent.ownerWebsiteLogin.collectAsState()
        val websiteLoginFolded by webAgent.ownerLoginFolded.collectAsState()
        val websiteWorks by webAgent.ownerWebsiteWorks.collectAsState()
        val websiteBinding by webAgent.ownerWorkBinding.collectAsState()
        val bookingPayment by webAgent.ownerPaymentCard.collectAsState()
        var connectionsOpen by rememberSaveable {mutableStateOf(false)}
        var connectionServiceId by rememberSaveable {mutableStateOf<String?>(null)}
        var connectionStatus by remember {mutableStateOf<String?>(null)}
        LaunchedEffect(loginCard?.id){if(loginCard!=null){chatManagerOpen=false;openPanel(0)}}
        var composerEpoch by remember {mutableLongStateOf(0)}
        val submittedTokens=remember {linkedSetOf<String>()}
        var imageStudioStatus by rememberSaveable {mutableStateOf("")}
        val requested by requestedTab.collectAsState()
        LaunchedEffect(requested){requested?.let {openPanel(it);requestedTab.value=null}}
        val state by status.collectAsState()
        val chat=app.runs
        val selectedThreadId by chat.selectedThreadId.collectAsState()
        // Freeze the value, not the delegated State getter, in callbacks retained by an old IME.
        val composerThreadId=selectedThreadId
        var attachmentPickerThread by rememberSaveable{mutableStateOf<String?>(null)}
        var attachmentPickerBranch by rememberSaveable{mutableStateOf<String?>(null)}
        val officeOpen=composerThreadId==ZipHapRoom.OFFICE.threadId
        val calendarGranted by officeCalendarReadGranted.collectAsState()
        val characterSettings=remember {getSharedPreferences("jipsooni-character",MODE_PRIVATE)}
        var character by remember {mutableStateOf(com.user.localagent.ui.CharacterPreference.load(characterSettings))}
        // 나의 집순이 > 집순이 모습 바꾸기: asks the chat WebView to open its one picker; the web acknowledges it.
        var characterPickerEpoch by remember {mutableStateOf<Long?>(null)}
        var characterPickerLast by rememberSaveable {mutableStateOf(0L)}
        val guideSettings=remember{getSharedPreferences("jipsooni-introduction",MODE_PRIVATE)}
        // The previous greeting flag does not acknowledge the new service and job cards.
        val serviceGuideKey="services-and-jobs-dismissed"
        var officeGuideDismissed by remember{mutableStateOf(guideSettings.getBoolean(serviceGuideKey,false))}
        fun dismissOfficeGuide(){
            if(!officeGuideDismissed){
                guideSettings.edit().putBoolean(serviceGuideKey,true).apply()
                officeGuideDismissed=true
            }
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
        LaunchedEffect(selectedThreadId){if(goalEditorThreadId!=selectedThreadId)goalEditorThreadId=null}
        val roomThreads by app.repository.chat.threads.collectAsState(initial=emptyList())
        val currentBranchId=roomThreads.firstOrNull{it.id==selectedThreadId}?.activeBranchId.orEmpty()
        DisposableEffect(composerThreadId,currentBranchId,tab,chatScreenUnavailable,composerReady){
            onDispose {
                closeVoiceTalk()
            }
        }
        var messageMenuId by remember(composerThreadId,currentBranchId){mutableStateOf<String?>(null)}
        var fileMessageId by remember(composerThreadId,currentBranchId){mutableStateOf<String?>(null)}
        var messageEdit by remember(composerThreadId,currentBranchId){mutableStateOf<String?>(null)}
        val answerStates by chat.answerStates.collectAsState()
        val pending by approval.collectAsState()
        val voiceProtected=pending!=null||loginCard!=null||websiteLogin!=null||connectionsOpen
        LaunchedEffect(voiceProtected){
            if(voiceProtected){
                stopReplySpeech()
                cancelVoicePrompt()
                if(voiceTalk?.stage in setOf("preparing","speaking"))voiceTurn?.cancel()
            }
        }
        val pendingActions by chat.pendingActions.collectAsState()
        var otherApprovalOpened by remember {mutableStateOf<String?>(null)}
        val messages by lines.collectAsState()
        val previewMessageIds = messages.takeLast(160).filter { it.role == "assistant" }.map { it.id }.toSet()
        val resultPreviews = key(composerThreadId, currentBranchId) {
            val result by produceState(kotlinx.serialization.json.JsonArray(emptyList()), previewMessageIds, activeExecutions.filter { it.threadId == composerThreadId }.map { it.runId }) {
                value = com.user.localagent.ui.ChatResultPreviews.load(
                    this@MainActivity, app, composerThreadId, currentBranchId, previewMessageIds)
            }
            result
        }
        val pdfDraftIds = messages.takeLast(160).filter { it.role == "assistant" && GoalPdfEmail.MARKER in it.text }.map { it.id }
        val pdfDrafts by produceState<Map<String,kotlinx.serialization.json.JsonObject>>(emptyMap(), composerThreadId, currentBranchId, pdfDraftIds) {
            value = emptyMap()
            value = pdfDraftIds.mapNotNull { id ->
                try { GoalPdfEmail.lookup(app,composerThreadId,currentBranchId,id)?.second?.let { id to GoalPdfEmail.preview(it) } }
                catch (cancel:CancellationException) { throw cancel }
                catch (_:Exception) { null }
            }.toMap()
        }
        val fileReferences = remember(messages) {
            messages.takeLast(160).filter { it.role=="assistant" && "[[file:" in it.text }.mapNotNull {
                message->chatFileNames(message.text).takeIf { it.isNotEmpty() }?.let { message.id to it }
            }.toMap()
        }
        val verifiedFiles = key(composerThreadId,currentBranchId,fileReferences) {
            val result by produceState<Map<String,List<String>>>(emptyMap(),activeExecutions.map { it.runId }) {
                value=ChatResultPreviews.files(this@MainActivity,app,composerThreadId,currentBranchId,fileReferences)
            }
            result
        }
        val installed by app.models.models.collectAsState()
        val chosenId by preferredModelId.collectAsState()
        val attachments by app.chatAttachments.state.collectAsState()
        val steerRunId=chat.activeRunId
        val steerThreadId=chat.activeThreadId
        Scaffold(containerColor=if(tab==0&&!chatScreenUnavailable)ZipHapColors.Surface else Background,
            contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)) {padding->
            if(tab==0&&chatScreenUnavailable){
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                    ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_error,"대화를 열지 못했어요.","다시 열어 보고, 계속 안 되면 앱을 종료한 뒤 다시 실행해 주세요."){
                        Column(horizontalAlignment=Alignment.CenterHorizontally){
                            ZipPrimaryButton("다시 열기",{chatScreenAttempt++;chatScreenUnavailable=false},fullWidth=false)
                            ZipTextButton("설정",{openPanel(4)})
                        }
                    }
                }
            }else if(tab==0){
                val runForRoom=steerRunId?.takeIf{steerThreadId==composerThreadId}
                val roomAuxiliary=auxiliaryExecution?.takeIf{it.threadId==composerThreadId}
                val approvalThread=activeExecutions.firstOrNull{it.threadId!=composerThreadId&&it.waitingForApproval}?.threadId
                val epoch=(runForRoom.orEmpty()+currentBranchId).hashCode().toLong().and(0x7fffffff)
                val modelChoices=listOf("auto" to "자동",com.user.localagent.externalagent.ExternalAgentSettings.MODEL_ID to "Codex",com.user.localagent.externalagent.ExternalAgentSettings.CLAUDE_MODEL_ID to "Claude",
                    com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION to com.user.localagent.core.ConversationPrivacy.LOCAL_LABEL)
                val inWebsiteRoom=websiteBinding?.let{it.threadId==composerThreadId&&it.branchId==currentBranchId}==true
                val inlineSite=websiteLogin.takeIf{inWebsiteRoom&&!connectionsOpen&&!websiteLoginFolded}
                val inlineCredentials=loginCard.takeIf{inWebsiteRoom&&!connectionsOpen}
                val visibleWebsiteWork=websiteWorks.lastOrNull{work->
                    work.binding?.let{it.threadId==composerThreadId&&it.branchId==currentBranchId}==true
                }
                val inlineApproval=pending?.takeIf {
                    composerReady&&it.threadId==composerThreadId&&it.branchId==currentBranchId&&
                        inlineCredentials==null&&!connectionsOpen&&!it.decision.isCompleted
                }
                val websiteSlotId=inlineCredentials?.id?.toString()
                val nativeSlotId=websiteSlotId?:inlineApproval?.presentationId
                val websiteKeyboardOpen=inlineCredentials!=null&&WindowInsets.ime.getBottom(
                    androidx.compose.ui.platform.LocalDensity.current)>0
                var nativeSlot by remember(nativeSlotId,composerThreadId,currentBranchId){
                    mutableStateOf<com.user.localagent.ui.NativeChatSlot?>(null)
                }
                val visibleMessages=messages.takeLast(160)
                val visibleMessageIds=visibleMessages.mapTo(hashSetOf()){it.id}
                val requestTitles=visibleMessages.asSequence().filter{it.role=="user"}.associate { message ->
                    message.id to visibleActivityText(message.text.lineSequence().firstOrNull(String::isNotBlank).orEmpty().trim().take(100),100)
                }
                val activitiesByRun=roomActivities.values.filter{
                    it.threadId==composerThreadId&&it.assistantMessageId in visibleMessageIds
                }.associateBy{it.runId}
                val reports=visibleMessages.mapNotNull { roomActivities[it.id] }.filter {it.threadId==composerThreadId && it.executionReport.isNotEmpty()}
                    .associateBy {it.reportGroup?:it.runId}.values.associateBy {it.assistantMessageId}
                var openingPausedLogin by remember(composerThreadId,currentBranchId){mutableStateOf(false)}
                val snapshot=buildJsonObject{
                    put("threadId",composerThreadId);put("branchId",currentBranchId);put("ready",composerReady)
                    put("singleConversation",true)
                    put("character",character.toJson())
                    characterPickerEpoch?.let {put("openCharacterPicker",it)}
                    put("inlineLogin",connectionsOpen)
                    if(connectionsOpen)connectionStatus?.let{put("browserLoginStatus",it)}
                    if(inlineCredentials!=null||visibleWebsiteWork!=null)put("websiteCard",buildJsonObject{
                        val workRun=visibleWebsiteWork?.anchorRunId?:websiteBinding?.runId
                        put("id",websiteSlotId?:workRun.orEmpty())
                        visibleWebsiteWork?.binding?.runId?.let { put("runId",it) }
                        val anchor=visibleWebsiteWork?.anchorMessageId
                            ?:activeExecutions.firstOrNull {it.runId==workRun&&it.threadId==composerThreadId}?.userMessageId
                            ?:roomActivities.values.firstOrNull {it.runId==workRun&&it.threadId==composerThreadId}?.userMessageId
                        anchor?.let { put("messageId",it) }
                        val domain=inlineCredentials?.host?:visibleWebsiteWork?.host.orEmpty()
                        put("title",when(domain){
                            "www.kairb.org"->"KAIRB 온라인 교육"
                            com.user.localagent.integrations.NolConnectionGuide.host->"야놀자(NOL) 로그인"
                            else->domain
                        })
                        put("status",visibleWebsiteWork?.status.orEmpty())
                        put("working",visibleWebsiteWork?.working==true)
                        put("steps",JsonArray(visibleWebsiteWork?.steps.orEmpty().map(::JsonPrimitive)))
                        if(inlineCredentials!=null)put("inputHeight",when {websiteLoginFolded->108;inlineSite!=null->430;else->308})
                        if(websiteKeyboardOpen)put("keyboardOpen",true)
                    })
                    bookingPayment?.takeIf{!connectionsOpen&&it.binding.threadId==composerThreadId&&it.binding.branchId==currentBranchId}
                        ?.let { payment->put("bookingPayment",buildJsonObject {
                            put("id",payment.id);put("seller","NICEPAY");put("amount",payment.amount);put("state",payment.state);put("hasBookingHistory",payment.merchant!=null)
                        }) }
                    inlineApproval?.let { action->put("approvalCard",buildJsonObject {
                        put("id",action.presentationId)
                        activeExecutions.firstOrNull{it.runId==action.runId}?.userMessageId?.let{put("messageId",it)}
                    }) }
                    put("draft",roomDraft);put("composerEpoch",composerEpoch);put("busy",runForRoom!=null)
                    put("voiceDraftEpoch",voiceDraftEpoch)
                    spokenMessageId?.let{put("spokenMessageId",it)}
                    voiceTalk?.takeIf{it.threadId==composerThreadId&&it.branchId==currentBranchId}?.let{talk->
                        put("voiceTalk",buildJsonObject {
                            put("stage",talk.stage);put("transcript",talk.transcript);put("reply",talk.reply)
                            put("blocked",voiceInputBlocked()||connectionsOpen||roomDraft.isNotBlank()||
                                activeExecutions.any{it.threadId==composerThreadId}||attachments.isNotEmpty()||preparingAttachments)
                        })
                    }
                    voicePrompt?.takeIf{it.talk==null&&it.threadId==composerThreadId&&it.branchId==currentBranchId}?.let{put("voicePrompt",it.stage)}
                    put("preparingAttachments",preparingAttachments)
                    put("canCancelPreparation",preparingAttachments&&attachmentJobThreadId==composerThreadId||roomAuxiliary!=null)
                    put("otherApprovalPending",approvalThread!=null)
                    put("status",if(runForRoom!=null||activeExecutions.isEmpty())state else "다른 대화에서 작업 중")
                    put("modelId",chosenId);put("modelLabel",modelChoices.find{it.first==chosenId}?.second?:"선택한 모델")
                    put("activeRunId",runForRoom);put("actionEpoch",epoch);put("canSteer",runForRoom!=null&&!chat.officeTeamRun(runForRoom)&&!preparingAttachments&&attachments.isEmpty())
                    put("resultPreviews",resultPreviews)
                    put("messages",buildJsonArray{visibleMessages.forEach{message->add(buildJsonObject{
                        val author = officeAuthor(message.role).takeIf { composerThreadId == officeRoomId }
                        put("id",message.id);put("role",if (author == null) message.role else "assistant")
                        message.createdAt?.let{put("sentAt",it)}
                        val text = if (message.role == "assistant" || author != null)
                            com.user.localagent.externalagent.CloudAgentFinals.visibleAnswer(message.text) else message.text
                        val cleanText = text.replace(GoalPdfEmail.MARKER,"").trimEnd()
                        pdfDrafts[message.id]?.let { put("pdfEmail",it) }
                        val files = verifiedFiles[message.id].orEmpty()
                        val shownText = (if (files.isEmpty()) cleanText else files.fold(cleanText){body,name->body.replace("[[file:$name]]","")}.trimEnd()).take(32000)
                        val activity = roomActivities[message.id]?.takeIf {message.role=="assistant"&&it.threadId==composerThreadId}
                        // The web shows runStatus as a card only when the message has no text; that card already
                        // carries the status label, so the report's last status line is not repeated under it.
                        val statusCard = activity?.runStatus?.takeIf { shownText.isBlank() &&
                            (it["phase"] as? JsonPrimitive)?.contentOrNull !in setOf("queued","running","waiting_approval") }
                        reports[message.id]?.takeIf {message.role=="assistant"}?.let { report ->
                            val lines = if (activity != null && statusCard != null && report === activity.executionReport && activity.executionReportEndsWithStatus) report.dropLast(1) else report
                            if (lines.isNotEmpty()) put("executionReport",JsonArray(lines.map(::JsonPrimitive)))
                        }
                        activity?.runStatus?.let {put("runStatus",it)}
                        activity?.receipt?.let {put("receipt",it)}
                        pdfDrafts[message.id]?.let { put("pdfEmail",it) }
                        if (files.isNotEmpty()) put("fileFormats",buildJsonArray { files.forEach { add(it.substringAfterLast('.')) } })
                        put("text",shownText)
                        author?.let { put("authorLabel", it.label) }
                    })}})
                    put("officeSelected",officeOpen)
                    put("showOfficeGuide",officeOpen&&!officeGuideDismissed)
                    if(officeOpen)put("officeCalendarReadGranted",calendarGranted)
                    put("threads",buildJsonArray{if(composerThreadId.isNotBlank())add(buildJsonObject{
                        put("id",composerThreadId);put("title","집순이");put("selected",true)
                    })})
                    put("models",buildJsonArray{modelChoices.forEach{(id,label)->add(buildJsonObject{put("id",id);put("label",label)})}})
                    put("attachments",buildJsonArray{attachments.forEach{add(buildJsonObject{put("id",it.id);put("name",it.name)})}})
                    put("tasks",buildJsonArray{
                        queuedRequests.filter{q->q.threadId==composerThreadId&&q.branchId==currentBranchId&&activeExecutions.none{it.runId==q.runId}}.take(8).forEach{request->
                            add(buildJsonObject{
                                put("id",request.runId);put("phase","queued");put("messageId",request.userMessageId)
                                put("label",requestTitles[request.userMessageId]?.ifBlank{"차례를 기다리는 일"}?:"차례를 기다리는 일")
                                put("detail","");put("canCancel",false)
                            })
                        }
                        activeExecutions.filter{it.threadId==composerThreadId}.forEach{run->add(buildJsonObject{
                        val phase=when{run.waitingForApproval->"approval";run.waitingForResource!=null->"waiting_resource";else->"running"}
                        val activity=activitiesByRun[run.runId]
                        put("id",run.runId);put("phase",phase);run.userMessageId?.let{put("messageId",it)}
                        put("label",run.userMessageId?.let(requestTitles::get)?.takeIf(String::isNotBlank)?:
                            when(phase){"approval"->"확인이 필요한 일";"waiting_resource"->"사용 가능한 실행 환경 대기";else->"맡은 일 진행 중"})
                        put("detail",run.status)
                        activity?.startedAt?.takeIf{it>0}?.let{put("startedAt",it)}
                        put("canCancel",run.runId==runForRoom)
                        put("evidence",buildJsonArray{publicActivityWindow(activity?.events.orEmpty()).forEach{event->add(buildJsonObject{
                            put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                            event.at.takeIf{it>0}?.let{put("at",it)}
                        })}})
                    })}
                        activitiesByRun.values.filter{it.status !in setOf("queued","running","waiting_approval")&&activeExecutions.none{run->run.runId==it.runId}}
                            .sortedBy{it.updatedAt}.takeLast(12).forEach{activity->add(buildJsonObject{
                                val latestQuestion=activity.waitingForReply&&runForRoom==null&&messages.lastOrNull()?.id==activity.assistantMessageId
                                val phase=when{
                                    latestQuestion->"waiting_input"
                                    activity.waitingForGoal->"waiting_goal"
                                    activity.waitingForReply->"clarification_record"
                                    else->activity.status
                                }
                                put("id",activity.runId);put("phase",phase)
                                if(latestQuestion&&activity.goalOwnerReply)put("goalOwnerReply",true)
                                if(latestQuestion&&activity.replyChoices.isNotEmpty())
                                    put("choices",JsonArray(activity.replyChoices.map{it.toJson()}))
                                activity.userMessageId?.let{put("messageId",it)}
                                if(activity.canOpenOwnerLogin&&inlineCredentials==null&&!connectionsOpen&&runForRoom==null)
                                    put("canReopenLogin",true)
                                put("label",activity.userMessageId?.let(requestTitles::get)?.takeIf(String::isNotBlank)?:
                                    when(phase){"waiting_input"->"답변을 기다리고 있어요";"waiting_goal"->"조건이 되면 이어서 진행";"clarification_record"->"추가 질문";"completed"->"응답 종료";"paused"->"확인 후 이어가기";"cancelled"->"중단됨";"failed"->"실행 실패";else->"실행 종료"})
                                activity.startedAt.takeIf{it>0}?.let{put("startedAt",it)}
                                if(phase!="waiting_goal")activity.finishedAt?.takeIf{it>0}?.let{put("finishedAt",it)}
                                put("detail",if(latestQuestion)"답 기다리는 중" else if(activity.waitingForGoal)visibleActivityText(activity.goalWaitDetail) else "");put("canCancel",activity.waitingForGoal)
                                put("evidence",buildJsonArray{publicActivityWindow(activity.events).forEach{event->add(buildJsonObject{
                                    put("id",event.id);put("label",visibleActivityText(event.label));put("detail",visibleActivityText(event.detail))
                                    event.at.takeIf{it>0}?.let{put("at",it)}
                                })}})
                            })}
                    })
                }.toString()
                Box(Modifier.fillMaxSize().padding(padding).imePadding()){
                Column(Modifier.fillMaxSize()){
                Box(Modifier.weight(1f).fillMaxWidth()){
                key(chatScreenAttempt){ReactChatHost(snapshot,onUnavailable={launchIntroPending=false;chatScreenUnavailable=true},
                    onFirstReady={launchIntroChatReady=true},modifier=Modifier.fillMaxSize(),nativeSlotId=nativeSlotId,
                    onNativeSlot={nativeSlot=it},onCommand={command->
                    val sameRoom=command.threadId==chat.selectedThreadId.value&&command.threadId==composerThreadId&&command.branchId==currentBranchId&&chat.selectionReady.value
                    if(sameRoom){
                        val payload=command.payload
                        when(command.action){
                            "openBookingPayment","checkBookingPayment","checkBookingHistory"->if(!connectionsOpen&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){
                                val id=payload.getValue("id").jsonPrimitive.content
                                val opened=if(command.action=="openBookingPayment")webAgent.openOwnerPayment(
                                    this@MainActivity,id,composerThreadId,currentBranchId)
                                else webAgent.openOwnerHistory(this@MainActivity,id,composerThreadId,currentBranchId,booking=command.action=="checkBookingHistory")
                                if(!opened)android.widget.Toast.makeText(this@MainActivity,
                                    "화면을 열 수 없음 · 예약처 예약 내역과 카드 거래 내역 확인",android.widget.Toast.LENGTH_LONG).show()
                            }
                            "reopenOwnerLogin"->{
                                val runId=payload.getValue("runId").jsonPrimitive.content
                                if(activitiesByRun[runId]?.canOpenOwnerLogin==true&&!openingPausedLogin&&!connectionsOpen&&
                                    webAgent.ownerLoginCard.value==null&&runForRoom==null) {
                                    openingPausedLogin=true
                                    lifecycleScope.launch {
                                        val opened=try { chat.reopenOwnerLogin(runId,composerThreadId,currentBranchId) }
                                            catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                                            catch(_:Exception){false}
                                            finally {openingPausedLogin=false}
                                        if(!opened)android.widget.Toast.makeText(this@MainActivity,
                                            "로그인 화면 열기 실패 · PC 연결 확인",android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            "answerGoalQuestion"->if(!attachmentPreparing.value&&app.chatAttachments.state.value.isEmpty()){
                                chat.sendGoalOwnerAnswer(payload.getValue("runId").jsonPrimitive.content,
                                    payload.getValue("text").jsonPrimitive.content,composerThreadId,currentBranchId)
                            }
                            "chooseReply"->if(!attachmentPreparing.value&&app.chatAttachments.state.value.isEmpty()&&chat.draft.value.isBlank()){
                                chat.sendOwnerChoice(payload.getValue("runId").jsonPrimitive.content,
                                    payload.getValue("index").jsonPrimitive.int,composerThreadId,currentBranchId)
                            }
                            "characterPickerShown"->characterPickerEpoch=com.user.localagent.ui.CharacterPickerFlag.cleared(
                                characterPickerEpoch,payload.getValue("epoch").jsonPrimitive.long)
                            "setCharacter"->{
                                character=character.update(payload.getValue("field").jsonPrimitive.content,
                                    payload.getValue("value").jsonPrimitive.content)
                                character.save(characterSettings)
                            }
                            "draft"->chat.updateDraft(payload.getValue("text").jsonPrimitive.content,composerThreadId)
                            "toggleAnswerSpeech"->{
                                val id=payload.getValue("messageId").jsonPrimitive.content
                                visibleMessages.firstOrNull{it.id==id&&it.role=="assistant"&&it.text.isNotBlank()}
                                    ?.takeIf{spokenMessageId==id||(!connectionsOpen&&activeExecutions.none{run->run.threadId==composerThreadId})}
                                    ?.let{toggleChatSpeech(it,composerThreadId,currentBranchId)}
                            }
                            "openVoiceTalk"->if(voiceTalk==null&&voicePrompt==null&&!connectionsOpen&&
                                !voiceInputBlocked()&&currentVoiceRoom(composerThreadId,currentBranchId)){
                                stopReplySpeech()
                                voiceTalk=VoiceTalk(composerThreadId,currentBranchId)
                            }
                            "closeVoiceTalk"->if(voiceTalk!=null)closeVoiceTalk()
                            "listenVoiceTalk"->voiceTalk?.takeIf{!connectionsOpen&&it.threadId==composerThreadId&&it.branchId==currentBranchId}?.let{talk->
                                when(talk.stage){
                                    "listening"->{
                                        talk.stage="finishing"
                                        voicePrompt?.takeIf{it.talk===talk}?.stage="finishing"
                                        app.phoneMedia.stopListening()
                                    }
                                    "idle","review","preparing","speaking"->if(!voiceInputBlocked()){
                                        voiceTurn?.cancel()
                                        talk.transcript=""
                                        startVoicePrompt(composerThreadId,currentBranchId,talk)
                                    }
                                    else->Unit
                                }
                            }
                            "sendVoiceTalk"->if(!connectionsOpen)voiceTalk?.let(::sendVoiceTalk)
                            "startVoicePrompt"->if(currentBranchId.isNotBlank()&&voiceTalk==null&&!connectionsOpen)
                                startVoicePrompt(composerThreadId,currentBranchId)
                            "finishVoicePrompt"->voicePrompt?.takeIf {
                                it.threadId==composerThreadId&&it.branchId==currentBranchId&&it.stage=="listening"
                            }?.let {it.stage="finishing";app.phoneMedia.stopListening()}
                            "cancelVoicePrompt"->if(voicePrompt?.threadId==composerThreadId&&voicePrompt?.branchId==currentBranchId)
                                cancelVoicePrompt()
                            "send"->{
                                val token=payload.getValue("submitToken").jsonPrimitive.content
                                val text=payload.getValue("text").jsonPrimitive.content
                                if(attachmentPreparing.value){status.value="첨부 준비 중 · 끝나면 보내기"}
                                else if(token !in submittedTokens&&chat.sendFromReact(text,composerThreadId,currentBranchId,token)){
                                    submittedTokens+=token;while(submittedTokens.size>128)submittedTokens.remove(submittedTokens.first())
                                    if(chat.draft.value==text)chat.updateDraft("",composerThreadId)
                                }
                                composerEpoch++
                            }
                            "stopGoalWait"->{
                                val runId=payload.getValue("runId").jsonPrimitive.content
                                if(roomActivities.values.any { it.runId==runId&&it.threadId==composerThreadId&&it.waitingForGoal })
                                    this@MainActivity.lifecycleScope.launch { chat.cancelParkedGoal(composerThreadId,runId,currentBranchId) }
                            }
                            "stop","steer"->{
                                val id=payload.getValue("runId").jsonPrimitive.content
                                if(id==runForRoom&&id==chat.activeRunId&&payload.getValue("actionEpoch").jsonPrimitive.long==epoch){
                                    if(command.action=="stop")chat.stopRun(id,composerThreadId)
                                    else if(!attachmentPreparing.value&&app.chatAttachments.state.value.isEmpty())chat.steer(payload.getValue("text").jsonPrimitive.content,id,composerThreadId)
                                    else status.value="첨부가 있을 때는 추가 지시 안 됨 · 입력은 그대로"
                                }else status.value="작업이 바뀌어 지시 안 넣음"
                                if(command.action!="stop")composerEpoch++
                            }
                            "dismissOfficeGuide"->if(officeOpen)dismissOfficeGuide()
                            "requestOfficeCalendar"->if(officeOpen&&!officeCalendarReadGranted.value)officeCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            "selectModel"->payload.getValue("id").jsonPrimitive.content.takeIf{id->modelChoices.any{it.first==id}}?.let{id->chooseConversationModel(id,composerThreadId)}
                            "attach"->if(currentBranchId.isNotBlank()&&attachmentScope==null&&!attachmentPreparing.value){
                                attachmentPickerThread=composerThreadId
                                attachmentPickerBranch=currentBranchId
                            }
                            "navigate"->{
                                val panel=payload.getValue("panel").jsonPrimitive.content
                                when(panel){
                                    "collab"->Unit
                                    "contacts"->openPanel(CONTACTS_TAB)
                                    "capabilities"->openPanel(DISCOVER_TAB)
                                    "services"->{openPanel(DISCOVER_TAB);showServiceCatalog=true}
                                    "historySearch"->openPanel(HISTORY_SEARCH_TAB)
                                    "connections","email"->{
                                        openPanel(0)
                                        connectionServiceId=if(panel=="email")"gmail" else null
                                        connectionStatus=null
                                        connectionsOpen=true
                                    }
                                    "maps"->openDefaultMap()
                                    "privacy"->{openPanel(4);manualSettings=true;settingsPage=SettingsPage.PRIVACY}
                                    else->openPanel(when(panel){"chat"->0;"images"->7;"tools"->3;"projects"->10;"history"->6;else->4})
                                }
                            }
                            "copy"->{val id=payload.getValue("messageId").jsonPrimitive.content;if(messages.any{it.id==id&&(it.role=="assistant"||officeAuthor(it.role)!=null)})chat.copyMessage(id)}
                            "openPdfEmail"->{
                                val id=payload.getValue("messageId").jsonPrimitive.content
                                val preview=pdfDrafts[id]
                                if(preview!=null)this@MainActivity.lifecycleScope.launch {
                                    try {
                                        val intent=GoalPdfEmail.composer(this@MainActivity,app,composerThreadId,currentBranchId,id,preview)
                                        if (app.runs.selectedThreadId.value == composerThreadId) startActivity(intent)
                                    } catch(cancel:CancellationException){throw cancel}
                                    catch(_:android.content.ActivityNotFoundException){status.value="Gmail을 열 수 없음 · 초안은 여기 남겨 둠"}
                                    catch(_:Exception){status.value="첨부 원본을 못 찾음 · 파일 다시 저장 필요"}
                                }
                            }
                            "messageFiles"->{
                                val id=payload.getValue("messageId").jsonPrimitive.content
                                val names=verifiedFiles[id]
                                if(names!=null)this@MainActivity.lifecycleScope.launch {
                                    val checked=ChatResultPreviews.files(this@MainActivity,app,composerThreadId,currentBranchId,mapOf(id to names))
                                    if(checked[id]==names)fileMessageId=id
                                    else status.value="저장한 파일을 못 찾음"
                                }
                            }
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
                            "openWaitingApproval"->otherApprovalOpened=pendingActions.firstOrNull{it.threadId==approvalThread}?.presentationId
                        }
                    }else if(command.action in setOf("send","steer","attach","removeAttachment")){
                        status.value="대화가 바뀌어 방금 부탁 안 넣음"
                    }
                })}
                if(inlineCredentials!=null)key(inlineCredentials.id){
                    com.user.localagent.ui.NativeChatOverlay(nativeSlot,websiteKeyboardOpen){
                        if(websiteLoginFolded)Column(Modifier.fillMaxSize().padding(horizontal=12.dp),
                            verticalArrangement=Arrangement.Center){
                            Text("로그인 대기 · ${inlineCredentials.host}")
                            Row {
                                TextButton(onClick={lifecycleScope.launch {
                                    val binding=websiteBinding
                                    val saved=binding?.let{app.repository.chat.run(it.runId)}
                                    if(saved?.errorCode=="browser_auth_resume_expired"){
                                        val opened=try{chat.reopenOwnerLogin(binding.runId,composerThreadId,currentBranchId)}
                                            catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                                            catch(_:Exception){false}
                                        if(!opened)status.value="이어갈 수 있는지 확인 필요"
                                    }else webAgent.unfoldOwnerLogin(inlineCredentials.id)
                                }}){Text("로그인 열기")}
                                TextButton(onClick={lifecycleScope.launch {
                                    chat.cancelOwnerLoginWait(inlineCredentials.id,composerThreadId,currentBranchId)
                                }}){Text("대기 그만두기")}
                            }
                        }else if(inlineSite?.nolGuide==true)com.user.localagent.integrations.InlineNolConnection(webAgent,
                            inlineSite,window,Modifier.fillMaxSize(),onVerified={lifecycleScope.launch {
                                app.repository.chat.addMessage(composerThreadId,currentBranchId,"assistant",
                                    "NOL 로그인 확인함 · 로그인이 유지되는 동안 이 휴대폰에서 이어 씀"+
                                        if(inlineSite.vaultSaveFailed)" · 이번 비밀번호는 금고에 저장 안 됨" else "")
                            }})
                        else if(inlineSite!=null)com.user.localagent.integrations.InlineWebsiteLogin(webAgent,inlineSite,window,Modifier.fillMaxSize())
                        else com.user.localagent.integrations.InlineBrowserLoginCard(webAgent,inlineCredentials,window,
                            Modifier.fillMaxSize())
                    }
                }else if(inlineApproval!=null)key(inlineApproval.presentationId){
                    com.user.localagent.ui.NativeChatOverlay(nativeSlot,false){
                        InlineActionApproval(inlineApproval,inlineApproval.reviewText?:actionSummary(inlineApproval.call)){ accepted->
                            if(approval.value===inlineApproval&&chat.isCurrentApprovalRoom(inlineApproval))
                                inlineApproval.decision.complete(accepted)
                        }
                    }
                }
                }
                if(connectionsOpen) {
                    com.user.localagent.connections.ChatConnectionCard(this@MainActivity,window,
                        initialServiceId=connectionServiceId,
                        onClose={connectionsOpen=false},onStatus={connectionStatus=it},
                        onConnectNol={
                            if(!composerReady||chat.selectedThreadId.value!=composerThreadId||currentBranchId.isBlank()||
                                !webAgent.canStartNolConnection())false
                            else {
                                val guide=com.user.localagent.integrations.NolConnectionGuide
                                val message=app.repository.chat.beginWebsiteConnection(composerThreadId,currentBranchId,guide.request,guide.intro)
                                val binding=com.user.localagent.integrations.AppWebAgent.WorkBinding(message.id,composerThreadId,currentBranchId)
                                val started=webAgent.startNolConnection(binding,message.id)
                                if(started){connectionsOpen=false;connectionStatus=null}
                                else app.repository.chat.addMessage(composerThreadId,currentBranchId,"assistant",
                                    "다른 로그인 작업이 열려 있음 · 그 일을 마치고 다시 연결")
                                started
                            }
                        }, modifier=Modifier.fillMaxWidth().fillMaxHeight(0.68f).padding(horizontal=12.dp))
                }
                }
                if(launchIntroPending) LaunchIntro(launchIntroChatReady,{launchIntroPending=false},Modifier.matchParentSize())
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
                    TextButton(onClick=::returnToParent,contentPadding=PaddingValues(start=4.dp,end=12.dp),
                        modifier=Modifier.semantics(mergeDescendants=true){contentDescription=if(returnsToSettings)"설정으로 돌아가기"else"대화로 돌아가기"}){
                        ZipIcon(com.user.localagent.runtime.R.drawable.zi_back,null,tint=ZipHapColors.TextPrimary,size=22.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(if(returnsToSettings)"설정"else"대화",color=ZipHapColors.TextPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    if(tab==4 && !manualSettings) TextButton(onClick={showManual(SettingsLink.MANUAL)}){Text("수동 설정",color=ZipHapTokens.CobaltText)}
                }
                run {
                    val title=if(tab==4&&settingsPage==SettingsPage.HOME&&!manualSettings)"나의 집순이"
                        else if(tab==4)settingsPage.title
                        else if(tab==DISCOVER_TAB&&showServiceCatalog)"앱과 서비스"
                        else listOf("","음성을 글로 바꾸기","","파일 작업","설정","","보관함","이미지 작업실","","웹·쇼핑 검색","프로젝트","연락처","둘러보기","대화 검색")[tab]
                    ZipScreenTitle(title,Modifier.padding(start=4.dp,top=2.dp,bottom=12.dp))
                }
                val panelStatus=if(tab==7)imageStudioStatus else state
                if(panelStatus.isNotBlank() && (tab!=4 || manualSettings))Text(panelStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
                when(tab){1->VoiceSettingsScreen();3->Box(Modifier.weight(1f)){ToolShelfPanel(app,{status.value=it}){request->tab=0;send(request)}};4->if(manualSettings) key(settingsPage){
                    SettingsScreen(settingsPage,chatScreenUnavailable,{settingsPage=it}){openPanel(it,fromSettings=true)}
                }else SettingsAssistantPanel(app,character,::showManual,shortcuts=remember{com.user.localagent.ui.AssistantShortcutActions {
                    characterPickerLast=com.user.localagent.ui.CharacterPickerFlag.next(characterPickerLast)
                    characterPickerEpoch=characterPickerLast
                    openPanel(0)
                }},moveToChat={request->
                    if(!chat.selectionReady.value||chat.selectedThreadId.value!=composerThreadId||
                        composerThreadId.isBlank()||chat.draft.value.isNotEmpty())false
                    else {
                        chat.updateDraft(request,composerThreadId)
                        openPanel(0)
                        true
                    }
                },modifier=Modifier.weight(1f));6->Box(Modifier.weight(1f)){ConversationHistoryPanel(app){status.value=it}};7->Box(Modifier.weight(1f)){ImageStudioPanel(app,{imageStudioStatus=it})};9->Box(Modifier.weight(1f)){WebResearchPanel(app){status.value=it}};10->Box(Modifier.weight(1f)){ProjectWorkspacePanel(app,{status.value=it}){request->tab=0;send(request)}}
                    CONTACTS_TAB->ContactsDirectory(Modifier.weight(1f))
                    HISTORY_SEARCH_TAB->Box(Modifier.weight(1f)){ConversationHistoryPanel(app,searchOnly=true){status.value=it}}
                    DISCOVER_TAB->OfficeCapabilityShelf(app,
                        startOnServices=showServiceCatalog,
                        onConnections={serviceId->openPanel(0);connectionServiceId=serviceId;connectionStatus=null;connectionsOpen=true},
                        onContacts={openPanel(CONTACTS_TAB)},
                        onMaps=::openDefaultMap,
                        onImages={openPanel(7)},
                        calendarReadGranted=calendarGranted,onCalendarRead={
                            if(!officeCalendarReadGranted.value)officeCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        },
                        onDraft={text->
                            if(!chat.selectionReady.value||chat.selectedThreadId.value!=composerThreadId||chat.draft.value.isNotBlank())false
                            else {chat.updateDraft(text,composerThreadId);openPanel(0);true}
                        },modifier=Modifier.weight(1f))
                }
                if(tab in setOf(6,DISCOVER_TAB,CONTACTS_TAB) || tab==4&&!manualSettings){
                    val barShape=RoundedCornerShape(ZipHapTokens.RadiusCard)
                    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp).zipShadow(ZipHapTokens.ShadowCard,barShape)
                        .clip(barShape).background(ZipHapColors.Surface).selectableGroup()){
                        listOf(Triple(0,"기본 채팅",com.user.localagent.runtime.R.drawable.zi_chat), Triple(DISCOVER_TAB,"둘러보기",com.user.localagent.runtime.R.drawable.zi_compass),
                            Triple(6,"보관함",com.user.localagent.runtime.R.drawable.zi_archive), Triple(4,"나의 집순이",com.user.localagent.runtime.R.drawable.zi_person)).forEach { (destination, label, icon) ->
                            val selected = tab == destination
                            Tab(selected=selected, onClick={ if (!selected) openPanel(destination) },
                                modifier=Modifier.weight(1f).heightIn(min=56.dp)) {
                                ZipIcon(icon,null,Modifier.padding(top=6.dp),
                                    tint=if(selected) MaterialTheme.colorScheme.primary else ZipHapColors.TextFaint,size=22.dp)
                                Text(label, modifier=Modifier.padding(start=2.dp, end=2.dp, top=2.dp, bottom=6.dp),
                                    textAlign=TextAlign.Center, style=MaterialTheme.typography.labelMedium,
                                    color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if(attachmentPickerThread!=null){
            fun closeAttachmentPicker(){
                attachmentPickerThread=null;attachmentPickerBranch=null
            }
            fun chooseAttachment(launch:()->Unit){
                val room=roomThreads.firstOrNull{it.id==attachmentPickerThread&&it.activeBranchId==attachmentPickerBranch}
                closeAttachmentPicker()
                if(room==null||!chat.selectionReady.value||chat.selectedThreadId.value!=room.id||
                    attachmentScope!=null||attachmentPreparing.value){
                    status.value="대화가 바뀌었어요. 첨부할 대화에서 다시 선택해 주세요."
                    return
                }
                attachmentScope=Triple(room.id,room.revision,room.activeBranchId)
                try{launch()}catch(_:Exception){
                    cameraAttachmentFile(cameraAttachmentName)?.delete()
                    cameraAttachmentName=null;attachmentScope=null
                    status.value="이 휴대폰에서 선택한 첨부 방법을 열 수 없어요."
                }
            }
            ZipSheet(onDismissRequest=::closeAttachmentPicker,title="첨부 추가",
                actions={ZipSecondaryButton("취소",::closeAttachmentPicker)}){
                Column{
                    ZipMenuRow("카메라",com.user.localagent.runtime.R.drawable.zi_image,tone=ZipTone.Coral,onClick={chooseAttachment{
                        val directory=File(cacheDir,"camera_attachments")
                        check(directory.isDirectory||directory.mkdirs())
                        val cutoff=System.currentTimeMillis()-24*60*60*1000L
                        directory.listFiles().orEmpty().filter{it.isFile&&it.lastModified()<cutoff}.forEach{it.delete()}
                        val file=File.createTempFile("camera-",".jpg",directory)
                        cameraAttachmentName=file.name
                        attachCamera.launch(cameraAttachmentUri(file))
                    }})
                    ZipMenuRow("사진",com.user.localagent.runtime.R.drawable.zi_image,tone=ZipTone.Lilac,onClick={chooseAttachment{
                        attachPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }})
                    ZipMenuRow("파일",com.user.localagent.runtime.R.drawable.zi_file,onClick={chooseAttachment{attachFiles.launch(arrayOf("*/*"))}})
                }
            }
        }
        if(chatManagerOpen) ZipSheet(onDismissRequest={chatManagerOpen=false},title="대화 도구",
            actions={ZipSecondaryButton("닫기",{chatManagerOpen=false})}){
            Column {
                ZipMenuRow("대화 기록",com.user.localagent.runtime.R.drawable.zi_history,tone=ZipTone.Neutral,onClick={chatManagerOpen=false;openPanel(6)})
                ZipMenuRow("대기 중인 요청",com.user.localagent.runtime.R.drawable.zi_clock,tone=ZipTone.Sun,onClick={chatManagerOpen=false;queueOpen=true})
                ZipMenuRow("대화의 목표",com.user.localagent.runtime.R.drawable.zi_compass,tone=ZipTone.Mint,onClick={chatManagerOpen=false;goalEditorThreadId=composerThreadId})
            }
        }
        verifiedFiles[fileMessageId]?.let {files->
            ZipSheet(onDismissRequest={fileMessageId=null},title="답변에 담긴 파일",
                actions={ZipSecondaryButton("닫기",{fileMessageId=null})}){
                Column { FileArtifactChatPreview(files.joinToString("\n") { "[[file:$it]]" }) }
            }
        }
        messages.firstOrNull{it.id==messageMenuId}?.let {message->
            fun closeMenu(){messageMenuId=null;messageEdit=null}
            ZipSheet(onDismissRequest=::closeMenu,title=if(message.role=="assistant")"답변 메뉴"else"메시지 메뉴",actions={
                val editing=messageEdit
                if(editing==null)ZipSecondaryButton("닫기",::closeMenu)
                else {
                    ZipSecondaryButton("취소",{messageEdit=null},Modifier.weight(1f),fullWidth=false)
                    ZipPrimaryButton("보내기",{closeMenu();chat.editMessage(message.id,editing)},Modifier.weight(1f),
                        enabled=editing.isNotBlank()&&editing.length<=12000,fullWidth=false)
                }
            }){
                val editing=messageEdit
                if(editing!=null)ZipOutlinedTextField(editing,{messageEdit=it},Modifier.fillMaxWidth(),label={Text("메시지")},maxLines=6)
                else Column {
                    if(message.role=="user")ZipMenuRow("수정해서 보내기",com.user.localagent.runtime.R.drawable.zi_edit,onClick={messageEdit=message.text})
                    else {
                        GeneratedImageChatPreview(message.text)
                        if(answerStates[message.id]?.canContinue==true)ZipMenuRow("답변 이어받기",com.user.localagent.runtime.R.drawable.zi_arrow,tone=ZipTone.Mint,onClick={closeMenu();chat.continueAnswer(message.id)})
                        ZipMenuRow("다시 답변 받기",com.user.localagent.runtime.R.drawable.zi_refresh,onClick={closeMenu();chat.regenerate(message.id)})
                        ZipMenuRow("답변 저장",com.user.localagent.runtime.R.drawable.zi_download,tone=ZipTone.Neutral,onClick={closeMenu();chat.exportMessage(message.id)})
                    }
                }
            }
        }
        if(queueOpen)RunQueueDialog(
            items=queuedRequests.map{request->UiQueuedRun(request.runId,request.threadId,request.text.lineSequence().firstOrNull().orEmpty().take(100),request.text,
                threadLabel=roomThreads.firstOrNull{it.id==request.threadId}?.title?:"대화",modelLabel=if(request.modelId=="auto")"모델 자동"else installed.firstOrNull{it.id==request.modelId}?.title?:"선택한 모델",
                modeLabel=com.user.localagent.core.AgentWorkMode.entries.firstOrNull{it.wireName==request.mode}?.label?:request.mode,goalLinked=request.goal!=null,continuation=request.continuation!=null)},
            onDismiss={queueOpen=false},onEdit=chat::editQueued,onMove=chat::reorderQueued,onCancel=chat::cancelQueued,
            onManageGoal={threadId->if(threadId==composerThreadId){queueOpen=false;goalEditorThreadId=threadId}},
        )
        goalEditorThreadId?.takeIf{it==selectedThreadId&&it.isNotBlank()}?.let {goalThread->
            GoalEditorDialog(goal=roomGoal?.takeIf{it.threadId==goalThread},threadId=goalThread,
                projectId=roomThreads.firstOrNull{it.id==goalThread}?.projectId,
                onSave={goal->chat.saveGoal(goal){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onStart={goal->chat.startGoal(goal){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onDelete={chat.deleteGoal(goalThread){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}},
                onDismiss={goalEditorThreadId=null},feedback=state,
                onDeleteVersioned={snapshot->chat.deleteGoal(goalThread,snapshot){if(goalEditorThreadId==goalThread)goalEditorThreadId=null}})
        }
        val dialogApproval=pendingActions.firstOrNull{it.presentationId==otherApprovalOpened&&!it.decision.isCompleted}
            ?:pending?.takeIf{(tab!=0||chatScreenUnavailable)&&it.threadId==composerThreadId&&
                it.branchId==currentBranchId&&!it.decision.isCompleted}
        dialogApproval?.let { action->key(action.presentationId){
            ActionApprovalDialog(action,action.reviewText?:actionSummary(action.call)){accepted->
                if(chat.pendingActions.value.any{it===action}&&
                    (action.presentationId==otherApprovalOpened||chat.isCurrentApprovalRoom(action)))
                    action.decision.complete(accepted)
                otherApprovalOpened=null
            }
        } }
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
        val developmentBuild=(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
        var endurance by remember { mutableStateOf(false) }
        var downloadJob by remember {mutableStateOf<Job?>(null)}
        var hashTarget by remember {mutableStateOf<String?>(null)}
        var expectedHash by remember {mutableStateOf("")}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(progress!=null) item {val p=progress!!;InfoCard(if(p.phase=="hash")"모델 무결성 검증" else "모델 다운로드","${p.title}\n${p.bytes/1024/1024} / ${p.total/1024/1024} MB");LinearProgressIndicator(progress={if(p.total>0)(p.bytes.toDouble()/p.total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());TextButton(onClick={downloadJob?.cancel()}){Text("중단 · 다음 다운로드에서 이어받기")}}
            item {InfoCard("녹음에서 글 만들기", "이 휴대폰에서 음성을 글로 바꿀 때 쓸 언어와 인식 방법을 고를 수 있어요.")}
            item {Text("녹음 언어",style=MaterialTheme.typography.titleSmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("ko" to "한국어", "en" to "영어", "auto" to "자동 감지").forEach {(code,label)->
                    ZipFilterChip(selected=asr.language==code,enabled=code!="auto"||asr.modelId!=com.user.localagent.models.AsrCatalog.DEVICE_MODEL,onClick={scope.launch {app.models.selectAsrLanguage(code)}},label={Text(label)})
                }
            }}
            item {
                val deviceAsr by app.models.deviceAsrStatus.collectAsState()
                ZipCard(Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    Text("휴대폰 기본 음성 인식",fontWeight=FontWeight.Bold)
                    Text("한국어와 영어를 지원해요. 이 휴대폰에서 사용할 수 있는지 확인하세요.",style=MaterialTheme.typography.bodySmall)
                    Text(deviceAsr.message,style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.prepareDeviceAsr(deviceAsr.downloadable)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"기기 내 AI 확인 실패"}}}){Text(if(deviceAsr.downloadable)"음성 인식 준비"else"지원 확인")}
                        ZipFilterChip(selected=asr.modelId==com.user.localagent.models.AsrCatalog.DEVICE_MODEL,enabled=deviceAsr.available&&asr.language!="auto",onClick={scope.launch {app.models.selectAsr(com.user.localagent.models.AsrCatalog.DEVICE_MODEL)}},label={Text("사용하기")})
                    }
                }}
            }
            items(app.models.candidates.filter {it.id in com.user.localagent.models.AsrCatalog.modelIds}) {candidate->
                ZipCard(Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth().padding(16.dp)){
                    val accuracyFirst=candidate.id=="asr-qwen-17b"
                    Text(if(accuracyFirst)"정확도 우선"else"저장 공간 절약",fontWeight=FontWeight.Bold)
                    Text(if(accuracyFirst)"약 2.8GB 다운로드"else"약 1.2GB 다운로드",style=MaterialTheme.typography.bodySmall)
                    Text("휴대폰에서 처리 · 공식 배포 파일",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        ZipFilledButton(enabled=progress==null,onClick={downloadJob=scope.launch {try{app.models.downloadBundle(candidate);status.value="음성 인식 파일 검증 완료"}catch(cancel:CancellationException){throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"}}}){Text("다운로드")}
                        ZipFilterChip(selected=asr.modelId==candidate.id,enabled=app.models.asrReady(candidate.id),onClick={scope.launch {app.models.selectAsr(candidate.id)}},label={Text("사용하기")})
                    }
                }}
            }
            if(developmentBuild)item {TextButton(onClick={advancedModels=!advancedModels}){Text(if(advancedModels)"모델·성능 설정 접기"else"모델·성능 설정 더 보기")}}
            if(developmentBuild&&advancedModels){
                item { InfoCard("PC와 휴대폰에서 나누어 계산","연결한 PC가 계산 중이면 다른 대화의 작업 하나를 휴대폰에서 진행할 수 있습니다. 휴대폰에는 모델 하나씩만 올립니다. 모델 파일은 APK와 별도로 내려받습니다.") }
                item {var routing by remember {mutableStateOf("실측 결과를 확인 중")};LaunchedEffect(installed){routing=app.orchestration.summary()};InfoCard("자동 오케스트레이션",routing)}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {ZipFilterChip(selected=options.backend=="CPU",onClick={modelOptions.value=options.copy(backend="CPU")},label={Text("CPU 측정")});ZipFilterChip(selected=options.backend=="GPU",onClick={modelOptions.value=options.copy(backend="GPU")},label={Text("GPU 실험 측정")});ZipFilterChip(selected=options.thinking,onClick={app.runs.setThinking(!options.thinking,thinkingThreadId)},enabled=thinkingReady,label={Text("이 대화 깊게 생각")})}}
                item {Row {ZipFilterChip(selected=options.contextSize==4096,onClick={modelOptions.value=options.copy(contextSize=4096)},label={Text("4K 문맥")});Spacer(Modifier.width(8.dp));ZipFilterChip(selected=options.contextSize==8192,onClick={modelOptions.value=options.copy(contextSize=8192)},label={Text("8K 문맥")})}}
                item {Text("CPU/GPU와 4K/8K는 아래 성능 측정에 사용합니다. 깊게 생각 요청은 현재 대화에 저장되며, 실제 모델·설정은 기기의 검증 결과와 자원 상태에 따라 선택됩니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                item {Row {Checkbox(endurance,onCheckedChange={endurance=it});Text("10분 연속 안정성 측정")}}
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ZipOutlinedButton(onClick={importModel.launch(arrayOf("application/octet-stream","*/*"))}) { Text("파일 가져오기") }
                    ZipOutlinedButton(onClick={scope.launch {maintainModelFiles {status.value="휴대폰 모델 메모리를 해제했습니다."}}}) {Text("메모리 해제")}
                } }
                items(app.models.candidates.filterNot {it.id=="gemma-e2b"||com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { candidate -> ZipCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(candidate.title,fontWeight=FontWeight.Bold)
                    Text("공식 메타데이터에서 버전·해시를 확인한 뒤 다운로드",style=MaterialTheme.typography.bodySmall)
                    ZipFilledButton(enabled=progress==null,onClick={downloadJob=scope.launch { status.value="모델 출처 확인 중";try{app.models.downloadBundle(candidate);status.value="다운로드·해시 검증 완료"}catch(cancel:CancellationException){status.value="중단했습니다. 받은 부분은 이어받기에 사용합니다.";throw cancel}catch(error:Exception){status.value=error.message?:"다운로드 실패"} }}) {Text("다운로드")}
                } } }
                item {ZipSectionTitle("설치한 모델",Modifier.padding(start=4.dp,top=4.dp))}
                items(installed.filterNot {it.id=="gemma-e2b"||com.user.localagent.models.GemmaMediaCatalog.auxiliary(it.id)}) { model -> ZipCard(Modifier.fillMaxWidth()) {Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(model.title,fontWeight=FontWeight.Bold)
                    val imageOnly=com.user.localagent.models.GemmaMediaCatalog.imageOnly(model.id,model.sha256)
                    Text(if(imageOnly) "이미지 전용" else if(selectedId==model.id) "이 대화에서 선택됨" else "선택 가능",color=Mint)
                    if(model.id in com.user.localagent.models.GemmaMediaCatalog.modelIds) {
                        val ready=app.models.mediaReady(model)
                        Text(if(ready)"사진 분석 지원"else"사진 분석 지원 파일 다운로드 필요",style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(!imageOnly)ZipFilterChip(selected=selectedId==model.id,enabled=model.verified&&thinkingReady,onClick={chooseConversationModel(model.id,thinkingThreadId)},label={Text("이 대화에서 사용")})
                            ZipFilterChip(selected=media.vision==model.id,enabled=ready,onClick={scope.launch {app.models.selectMedia("vision",model.id)}},label={Text("사진용")})
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
        hashTarget?.let {id->AlertDialog(onDismissRequest={hashTarget=null},title={Text("파일 확인")},text={Column{Text("배포처가 알려준 64자리 확인 코드를 입력해 주세요.");ZipOutlinedTextField(value=expectedHash,onValueChange={expectedHash=it.trim()},label={Text("확인 코드")})}},confirmButton={TextButton(enabled=expectedHash.matches(Regex("[a-fA-F0-9]{64}")),onClick={scope.launch {runCatching {app.models.verifyImported(id,expectedHash)}.onSuccess{status.value="파일이 배포처의 원본과 일치해요.";hashTarget=null}.onFailure{status.value=it.message?:"검증 실패"}}}){Text("파일 검증")}},dismissButton={TextButton(onClick={hashTarget=null}){Text("취소")}})}
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
                    item {ZipSection {
                        SettingsRow("앱과 컴퓨터 연결","메일·문서·메신저 또는 내 컴퓨터를 연결해요.",com.user.localagent.runtime.R.drawable.zi_link){onPage(SettingsPage.CONNECTIONS)}
                        ZipInsetDivider()
                        SettingsRow("음성을 글로 바꾸기","녹음에 쓸 언어와 휴대폰의 음성 인식을 설정해요.",com.user.localagent.runtime.R.drawable.zi_microphone,ZipTone.Lilac){onOpen(1)}
                        ZipInsetDivider()
                        SettingsRow("개인정보와 권한","화면 접근이나 전에 허용한 작업을 확인해요.",com.user.localagent.runtime.R.drawable.zi_shield,ZipTone.Mint){onPage(SettingsPage.PRIVACY)}
                        ZipInsetDivider()
                        SettingsRow("자동으로 도와주기","통화 뒤 정리하거나 사용 중에 도움을 받도록 설정해요.",com.user.localagent.runtime.R.drawable.zi_sparkle,ZipTone.Sun){onPage(SettingsPage.AUTOMATION)}
                        ZipInsetDivider()
                        SettingsRow("앱 업데이트","새 버전을 확인하고 설치해요.",com.user.localagent.runtime.R.drawable.zi_download){onPage(SettingsPage.UPDATES)}
                        if(showChatRecoveryLinks){
                            ZipInsetDivider()
                            SettingsRow("대화 기록","저장한 대화를 찾아요.",com.user.localagent.runtime.R.drawable.zi_history,ZipTone.Neutral){onOpen(6)}
                        }
                    }}
                    item {
                        SettingsDisclosure("저장한 자료와 기록","파일 작업과 검색 기록을 찾아요.",com.user.localagent.runtime.R.drawable.zi_archive,ZipTone.Sun,flush=true){
                            ZipMenuRow("파일 작업",com.user.localagent.runtime.R.drawable.zi_file,{onOpen(3)},Modifier.padding(horizontal=12.dp))
                            ZipMenuRow("검색 기록",com.user.localagent.runtime.R.drawable.zi_search,{onOpen(9)},Modifier.padding(horizontal=12.dp),tone=ZipTone.Neutral)
                        }
                    }
                }
                SettingsPage.CONNECTIONS->{
                    item {SettingsEntry("메일·일정·문서 연결","Google 계정으로 사용할 기능을 선택해요.",com.user.localagent.runtime.R.drawable.zi_mail){
                        startActivity(Intent(this@MainActivity,com.user.localagent.connections.ConnectionsActivity::class.java))
                    }}
                    item {SettingsDisclosure("내 컴퓨터","컴퓨터를 추가하거나 연결 상태를 확인해요.",com.user.localagent.runtime.R.drawable.zi_chip,ZipTone.Lilac){
                        PcComputePanel(assistantBusy||imageWork.running||RuntimeGate.benchmark.get(),showDiagnostics=false){status.value=it}
                    }}
                    item {SettingsDisclosure("메신저","카카오톡 등에서 받아볼 내용을 정해요.",com.user.localagent.runtime.R.drawable.zi_chat,ZipTone.Sun){
                        com.user.localagent.integrations.MessengerPanel(this@MainActivity,onAsk={request->onOpen(0);send(request)}){status.value=it}
                    }}
                }
                SettingsPage.AUTOMATION->{
                    item {SettingsDisclosure("통화 뒤 정리","통화 녹음으로 할 일과 메모를 제안받아요.",com.user.localagent.runtime.R.drawable.zi_phone,ZipTone.Mint){
                        CallFollowUpPanel(app,{status.value=it}){onOpen(6)}
                    }}
                    item {SettingsDisclosure("사용 기록으로 도움 받기","사용 시간과 알림 횟수로 제안을 받아요.",com.user.localagent.runtime.R.drawable.zi_activity,ZipTone.Sun){
                        AutomationPanel(this@MainActivity,app.automation,{startActivity(it)},{status.value=it},{app.repository.discardProposedMemories();Unit})
                    }}
                }
                SettingsPage.PRIVACY->{
                    item {SettingsEntry("휴대폰 앱 권한","연락처와 캘린더 등에 허용한 접근을 관리해요.",com.user.localagent.runtime.R.drawable.zi_shield){
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.fromParts("package",packageName,null)))
                    }}
                    item {SettingsDisclosure("다른 앱 화면 접근","화면을 읽거나 조작할 수 있는 범위를 정해요.",com.user.localagent.runtime.R.drawable.zi_native){
                        com.user.localagent.integrations.ScreenBridgePanel(this@MainActivity,{startActivity(it)}){status.value=it}
                    }}
                    item {SettingsDisclosure("이전에 허용한 작업","반복 작업에 저장한 허용을 해제할 수 있어요.",com.user.localagent.runtime.R.drawable.zi_lock,ZipTone.Neutral){
                        val scope=rememberCoroutineScope()
                        ZipOutlinedButton(onClick={scope.launch {app.repository.revokeGrant("builtin");status.value="기본 도구의 반복 작업 허용을 해제했습니다."}}){Text("반복 작업 허용 해제")}
                    }}
                    item {SettingsDisclosure("Codex 연결","이 앱이 Codex를 사용하도록 허용한 상태를 관리해요.",com.user.localagent.runtime.R.drawable.zi_robot,ZipTone.Neutral){
                        TextButton(onClick={
                            com.user.localagent.externalagent.ExternalAgentSettings.get(this@MainActivity).setEnabled(false)
                            status.value="Codex 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다."
                        }){Text("Codex 연결 해제",color=ZipTone.Coral.content)}
                    }}
                    item {SettingsDisclosure("Claude 연결","이 앱이 Claude를 사용하도록 허용한 상태를 관리해요.",com.user.localagent.runtime.R.drawable.zi_robot,ZipTone.Lilac){
                        TextButton(onClick={
                            com.user.localagent.externalagent.ExternalAgentSettings.get(this@MainActivity,com.user.localagent.externalagent.ExternalAgentSettings.CLAUDE_MODEL_ID).setEnabled(false)
                            status.value="Claude 연결을 해제했습니다. 기존 요청의 권한도 만료됩니다."
                        }){Text("Claude 연결 해제",color=ZipTone.Coral.content)}
                    }}
                }
                SettingsPage.UPDATES->{
                    item {com.user.localagent.updates.UpdatePanel(app.updates,{startActivity(it)},{status.value=it})}
                    item {Text("버전 ${com.user.localagent.runtime.RuntimePackageMetadata(this@MainActivity).versionName}",Modifier.padding(start=4.dp),style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)}
                }
            }
        }
    }
    @Composable private fun InfoCard(title:String,body:String) {ZipHapCard {Text(title,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}}
}
