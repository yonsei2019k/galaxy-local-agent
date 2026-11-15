package com.user.localagent

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.externalagent.ExternalAgentContract
import com.user.localagent.externalagent.ExternalAgentSettings
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Root-only ordinary SINGLETON app.runs.send, actual HTTP/provider/MCP and existing PendingAction.
 * No fake engine, coordinator override, executor interception, direct tool invocation or plan injection.
 * Guarded synthetic fixture consent is NOT a manual user/Compose approval.
 */
@RunWith(AndroidJUnit4::class)
class ExternalAgentNormalConversationCanary {
    @Test fun normalCloudConversationApprovesSavesSearchesAndReadsOnlyItsOwnSyntheticNote() {
        val session=Session.createApproved()
        runBlocking(Dispatchers.IO){session.execute()}
        session.publishFinal()
        session.assertPassed()
    }

    private class Session private constructor(
        private val instrumentation:Instrumentation,
        private val args:Bundle,
        private val context:Context,
        private val version:Long,
        private val case:String,
    ) {
        companion object {
            fun createApproved():Session {
                val instrumentation=InstrumentationRegistry.getInstrumentation()
                val args=InstrumentationRegistry.getArguments()
                assumeTrue(args.getString("externalAgentNormalCanaryApproved")=="true")
                val context=instrumentation.targetContext
                val version=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
                check(version==1088L&&args.getString("externalAgentNormalCanaryExpectedVersion")?.toLongOrNull()==version)
                val case=args.getString("externalAgentNormalCase")?:"save_readback"
                check(case in setOf("save_readback","decline","revoke"))
                return Session(instrumentation,args,context,version,case)
            }
        }
        private val app=(context.applicationContext as LocalAgentApplication).container
        private val coordinator=app.runs
        private val store=app.repository.chat
        private val preferences=context.getSharedPreferences("chat-preferences",Context.MODE_PRIVATE)
        private val external=ExternalAgentSettings.get(context)
        private val diagnostics=checkNotNull(context.getExternalFilesDir("diagnostics"))
        private val report=File(diagnostics,"external-agent-normal-conversation-$case-canary.json")
        private var originalThread=""
        private var originalProject=""
        private var originalDraft:ChatDraftRecord?=null
        private var originalDraftText=""
        private var originalPreferred=""
        private lateinit var originalOptions:InferenceOptions
        private var originalPreferences:Map<String,*> = emptyMap<String,Any?>()
        private lateinit var originalAuthority:ExternalAgentSettings.Authority
        private var originalGrants:Set<GrantRecord> = emptySet()
        private val prefix="qa-cloud-normal-"+UUID.randomUUID()
        private val targetTitle="검증 메모 "+UUID.randomUUID()
        private val targetBody="검증 코드: "+UUID.randomUUID()+"\n보존 문장: 구름과 별빛 사이 두  공백 유지"
        private val keepTitle="보존 메모 "+UUID.randomUUID()
        private val keepBody="변경 금지 "+UUID.randomUUID()+"\n두  공백과 원문 유지"
        private val collection="default"
        private val allowed=setOf("save_note","search_notes","read_note")
        private val projectSettings=ProjectSettings(modelId=ExternalAgentSettings.MODEL_ID,contextSize=8192,thinking=false,
            useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed)
        private val request="메모 모음 '$collection'에 제목 '$targetTitle'로 새 메모 하나를 저장해줘. 본문은 아래 본문 시작과 본문 끝 사이의 두 줄만 그대로 사용하고, "+
            "제목이나 구분 문구를 본문에 넣지 마. 저장 전 제목과 본문을 확인받아. 저장한 뒤 그 제목으로 검색하고, "+
            "단순 검색 결과만 보지 말고 같은 메모를 다시 열어 본문 전체가 정확히 같은지 확인해줘. 기존 메모는 수정하거나 삭제하지 마.\n"+
            "본문 시작\n$targetBody\n본문 끝"
        private var project:ProjectRecord?=null;private var thread:ChatThreadRecord?=null;private var workspace:ProjectWorkspace?=null
        private var keep:NoteRecord?=null;private var saved:NoteRecord?=null;private var ownRun:String?=null
        private var qaAuthority:ExternalAgentSettings.Authority?=null
        private var revokedAuthority:ExternalAgentSettings.Authority?=null
        private var stage="preparing";private var lastCheck="INITIAL";private var firstFailureStage:String?=null;private var firstFailureCheck:String?=null
        private var failure:Throwable?=null;private var workflowPassed=false;private var fixtureClean=false;private var selectionRestored=false
        private var preferencesRestored=false;private var authorityRestored=false
        private var approvalCount=0;private var exactApproval=false;private var keepUnchanged=false;private var exactSaved=false;private var wholeRead=false
        private var decisionAccepted=false;private var noSavedNote=false;private var grantsUnchanged=false
        private var boundaryCancelledWithoutAnswer=false
        private var closeReceipt=false;private var originalRunStatus:String?=null;private var terminalError:String?=null
        private var capturedFreshAuthority=false;private var selectedRequirements=false
        private var verifiedTools:List<String> = emptyList();private var actualPhoneCalls=0
        private var finalAnswerSha:String?=null
        private var terminalSteps:JsonArray=buildJsonArray{}
        private var terminalPlan:JsonElement=JsonNull
        private var terminalAnswerFile:String?=null
        private var terminalAnswerBytes=0
        private var terminalAnswerSha:String?=null
        private var began=0L
        suspend fun execute() {
            captureOriginalState()
            try {
                prepare()
                run()
                captureTerminalEvidence()
                verifyOutcome()
            }catch(error:Throwable){
                recordFailure(error)
            }finally{
                withContext(NonCancellable){cleanup()}
            }
            stage=if(failure==null&&workflowPassed)"completed"else"failed"
        }

        fun publishFinal(){publish(final=true)}

        fun assertPassed(){
            failure?.let{throw IllegalStateException("EXTERNAL_AGENT_NORMAL_CANARY_FAILED",it)}
            check(workflowPassed&&fixtureClean&&selectionRestored&&preferencesRestored&&authorityRestored)
        }

        private suspend fun waitFor(timeout:Long,condition:suspend()->Boolean){
            withTimeout(timeout){while(!condition())delay(50)}
        }
        private fun json():JsonObject=buildJsonObject {
            put("schema",1);put("kind","actual_normal_conversation_cloud_phone_canary");put("versionCode",version);put("case",case)
            put("stage",stage);put("lastCheck",lastCheck);put("firstFailureStage",firstFailureStage);put("firstFailureCheck",firstFailureCheck)
            put("prefix",prefix);put("projectId",project?.id);put("threadId",thread?.id);put("runId",ownRun)
            put("modelId",ExternalAgentSettings.MODEL_ID);put("sourceRunStatus",originalRunStatus);put("terminalErrorCode",terminalError)
            put("originalThreadId",originalThread);put("originalProjectId",originalProject);put("originalPreferredModel",originalPreferred)
            put("originalExternalEnabled",originalAuthority.enabled);put("originalExternalRevision",originalAuthority.revision)
            put("elapsedMs",SystemClock.elapsedRealtime()-began);put("approvalCount",approvalCount)
            put("fixtureConsent",true);put("manualHumanApprovalProven",false);put("exactApproval",exactApproval)
            put("fixtureDecisionAccepted",decisionAccepted);put("authorityRevokedWhileOwnApprovalPending",revokedAuthority!=null);put("noSavedNote",noSavedNote)
            put("rememberRequested",case=="revoke");put("permissionGrantsUnchanged",grantsUnchanged)
            put("boundaryCancelledWithoutAnswer",boundaryCancelledWithoutAnswer)
            put("expectedBodySha256",ExternalAgentContract.sha(targetBody));put("keepBodySha256",ExternalAgentContract.sha(keepBody))
            put("exactSaved",exactSaved);put("keepUnchanged",keepUnchanged);put("wholeAfterRead",wholeRead)
            put("capturedFreshAuthority",capturedFreshAuthority);put("selectedRequirementsStored",selectedRequirements)
            put("verifiedTools",JsonArray(verifiedTools.map(::JsonPrimitive)));put("actualPhoneCalls",actualPhoneCalls)
            put("sessionCloseReceipt",closeReceipt);put("finalAnswerSha256",finalAnswerSha)
            put("terminalSteps",terminalSteps);put("terminalPlan",terminalPlan)
            put("terminalAnswerFile",terminalAnswerFile);put("terminalAnswerBytes",terminalAnswerBytes);put("terminalAnswerSha256",terminalAnswerSha)
            put("workflowPassed",workflowPassed&&case=="save_readback");put("expectedBoundaryPassed",workflowPassed&&case!="save_readback")
            put("caseOutcomePassed",workflowPassed);put("fixtureCleanupVerified",fixtureClean);put("selectionRestored",selectionRestored)
            put("preferencesRestored",preferencesRestored);put("originalExternalEnabledRestored",authorityRestored)
            put("authorityRevisionRolledBack",false)
            put("passed",workflowPassed&&fixtureClean&&selectionRestored&&preferencesRestored&&authorityRestored&&failure==null)
        }
        private fun publish(final:Boolean=false) {
            val bytes=json().toString().toByteArray(Charsets.UTF_8);check(bytes.size<=32_768)
            val file=AtomicFile(report);val output=file.startWrite()
            try{output.write(bytes);file.finishWrite(output)}catch(error:Throwable){file.failWrite(output);throw error}
            check(file.openRead().use{it.readBytes()}.contentEquals(bytes))
            if(final)instrumentation.sendStatus(2,Bundle().apply {
                putInt("externalAgentNormalCanaryFinalBytes",bytes.size)
                putString("externalAgentNormalCanaryFinalSha256",ExternalAgentContract.sha(String(bytes,Charsets.UTF_8)))
                putBoolean("externalAgentNormalCanaryReadbackVerified",true)
            })
        }
        private fun verify(code:String,value:Boolean){lastCheck=code;check(value){code}}
        private suspend fun scopeGuard() {
            val p=checkNotNull(project);val t=checkNotNull(thread)
            verify("QA_SCOPE",store.project(p.id)?.let{it.title==prefix&&it.workspaceKey==p.id&&it.settings==projectSettings}==true&&
                store.thread(t.id)?.let{it.projectId==p.id&&it.activeBranchId==t.activeBranchId}==true)
            verify("QA_SELECTION",coordinator.selectedThreadId.value==t.id&&coordinator.selectedProjectId.value==p.id)
            verify("QA_MODEL",coordinator.preferredModelId.value==ExternalAgentSettings.MODEL_ID&&preferences.getString("model",null)==ExternalAgentSettings.MODEL_ID)
            verify("QA_AUTHORITY",external.capture()==(revokedAuthority?:qaAuthority)&&external.capture().enabled==(revokedAuthority==null))
            verify("QA_NO_OTHER_RUN",coordinator.activeExecutions.value.all{it.threadId==t.id&&it.projectId==p.id}&&
                store.queuedRuns().all{it.threadId==t.id&&it.projectId==p.id})
            verify("QA_NO_ATTACHMENTS",app.chatAttachments.state.value.isEmpty())
        }
        private suspend fun captureOriginalState() {
            waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value.isNotBlank()}
            check(!coordinator.hasActiveRuns.value&&!coordinator.busy.value&&store.queuedRuns().isEmpty()&&!app.imageStudio.state.value.running)
            check(app.chatAttachments.state.value.isEmpty())
            originalThread=coordinator.selectedThreadId.value
            originalProject=coordinator.selectedProjectId.value
            originalDraft=store.readDraft(originalThread)
            originalDraftText=coordinator.draft.value
            originalPreferred=coordinator.preferredModelId.value
            originalOptions=coordinator.modelOptions.value
            originalPreferences=preferences.all.toMap()
            originalAuthority=external.capture()
            originalGrants=app.repository.listGrants().toSet()
            check(!report.exists()){"Archive the original exact report before another attempt"}
            began=SystemClock.elapsedRealtime()
        }
        private suspend fun prepare() {
            publish()
            val p=store.createProject(prefix,projectSettings).also{project=it}
            verify("QA_PRIVATE_PROJECT",p.workspaceKey==p.id&&p.workspaceKey !in setOf("personal","default"))
            val t=store.createThread(p.id,prefix).also{thread=it}
            val scoped=app.repository.forThread(t.id)
            val ws=app.workspaceFor(p.id).also{workspace=it}
            verify("QA_EMPTY_WORKSPACE",!ws.state.value.external&&ws.list().isEmpty())
            keep=scoped.saveNote(keepTitle,keepBody,collection)
            verify("QA_ONLY_KEEP_NOTE",scoped.notes.first().map{it.id}==listOf(checkNotNull(keep).id))
            withContext(Dispatchers.Main.immediate){coordinator.selectThread(t.id)}
            waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value==t.id}
            withContext(Dispatchers.Main.immediate){
                coordinator.chooseModel(ExternalAgentSettings.MODEL_ID,t.id)
                coordinator.setThinking(false,t.id)
            }
            waitFor(15_000){store.readDraft(t.id)?.let{it.modelId==ExternalAgentSettings.MODEL_ID&&!it.thinking}==true}
            qaAuthority=external.setEnabled(true)
        }
        private suspend fun run() {
            val t=checkNotNull(thread)
            scopeGuard();stage="submitting";publish()
            verify("QA_NORMAL_SEND_ACCEPTED",withContext(Dispatchers.Main.immediate){coordinator.send(request,t.id)})
            waitFor(20_000){store.runsForThread(t.id).isNotEmpty()}
            val run=store.runsForThread(t.id).single();ownRun=run.id
            stage="running";publish()
            withTimeout(360_000){waitUntilTerminal()}
        }
        private suspend fun waitUntilTerminal() {
            val p=checkNotNull(project);val t=checkNotNull(thread)
            val scoped=app.repository.forThread(t.id)
            val runId=checkNotNull(ownRun)
            var publishedAt=0L
            while(true){
                scopeGuard()
                val current=checkNotNull(store.run(runId))
                verify("QA_RUN_BINDING",current.threadId==t.id&&current.projectId==p.id&&current.branchId==t.activeBranchId&&
                    current.userMessageId?.let{scoped.getMessage(it)?.text==request}==true)
                val captured=QueuedChatRequest.fromJson(current.contextJson)
                capturedFreshAuthority=captured.runId==runId&&captured.threadId==t.id&&captured.projectId==p.id&&
                    captured.modelId==ExternalAgentSettings.MODEL_ID&&captured.allowExternalAgent&&
                    captured.externalAgentRevision==qaAuthority?.revision&&captured.allowTools&&captured.attachments.isEmpty()&&
                    captured.previousSteps.none{it.tool !in allowed}
                verify("QA_CAPTURED_AUTHORITY",capturedFreshAuthority)
                waitApproval(runId)
                if(current.status in setOf("completed","failed","cancelled","paused")&&coordinator.executionForRun(runId)==null){
                    originalRunStatus=current.status;terminalError=current.errorCode;break
                }
                val now=SystemClock.elapsedRealtime()
                if(now-publishedAt>=2000){publishedAt=now;publish()}
                delay(50)
            }
        }
        private fun waitApproval(runId:String) {
            val p=checkNotNull(project);val t=checkNotNull(thread)
            for(pending in coordinator.pendingActions.value.filter{it.runId==runId&&!it.decision.isCompleted}){
                verify("QA_ONLY_ONE_EXACT_SAVE_APPROVAL",approvalCount==0&&pending.threadId==t.id&&pending.projectId==p.id&&
                    pending.call.name=="save_note"&&pending.call.arguments["title"]?.jsonPrimitive?.content==targetTitle&&
                    pending.call.arguments["content"]?.jsonPrimitive?.content==targetBody&&
                    pending.call.arguments["scope"]?.jsonPrimitive?.content.orEmpty().ifEmpty{"default"}==collection&&
                    pending.call.arguments.keys.all{it in setOf("title","content","scope")})
                pending.remember.value=case=="revoke"
                if(case=="revoke"){
                    verify("QA_REVOKE_ONLY_OWN_APPROVAL",external.capture()==qaAuthority&&pending.runId==runId&&pending.projectId==p.id)
                    revokedAuthority=external.setEnabled(false)
                    verify("QA_PERMISSION_REVOKED",revokedAuthority?.enabled==false&&checkNotNull(revokedAuthority).revision>checkNotNull(qaAuthority).revision)
                }
                decisionAccepted=case!="decline"
                verify("QA_FIXTURE_DECISION_DELIVERED",pending.decision.complete(decisionAccepted))
                approvalCount++;exactApproval=true;publish()
            }
        }
        private suspend fun captureTerminalEvidence() {
            scopeGuard()
            val bound=checkNotNull(store.run(checkNotNull(ownRun)))
            check(bound.threadId==thread?.id&&bound.projectId==project?.id&&bound.status in setOf("completed","paused","failed","cancelled"))
            val snapshot=QueuedChatRequest.fromJson(bound.contextJson)
            check(snapshot.runId==bound.id&&snapshot.text==request&&snapshot.threadId==thread?.id&&snapshot.projectId==project?.id)
            terminalSteps=buildJsonArray{snapshot.previousSteps.forEach{step->add(buildJsonObject{
                put("tool",step.tool);put("state",step.evidence.state.name);put("arguments",step.arguments);put("summary",step.evidence.summary)
            })}}
            terminalPlan=Json.parseToJsonElement(bound.contextJson).jsonObject["externalAgentPlan"]?:JsonNull
            store.assistantForRun(bound.id)?.let{answer->
                check(answer.runId==bound.id&&answer.threadId==thread?.id&&answer.role=="assistant")
                val bytes=store.fullMessageText(answer.id).toByteArray(Charsets.UTF_8);check(bytes.size<=32_768)
                val file=File(diagnostics,"$prefix-terminal-answer.txt");check(!file.exists())
                val atomic=AtomicFile(file);val output=atomic.startWrite()
                try{output.write(bytes);atomic.finishWrite(output)}catch(error:Throwable){atomic.failWrite(output);throw error}
                check(atomic.openRead().use{it.readBytes()}.contentEquals(bytes))
                terminalAnswerFile=file.name;terminalAnswerBytes=bytes.size;terminalAnswerSha=ExternalAgentContract.sha(String(bytes,Charsets.UTF_8))
            }
        }
        private suspend fun verifyOutcome() {
            val runId=checkNotNull(ownRun)
            stage="verifying";publish()
            val completed=checkNotNull(store.run(runId))
            grantsUnchanged=app.repository.listGrants().toSet()==originalGrants
            verify("QA_REVOKED_APPROVAL_CANNOT_PERSIST_GRANT",grantsUnchanged)
            verify("QA_APPROVAL_ONE",approvalCount==1&&exactApproval)
            val capture=QueuedChatRequest.fromJson(completed.contextJson)
            val steps=capture.previousSteps
            verifiedTools=steps.filter{it.evidence.state==VerificationState.VERIFIED&&!it.evidence.effectNotApplied}.map{it.tool}
            if(case=="save_readback"&&completed.status!="completed"){
                // A negative workflow result remains data until cleanup and final receipt publication.
                firstFailureStage=stage;firstFailureCheck="QA_PARENT_COMPLETED"
                failure=IllegalStateException("QA_PARENT_COMPLETED")
            }else if(case!="save_readback"){
                verifyBoundary(completed)
            }else{
                verifyPositive(completed,capture)
            }
        }
        private suspend fun verifyBoundary(completed:ChatRunRecord) {
            val t=checkNotNull(thread);val scoped=app.repository.forThread(t.id)
            val runId=checkNotNull(ownRun)
            verify("QA_BOUNDARY_PARENT_NOT_COMPLETED",completed.status!="completed"&&completed.status in setOf("paused","failed","cancelled"))
            val notes=scoped.notes.first()
            noSavedNote=notes.none{it.title==targetTitle}&&notes.map{it.id}==listOf(checkNotNull(keep).id)
            keepUnchanged=notes.singleOrNull{it.id==keep?.id}==keep
            verify("QA_BOUNDARY_NO_WRITE_AND_KEEP",noSavedNote&&keepUnchanged&&verifiedTools.none{it=="save_note"})
            val answer=store.assistantForRun(runId)
            if(answer==null){
                verify("QA_ONLY_CANCELLED_MAY_HAVE_NO_RESPONSE",completed.status=="cancelled")
                boundaryCancelledWithoutAnswer=true
            }else{
                val text=store.fullMessageText(answer.id)
                verify("QA_BOUNDARY_ACTUAL_STORED_RESPONSE",answer.runId==runId&&answer.threadId==t.id)
                if(text.isBlank()){
                    verify("QA_ONLY_CANCELLED_MAY_HAVE_EMPTY_PROGRESS_ROW",completed.status=="cancelled")
                    boundaryCancelledWithoutAnswer=true
                }else finalAnswerSha=ExternalAgentContract.sha(text)
            }
            workflowPassed=true
        }
        private suspend fun verifyPositive(completed:ChatRunRecord,capture:QueuedChatRequest) {
            val t=checkNotNull(thread);val scoped=app.repository.forThread(t.id)
            val runId=checkNotNull(ownRun);val steps=capture.previousSteps
            verify("QA_PARENT_COMPLETED",completed.status=="completed")
            verify("QA_ACTUAL_SAVE_SEARCH_READ_STEPS",steps.all{it.tool in allowed&&it.evidence.state==VerificationState.VERIFIED&&!it.evidence.effectNotApplied}&&
                verifiedTools.count{it=="save_note"}==1&&verifiedTools.containsAll(allowed))
            val notes=scoped.notes.first()
            saved=notes.singleOrNull{it.title==targetTitle}
            val target=checkNotNull(saved)
            exactSaved=target.body==targetBody&&target.collection==collection&&target.title==targetTitle
            keepUnchanged=notes.singleOrNull{it.id==keep?.id}==keep
            verify("QA_EXACT_SAVED_AND_KEEP",exactSaved&&keepUnchanged&&notes.map{it.id}.toSet()==setOf(target.id,checkNotNull(keep).id))
            val patchIndex=steps.indexOfFirst{it.tool=="save_note"}
            val reads=steps.withIndex().filter{(index,step)->index>patchIndex&&step.tool=="read_note"}
            val matching=reads.map{it.value.arguments}.filter{
                it["id"]?.jsonPrimitive?.content==target.id&&it["scope"]?.jsonPrimitive?.content==collection&&
                    it["expectedUpdatedAt"]?.jsonPrimitive?.content==target.updatedAt.toString()
            }
            wholeRead=matching.any{(it["offset"]?.jsonPrimitive?.intOrNull?:0)==0&&(it["limit"]?.jsonPrimitive?.intOrNull?:900)>=target.body.length}
            verify("QA_SAME_TARGET_NEW_VERSION_FULL_READ",wholeRead)
            val contextJson=Json.parseToJsonElement(completed.contextJson).jsonObject
            val plan=contextJson.getValue("externalAgentPlan").jsonObject
            val selected=plan.getValue("requiredTools").jsonArray.map{it.jsonPrimitive.content}.toSet()
            selectedRequirements=selected==allowed&&capture.readbackObligations!=null
            verify("QA_PLAN_READBACK_STORED",selectedRequirements)
            val proof=checkNotNull(capture.readbackObligations)
            verify("QA_EXISTING_READBACK_GATE",RequiredReadbackObligations.assess(proof,proof.context,steps,0,allowed,18-steps.size,1,true).readbackSatisfied)
            val receipt=contextJson.getValue("externalAgentReceipt").jsonObject
            actualPhoneCalls=receipt.getValue("actualPhoneCalls").jsonPrimitive.int
            closeReceipt=receipt.getValue("sessionClosed")==JsonPrimitive(true)&&receipt.getValue("phoneEvidenceImported")==JsonPrimitive(false)&&
                receipt.getValue("pcCalls").jsonPrimitive.int==0&&actualPhoneCalls==steps.size&&
                receipt.getValue("planDigest")==plan.getValue("planDigest")
            verify("QA_ACTUAL_CLOSE_RECEIPT",closeReceipt)
            val answer=checkNotNull(store.assistantForRun(runId))
            verify("QA_FINAL_ANSWER_BOUND",answer.runId==runId&&answer.threadId==t.id)
            val body=store.fullMessageText(answer.id);verify("QA_FINAL_STORED_ANSWER",body.isNotBlank())
            finalAnswerSha=ExternalAgentContract.sha(body)
            workflowPassed=true
        }
        private suspend fun recordFailure(error:Throwable) {
            firstFailureStage=stage;firstFailureCheck=lastCheck;failure=error
            ownRun?.let{store.run(it)?.let{r->originalRunStatus=r.status;terminalError=r.errorCode}}
            stage="failed";runCatching{publish()}
        }
        private suspend fun cleanupStep(block:suspend()->Unit){
            try{block()}catch(error:Throwable){
                if(failure==null){failure=error;firstFailureStage=stage;firstFailureCheck=lastCheck}
                else failure?.addSuppressed(error)
            }
        }

        private suspend fun cleanup(){
            cleanupStep(::cancelOwnRun)
            cleanupStep(::restoreSelection)
            cleanupStep(::removeFixture)
            cleanupStep(::restorePreferences)
            cleanupStep(::restoreAuthority)
        }
        private suspend fun cancelOwnRun() {
            thread?.let{t->
                val job=withContext(Dispatchers.Main.immediate){coordinator.runningJobForThread(t.id).also{coordinator.cancelThread(t.id)}}
                withTimeout(60_000){job?.join();while(coordinator.executionForRun(ownRun.orEmpty())!=null)delay(50)}
                val candidates=store.runsForThread(t.id)
                if(ownRun==null&&candidates.size==1&&candidates.single().userMessageId?.let{app.repository.forThread(t.id).getMessage(it)?.text==request}==true)ownRun=candidates.single().id
                verify("QA_ONLY_OWN_RUN_CLEANUP",candidates.all{it.id==ownRun})
                ownRun?.let{id->val r=store.run(id);if(r?.status=="queued")store.cancelQueuedRun(id) else if(r?.status !in setOf("completed","failed","cancelled","paused"))store.updateRun(id,"cancelled")}
            }
        }
        private suspend fun restoreSelection() {
            if(thread?.id==coordinator.selectedThreadId.value){
                verify("QA_RESTORE_NO_OTHER_WORK",!coordinator.hasActiveRuns.value&&!coordinator.busy.value)
                verify("QA_ORIGINAL_DRAFT_UNCHANGED",store.readDraft(originalThread)==originalDraft)
                withContext(Dispatchers.Main.immediate){coordinator.selectThread(originalThread)}
                waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value==originalThread}
            }
            selectionRestored=coordinator.selectedThreadId.value==originalThread&&coordinator.selectedProjectId.value==originalProject&&
                coordinator.draft.value==originalDraftText&&store.readDraft(originalThread)==originalDraft&&
                coordinator.preferredModelId.value==originalPreferred&&coordinator.modelOptions.value.thinking==originalOptions.thinking
            verify("QA_SELECTION_RESTORED",selectionRestored)
        }
        private suspend fun removeFixture() {
            project?.let{p->
                verify("QA_CLEANUP_PROJECT_SCOPE",store.project(p.id)?.let{it.title==prefix&&it.workspaceKey==p.id&&it.settings==projectSettings}==true)
                verify("QA_CLEANUP_NO_OTHER_THREADS",store.threads.first().filter{it.projectId==p.id}.map{it.id}.toSet()==listOfNotNull(thread?.id).toSet())
                thread?.let{t->
                    verify("QA_FIXTURE_UNSELECTED",coordinator.selectedThreadId.value!=t.id&&coordinator.activeExecutions.value.none{it.threadId==t.id})
                    val scoped=app.repository.forThread(t.id)
                    val notes=scoped.notes.first()
                    verify("QA_ONLY_OWN_NOTES",notes.all{it.collection==collection&&it.title in setOf(targetTitle,keepTitle)}&&
                        notes.firstOrNull{it.id==keep?.id}?.let{it==keep}!=false)
                    notes.forEach{scoped.deleteNote(it.id,it.updatedAt)}
                    verify("QA_NO_OTHER_DERIVED_DATA",scoped.listReminders().isEmpty())
                }
                workspace?.let{verify("QA_EMPTY_PRIVATE_WORKSPACE",!it.state.value.external&&it.list().isEmpty())}
                store.deleteProject(p.id,true);workspace?.discard()
                verify("QA_FIXTURE_ABSENT",store.project(p.id)==null&&thread?.let{store.thread(it.id)==null}!=false)
            }
            fixtureClean=true
        }
        private suspend fun restorePreferences() {
            val current=preferences.getString("model",null)
            if(current==ExternalAgentSettings.MODEL_ID){
                val edit=preferences.edit()
                if(originalPreferences.containsKey("model"))edit.putString("model",originalPreferences["model"] as? String)else edit.remove("model")
                check(edit.commit())
            }
            if(coordinator.selectedThreadId.value==originalThread&&preferences.getString("thread",null)==originalThread){
                val edit=preferences.edit()
                if(originalPreferences.containsKey("thread"))edit.putString("thread",originalPreferences["thread"] as? String)else edit.remove("thread")
                check(edit.commit())
            }
            preferencesRestored=preferences.all==originalPreferences
            verify("QA_PREFERENCES_RESTORED",preferencesRestored)
        }
        private suspend fun restoreAuthority() {
            val current=external.capture()
            if(current==(revokedAuthority?:qaAuthority))external.setEnabled(originalAuthority.enabled)
            val restored=external.capture()
            authorityRestored=restored.enabled==originalAuthority.enabled&&restored.revision>=originalAuthority.revision
            verify("QA_ORIGINAL_PERMISSION_RESTORED_WITHOUT_REVISION_ROLLBACK",authorityRestored)
        }
    }
}
