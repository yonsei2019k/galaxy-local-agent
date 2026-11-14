package com.user.localagent

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.*
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import com.user.localagent.ui.UiAnswerPhase
import com.user.localagent.workspace.ProjectWorkspace
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual PC + actual coordinator; synthetic public prompt, no tools, private owner data or cloud.
 * Requires durable text, a later parsed-SSE window and a request-matched real cancel ACK.
 * The window is not proof of native decoder activity; that needs a stronger backend receipt.
 */
@RunWith(AndroidJUnit4::class)
class ActualPcCancellationDeviceValidation {
    private data class Window(val serial:Int, val preparedChars:Int)
    private data class SavedPart(val id:String, val number:Int, val text:String)

    /** Test-only adapter for the CURRENT PC state's exact, source-reviewed fixed protocol. */
    private class Probe(private val actual:AutomaticComputeEngine, observerScope:CoroutineScope):ComputeExecutionEngine by actual,PairedPcTextLoader {
        private val lock=Any()
        private var serial=0
        private var active=0
        private var armed=0
        private var progressed=0
        private var prepared=0
        private val receipts=mutableListOf<PcCleanupEvidence>()
        private var typedLoads=0
        private var matchingTypedReceipts=0
        @Volatile var cancellation:GenerationSummary?=null
            private set

