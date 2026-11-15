package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import com.user.localagent.workspace.ProjectWorkspace
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One root-only actual-PC canary. Normal send/selector; no model output/plan/choice injection.
 * Actual PendingAction is accepted through guarded fixture consent, NOT a human/Compose UI click.
 * The private AtomicFile report is also a recovery locator; no owner note/content is read. */
@RunWith(AndroidJUnit4::class)
class NotePatchActualPcCanary {
    private class Probe(private val real:AutomaticComputeEngine,private val request:QueuedChatRequest):ComputeExecutionEngine by real,PairedPcTextLoader {
        private val lock=Any()
        private var loads=0;private var receipts=0;private var generations=0;private var allPc=true
        override suspend fun load(path:String,options:InferenceOptions){error("QA_UNEXPECTED_FILE_LOADER")}
        override suspend fun loadPairedPcText(target:PairedPcText,options:InferenceOptions):PairedPcLoadReceipt {
            check(request.modelId==PairedPcTextProfile.SELECTION_ID&&!request.allowCloudText&&request.capturedPcRevision==target.authorizationRevision){"QA_TYPED_AUTHORITY"}
            synchronized(lock){loads++}
            val receipt=real.loadPairedPcText(target,options)
            check(receipt.matches(target,options.contextSize)){"QA_TYPED_RECEIPT"}
            synchronized(lock){receipts++};return receipt
        }
        private fun observe(action:()->Flow<GenerationEvent>):Flow<GenerationEvent> = flow {
            synchronized(lock){generations++;allPc=allPc&&real.executionLocation=="pc"}
            emitAll(action()) // Actual output is never rewritten, scripted, replayed or repaired here.
        }
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=
            observe{real.generateChatEvents(userText,systemInstruction,history)}
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=
            observe{real.generateStructuredEvents(userText,systemInstruction,schema,history)}
        fun facts():JsonObject=synchronized(lock){buildJsonObject{
            put("typedLoadCount",loads);put("matchingReceiptCount",receipts);put("generationCount",generations)
            put("allTypedReceiptsMatched",loads>0&&loads==receipts);put("allGenerationsPc",generations>0&&allPc)
        }}
    }
    private fun reportableTerminalErrorCode(raw:String?):String?=when(raw){
        null->null
        "awaiting_owner_input",
        "execution_not_verified",
        "invalid_clarification",
        "owner_requested_cancel",
        "plan_rejected_canonical_validation_failed",
        "plan_rejected_duplicate_key",
        "plan_rejected_invalid_action",
        "plan_rejected_invalid_array_type",
        "plan_rejected_invalid_field_length",
        "plan_rejected_invalid_field_type",
        "plan_rejected_invalid_host_tools",
        "plan_rejected_invalid_json",
        "plan_rejected_invalid_json_length",
        "plan_rejected_invalid_privacy_type",
        "plan_rejected_invalid_public_tools",
        "plan_rejected_invalid_record_tools",
        "plan_rejected_invalid_source_kind",
        "plan_rejected_invalid_target_type",
        "plan_rejected_missing_action",
        "plan_rejected_missing_connection_target",
        "plan_rejected_missing_content_read",
        "plan_rejected_missing_goal",
        "plan_rejected_missing_public_query",
        "plan_rejected_missing_question",
        "plan_rejected_missing_record_source",
        "plan_rejected_missing_required_tools",
        "plan_rejected_missing_target_type",
        "plan_rejected_mixed_private_public",
        "plan_rejected_public_private_conflict",
        "plan_rejected_shopping_target_mismatch",
        "plan_rejected_too_many_tools",
        "plan_rejected_unavailable_tool",
        "plan_rejected_unexpected_tools",
        "plan_rejected_unknown_field",
        "plan_rejected_unknown_tool",
        "readback_obligation_unmet",
        "reasoning_budget_exhausted",
        "request_failed_before_finalization",
        "stuck_repeated_action"->raw
        else->"UNMAPPED"
    }
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun atomic(file:File,value:JsonObject){
        check(file.parentFile?.isDirectory==true||file.parentFile?.mkdirs()==true){"QA_REPORT_DIRECTORY"}
        val target=AtomicFile(file);val stream=target.startWrite()
        try{stream.write(value.toString().toByteArray(Charsets.UTF_8));target.finishWrite(stream)}catch(error:Throwable){target.failWrite(stream);throw error}
    }

