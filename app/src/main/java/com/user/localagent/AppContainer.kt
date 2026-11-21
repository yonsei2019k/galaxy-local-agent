package com.user.localagent

import android.content.Context
import com.user.localagent.data.LocalRepository
import com.user.localagent.inference.LocalInferenceEngine
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.PhoneRecords
import com.user.localagent.models.ModelManager
import com.user.localagent.scheduling.ReminderScheduler
import com.user.localagent.tools.ToolExecutor
import com.user.localagent.updates.UpdateManager
import com.user.localagent.automation.AutomationController
import com.user.localagent.skills.BuiltinSkills
import com.user.localagent.models.LocalOrchestration
import com.user.localagent.images.ImageStudioController
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.*
import com.user.localagent.benchmark.RuntimeGate

class AppContainer(context: Context,repositoryOverride:LocalRepository?=null,private val assistantWorkEnabled:Boolean=true) {
    private val appContext=context.applicationContext
    internal val applicationContext get()=appContext
    val hostCodeHash:String by lazy {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        java.io.File(appContext.applicationInfo.sourceDir).inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    val repository = repositoryOverride?:LocalRepository(context.applicationContext)
    val models = ModelManager(context.applicationContext)
    val engine = com.user.localagent.inference.AutomaticComputeEngine(context.applicationContext)
    val orchestration = LocalOrchestration(context.applicationContext,models,engine)
    val scheduler = ReminderScheduler(context.applicationContext)
    val conversationImports = ConversationImport(context.applicationContext,repository)
    val phoneRecords = PhoneRecords(context.applicationContext)
    val privateDataPickerOrigins=com.user.localagent.audio.PrivateDataPickerOrigins()
    val smsAnalysis by lazy {com.user.localagent.automation.SmsAnalysisController(appContext,repository)}
    val calendarGateway by lazy {com.user.localagent.automation.CalendarGateway(appContext,repository)}
    val webResearch by lazy {com.user.localagent.web.WebResearchController(appContext,repository)}
    val imageStudio by lazy {ImageStudioController(context.applicationContext,this)}
    val tools = ToolExecutor(context.applicationContext, repository, scheduler,conversationImports,phoneRecords,
        createDocument={call->fileTools.createDocument(call.arguments.getValue("format").jsonPrimitive.content,call.arguments.getValue("title").jsonPrimitive.content,call.arguments.getValue("content").jsonPrimitive.content).toolResult()},
        researchWeb={call->webResearch.research(call.arguments.getValue("query").jsonPrimitive.content,call.name=="search_shopping",call.arguments["store"]?.jsonPrimitive?.content).toolResult()},
        projectWorkspaceProvider={executionWorkspace?:projectWorkspace}) {call->
        imageStudio.generateInCurrentRequest(call.arguments.getValue("prompt").jsonPrimitive.content,
            call.arguments["negativePrompt"]?.jsonPrimitive?.content.orEmpty(),
            call.arguments["steps"]?.jsonPrimitive?.intOrNull,
            call.arguments["seed"]?.jsonPrimitive?.longOrNull?:-1,
            referenceUri=chatAttachments.currentRequestReferenceUri()).toolResult()
    }
    val updates = UpdateManager(context.applicationContext)
    val automation = AutomationController(context.applicationContext)
    val callFollowUpPipeline by lazy {com.user.localagent.callfollowup.PersistentCallFollowUpPipeline(appContext,this)}
    val callFollowUp by lazy {com.user.localagent.callfollowup.CallFollowUpController(appContext){callFollowUpPipeline}}
    val skills = BuiltinSkills(context.applicationContext)
    val officeTools by lazy {com.user.localagent.office.NativeOfficeTools(appContext)}
    val emailTools by lazy {com.user.localagent.skills.EmailFileTools(appContext)}
    val phoneMedia by lazy {com.user.localagent.skills.PhoneMediaTools(appContext)}
    val videoTools by lazy {com.user.localagent.video.LocalVideoTools(appContext)}
    val fileTools by lazy {com.user.localagent.skills.FileToolOperations(appContext,officeTools,skills)}
    val imageUnderstanding by lazy {com.user.localagent.media.LocalImageUnderstanding(appContext,this)}
    private val workspaces=java.util.concurrent.ConcurrentHashMap<String,com.user.localagent.workspace.ProjectWorkspace>()
    @Volatile private var selectedWorkspaceKey="default"
    @Volatile var executionWorkspace:com.user.localagent.workspace.ProjectWorkspace?=null
    val projectWorkspace get()=workspaces.getOrPut(selectedWorkspaceKey){com.user.localagent.workspace.ProjectWorkspace(appContext,selectedWorkspaceKey)}
    suspend fun workspaceFor(projectId:String?):com.user.localagent.workspace.ProjectWorkspace {
        val key=projectId?.let {repository.chat.project(it)?.workspaceKey}?:"personal"
        return workspaces.getOrPut(key){com.user.localagent.workspace.ProjectWorkspace(appContext,key)}
    }
    suspend fun selectProjectWorkspace(projectId:String?){
        selectedWorkspaceKey=projectId?.let {repository.chat.project(it)?.workspaceKey}?:"personal"
    }
    val chatAttachments by lazy {com.user.localagent.workspace.ChatAttachments(appContext,this)}
    val knowledgeIngestion by lazy {com.user.localagent.automation.KnowledgeIngestionController(appContext,repository)}
    val planningFeedback by lazy {com.user.localagent.automation.PlanningFeedbackStore(repository)}
    val runs by lazy {
        check(assistantWorkEnabled){"이 앱에서는 일반 에이전트를 시작하지 않습니다."}
        ChatRunCoordinator(appContext,this)
    }
    val remoteTests by lazy {com.user.localagent.compute.PcRemoteTestInbox(appContext,this)}
    suspend fun validatePrivateDataOrigin(origin:com.user.localagent.audio.PrivateDataPickerOrigin) {
        val expected=origin.scope
        val access=repository.chat.accessScope(expected.threadId)
        val thread=checkNotNull(repository.chat.thread(expected.threadId)){"자료를 선택한 대화방이 없습니다."}
        check(thread.activeBranchId==expected.branchId&&access.threadId==expected.threadId&&access.projectId==expected.projectId&&
            access.scopeKey==expected.scopeKey&&access.useOwnerMemory==expected.useOwnerMemory&&
            access.threadRevision==expected.threadRevision&&access.memoryEnabled==expected.memoryEnabled){"자료를 선택한 대화·프로젝트·분기 또는 접근 정책이 바뀌었습니다. 파일을 다시 선택해주세요."}
    }
    suspend fun privateDataRepository(origin:com.user.localagent.audio.PrivateDataPickerOrigin):LocalRepository {
        validatePrivateDataOrigin(origin)
        return repository.forThread(origin.scope.threadId).also {validatePrivateDataOrigin(origin)}
    }
    suspend fun toolsForThread(threadId:String,workspace:com.user.localagent.workspace.ProjectWorkspace,
        analysisChoose:suspend(String)->com.user.localagent.models.ChosenInference?={null},
        requestRunId:String?=null,requestBranchId:String?=null,requestJob:Job?=null,analysisProgress:(String)->Unit={},
        authorizedGoalTools:()->Set<String> = {emptySet()},
        goalLifecycle:com.user.localagent.automation.GoalToolLifecycle?=null,
        requestEngine:com.user.localagent.inference.InferenceEngine=engine,
        isRunLive:()->Boolean={false},
        approveGmailSend:suspend(String,com.user.localagent.core.ToolCall,com.user.localagent.core.connections.GmailSendPreview)->Boolean={_,_,_->false},
        goalStore:com.user.localagent.automation.ChatGoalStore=runs.goals,
        onGoalChanged:suspend(com.user.localagent.core.GoalDefinition?)->Unit={},
        pcDataBinding:String?=null,
        kakaoReadBinding:String?=null,
        pcImageRecoveryAllowed:Boolean=false,
        ownerRequest:String?=null,
        connectionHub:com.user.localagent.connections.ConnectionHub?=null,
        contactLookup:((String,Int)->List<com.user.localagent.core.ContactCandidate>)?=null,
    ):ToolExecutor {
        val scoped=repository.forThread(threadId)
        val capturedAccess=repository.chat.accessScope(threadId)
        val imports=ConversationImport(appContext,scoped,scoped::discardIncompleteConversationNotes)
        suspend fun verifyAnalysisAccess() {
            checkNotNull(requestJob){"진행 중인 대화 요청이 필요합니다."}.ensureActive()
            check(requestRunId!=null&&isRunLive()){"자료 분석의 실행 범위가 끝났습니다."}
            check(repository.chat.accessScope(threadId)==capturedAccess){"자료 분석의 대화·프로젝트·접근 정책이 바뀌었습니다."}
        }
        val sources=com.user.localagent.analysis.LocalPrivateAnalysisSources(appContext,scoped,capturedAccess.scopeKey,
            cleanupIncomplete=scoped::discardIncompletePrivateSourceNotes)
        val journal=com.user.localagent.analysis.RepositoryAnalysisJournal(scoped,capturedAccess.scopeKey,
            com.user.localagent.analysis.AnalysisCheckpointCommit(scoped::commitPrivateAnalysisCheckpoint))
        val analysis=com.user.localagent.analysis.PrivateAnalysisExecutor(appContext,this,scoped,capturedAccess.scopeKey,sources,journal,
            choose=analysisChoose,progress={detail->checkNotNull(requestJob).ensureActive();analysisProgress(detail)},requestEngine=requestEngine)
        val recordings=com.user.localagent.audio.ListedRecordingStore(threadId){
            requestRunId?.takeIf {requestJob?.isActive==true&&isRunLive()}
        }
        val transcription=com.user.localagent.audio.ScopedRecordingTool(appContext,this,scoped,recordings,scoped::discardIncompleteAudioNotes,requestEngine=requestEngine)
        val requestImages by lazy {ImageStudioController(appContext,this,requestEngine=requestEngine,ownerRunId=requestRunId,recoveryAllowed=pcImageRecoveryAllowed,
            scopedRepository=scoped,ownerThreadId=threadId,verifyAccess=::verifyAnalysisAccess)}
        val noteHost=if(requestRunId!=null&&requestBranchId!=null){
            val binding=com.user.localagent.core.NoteReadBinding(requestRunId,threadId,requestBranchId,capturedAccess.scopeKey)
            com.user.localagent.notes.NoteReadPatchHost(binding,com.user.localagent.notes.NotePatchStore(binding,scoped.notePatchTransactions(binding)),::verifyAnalysisAccess)
        }else null
        val goalReadAuthority=com.user.localagent.automation.GoalReadAuthority()
        val goalTools=goalLifecycle?.let {lifecycle->com.user.localagent.automation.ScopedGoalTools(capturedAccess,
            com.user.localagent.automation.GuardedChatGoalStore(goalStore),authorizedGoalTools,requestRunId,lifecycle,
            editReadGuard=goalReadAuthority::requireEdit)}
        return ToolExecutor(appContext,scoped,scheduler,imports,phoneRecords,
            createDocument={call->fileTools.createDocument(call.arguments.getValue("format").jsonPrimitive.content,call.arguments.getValue("title").jsonPrimitive.content,call.arguments.getValue("content").jsonPrimitive.content).toolResult()},
            researchWeb={call->com.user.localagent.web.WebResearchController(appContext,scoped).research(call.arguments.getValue("query").jsonPrimitive.content,call.name=="search_shopping",call.arguments["store"]?.jsonPrimitive?.content).toolResult()},
            privateAnalysis={call->
                try {
                    com.user.localagent.analysis.PrivateAnalysisHost.withLiveAccess(::verifyAnalysisAccess,{analysis.execute(call)})
                } finally {
                    if(call.name in com.user.localagent.core.PrivateAnalysisToolCatalog.analysisNames)
                        com.user.localagent.analysis.AnalysisMemoryWorker.enqueue(appContext)
                }
            },
            transcribeRecording={call->com.user.localagent.analysis.PrivateAnalysisHost.withLiveAccess(::verifyAnalysisAccess,{transcription.execute(call)})},
            recordingListing=recordings::register,
            goalTools={call->verifyAnalysisAccess();checkNotNull(goalTools){"목표 실행의 원래 요청 연결이 필요합니다."}.execute(call).also {
                verifyAnalysisAccess()
                val actual=goalStore.loadGuarded(capturedAccess)
                onGoalChanged(actual)
            }},
            goalReadContext=goalReadAuthority::accept,
            goalEditPreflight={call->
                verifyAnalysisAccess();check(call.name in authorizedGoalTools()){"현재 요청이 목표 수정을 허용하지 않습니다."}
                val current=checkNotNull(goalStore.loadGuarded(capturedAccess)){"현재 목표를 먼저 읽어주세요."}
                goalReadAuthority.requireEdit(call,current)
            },
            connectionRunId=requestRunId,connectionIsRunLive={requestJob?.isActive==true&&isRunLive()},connectionApproveGmailSend=approveGmailSend,
            connectionThreadId=threadId,connectionPcDataBinding=pcDataBinding,connectionVerifyAccess=::verifyAnalysisAccess,
            connectionHubOverride=connectionHub,
            kakaoReadBinding=kakaoReadBinding,
            connectionComputeEngine=requestEngine as? com.user.localagent.inference.ComputeExecutionEngine,
            noteReadPatch=noteHost,ownerRequest=ownerRequest,contactLookup=contactLookup,projectWorkspaceProvider={workspace}){call->com.user.localagent.analysis.PrivateAnalysisHost.withLiveAccess(::verifyAnalysisAccess,{requestImages.generateInCurrentRequest(call.arguments.getValue("prompt").jsonPrimitive.content,
                call.arguments["negativePrompt"]?.jsonPrimitive?.content.orEmpty(),call.arguments["steps"]?.jsonPrimitive?.intOrNull,
                call.arguments["seed"]?.jsonPrimitive?.longOrNull?:-1,referenceUri=chatAttachments.currentRequestReferenceUri(requestRunId)).toolResult()})}
    }
    suspend fun capabilitySnapshot():List<com.user.localagent.core.ToolCapability> = withContext(Dispatchers.IO) {
        val grants=mutableSetOf<String>()
        val connections=com.user.localagent.connections.ConnectionHub.get(appContext)
        grants+=connections.authorizedServiceIds().map {"connector:$it"}
        grants+=connections.authorizedServiceIds(writeOnly=true).map {"connector:$it:write"}
        listOf(android.Manifest.permission.READ_SMS,android.Manifest.permission.READ_CALL_LOG,android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR,android.Manifest.permission.READ_MEDIA_AUDIO,android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.POST_NOTIFICATIONS).forEach {permission->
            if(androidx.core.content.ContextCompat.checkSelfPermission(appContext,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED)grants+=permission
        }
        if(com.user.localagent.automation.AssistantNotificationListener.granted(appContext))grants+="notification_access"
        if(com.user.localagent.integrations.KakaoScreenReader(appContext).binding()!=null&&com.user.localagent.integrations.ScreenBridgeController(appContext).state().let{it.systemEnabled&&it.connected})grants+="kakao_screen_access"
        if(automation.hasUsageAccess())grants+="usage_access"
        if(com.user.localagent.integrations.ScreenBridgeController(appContext).state().systemEnabled)grants+="screen_access"
        if(com.user.localagent.integrations.ScreenControl(appContext).ready())grants+="screen_control_access"
        val handlers=mutableSetOf<String>()
        handlers+=com.user.localagent.connections.ConsumerApps(appContext).installed().map {"consumer:${it.id}"}
        val candidates=mapOf("calendar" to android.content.Intent(android.content.Intent.ACTION_INSERT,android.provider.CalendarContract.Events.CONTENT_URI),
            "alarm" to android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM),
            "sms" to android.content.Intent(android.content.Intent.ACTION_SENDTO,android.net.Uri.parse("smsto:")),
            "email" to android.content.Intent(android.content.Intent.ACTION_SENDTO,android.net.Uri.parse("mailto:")),
            "maps" to android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("geo:0,0")),
            "browser" to android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://example.com")))
        candidates.forEach {(key,intent)->if(intent.resolveActivity(appContext.packageManager)!=null)handlers+=key}
        val localModels=mutableSetOf<String>()
        if(models.models.value.any {it.verified&&it.runtime in setOf("litertlm","gguf")})localModels+="text"
        imageStudio.models.refresh()
        val pcImages=com.user.localagent.compute.PcComputeSettings.get(appContext).state.value.let{it.paired&&it.preferPcImages}&&
            com.user.localagent.compute.PcTransport(appContext).available()!=null
        if(imageStudio.models.state.value.available||pcImages)localModels+="image"
        val media=phoneMedia.capabilities()
        if(media.offlineKoreanVoices.isNotEmpty())handlers+="tts"
        if(media.onDeviceRecognitionAvailable)localModels+="asr"
        val selectedAsr=models.asrSelection.value
        var deviceFileAsrReady=false
        if(selectedAsr.modelId==com.user.localagent.models.AsrCatalog.DEVICE_MODEL)try {
            withTimeout(5000){models.prepareDeviceAsr(download=false);deviceFileAsrReady=models.deviceAsrStatus.value.available}
        }catch(timeout:TimeoutCancellationException){currentCoroutineContext().ensureActive()}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){/* Capability remains unconfirmed; no download or live-microphone fallback. */}
        if(if(selectedAsr.modelId==com.user.localagent.models.AsrCatalog.DEVICE_MODEL)
                deviceFileAsrReady&&selectedAsr.language!="auto" else models.asrReady(selectedAsr.modelId))localModels+="asr_file"
        val verified=appContext.getSharedPreferences("validated-tools",Context.MODE_PRIVATE).all.filterValues {it==true}.keys
        com.user.localagent.core.ToolCapabilityCatalog.snapshot(com.user.localagent.core.CapabilityEnvironment(
            grantedPermissions=grants,installedHandlers=handlers,installedModels=localModels,validatedConnectors=connections.verifiedServiceIds(),
            validatedCapabilities=verified,apiLevel=android.os.Build.VERSION.SDK_INT))
    }
    private val maintenanceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var idleUnload:Job?=null
    @Synchronized fun cancelIdleUnload(){idleUnload?.cancel();idleUnload=null}
    @Synchronized fun scheduleIdleUnload(){
        cancelIdleUnload()
        idleUnload=maintenanceScope.launch {
            delay(120_000)
            // Image-only cleanup must not initialize the assistant's lazy coordinator.
            val mayUnload = !assistantWorkEnabled || (!runs.busy.value && runs.queued.value.isEmpty())
            if(mayUnload&&RuntimeGate.inference.compareAndSet(false,true))
                try{engine.unloadPhoneResidency()}finally{RuntimeGate.inference.set(false)}
        }
    }
}