        init {
            observerScope.launch(start=CoroutineStart.UNDISPATCHED){
                actual.state.collect { value->synchronized(lock){
                    if(active!=0&&value==START){armed=active;progressed=0;prepared=0}
                    val chars=PROGRESS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
                    if(active!=0&&armed==active&&chars!=null&&chars>0){progressed=active;prepared=chars}
                }}
            }
            observerScope.launch(start=CoroutineStart.UNDISPATCHED){
                actual.lastPcCleanup.filterNotNull().collect { evidence->synchronized(lock){
                    if(receipts.size<24)receipts+=evidence
                }}
            }
        }
        fun window():Window?=synchronized(lock){
            val live=PROGRESS.matchEntire(actual.state.value)?.groupValues?.get(1)?.toIntOrNull()
            if(active!=0&&armed==active&&progressed==active&&prepared>0&&live!=null&&live>0&&
                actual.computeLocation.value==ComputeResource.PC&&actual.lastGeneration.value==null)
                Window(active,prepared) else null
        }
        fun count():Int=synchronized(lock){serial}
        fun typedLoadCount():Int=synchronized(lock){typedLoads}
        fun typedReceiptCount():Int=synchronized(lock){matchingTypedReceipts}
        fun allTypedReceiptsMatched():Boolean=synchronized(lock){typedLoads>0&&matchingTypedReceipts==typedLoads}
        override suspend fun loadPairedPcText(target:PairedPcText,options:InferenceOptions):PairedPcLoadReceipt {
            synchronized(lock){typedLoads++}
            val receipt=actual.loadPairedPcText(target,options)
            check(receipt.matches(target,options.contextSize)){"QA_TYPED_LOAD_RECEIPT_MISMATCH"}
            synchronized(lock){matchingTypedReceipts++}
            return receipt // The real receipt is returned unchanged.
        }
        fun cleanup():List<PcCleanupEvidence> = synchronized(lock){receipts.toList()}
        fun cancelOutcome(requestId:String):PcCancelOutcome?=actual.pcCancelOutcome(requestId)
        private fun snapshotCurrentCleanup(){
            // StateFlow may conflate cancel -> unload. Preserve only an actually published value;
            // this cannot recover a late reply rejected by the PC engine's lifecycle guard.
            val evidence=actual.lastPcCleanup.value?:return
            synchronized(lock){if(receipts.size<24)receipts+=evidence}
        }
        override suspend fun unload(){
            snapshotCurrentCleanup() // No delay, network call or inferred cancel receipt.
            actual.unload()
        }
        private fun observe(block:()->Flow<GenerationEvent>):Flow<GenerationEvent> = flow {
            val mine=synchronized(lock){++serial;active=serial;armed=0;progressed=0;prepared=0;serial}
            try { emitAll(block()) }
            catch(cancel:CancellationException){
                // AutomaticComputeEngine.events finally snapshots PcInferenceEngine's terminal
                // summary BEFORE its caller starts owner-scoped unload. Read it here, not later.
                cancellation=actual.lastGeneration.value?.takeIf{it.reason==GenerationStopReason.CANCELLED}
                snapshotCurrentCleanup()
                throw cancel
            }finally{synchronized(lock){if(active==mine)active=0}}
        }
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>) =
            observe {actual.generateChatEvents(userText,systemInstruction,history)}
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>) =
            observe {actual.generateStructuredEvents(userText,systemInstruction,schema,history)}

        companion object {
            private const val START="PC에서 계산 중 · 결과는 스마트폰에 저장"
            private val PROGRESS=Regex("PC에서 답변 작성 중 · ([1-9][0-9]*)자 준비 · 완료 확인 대기")
        }
    }

    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun writeAtomic(file:File,value:JsonObject){
        check(file.parentFile?.isDirectory==true||file.parentFile?.mkdirs()==true){"QA_REPORT_DIRECTORY"}
        val atomic=AtomicFile(file);val out=atomic.startWrite()
        try {out.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)}
        catch(error:Throwable){atomic.failWrite(out);throw error}
    }

    @Test fun realPcContinuationCanBeStoppedWithoutLosingAcknowledgedText()=runBlocking(Dispatchers.Default){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("actualPcCancelValidationApproved=true required",args.getString("actualPcCancelValidationApproved")=="true")
        val logicalTarget=args.getString("actualPcCancelLogicalTarget")=="true"
        val expected=checkNotNull(args.getString("actualPcCancelExpectedVersion")?.toLongOrNull()){"QA_EXPECTED_VERSION_REQUIRED"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        val version=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
        check(version==expected){"QA_INSTALLED_VERSION_MISMATCH"}
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()){"QA_OWNER_ALREADY_ACTIVE"}
        val settings=PcComputeSettings.get(context)
        val revision=settings.authorizationRevision
        val original=checkNotNull(settings.snapshot()){"QA_PAIRING_REQUIRED"}
        val originalCloudChoice=settings.state.value.claudeText
        if(logicalTarget)check(!originalCloudChoice){"QA_LOGICAL_TARGET_REQUIRES_LOCAL_TEXT_SETTING"}
        fun authorityUnchanged(){
            val current=checkNotNull(settings.snapshot()){"QA_PAIRING_REMOVED"}
            check(settings.authorizationRevision==revision&&current.serverId==original.serverId&&
                current.token==original.token&&current.certificateSha256==original.certificateSha256&&
                settings.state.value.claudeText==originalCloudChoice){"QA_AUTHORITY_CHANGED"}
        }
        suspend fun health(owner:String?):JsonObject {
            authorityUnchanged()
            val value=withContext(Dispatchers.IO){PcTransport(context).json(original,"/v1/health",timeout=2_000,computeOwner=owner)}
            check(value["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&value["serverId"]?.jsonPrimitive?.content==original.serverId){"QA_HEALTH_IDENTITY"}
            check(value["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1){"QA_OWNER_PROTOCOL"}
            return value
        }
        fun idle(value:JsonObject)=value["compute_busy"]?.jsonPrimitive?.booleanOrNull==false&&
            value["cleanup_pending"]?.jsonPrimitive?.booleanOrNull==false
        val initial=health(null)
        check(idle(initial)&&(initial["llmResident"]==null||initial["llmResident"] is JsonNull)){"QA_PC_NOT_EMPTY_AND_IDLE"}

        val token=UUID.randomUUID().toString()
        val title="qa-actual-pc-cancel-"+token
        val owner="허구의 우주 정거장에서 화분을 기르는 이야기 400문단을 처음부터 끝까지 써줘. "+
            "각 문단은 70자 이상이며 서로 다른 사건을 담고, 문단 번호는 1부터 400까지 이어져야 해. "+
            "계획이나 요약으로 대신하지 말고 지금 본문을 시작해. 도구나 외부 자료는 사용하지 마."
        val report=File(checkNotNull(context.getExternalFilesDir(null)),if(logicalTarget)"diagnostics/actual-pc-cancel-logical-target-validation.json"else"diagnostics/actual-pc-cancel-validation.json")
        var project:ProjectRecord?=null;var room:ChatThreadRecord?=null;var workspace:ProjectWorkspace?=null
        var runner:ChatRunCoordinator?=null;var runId:String?=null;var originalUserId:String?=null
        var stage="creating_fixture";var validated=false;var cleanupVerified=false
        var caught:Throwable?=null;var cleanupFailure:Throwable?=null
        var beforeText="";var afterText="";var answerId:String?=null
        var beforeParts=emptyList<SavedPart>();var window:Window?=null
        var countAtStop=0;var cancelAck=false;var unloadAck=false;var finalIdle=false;var phoneStopVerified=false
        val interrupted=AtomicBoolean(false)
        val probe=AtomicReference<Probe?>()
        val observers=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=SystemClock.elapsedRealtime()
        fun publish(){writeAtomic(report,buildJsonObject {
            put("kind","actual_pc_coordinator_owned_cancel");put("fixtureToken",token);put("versionCode",version)
            put("stage",stage);put("passed",validated&&caught==null&&cleanupVerified&&cleanupFailure==null)
            put("actualModel",true);put("allowCloudText",false);put("toolsEnabled",false)
            put("targetMode",if(logicalTarget)"logical_paired_pc_profile"else"verified_phone_label")
            put("choiceOverrideUsedForCancellationIsolation",true)
            put("normalSendSelectorProven",false);put("cleanOnboardingProven",false)
            put("typedLoadCount",probe.get()?.typedLoadCount()?:0)
            put("typedLoadReceiptCount",probe.get()?.typedReceiptCount()?:0)
            put("allTypedLoadReceiptsMatched",probe.get()?.allTypedReceiptsMatched()?:false)
            put("projectId",project?.id);put("threadId",room?.id);put("runId",runId);put("userMessageId",originalUserId);put("ownerRequestSha256",sha(owner))
            put("inflightSignal","positive_parsed_SSE_progress_AND_owner_health_NOT_native_active_proof")
            put("inflightObserved",window!=null);put("inflightGenerationSerial",window?.serial);put("preparedChars",window?.preparedChars)
            put("acknowledgedCharsBeforeStop",beforeText.length);put("acknowledgedPartsBeforeStop",beforeParts.size)
            put("acknowledgedTextPreserved",beforeText.isNotEmpty()&&afterText.startsWith(beforeText))
            put("generationCallsAtStop",countAtStop);put("generationCallsFinal",probe.get()?.count())
            put("backendCancelledSummary",probe.get()?.cancellation?.reason==GenerationStopReason.CANCELLED)
            val outcome=probe.get()?.let{p->p.cancellation?.requestId?.let{p.cancelOutcome(it)}}
            put("cancelHandshakeStage",outcome?.reply?.stage?.name?:"UNKNOWN")
            put("cancelHttpStatus",outcome?.reply?.httpStatus?.let{JsonPrimitive(it)}?:JsonNull)
            put("cancelReplyAcknowledged",outcome?.reply?.acknowledged?:false)
            put("cancelReplyAccepted",outcome?.reply?.cancelled?.let{JsonPrimitive(it)}?:JsonNull)
            put("cancelReplyCleanupComplete",outcome?.reply?.cleanupComplete?.let{JsonPrimitive(it)}?:JsonNull)
            put("nativeDecoderActiveProven",false)
            put("cancelAckMatchedGeneration",cancelAck);put("ownerScopedUnloadAck",unloadAck);put("postStopIdleHealth",finalIdle)
            put("phoneStopAndAcknowledgedTextVerified",phoneStopVerified)
            put("ownerInterrupted",interrupted.get());put("cleanupVerified",cleanupVerified)
            put("elapsedMs",SystemClock.elapsedRealtime()-started)
            caught?.let{put("failureClass",it.javaClass.simpleName)}
            cleanupFailure?.let{put("cleanupFailureClass",it.javaClass.simpleName)}
        })}
        fun ownerQuiet(){check(!interrupted.get()&&!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"QA_OWNER_WORK_INTERRUPTED"}}

        try {
            ownerQuiet()
            val p=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,
                disabledTools=ToolRegistry.definitions.keys)).also{project=it;publish()}
            check(p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal")){"QA_PROJECT_SCOPE"}
            val r=store.createThread(p.id,title).also{room=it;publish()}
            val ws=app.workspaceFor(p.id).also{workspace=it}
            check(!ws.state.value.external){"QA_EXTERNAL_WORKSPACE_FORBIDDEN"}
            val choice=if(logicalTarget){
                authorityUnchanged()
                val captured=settings.captureTextAuthority()
                check(captured.revision==revision&&!captured.allowCloudText){"QA_LOGICAL_TARGET_AUTHORITY"}
                checkNotNull(RequestInferenceSelection.pairedText(PairedPcTextProfile.SELECTION_ID,revision,captured.allowCloudText,
                    captured.revision,settings.snapshot()!=null,false)){"QA_LOGICAL_TARGET_NOT_SELECTED"}
                    .let{it.copy(options=it.options.copy(contextSize=8192,maxOutputTokens=128,thinking=false))}
            }else{
            app.models.refresh()
            val model=checkNotNull(app.models.models.value.firstOrNull{it.id=="gemma-e2b"&&it.verified}
                ?:app.models.models.value.firstOrNull{it.verified&&!it.id.contains("mmproj")&&it.runtime in setOf("gguf","litertlm")}){"QA_VERIFIED_LABEL_REQUIRED"}
            // Request-local cap forces real prose continuation; no global model setting is changed.
            val choice=ChosenInference(model,InferenceOptions(contextSize=8192,maxOutputTokens=128,thinking=false),"Actual PC owned-cancellation QA")
                choice
            }
            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                inferenceChoiceOverride={choice},workerEngineFactory={request->
                    check(request.projectId==p.id&&request.threadId==r.id&&!request.allowCloudText&&!request.allowTools&&request.capturedPcRevision==revision){"QA_ENGINE_BINDING"}
                    Probe(AutomaticComputeEngine(context,ownerRunId=request.runId,
                        beforePhoneLoad={error("QA_PHONE_FALLBACK_FORBIDDEN")},capturedPcRevision=revision,allowCloudText=false),observers).also{probe.set(it)}
                },externalIntentLauncher={error("QA_EXTERNAL_INTENT_FORBIDDEN")})}.also{runner=it}
            ownerQuiet()
            val id=withContext(Dispatchers.Main){
                val message=store.addMessage(r.id,r.activeBranchId,"user",owner).also{originalUserId=it.id}
                val run=store.createRun(r.id,r.activeBranchId,message.id,choice.model.id,8192,false)
                val request=QueuedChatRequest(run.id,r.id,r.activeBranchId,p.id,owner,choice.model.id,false,emptyList(),message.id,
                    allowTools=false,mode="act",workspaceBinding=ws.bindingFingerprint(),capturedPcRevision=revision,allowCloudText=false)
                runId=run.id;publish()
                store.updateRunContext(run.id,request.toJson())
                coordinator.queueMutation.withLock{coordinator.queued.value=coordinator.queued.value+request};coordinator.drain()
                run.id
            }
            observers.launch {
                while(isActive){
                    if(app.runs.hasActiveRuns.value||app.runs.busy.value){
                        interrupted.set(true)
                        withContext(Dispatchers.Main){coordinator.stopRun(id,r.id)}
                        return@launch
                    }
                    delay(50)
                }
            }
            stage="waiting_for_durable_part_and_later_inflight_generation";publish()
            val job=withTimeout(600_000){
                while(true){
                    ownerQuiet();authorityUnchanged()
                    check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==version){"QA_VERSION_CHANGED"}
                    check(coordinator.pendingActions.value.none{it.runId==id}){"QA_UNEXPECTED_APPROVAL"}
                    val current=checkNotNull(store.run(id)){"QA_RUN_DISAPPEARED"}
                    check(current.status in setOf("queued","running")){"QA_INFLIGHT_NOT_REACHED_BEFORE_TERMINAL_STATE"}
                    val message=store.assistantForRun(id)
                    val candidate=probe.get()?.window()
                    if(message!=null&&candidate!=null&&candidate.serial>=2){
                        val text=store.fullMessageText(message.id)
                        val parts=store.segmentsForMessage(message.id)
                        if(text.isNotEmpty()&&parts.isNotEmpty()&&parts.all{it.status=="completed"}){
                            val saved=parts.map{SavedPart(it.id,it.partNumber,store.segmentText(it.id))}
                            check(saved.joinToString(""){it.text}==text){"QA_ACK_READBACK_MISMATCH"}
                            val online=health(id)
                            val resident=online["llmResident"]?.takeUnless{it is JsonNull}?.jsonObject
                            if(online["compute_busy"]?.jsonPrimitive?.booleanOrNull==true&&
                                online["cleanup_pending"]?.jsonPrimitive?.booleanOrNull==false&&resident?.get("ownerMatches")?.jsonPrimitive?.booleanOrNull==true){
                                val stopped=withContext(Dispatchers.Main){
                                    ownerQuiet();authorityUnchanged()
                                    val live=probe.get()?.window()
                                    if(live?.serial!=candidate.serial||!coordinator.isRunLive(id))null else {
                                        val ownJob=checkNotNull(coordinator.runningJobForThread(r.id)){"QA_OWN_JOB_MISSING"}
                                        beforeText=text;beforeParts=saved;answerId=message.id;window=live
                                        countAtStop=checkNotNull(probe.get()).count();stage="stopping_own_observed_pc_generation"
                                        coordinator.stopRun(id,r.id)
                                        ownJob
                                    }
                                }
                                if(stopped!=null){publish();return@withTimeout stopped}
                            }
                        }
                    }
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE") error("QA_UNREACHABLE")
            }
            stage="verifying_owned_stop_and_storage";publish()
            withTimeout(60_000){job.join();while(coordinator.executionForRun(id)!=null)delay(25)}
            ownerQuiet();authorityUnchanged()
            check(store.run(id)?.status=="cancelled"){"QA_RUN_NOT_CANCELLED"}
            val captured=QueuedChatRequest.fromJson(checkNotNull(store.run(id)).contextJson)
            check(captured.runId==id&&captured.threadId==r.id&&captured.projectId==p.id&&captured.text==owner&&
                !captured.allowTools&&!captured.allowCloudText&&captured.capturedPcRevision==revision){"QA_ORIGINAL_REQUEST_CHANGED"}
            val originalMessage=checkNotNull(app.repository.getMessage(checkNotNull(originalUserId))){"QA_USER_MESSAGE_LOST"}
            check(originalMessage.role=="user"&&originalMessage.threadId==r.id&&originalMessage.text==owner&&
                captured.userMessageId==originalMessage.id){"QA_USER_MESSAGE_CHANGED"}
            check(captured.previousSteps.isEmpty()){ "QA_UNEXPECTED_TOOL_EFFECT" }
            val a=checkNotNull(answerId)
            afterText=store.fullMessageText(a)
            check(afterText.startsWith(beforeText)&&beforeParts.isNotEmpty()){"QA_ACK_BODY_LOST"}
            for(part in beforeParts){
                check(store.segmentsForMessage(a).any{it.id==part.id&&it.partNumber==part.number}){"QA_ACK_PART_LOST"}
                check(store.segmentText(part.id)==part.text){"QA_ACK_PART_CHANGED"}
            }
            val ui=checkNotNull(coordinator.answerStates.value[a]){"QA_CANCELLED_UI_MISSING"}
            check(ui.phase==UiAnswerPhase.CANCELLED&&!ui.canContinue){"QA_UI_NOT_TERMINALLY_CANCELLED"}
            check(ui.parts.joinToString(""){it.text}==afterText){"QA_CANCELLED_UI_STORAGE_MISMATCH"}
            phoneStopVerified=true
            val recorded=checkNotNull(probe.get())
            val stoppedSummary=checkNotNull(recorded.cancellation){"QA_BACKEND_CANCELLATION_NOT_PROVEN"}
            check(UUID.fromString(stoppedSummary.requestId).toString()==stoppedSummary.requestId){"QA_BACKEND_REQUEST_ID_INVALID"}
            withTimeoutOrNull(5_000){
                while(true){
                    val receipts=recorded.cleanup()
                    val outcome=recorded.cancelOutcome(stoppedSummary.requestId)
                    cancelAck=outcome?.let{it.key.requestId==stoppedSummary.requestId&&it.reply.acknowledged&&
                        it.reply.cancelled==true&&it.reply.cleanupComplete==true&&it.confirmed}==true
                    unloadAck=receipts.any{it.operation=="unload"&&it.acknowledged&&it.cleanupComplete==true}
                    if(cancelAck)break
                    delay(25)
                }
            }
            repeat(2){
                ownerQuiet();authorityUnchanged()
                val post=health(id)
                check(idle(post)&&(post["llmResident"]==null||post["llmResident"] is JsonNull)){"QA_PC_CLEANUP_UNCONFIRMED"}
                check(recorded.count()==countAtStop){"QA_GENERATION_AFTER_STOP"}
                check(coordinator.queued.value.none{it.runId==id}&&!coordinator.isRunLive(id)){"QA_OWN_WORK_STILL_PENDING"}
                check(store.fullMessageText(a)==afterText){"QA_TEXT_CHANGED_AFTER_STOP"}
                delay(250)
            }
            finalIdle=true
            // A HOST CANCELLED summary can also be produced just after upstream DONE.
            // Unload proves owner cleanup, but cannot establish that the chat operation
            // was still active when stop reached the PC. Do not count that race as PASS.
            check(cancelAck){"QA_MATCHED_INFLIGHT_CANCEL_ACK_NOT_OBSERVED"}
            if(logicalTarget){
                check(captured.modelId==PairedPcTextProfile.SELECTION_ID){"QA_LOGICAL_TARGET_CAPTURE_CHANGED"}
                check(recorded.typedLoadCount()>0&&recorded.allTypedReceiptsMatched()){"QA_TYPED_LOAD_NOT_VERIFIED"}
            }
            validated=true;stage="owned_cancel_verified";publish()
        }catch(error:Throwable){caught=error}
        finally {
            withContext(NonCancellable){
                try {
                    val c=runner;val r=room
                    if(c!=null&&r!=null){
                        val ownJob=withContext(Dispatchers.Main){c.runningJobForThread(r.id).also{c.cancelThread(r.id)}}
                        withTimeout(60_000){ownJob?.join();while(c.hasActiveRuns.value)delay(25)}
                        c.scope.cancel()
                    }
                    project?.let{p->
                        val fresh=checkNotNull(store.project(p.id)){"QA_CLEANUP_PROJECT_MISSING"}
                        check(p.title==title&&p.workspaceKey==p.id&&fresh.title==title&&fresh.workspaceKey==p.id){"QA_CLEANUP_PROJECT_BINDING"}
                        check(store.threads.first().filter{it.projectId==p.id}.all{it.id==room?.id}){"QA_CLEANUP_UNEXPECTED_THREAD"}
                        room?.let{r->
                            val freshRoom=checkNotNull(store.thread(r.id)){"QA_CLEANUP_THREAD_MISSING"}
                            check(freshRoom.projectId==p.id&&freshRoom.title==title&&freshRoom.activeBranchId==r.activeBranchId){"QA_CLEANUP_THREAD_BINDING"}
                            check(store.branches(r.id).all{it.id==r.activeBranchId}){"QA_CLEANUP_UNEXPECTED_BRANCH"}
                            check(store.runsForThread(r.id).all{it.id==runId}){"QA_CLEANUP_UNEXPECTED_RUN"}
                            check(store.messages(r.id).first().all{message->
                                message.id==originalUserId&&message.role=="user"&&message.text==owner||
                                    message.role=="assistant"&&runId!=null&&message.runId==runId
                            }){"QA_CLEANUP_UNEXPECTED_MESSAGE"}
                        }
                        runId?.let{id->
                            val actual=checkNotNull(store.run(id)){"QA_CLEANUP_RUN_MISSING"}
                            check(actual.threadId==room?.id&&actual.projectId==p.id&&actual.status !in setOf("queued","running","waiting_approval")){"QA_CLEANUP_RUN_STILL_ACTIVE"}
                        }
                        store.deleteProject(p.id,deleteDerivedMemory=true)
                        check(store.project(p.id)==null&&room?.let{store.thread(it.id)==null}!=false){"QA_CLEANUP_SCOPE_REMAINS"}
                        workspace?.discard()
                    }
                    cleanupVerified=true
                }catch(error:Throwable){cleanupFailure=error}
                observers.cancel()
                publish()
            }
        }
        caught?.let{throw it}
        cleanupFailure?.let{throw it}
        assertTrue("QA_NOT_VERIFIED",validated&&cleanupVerified)
    }
}
