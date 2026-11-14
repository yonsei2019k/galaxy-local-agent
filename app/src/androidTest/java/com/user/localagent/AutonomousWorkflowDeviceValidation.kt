package com.user.localagent

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.GenerationSummary
import com.user.localagent.ui.UiWorkEventKind
import com.user.localagent.ui.UiWorkEvidence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real cached local-model three-tool workflow; no general semantic-quality claim. */
@RunWith(AndroidJUnit4::class)
class AutonomousWorkflowDeviceValidation {
    @Test fun realLocalModelPerformsFollowupVerificationWithLiveActivity(){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("autonomousWorkflowApproved=true required",args.getString("autonomousWorkflowApproved")=="true")
        val seconds=args.getString("autonomousWorkflowTimeoutSeconds")?.toIntOrNull()?:1800
        require(seconds in 300..1800){"Explicit workflow timeout must remain between five and thirty minutes"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking(Dispatchers.Default){AutonomousWorkflowFixture(context,args.getString("autonomousWorkflowModelId"),seconds*1000L).execute()}
    }

    /** Explicit recovery only; no model calls and no unjournaled fixture discovery. */
    @Test fun recoverOnlyJournaledSyntheticFixtureAfterProcessDeath(){
        assumeTrue("autonomousWorkflowRecoveryApproved=true required",InstrumentationRegistry.getArguments().getString("autonomousWorkflowRecoveryApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking(Dispatchers.Default){AutonomousWorkflowFixture(context,null,300000).recover()}
    }
}

/** State lives in ordinary fields, rather than dozens of spilled locals in one suspend lambda. */
private class AutonomousWorkflowFixture(private val context:Context,private val requestedModel:String?,private val timeoutMillis:Long){
    private val app=(context.applicationContext as LocalAgentApplication).container
    private val repo=app.repository
    private val chat=repo.chat
    private val preferences=context.getSharedPreferences("chat-preferences",0)
    private val pc=PcComputeSettings.get(context)
    private val journal=DraftStore(context,"qa-autonomous-workflow-cleanup-v2",16384)
    private val report=AtomicFile(File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks/autonomous-workflow-device.json"))
    private val started=SystemClock.elapsedRealtime()
    private lateinit var coordinator:ChatRunCoordinator
    private var token=UUID.randomUUID().toString()
    private var originalThreadId:String?=null
    private var originalDraftHash:String?=null
    private var originalModelPresent=false
    private var originalModel:String?=null
    private var originalPcAutomatic=true
    private var pairingVersion=""
    private var pcChangeIntent=false
    private var modelChangeIntent=false
    private var armed=false
    private var projectId:String?=null
    private var threadId:String?=null
    private var runId:String?=null
    private var chosenModelId:String?=null
    private var chosenModelSha:String?=null
    private var chosenRuntime:String?=null
    private var stage="initialization"
    private var failureStage:String?=null
    private var recoveryOnly=false
    private var failureClass:String?=null
    private var primaryFailure:Throwable?=null
    private val cleanupErrors=mutableListOf<String>()
    private var runStatus:String?=null
    private var runErrorCode:String?=null
    private var diagnosticCodes:List<String> = emptyList()
    private var syntheticHostErrorHash:String?=null
    private var syntheticHostErrorLength:Int?=null
    private var auditCount=0
    private var traceCount=0
    private var bodyChars=0
    private var activityCount=0
    private var receiptVerified=false
    private var bindingVerified=false
    private var phoneVerified=false
    private var liveActivitySeen=false
    private var followupSeen=false
    private var reviewCompleted=false
    private var reviewBudgetFinalized=false
    private var cleanupVerified=false
    private var lastSummary:GenerationSummary?=null
    private var lastNativeProgress:com.user.localagent.inference.NativeGenerationProgress?=null
    private var planningElapsedMs:Long?=null
    private var semanticStartedAt:Long?=null
    private var semanticAction:String?=null
    private var calculationReadback:JsonArray=JsonArray(emptyList())
    private val allowed=setOf("calculate","list_skills","read_skill")
    private val projectTitle get()="Synthetic autonomous project $token"
    private val threadTitle get()="Synthetic autonomous chat $token"

    suspend fun execute(){
        check(journal.readStrict().isEmpty()){"A previous fixture cleanup journal exists; run the explicit recovery gate first"}
        try{prepare();submit();awaitWorkflow();verify()}
        catch(error:Throwable){primaryFailure=error;failureClass=error.javaClass.simpleName;failureStage=stage;captureSafely();checkpoint(stage);throw error}
        finally{withContext(NonCancellable){cleanup()}}
    }

    suspend fun recover(){
        val raw=journal.readStrict()
        if(raw.isEmpty())return
        restoreJournal(raw)
        recoveryOnly=true
        coordinator=withContext(Dispatchers.Main.immediate){app.runs}
        waitPrepared()
        idle() // Recovery never changes routing while an unrelated owner/native task is running.
        checkpoint("explicit_recovery_started")
        withContext(NonCancellable){cleanup()}
    }

    private suspend fun prepare(){
        coordinator=withContext(Dispatchers.Main.immediate){app.runs}
        waitPrepared();idle()
        check(chat.queuedRuns().isEmpty()){"An owner request is queued"}
        originalThreadId=coordinator.selectedThreadId.value
        originalDraftHash=draftHash(chat.readDraft(requireNotNull(originalThreadId)))
        originalModelPresent=preferences.contains("model");originalModel=preferences.getString("model",null)
        originalPcAutomatic=pc.state.value.automaticText;pairingVersion=currentPairingVersion()
        checkpoint("selecting_cached_model")
        app.models.refresh();idle();selectCachedModel()
        // Write-ahead intent precedes every global setting mutation and fixture creation.
        armed=true;pcChangeIntent=true;checkpoint("before_phone_route_change")
        withContext(Dispatchers.Main.immediate){pc.automaticText(false)}
        check(!pc.state.value.automaticText){"Phone-only setting was not consumed"}
        checkpoint("before_project_creation")
        val settings=ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed)
        projectId=chat.createProject(projectTitle,settings).id
        checkpoint("before_thread_creation")
        threadId=chat.createThread(requireNotNull(projectId),threadTitle).id
        checkpoint("preparing_scoped_chat")
        select(requireNotNull(threadId));idle()
    }

    private fun selectCachedModel(){
        val cached=app.models.models.value.filter{it.verified&&it.runtime in setOf("gguf","litertlm")&&!it.id.contains("mmproj")&&File(it.path).isFile}
        val selected=if(requestedModel!=null)cached.firstOrNull{it.id==requestedModel}else cached.minByOrNull{
            when(it.id){"gemma-e2b"->0;"qwen-2b"->1;"gemma-e2b-media"->2;else->3}}
        val model=checkNotNull(selected){"A verified cached local model is required; no download is allowed"}
        chosenModelId=model.id;chosenModelSha=model.sha256;chosenRuntime=model.runtime
    }

    private suspend fun submit(){
        modelChangeIntent=true;checkpoint("before_model_preference_change")
        withContext(Dispatchers.Main.immediate){
            check(coordinator.selectedThreadId.value==threadId&&coordinator.selectionReady.value)
            coordinator.changeMode("act");coordinator.setThinking(false,threadId)
            coordinator.chooseModel(requireNotNull(chosenModelId),threadId)
        }
        waitUntil(15000){chat.readDraft(requireNotNull(threadId))?.let{it.modelId==chosenModelId&&!it.thinking}==true}
        checkpoint("before_request_submission")
        check(withContext(Dispatchers.Main.immediate){coordinator.send(REQUEST,threadId)}){"The real request was not accepted"}
        waitUntil(15000){chat.runsForThread(requireNotNull(threadId)).isNotEmpty()}
        val actual=chat.runsForThread(requireNotNull(threadId)).single()
        runId=actual.id;checkpoint("waiting_real_model_tool_loop")
    }

    private suspend fun awaitWorkflow(){
        val deadline=SystemClock.elapsedRealtime()+timeoutMillis
        var nextCheckpoint=0L
        while(true){
            currentCoroutineContext().ensureActive();checkOwnerScope()
            coordinator.executionForRun(requireNotNull(runId))?.let {worker->
                if(worker.executionEngine.executionLocation=="phone"&&worker.executionEngine.residentModelPath!=null)phoneVerified=true
            }
            chat.assistantForRun(requireNotNull(runId))?.let {message->
                coordinator.workActivities.value[message.id]?.let {activity->
                    if(activity.status=="running"&&activity.events.isNotEmpty())liveActivitySeen=true
                }
            }
            runStatus=chat.run(requireNotNull(runId))?.status
            if(runStatus in ChatStore.TERMINAL||runStatus in setOf("paused","waiting_approval"))break
            val now=SystemClock.elapsedRealtime()
            if(now>=nextCheckpoint){capture();checkpoint("waiting_real_model_tool_loop");nextCheckpoint=now+15000}
            if(now>=deadline){capture();checkpoint("workflow_timeout");throw AssertionError("The actual model workflow did not finish within its explicit bounded timeout")}
            delay(100)
        }
        capture();checkpoint("checking_actual_results")
    }

    private fun checkOwnerScope(){
        check(coordinator.selectedThreadId.value==threadId){"Owner navigation interrupted the synthetic test"}
        check(preferences.getString("model",null)==chosenModelId){"Owner model choice changed"}
        check(!pc.state.value.automaticText&&currentPairingVersion()==pairingVersion){"Owner PC routing or pairing changed"}
    }

    private suspend fun verify(){
        assertTrue("No live exact-run activity projection was observed",liveActivitySeen)
        assertTrue("The actual inference route was not phone",phoneVerified)
        val ownThread=requireNotNull(threadId);val ownRun=requireNotNull(runId)
        waitUntil(120000){coordinator.executionForRun(ownRun)==null}
        val trace=allTraceEvents(ownThread,ownRun)
        assertTrue("No independent calculation was actually executed",auditCount>=2)
        assertTrue("The calculations did not use independent arguments",trace.filter{it.event.code==WorkTraceCode.TOOL_RESULT&&it.event.toolName=="calculate"&&it.event.evidence==VerificationState.VERIFIED}.map{it.event.argsHash}.distinct().size>=2)
        val storedCalculations=QueuedChatRequest.fromJson(checkNotNull(chat.run(ownRun)).contextJson).previousSteps
            .filter{it.tool=="calculate"&&it.evidence.state==VerificationState.VERIFIED}
        assertEquals("Stored execution evidence differs from actual calculation audits",auditCount,storedCalculations.size)
        // This fixed public fixture allows numeric arithmetic only. Pure readback is not
        // another model-selected tool execution and does not add an execution receipt.
        calculationReadback=buildJsonArray{storedCalculations.forEach{step->
            val value=Json.parseToJsonElement(OfficeToolEngine.execute(ToolCall("synthetic-readback","calculate",step.arguments))).jsonObject
            add(buildJsonObject{put("expression",step.arguments.getValue("expression"));put("value",value.getValue("value"))})
        }}
        val result=checkNotNull(trace.firstOrNull{it.event.code==WorkTraceCode.TOOL_RESULT&&it.event.toolName=="calculate"&&it.event.evidence==VerificationState.VERIFIED}){"No actual verified clock trace result was produced"}
        followupSeen=trace.any {it.event.code==WorkTraceCode.PLAN_READY&&it.event.summarySource==WorkSummarySource.MODEL_PLAN&&it.event.publicSummary?.startsWith("발견한 확인 과제:")==true}
        reviewCompleted=trace.any {it.event.code==WorkTraceCode.PLAN_READY&&it.event.summarySource==WorkSummarySource.MODEL_PLAN&&it.event.publicSummary?.startsWith("후속 검토 요약:")==true}
        assertTrue("The local model did not select a followup during the review",followupSeen)
        reviewBudgetFinalized=Json.parseToJsonElement(checkNotNull(chat.run(ownRun)).contextJson).jsonObject["reviewBudgetFinalization"]?.jsonPrimitive?.booleanOrNull==true
        assertTrue("Neither model review nor explicitly limited final writing completed",reviewCompleted||reviewBudgetFinalized)
        trace.filter {it.event.code==WorkTraceCode.TOOL_RESULT&&it.event.toolName=="calculate"&&it.event.evidence==VerificationState.VERIFIED}.forEach {verifyReceipt(it,trace)}
        val userMessages=chat.messages(ownThread).first().filter {it.role=="user"}
        assertEquals("Model followup became invented owner speech",listOf(REQUEST),userMessages.map {it.text})
        assertTrue("Real model planning was not recorded",trace.any{it.event.code==WorkTraceCode.SEMANTIC_STARTED}&&trace.any{it.event.code==WorkTraceCode.PLAN_READY})
        assertTrue("Real model inference was not recorded",trace.any{it.event.code==WorkTraceCode.INFERENCE_STARTED}&&trace.any{it.event.code==WorkTraceCode.INFERENCE_FINISHED})
        assertTrue("The actual workflow did not complete",runStatus=="completed")
        checkpoint("checking_final_answer_and_activity")
        val assistant=checkNotNull(chat.assistantForRun(ownRun))
        val body=chat.fullMessageText(assistant.id);bodyChars=body.length
        assertTrue("The final body is empty or an unexecuted tool envelope",body.trim().length>=10&&body.contains("323")&&!body.trimStart().startsWith("{\"type\":\"tool_call\""))
        if(reviewBudgetFinalized)assertTrue("Limited review was hidden from the owner",body.contains(AutonomousReviewPolicy.LIMITED_FINAL_NOTICE))
        verifyActivity(assistant)
        checkpoint("completed")
    }

    private suspend fun verifyReceipt(result:WorkTraceRecord,trace:List<WorkTraceRecord>){
        val ownThread=requireNotNull(threadId);val ownRun=requireNotNull(runId)
        val audit=checkNotNull(repo.forThread(ownThread).toolRunEvidence(requireNotNull(result.event.auditId)))
        val start=trace.single{it.event.code==WorkTraceCode.TOOL_STARTED&&it.event.invocationId==result.event.invocationId}
        assertTrue("Clock start and result scopes differ",start.event.argsHash==result.event.argsHash&&start.event.scopeHash==result.event.scopeHash)
        val count=trace.size
        val receipt=repo.workTrace.recordOutcome(repo.workTrace.open(ownRun,ownThread),start.id,true,VerificationState.VERIFIED,audit.summary.substringAfter(" · "))
        assertTrue("Immutable receipt readback failed",receipt.audit==audit&&receipt.receiptId==WorkTraceReceiptCodec.receiptId(audit.id))
        assertEquals(count,allTraceEvents(ownThread,ownRun).size)
        receiptVerified=true
    }

    private suspend fun verifyActivity(assistant:MessageRecord){
        waitUntil(15000){coordinator.workActivities.value[assistant.id]?.let{it.runId==runId&&it.threadId==threadId&&it.assistantMessageId==assistant.id&&it.status=="completed"&&it.events.any{event->event.kind==UiWorkEventKind.TOOL&&event.evidence==UiWorkEvidence.VERIFIED}}==true}
        val activities=coordinator.workActivityController.activities.value;activityCount=activities.size
        assertTrue("Another room appears in the projection",activities.values.all{it.threadId==threadId})
        assertTrue("Primary answer scope differs",assistant.runId==runId&&assistant.threadId==threadId)
        bindingVerified=true
    }

    private suspend fun capture(){
        val ownRun=runId?:return;val ownThread=threadId?:return
        val run=chat.run(ownRun);runStatus=run?.status;runErrorCode=run?.errorCode
        val events=repo.workTrace.events(ownThread,ownRun)
        val semanticStart=events.firstOrNull{it.event.code==WorkTraceCode.SEMANTIC_STARTED}
        val firstPlan=events.firstOrNull{it.event.code==WorkTraceCode.PLAN_READY&&it.event.summarySource==WorkSummarySource.MODEL_PLAN}
        if(semanticStartedAt==null&&semanticStart!=null)semanticStartedAt=semanticStart.atEpochMillis
        if(semanticStartedAt!=null&&firstPlan!=null&&planningElapsedMs==null)planningElapsedMs=(firstPlan.atEpochMillis-requireNotNull(semanticStartedAt)).coerceAtLeast(0)
        coordinator.executionForRun(ownRun)?.lastSemanticAction?.value?.let{semanticAction=it}
        coordinator.executionForRun(ownRun)?.executionEngine?.nativeProgressSnapshot?.let{lastNativeProgress=it}
        traceCount=events.size
        diagnosticCodes=events.map {it.event.code.name}.takeLast(40)
        // This isolated project disables owner memory and offers only three public tools.
        // Export only its fingerprint/length so source constants can be matched locally;
        // exception strings can contain quoted data and are never copied into the report.
        if(runStatus=="failed"&&projectId?.let {chat.project(it)?.let(::safeProject)}==true){
            val users=chat.messages(ownThread).first().filter {it.role=="user"}
            if(users.map {it.text}==listOf(REQUEST)){
                val body=chat.assistantForRun(ownRun)?.text.orEmpty()
                if(body.startsWith("작업을 끝내지 못했습니다: ")){
                    val failure=body.substringAfter(": ").lineSequence().first().take(400)
                    syntheticHostErrorHash=hash(failure);syntheticHostErrorLength=failure.length
                }
            }
        }
        auditCount=chat.toolRunsForThread(ownThread,ownRun).first().count{it.name=="calculate"&&it.success&&it.summary.startsWith("VERIFIED")}
        bodyChars=chat.assistantForRun(ownRun)?.text?.length?:0
        coordinator.executionForRun(ownRun)?.executionEngine?.lastGeneration?.value?.let {lastSummary=it}
    }
    private suspend fun captureSafely(){try{capture()}catch(_:Exception){}}
    private suspend fun allTraceEvents(thread:String,run:String):List<WorkTraceRecord>{
        val found=linkedMapOf<String,WorkTraceRecord>()
        var cursor:WorkTraceCursor?=null
        repeat(64){
            currentCoroutineContext().ensureActive()
            val page=repo.workTrace.page(thread,run,cursor)
            page.events.forEach{found[it.id]=it}
            val next=page.nextCursor?:return found.values.sortedBy{it.sequence}
            check(next!=cursor){"Synthetic trace cursor did not advance"};cursor=next
        }
        error("Synthetic trace exceeds bounded verification window")
    }

    private suspend fun cleanup(){
        cleanupStep{checkpoint("cleanup_started")}
        cleanupStep{resolveCreationIntents();stopOwnRun()}
        cleanupStep{restoreNavigation()}
        cleanupStep{deleteOwnThreadAndArchiveProject()}
        // Global settings restoration is independent of failed navigation or fixture cleanup.
        cleanupStep{restoreModelPreference()}
        cleanupStep{restorePcPolicy()}
        cleanupVerified=cleanupErrors.isEmpty()
        stage=if(cleanupVerified&&primaryFailure==null){if(recoveryOnly)"recovery_completed"else"completed"}else failureStage?:"cleanup_failed"
        cleanupStep{writeReport()}
        cleanupVerified=cleanupErrors.isEmpty()
        if(cleanupVerified){journal.write("");armed=false}
        if(primaryFailure==null&&cleanupErrors.isNotEmpty())throw AssertionError("Synthetic fixture cleanup was incomplete; encrypted recovery journal retained")
    }

    private suspend fun cleanupStep(action:suspend()->Unit){
        try{action()}catch(error:Throwable){cleanupErrors+=error.javaClass.simpleName;primaryFailure?.addSuppressed(error)}
    }

    private suspend fun resolveCreationIntents(){
        if(!armed)return
        if(projectId==null)projectId=chat.projects.first().filter{it.title==projectTitle}.singleOrNull()?.id
        val project=projectId?.let{chat.project(it)}?:return
        check(project.title==projectTitle&&safeProject(project)){"Synthetic project changed; preserving it"}
        if(threadId==null)threadId=chat.threads.first().filter{it.projectId==project.id&&it.title==threadTitle}.singleOrNull()?.id
        val own=threadId?.let{chat.thread(it)}?:return
        check(own.title==threadTitle&&own.projectId==project.id){"Synthetic room changed; preserving it"}
        if(runId==null){
            val candidates=chat.runsForThread(own.id)
            val candidate=candidates.singleOrNull()
            if(candidate!=null&&candidate.userMessageId?.let{repo.getMessage(it)?.text==REQUEST}==true)runId=candidate.id
        }
    }

    private suspend fun stopOwnRun(){
        val ownRun=runId?:return
        if(threadId?.let{chat.thread(it)}==null)return
        val actual=chat.run(ownRun)?:return
        check(actual.threadId==threadId&&actual.projectId==projectId)
        val ownedJob=coordinator.runningJobForThread(requireNotNull(threadId))
        if(ownedJob!=null)withContext(Dispatchers.Main.immediate){coordinator.stopRun(ownRun,threadId)}
        if(ownedJob!=null)withTimeout(60000){ownedJob.join()}
        waitUntil(15000){!coordinator.isRunLive(ownRun)}
        // Let the writer durably flush before changing its lifecycle. Never close a running
        // segment out from under cancellation cleanup just to make this fixture finish faster.
        val after=chat.run(ownRun)?:return
        if(after.status=="queued")chat.cancelQueuedRun(ownRun)
        else if(after.status !in ChatStore.TERMINAL)chat.updateRun(ownRun,"cancelled",errorCode="synthetic_visibility_cleanup")
    }

    private suspend fun restoreNavigation(){
        val original=originalThreadId?:return
        val current=coordinator.selectedThreadId.value
        if(current!=threadId&&current!=original)return // Keep later owner navigation intact.
        check(coordinator.activeRunId==null&&!coordinator.busy.value){"Owner work prevents navigation restoration"}
        check(draftHash(chat.readDraft(original))==originalDraftHash){"Original draft changed; preserving owner state"}
        select(original)
        assertTrue("Original draft changed",draftHash(chat.readDraft(original))==originalDraftHash)
    }

    private suspend fun deleteOwnThreadAndArchiveProject(){
        val own=threadId?.let{chat.thread(it)}
        if(own!=null){
            check(own.title==threadTitle&&own.projectId==projectId)
            check(coordinator.activeThreadId!=own.id&&coordinator.selectedThreadId.value!=own.id)
            check(chat.runsForThread(own.id).all{it.id==runId}){"Additional owner runs exist; preserving the room"}
            val messages=chat.messages(own.id).first()
            check(messages.filter{it.role=="user"}.all{it.text==REQUEST}){"Additional owner messages exist; preserving the room"}
            chat.deleteThread(own.id,deleteDerivedMemory=true)
            preferences.edit().remove("mode-${own.id}").commit()
            check(chat.thread(own.id)==null)
        }
        projectId?.let{id->
            val project=checkNotNull(chat.project(id));check(project.title==projectTitle&&safeProject(project))
            check(chat.threads.first().none{it.projectId==id&&it.id!=threadId}){"Additional owner rooms exist; preserving the project"}
            chat.archiveProject(id)
        }
    }

    private suspend fun restoreModelPreference()=withContext(Dispatchers.Main.immediate){
        if(modelChangeIntent&&preferences.getString("model",null)==chosenModelId){
            val edit=preferences.edit();if(originalModelPresent)edit.putString("model",originalModel)else edit.remove("model")
            check(edit.commit()){"Model preference restoration failed"}
        }
    }
    private suspend fun restorePcPolicy()=withContext(Dispatchers.Main.immediate){
        if(pcChangeIntent&&!pc.state.value.automaticText&&currentPairingVersion()==pairingVersion)pc.automaticText(originalPcAutomatic)
    }

    private fun safeProject(project:ProjectRecord)=!project.settings.useOwnerMemory&&!project.settings.learnOwnerFacts&&ToolRegistry.definitions.keys-project.settings.disabledTools==allowed
    private suspend fun waitPrepared(){waitUntil(30000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value.isNotBlank()};withContext(Dispatchers.Main.immediate){yield()}}
    private suspend fun select(id:String){withContext(Dispatchers.Main.immediate){coordinator.selectThread(id)};waitUntil(15000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value==id}}
    private suspend fun waitUntil(timeout:Long,condition:suspend()->Boolean){
        val complete=withTimeoutOrNull(timeout){while(!condition()){currentCoroutineContext().ensureActive();delay(50)};true}
        check(complete==true){"A bounded fixture wait did not complete"}
    }
    private fun idle(){
        check(!RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get()){"A native task is active"}
        check(!coordinator.hasActiveRuns.value&&coordinator.queued.value.isEmpty()){"Owner work is active or queued"}
        check(!app.imageStudio.state.value.running){"An image task is active"}
    }

    private fun checkpoint(value:String){stage=value;if(armed)journal.write(journalJson());writeReport()}
    private fun writeReport(){
        val body=buildJsonObject{
            put("synthetic_project_only",true);put("allowed_tool_count",3);put("default_tool_quality_claim",false)
            put("stage",stage);put("failure_stage",failureStage);put("recovery_only",recoveryOnly);put("elapsed_ms",SystemClock.elapsedRealtime()-started);put("workflow_timeout_ms",timeoutMillis)
            put("run_status",runStatus);put("failure_class",failureClass);put("model_id",chosenModelId);put("model_sha256",chosenModelSha);put("runtime",chosenRuntime)
            put("run_error_code",runErrorCode);put("trace_codes",buildJsonArray{diagnosticCodes.forEach{add(it)}})
            put("synthetic_host_failure_sha256",syntheticHostErrorHash);put("synthetic_host_failure_chars",syntheticHostErrorLength)
            put("verified_calculation_audits",auditCount);put("live_activity_seen",liveActivitySeen);put("model_followup_seen",followupSeen);put("final_review_completed",reviewCompleted);put("trace_events",traceCount);put("final_body_chars",bodyChars);put("activity_entries",activityCount)
            put("immutable_receipt_verified",receiptVerified);put("exact_activity_binding_verified",bindingVerified);put("actual_phone_execution_verified",phoneVerified);put("fixture_cleanup_verified",cleanupVerified)
            put("review_budget_finalized",reviewBudgetFinalized)
            put("calculation_readback",calculationReadback)
            put("cleanup_error_classes",buildJsonArray{cleanupErrors.take(8).forEach{add(it)}})
            put("last_generation",lastSummary?.let{summary->buildJsonObject{put("reason",summary.reason.name);put("evidence",summary.evidence.name);summary.decodeTokens?.let{put("decode_tokens",it)}}}?:JsonNull)
            put("planning_elapsed_ms",planningElapsedMs?.let(::JsonPrimitive)?:JsonNull)
            put("semantic_action",semanticAction)
            put("native_progress",lastNativeProgress?.let{measured->buildJsonObject{
                put("phase",measured.phase.name);put("processed",measured.processedPromptTokens);put("total",measured.totalPromptTokens)
                put("sampled",measured.sampledTokens);put("visible_bytes",measured.visibleUtf8Bytes);put("elapsed_ms",measured.elapsedMillis)
            }}?:JsonNull)
            put("raw_owner_context_exported",false);put("raw_answer_exported",false)
        }.toString()
        val parent=checkNotNull(report.baseFile.parentFile);check(parent.isDirectory||parent.mkdirs())
        val output=report.startWrite();try{output.write(body.toByteArray(Charsets.UTF_8));report.finishWrite(output)}catch(error:Throwable){report.failWrite(output);throw error}
    }

    private fun journalJson()=buildJsonObject{
        put("version",2);put("token",token);put("stage",stage);put("originalThreadId",originalThreadId);put("originalDraftHash",originalDraftHash)
        put("originalModelPresent",originalModelPresent);put("originalModel",originalModel);put("originalPcAutomatic",originalPcAutomatic);put("pairingVersion",pairingVersion)
        put("pcChangeIntent",pcChangeIntent);put("modelChangeIntent",modelChangeIntent);put("modelId",chosenModelId);put("modelSha",chosenModelSha);put("runtime",chosenRuntime)
        put("projectId",projectId);put("threadId",threadId);put("runId",runId)
    }.toString()

    private fun restoreJournal(raw:String){
        val value=Json.parseToJsonElement(raw).jsonObject
        val keys=setOf("version","token","stage","originalThreadId","originalDraftHash","originalModelPresent","originalModel","originalPcAutomatic","pairingVersion","pcChangeIntent","modelChangeIntent","modelId","modelSha","runtime","projectId","threadId","runId")
        require(value.keys==keys&&value.getValue("version").jsonPrimitive.int==2)
        fun text(key:String)=value.getValue(key).takeUnless{it is JsonNull}?.jsonPrimitive?.content
        token=checkNotNull(text("token"));require(UUID.fromString(token).toString()==token)
        originalThreadId=text("originalThreadId");originalDraftHash=text("originalDraftHash")
        originalModelPresent=value.getValue("originalModelPresent").jsonPrimitive.boolean;originalModel=text("originalModel")
        originalPcAutomatic=value.getValue("originalPcAutomatic").jsonPrimitive.boolean;pairingVersion=checkNotNull(text("pairingVersion"))
        pcChangeIntent=value.getValue("pcChangeIntent").jsonPrimitive.boolean;modelChangeIntent=value.getValue("modelChangeIntent").jsonPrimitive.boolean
        chosenModelId=text("modelId");chosenModelSha=text("modelSha");chosenRuntime=text("runtime")
        projectId=text("projectId");threadId=text("threadId");runId=text("runId");stage="recovery_requested";armed=true
        originalThreadId?.let{require(it.length in 1..100&&it.none{c->c.code<32})}
        listOfNotNull(projectId,threadId,runId).forEach{require(UUID.fromString(it).toString()==it)}
        chosenModelId?.let{require(it.length in 1..200&&it.none{c->c.code<32})}
        require(pairingVersion=="unpaired"||pairingVersion.matches(Regex("[a-f0-9]{64}")))
    }

    private fun currentPairingVersion():String {
        val connection=pc.snapshot()?:return "unpaired"
        // Authenticated discovery may update a LAN address after reconnect. Identity and credentials,
        // rather than a mutable endpoint, define pairing. No credentials enter the report/journal.
        return hash(listOf(connection.serverId,connection.token,connection.certificateSha256,connection.discoveryPort.toString()).joinToString("\u0000"))
    }
    private fun draftHash(value:ChatDraftRecord?):String=if(value==null)"absent"else hash(buildJsonObject{
        put("id",value.threadId);put("text",value.text);put("model",value.modelId);put("thinking",value.thinking);put("revision",value.revision);put("at",value.updatedAt);put("anchor",value.scrollMessageId);put("offset",value.scrollOffset)
    }.toString())
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {const val REQUEST="계산 도구로 17 곱하기 19를 구해줘. 첫 계산 결과를 얻은 뒤 결론의 근거가 충분한지 스스로 검토해. 다른 수식을 이용한 검산이 빠져 있으면 그 검산을 후속 작업으로 직접 진행하고, 검산 결과까지 확인한 뒤 최종 결론을 알려줘."}
}