    @Test fun normalRequestEditsOnlyTheObservedNoteAndReadsTheSavedVersion()=runBlocking(Dispatchers.Default){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("notePatchActualPcApproved=true required",args.getString("notePatchActualPcApproved")=="true")
        val directNextAction=args.getString("notePatchDirectNextAction")?.let{check(it in setOf("true","false")){"QA_INVALID_DIRECT_VARIANT"};it=="true"}?:false
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=checkNotNull(args.getString("notePatchActualPcExpectedVersion")?.toLongOrNull()){"QA_EXPECTED_VERSION"}
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expected){"QA_INSTALLED_VERSION"}
        val app=(base.applicationContext as LocalAgentApplication).container;val store=app.repository.chat
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()&&!app.imageStudio.state.value.running&&
            !RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get()){"QA_OWNER_ALREADY_ACTIVE"}
        val allowed=setOf("search_notes","read_note","patch_note")
        check(allowed.all{it in ToolRegistry.definitions}){"QA_NOTE_TOOLS_NOT_REGISTERED"}
        val settings=PcComputeSettings.get(base);val connection=checkNotNull(settings.snapshot()){"QA_PAIRING_REQUIRED"}
        val revision=settings.authorizationRevision;check(!settings.state.value.claudeText){"QA_LOCAL_ONLY_REQUIRED"}
        val originalProject=app.runs.selectedProjectId.value
        val prefix="qa-note-pc-"+UUID.randomUUID();val collection="qa-note-"+UUID.randomUUID();val prefs="$prefix-chat-preferences"
        val context=object:ContextWrapper(base){override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=
            if(name=="chat-preferences")base.getSharedPreferences(prefs,mode)else super.getSharedPreferences(name,mode)}
        val privatePreferences=context.getSharedPreferences("chat-preferences",Context.MODE_PRIVATE)
        check(privatePreferences.all.isEmpty()){"QA_PRIVATE_PREFS_NOT_FRESH"}
        var directPreferenceObserved=false;var privatePreferencesRestored=false
        val oldText="목요일 14시";val newText="금요일 16시"
        val before="인수 시각: $oldText\r\n담당: 운영팀\r\n두  공백 😀 e\u0301\r\n"+"보존 구간  원문 😀 e\u0301\r\n".repeat(75)+"마지막 줄은 유지"
        val after=before.replaceFirst(oldText,newText)
        check(before.length in 1201..2200&&before.indexOf(oldText)==before.lastIndexOf(oldText)){"QA_FIXTURE_SHAPE"}
        val targetTitle="$prefix-target";val keepTitle="$prefix-keep"
        val owner="제목 '$targetTitle' 메모를 찾아 현재 원문을 읽고, 인수 시각의 '$oldText'만 '$newText'로 고쳐줘. "+
            "제목과 다른 줄, 줄바꿈, 연속 공백과 이모지는 그대로 유지해. 다른 메모는 바꾸지 마. 저장된 같은 메모를 다시 읽어 수정 결과를 확인하고 알려줘."
        val report=File(checkNotNull(base.getExternalFilesDir(null)),if(directNextAction)"diagnostics/note-patch-actual-pc-canary-direct-next-action.json"else"diagnostics/note-patch-actual-pc-canary.json")
        val started=SystemClock.elapsedRealtime();var stage="before_fixture";var lastCheck="QA_NOT_STARTED"
        var firstFailureCheckCode:String?=null;var terminalErrorCode:String?=null;var terminalErrorCodeCaptured=false
        var project:ProjectRecord?=null;var room:ChatThreadRecord?=null;var repository:LocalRepository?=null
        var workspace:ProjectWorkspace?=null;var workspaceBinding:String?=null;var privateRoot:File?=null
        var seed:NoteRecord?=null;var keep:NoteRecord?=null;var runner:ChatRunCoordinator?=null;var ownRun:String?=null
        var status:String?=null;val selectedRequest=AtomicReference<QueuedChatRequest?>();var steps=emptyList<HarnessStep>()
        val probe=AtomicReference<Probe?>();var approvalCount=0;var matchedPreview=false;var sameTargetSearch=false
        var freshRead=false;var stateObserved=false;var exactEffect=false;var keepUnchanged=false;var broadPairSelected=false;var wholeAfterProven=false
        var validated=false;var cleanup=false;var finalIdle=false;var ownerInterrupted=false
        var failure:Throwable?=null;var cleanupFailure:Throwable?=null
        fun verify(code:String,condition:Boolean){lastCheck=code;if(!condition&&firstFailureCheckCode==null)firstFailureCheckCode=code;check(condition){code}}
        fun authority(){val current=settings.snapshot();verify("QA_PAIRING_UNCHANGED",current!=null&&settings.authorizationRevision==revision&&
            current.serverId==connection.serverId&&current.token==connection.token&&current.certificateSha256==connection.certificateSha256&&!settings.state.value.claudeText)}
        fun ownerQuiet(){if(app.runs.hasActiveRuns.value||app.runs.busy.value||app.imageStudio.state.value.running){ownerInterrupted=true;error("QA_OWNER_INTERRUPTED")}}
        suspend fun health(ownerId:String?):JsonObject {authority();return withContext(Dispatchers.IO){PcTransport(base).json(connection,"/v1/health",timeout=2_000,computeOwner=ownerId)}.also{
            verify("QA_HEALTH_IDENTITY",it["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&it["serverId"]?.jsonPrimitive?.content==connection.serverId)}}
        fun idle(value:JsonObject)=value["compute_busy"]?.jsonPrimitive?.booleanOrNull==false&&value["cleanup_pending"]?.jsonPrimitive?.booleanOrNull==false&&
            (value["llmResident"]==null||value["llmResident"] is JsonNull)
        fun publish(){atomic(report,buildJsonObject{
            put("requestLoopVariant",if(directNextAction)"DIRECT_NEXT_ACTION_OPT_IN"else"ORIGINAL_SEMANTIC")
            put("directPreferenceObserved",directPreferenceObserved);put("privatePreferencesRestored",privatePreferencesRestored)
            put("schema",1);put("kind","actual_pc_normal_send_note_patch_canary");put("versionCode",expected);put("stage",stage);put("lastCheckCode",lastCheck)
            put("firstFailureCheckCode",firstFailureCheckCode);put("terminalErrorCodeCaptured",terminalErrorCodeCaptured);put("terminalErrorCode",terminalErrorCode)
            put("passed",validated&&cleanup&&failure==null&&cleanupFailure==null);put("runStatus",status);put("cleanupVerified",cleanup);put("postRunIdleVerified",finalIdle)
            put("normalSendSelectorUsed",true);put("choiceOverrideUsed",false);put("emptyPhoneModelListProven",false)
            put("approvalMethod","actual_PendingAction_guarded_fixture_consent");put("humanOrComposeApprovalProven",false)
            put("modelOutputOrPlanInjected",false);put("allowCloudText",false);put("ownerInterrupted",ownerInterrupted)
            put("approvalCount",approvalCount);put("exactApprovalPreviewVerified",matchedPreview);put("sameTargetSearchVerified",sameTargetSearch);put("freshSameTargetRead",freshRead)
            put("qaOracleReadsAreNotModelFeedback",true)
            put("stateObservationPerformed",stateObserved)
            put("exactScopedEffect",if(stateObserved)JsonPrimitive(exactEffect)else JsonNull);put("otherQaNoteUnchanged",if(stateObserved)JsonPrimitive(keepUnchanged)else JsonNull)
            put("wholeReadPairSelected",broadPairSelected);put("wholeModelAfterReadProven",wholeAfterProven)
            put("locator",buildJsonObject{put("prefix",prefix);put("projectId",project?.id);put("workspaceKey",project?.workspaceKey);put("workspaceBinding",workspaceBinding)
                put("threadId",room?.id);put("branchId",room?.activeBranchId);put("runId",ownRun);put("seedId",seed?.id);put("keepId",keep?.id)
                put("collection",collection);put("seedBeforeUpdatedAt",seed?.updatedAt);put("seedBeforeSha256",sha(before));put("seedAfterSha256",sha(after))
                put("keepSha256",keep?.let{sha(it.body)});put("ownerRequestSha256",sha(owner));put("capturedPcRevision",revision)
                put("modelSelection",PairedPcTextProfile.SELECTION_ID);put("useOwnerMemory",false);put("learnOwnerFacts",false)})
            probe.get()?.facts()?.forEach{(k,v)->put(k,v)}
            put("verifiedTools",JsonArray(steps.filter{it.evidence.state==VerificationState.VERIFIED}.map{JsonPrimitive(it.tool)}))
            put("elapsedMs",SystemClock.elapsedRealtime()-started)
            failure?.let{put("failureClass",it.javaClass.simpleName)};cleanupFailure?.let{put("cleanupFailureClass",it.javaClass.simpleName)}
        })}
        publish()
        try{
            verify("QA_INITIAL_PC_IDLE",idle(health(null)));ownerQuiet()
            val p=store.createProject(prefix,ProjectSettings(modelId=PairedPcTextProfile.SELECTION_ID,contextSize=8192,
                useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed)).also{project=it;publish()}
            verify("QA_PROJECT_BINDING",p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
            val r=store.createThread(p.id,prefix).also{room=it;publish()}
            val scoped=app.repository.forThread(r.id).also{repository=it};val ws=app.workspaceFor(p.id).also{workspace=it}
            workspaceBinding=ws.bindingFingerprint();verify("QA_PRIVATE_EMPTY_WORKSPACE",!ws.state.value.external&&ws.list().isEmpty())
            privateRoot=File(base.filesDir,"projects/"+p.workspaceKey).canonicalFile
            verify("QA_PRIVATE_ROOT",privateRoot?.parentFile==File(base.filesDir,"projects").canonicalFile)
            seed=scoped.saveNote(targetTitle,before,collection);publish()
            keep=scoped.saveNote(keepTitle,"보존 메모\r\n인수 시각: $oldText\r\n두  공백 😀 e\u0301",collection);publish()
            verify("QA_EXACT_INITIAL_NOTES",scoped.notes.first().map{it.id}.toSet()==setOf(checkNotNull(seed).id,checkNotNull(keep).id))
            val initialAccess=store.accessScope(r.id)
            if(directNextAction)verify("QA_DIRECT_PREF_COMMIT",privatePreferences.edit().putBoolean("direct-next-action",true).commit())
            verify("QA_DIRECT_PREF_BEFORE_CAPTURE",privatePreferences.getBoolean("direct-next-action",false)==directNextAction)
            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                workerEngineFactory={request->
                    check(context.getSharedPreferences("chat-preferences",Context.MODE_PRIVATE).getBoolean("direct-next-action",false)==directNextAction){"QA_DIRECT_PREF_CAPTURE_CHANGED"}
                    directPreferenceObserved=true
                    check(request.threadId==r.id&&request.projectId==p.id&&request.branchId==r.activeBranchId&&request.modelId==PairedPcTextProfile.SELECTION_ID&&
                        request.capturedPcRevision==revision&&!request.allowCloudText&&request.allowTools){"QA_REQUEST_BINDING"}
                    check(selectedRequest.compareAndSet(null,request)){"QA_ADDITIONAL_REQUEST"};ownRun=request.runId
                    Probe(AutomaticComputeEngine(context,ownerRunId=request.runId,beforePhoneLoad={error("QA_PHONE_FALLBACK")},
                        capturedPcRevision=request.capturedPcRevision,allowCloudText=false),request).also{probe.set(it)}
                },externalIntentLauncher={error("QA_EXTERNAL_INTENT")})}.also{runner=it}
            withContext(Dispatchers.Main){coordinator.selectThread(r.id)}
            withTimeout(15_000){while(!coordinator.selectionReady.value)delay(50)}
            verify("QA_SELECTED_PROFILE",coordinator.preferredModelId.value==PairedPcTextProfile.SELECTION_ID)
            stage="running_actual_request";publish()
            verify("QA_NORMAL_SEND_ACCEPTED",withContext(Dispatchers.Main){coordinator.send(owner,r.id)})
            val completed=withTimeout(600_000){
                var terminal:ChatRunRecord?=null;var nextPublish=0L
                while(terminal==null){
                    ownerQuiet();authority();verify("QA_VERSION_UNCHANGED",base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expected)
                    verify("QA_SCOPE_UNCHANGED",store.accessScope(r.id)==initialAccess)
                    val runs=store.runsForThread(r.id);verify("QA_SINGLE_OWN_RUN",runs.size<=1)
                    val run=runs.singleOrNull();if(run!=null){ownRun=run.id;status=run.status
                        verify("QA_NO_OTHER_QUEUED_RUN",store.queuedRuns().all{it.id==run.id})
                        coordinator.pendingActions.value.firstOrNull{it.runId==run.id&&!it.decision.isCompleted}?.let{action->
                            stage="checking_actual_patch_approval";publish()
                            verify("QA_APPROVAL_EXACT_SCOPE",action.threadId==r.id&&action.projectId==p.id&&action.recipientChoices.isEmpty()&&action.call.name=="patch_note"&&approvalCount==0)
                            val args=action.call.arguments;fun text(key:String)=(args[key] as? JsonPrimitive)?.takeIf{it.isString}?.content?:error("QA_PATCH_FIELD_TYPE")
                            val original=checkNotNull(seed);verify("QA_APPROVAL_CURRENT_SOURCE",scoped.getNote(original.id)==original)
                            val prior=QueuedChatRequest.fromJson(checkNotNull(store.run(run.id)).contextJson).previousSteps
                            val priorRead=prior.withIndex().lastOrNull{it.value.tool=="read_note"&&it.value.evidence.state==VerificationState.VERIFIED&&
                                it.value.arguments["id"]?.jsonPrimitive?.content==original.id&&it.value.arguments["scope"]?.jsonPrimitive?.content==collection&&
                                it.value.arguments["expectedUpdatedAt"]?.jsonPrimitive?.content==original.updatedAt.toString()}
                            val search=prior.withIndex().lastOrNull{it.value.tool=="search_notes"&&it.value.evidence.state==VerificationState.VERIFIED&&it.index<(priorRead?.index?:-1)}
                            val searchArgs=checkNotNull(search){"QA_SEARCH_BEFORE_ORIGINAL_READ"}.value.arguments
                            sameTargetSearch=scoped.searchUserNotes(searchArgs.getValue("query").jsonPrimitive.content,
                                searchArgs["scope"]?.jsonPrimitive?.content.orEmpty(),(searchArgs["limit"]?.jsonPrimitive?.intOrNull?:5).coerceIn(1,5)).any{it==original}
                            verify("QA_SEARCH_RETURNED_SAME_TARGET",sameTargetSearch)
                            verify("QA_APPROVAL_EXACT_TARGET",text("id")==original.id&&text("scope")==collection&&text("expectedUpdatedAt")==original.updatedAt.toString())
                            val old=text("oldText");val replacement=text("newText");val at=before.indexOf(old)
                            verify("QA_APPROVAL_EXACT_EFFECT",old.isNotEmpty()&&old.length<=900&&replacement.length<=2000&&at>=0&&before.indexOf(old,at+1)==-1&&
                                before.substring(0,at)+replacement+before.substring(at+old.length)==after)
                            val preview=checkNotNull(runCatching{Json.parseToJsonElement(checkNotNull(action.reviewText)).jsonObject}.getOrNull()){"QA_PREVIEW_JSON"}
                            val diff=checkNotNull(preview["diff"] as? JsonObject){"QA_PREVIEW_DIFF"}
                            verify("QA_APPROVAL_REAL_PREVIEW",preview["title"]?.jsonPrimitive?.content==original.title&&preview["collection"]?.jsonPrimitive?.content==collection&&
                                diff["id"]?.jsonPrimitive?.content==original.id&&diff["scope"]?.jsonPrimitive?.content==collection&&
                                diff["expectedUpdatedAt"]?.jsonPrimitive?.content==original.updatedAt.toString()&&diff["oldText"]?.jsonPrimitive?.content==old&&
                                diff["newText"]?.jsonPrimitive?.content==replacement&&diff["beforeSha256"]?.jsonPrimitive?.content==sha(before)&&diff["afterSha256"]?.jsonPrimitive?.content==sha(after))
                            matchedPreview=true;action.remember.value=false
                            verify("QA_APPROVAL_ACCEPTED",action.decision.complete(true));approvalCount++;stage="running_actual_request";publish()
                        }
                        if(run.status !in setOf("queued","running","waiting_approval")&&coordinator.executionForRun(run.id)==null)terminal=run
                    }
                    if(SystemClock.elapsedRealtime()>=nextPublish){publish();nextPublish=SystemClock.elapsedRealtime()+5_000}
                    if(terminal==null)delay(50)
                }
                checkNotNull(terminal)
            }
            status=completed.status
            terminalErrorCode=reportableTerminalErrorCode(completed.errorCode);terminalErrorCodeCaptured=true
            val captured=checkNotNull(selectedRequest.get());val request=QueuedChatRequest.fromJson(completed.contextJson);steps=request.previousSteps
            stage="terminal_observed";publish()
            verify("QA_RUN_COMPLETED","completed"==completed.status)
            verify("QA_ORIGINAL_REQUEST_PRESERVED",request.runId==captured.runId&&request.threadId==r.id&&request.projectId==p.id&&request.branchId==r.activeBranchId&&
                request.text==owner&&request.allowTools&&!request.allowCloudText&&request.capturedPcRevision==revision&&request.modelId==PairedPcTextProfile.SELECTION_ID)
            val facts=checkNotNull(probe.get()).facts();verify("QA_ACTUAL_PC_AND_RECEIPTS",facts["allGenerationsPc"]?.jsonPrimitive?.boolean==true&&facts["allTypedReceiptsMatched"]?.jsonPrimitive?.boolean==true)
            verify("QA_NO_UNKNOWN_EFFECTS",steps.all{it.tool in allowed}&&steps.none{it.evidence.state in setOf(VerificationState.PREPARED,VerificationState.UNVERIFIED)})
            val verified=steps.withIndex().filter{it.value.evidence.state==VerificationState.VERIFIED&&!it.value.evidence.effectNotApplied}
            val patches=verified.filter{it.value.tool=="patch_note"}
            verify("QA_ONE_VERIFIED_PATCH",patches.size==1)
            val patch=patches.single();val original=checkNotNull(seed)
            verify("QA_SEARCH_THEN_ORIGINAL_READ",sameTargetSearch&&verified.any{read->read.index<patch.index&&read.value.tool=="read_note"&&
                read.value.arguments["id"]?.jsonPrimitive?.content==original.id&&read.value.arguments["scope"]?.jsonPrimitive?.content==collection&&read.value.arguments["expectedUpdatedAt"]?.jsonPrimitive?.content==original.updatedAt.toString()&&
                verified.any{it.index<read.index&&it.value.tool=="search_notes"}})
            verify("QA_SINGLE_PATCH_APPROVAL",approvalCount==1&&matchedPreview&&patch.value.arguments["id"]?.jsonPrimitive?.content==original.id)
            val current=checkNotNull(scoped.getNote(original.id));exactEffect=current.title==original.title&&current.collection==collection&&current.body==after&&current.updatedAt>original.updatedAt
            keepUnchanged=scoped.getNote(checkNotNull(keep).id)==keep;stateObserved=true
            verify("QA_EXACT_STATE_AND_OTHER_NOTE",exactEffect&&keepUnchanged&&scoped.notes.first().map{it.id}.toSet()==setOf(original.id,checkNotNull(keep).id))
            freshRead=verified.any{it.index>patch.index&&it.value.tool=="read_note"&&it.value.arguments["id"]?.jsonPrimitive?.content==current.id&&
                it.value.arguments["scope"]?.jsonPrimitive?.content==collection&&it.value.arguments["expectedUpdatedAt"]?.jsonPrimitive?.content==current.updatedAt.toString()}
            verify("QA_FRESH_SAME_NOTE_VERSION_READ",freshRead)
            val ledger=request.readbackObligations
            broadPairSelected=ledger?.selected?.contains(RequiredReadbackObligations.Pair.NOTE_PATCH)==true
            if(broadPairSelected){val proof=checkNotNull(ledger)
                wholeAfterProven=RequiredReadbackObligations.assess(proof,proof.context,steps,0,allowed,(WorkSegments.STEP_BUDGET-steps.size).coerceAtLeast(0),1,requestedFinish=true).readbackSatisfied
                verify("QA_DECLARED_WHOLE_READBACK_GATE",wholeAfterProven&&!proof.invalidHostProof&&proof.context.runId==completed.id&&proof.context.threadId==r.id&&proof.context.projectId==p.id&&proof.context.scopeKey==initialAccess.scopeKey&&proof.context.workspaceBinding==workspaceBinding)
            }
            val answer=checkNotNull(store.assistantForRun(completed.id));verify("QA_FINAL_ANSWER_SUPPORTED",store.fullMessageText(answer.id).contains(newText))
            verify("QA_NO_FILE_OR_REMINDER_EFFECTS",ws.list().isEmpty()&&scoped.listReminders().isEmpty())
            verify("QA_FINAL_PC_IDLE",idle(health(completed.id)));finalIdle=true;validated=true;stage="actual_note_patch_verified";publish()
        }catch(error:Throwable){failure=error;if(firstFailureCheckCode==null)firstFailureCheckCode="UNCLASSIFIED_FAILURE"}
        finally{withContext(NonCancellable){
            stage="cleanup";publish()
            try{
                runner?.let{own->val job=room?.let{r->withContext(Dispatchers.Main){own.runningJobForThread(r.id).also{own.cancelThread(r.id)}}}
                    withTimeout(60_000){job?.join();while(own.hasActiveRuns.value)delay(50)};own.scope.cancel()}
                project?.let{p->
                    val fresh=checkNotNull(store.project(p.id));verify("QA_CLEANUP_PROJECT",fresh.title==prefix&&fresh.workspaceKey==p.id&&fresh.settings==p.settings&&!fresh.archived)
                    val r=room;verify("QA_CLEANUP_ROSTER",store.threads.first().filter{it.projectId==p.id}.map{it.id}.toSet()==listOfNotNull(r?.id).toSet())
                    if(r!=null){verify("QA_CLEANUP_BRANCH_RUN",store.branches(r.id).all{it.id==r.activeBranchId}&&store.runsForThread(r.id).all{it.id==ownRun&&it.status !in setOf("queued","running","waiting_approval")})}
                    workspace?.let{verify("QA_CLEANUP_WORKSPACE",!it.state.value.external&&it.bindingFingerprint()==workspaceBinding&&it.list().isEmpty())}
                    repository?.let{scoped->
                        val notes=scoped.notes.first();val ids=listOfNotNull(seed?.id,keep?.id).toSet()
                        if(seed!=null&&keep!=null){
                            val initial=checkNotNull(seed);val observed=notes.singleOrNull{it.id==initial.id}
                            exactEffect=observed?.let{it.title==initial.title&&it.collection==collection&&it.body==after&&it.updatedAt>initial.updatedAt}==true
                            keepUnchanged=notes.singleOrNull{it.id==checkNotNull(keep).id}==keep;stateObserved=true
                        }
                        verify("QA_CLEANUP_EXACT_NOTES",notes.map{it.id}.toSet()==ids&&scoped.listReminders().isEmpty())
                        for(note in notes){val expectedNote=if(note.id==seed?.id)checkNotNull(seed)else checkNotNull(keep)
                            verify("QA_CLEANUP_NOTE_BINDING",note.title==expectedNote.title&&note.collection==collection&&
                                if(note.id==seed?.id)note.body in setOf(before,after)else note==expectedNote)
                            scoped.deleteNote(note.id,expectedUpdatedAt=note.updatedAt)
                        }
                        verify("QA_CLEANUP_NOTES_ABSENT",scoped.notes.first().isEmpty())
                    }
                    store.deleteProject(p.id,true);workspace?.discard();verify("QA_CLEANUP_ABSENT",store.project(p.id)==null&&r?.let{store.thread(it.id)==null}!=false&&privateRoot?.exists()!=true)
                }
                cleanup=true
            }catch(error:Throwable){cleanupFailure=error}
            try{
                if(app.runs.selectedProjectId.value==originalProject)app.selectProjectWorkspace(originalProject.takeIf{it.isNotBlank()})
                verify("QA_PRIVATE_PREF_RESTORE",privatePreferences.edit().clear().commit()&&privatePreferences.all.isEmpty())
                privatePreferencesRestored=true
                verify("QA_PREFS_CLEANUP",base.deleteSharedPreferences(prefs))
            }catch(error:Throwable){cleanup=false;if(cleanupFailure==null)cleanupFailure=error else cleanupFailure?.addSuppressed(error)}
            stage="finished";publish()
        }}
        failure?.let{throw it};cleanupFailure?.let{throw it};assertTrue(validated&&cleanup&&finalIdle)
    }
}
