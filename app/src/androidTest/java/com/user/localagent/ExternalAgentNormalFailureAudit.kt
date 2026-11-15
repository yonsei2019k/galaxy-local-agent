package com.user.localagent

import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.externalagent.ExternalAgentContract
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exact report-bound audit. Small named suspend methods; no original-report writes or provider calls. */
@RunWith(AndroidJUnit4::class)
class ExternalAgentNormalFailureAudit {
    @Test fun auditOnlyOrExplicitlyCleanTheExactReportBoundQaFixture() {
        val session=Session()
        try { runBlocking(Dispatchers.IO) { session.audit();if(session.cleanup)session.cleanupExact() } }
        catch(failure:Throwable){session.error=failure}
        session.publish(true)
        session.error?.let{throw IllegalStateException("EXACT_FAILURE_AUDIT_REJECTED",it)}
        check(session.audited)
    }
    private class Session {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments().also{assumeTrue(it.getString("externalAgentNormalAuditApproved")=="true")}
        val context=instrumentation.targetContext
        val installedExpected=checkNotNull(args.getString("externalAgentNormalAuditExpectedVersion")?.toLongOrNull()).also{
            check(it in setOf(1087L,1088L)&&context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==it)
        }
        val expected=checkNotNull(args.getString("externalAgentNormalAuditExpectedReportSha256")).also{check(it.matches(Regex("[a-f0-9]{64}")))}
        val case=(args.getString("externalAgentNormalAuditCase")?:"save_readback").also{check(it in setOf("save_readback","decline","revoke"))}
        val cleanup=args.getString("externalAgentNormalAuditCleanupQA")=="true"
        val directory=checkNotNull(context.getExternalFilesDir("diagnostics"))
        val original=File(directory,"external-agent-normal-conversation-$case-canary.json")
        val bytes=original.also{check(it.isFile&&it.length() in 1..65_536)}.readBytes().also{
            check(ExternalAgentContract.sha(String(it,Charsets.UTF_8))==expected)
        }
        val source=ExternalAgentContract.parseObject(String(bytes,Charsets.UTF_8))
        fun text(key:String)=source.getValue(key).jsonPrimitive.also{check(it.isString)}.content
        val prefix=text("prefix");val projectId=text("projectId");val threadId=text("threadId");val runId=text("runId")
        val expectedBodySha=text("expectedBodySha256");val keepSha=text("keepBodySha256")
        val app=(context.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        val external=ExternalAgentSettings.get(context)
        val output=File(directory,"external-agent-normal-failure-"+expected.take(16)+"-"+(if(cleanup)"cleanup"else"audit")+".json")
        val companion=File(directory,"external-agent-normal-failure-"+expected.take(16)+"-assistant.txt")
        var audited=false;var cleaned=false;var revoked=false;var fallback=false;var qaModelOverrideCleared=false;var error:Throwable?=null
        var runStatus:String?=null;var runError:String?=null;var capturedRevision:Long?=null
        var assistantCaptured=false;var assistantBytes=0;var assistantSha:String?=null;var assistantId:String?=null
        var matchingSaved=0;var matchingKeep=0;var currentExternalRevision:Long?=null;var currentExternalEnabled:Boolean?=null
        var requestSha:String?=null;var selectedBefore:String?=null;var selectedAfter:String?=null;var cleanupBlockedBySelection=false
        var steps:JsonArray=buildJsonArray{};var notes:JsonArray=buildJsonArray{}
        var auditStage="prepared";var fullyAbsent=false;var erasedFixtureVerified=false
        lateinit var p:ProjectRecord;lateinit var t:ChatThreadRecord;lateinit var r:ChatRunRecord
        lateinit var scoped:LocalRepository;lateinit var q:QueuedChatRequest
        lateinit var ws:com.user.localagent.workspace.ProjectWorkspace
        lateinit var actualNotes:List<NoteRecord>;var title=""
        init {
            assumeTrue(args.getString("externalAgentNormalAuditApproved")=="true")
            check(installedExpected in setOf(1087L,1088L)&&context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==installedExpected)
            check(expected.matches(Regex("[a-f0-9]{64}"))&&case in setOf("save_readback","decline","revoke"))
            check(original.isFile&&original.length() in 1..65_536&&ExternalAgentContract.sha(String(bytes,Charsets.UTF_8))==expected)
            check(source.getValue("schema")==JsonPrimitive(1)&&text("kind")=="actual_normal_conversation_cloud_phone_canary"&&
                source.getValue("versionCode")==JsonPrimitive(1087)&&text("case")==case&&source["fixtureConsent"]==JsonPrimitive(true))
            check(prefix.startsWith("qa-cloud-normal-"));ExternalAgentContract.uuid(prefix.removePrefix("qa-cloud-normal-"))
            listOf(projectId,threadId,runId).forEach(ExternalAgentContract::uuid)
            check(text("modelId")==ExternalAgentSettings.MODEL_ID&&expectedBodySha.matches(Regex("[a-f0-9]{64}"))&&keepSha.matches(Regex("[a-f0-9]{64}")))
            check(!output.exists()){ "Preserve the previous audit result before another attempt" }
        }
        fun writeAtomic(path:File,value:ByteArray){
            val atomic=AtomicFile(path);val stream=atomic.startWrite()
            try{stream.write(value);atomic.finishWrite(stream)}catch(failure:Throwable){atomic.failWrite(stream);throw failure}
            check(path.readBytes().contentEquals(value))
        }
        fun progress(stage:String){auditStage=stage;publish(false)}
        suspend fun audit(){
            inactive();progress("checking_binding")
            if(checkAbsence()){fullyAbsent=true;audited=true;progress("bound_metadata_absent");return}
            loadBinding();progress("binding_verified")
            collectSteps();readAssistant();readNotes()
            val authority=external.capture();currentExternalRevision=authority.revision;currentExternalEnabled=authority.enabled
            audited=true;progress("audit_verified")
        }
        suspend fun inactive(){
            check(app.runs.executionForRun(runId)==null&&app.runs.activeExecutions.value.none{it.runId==runId||it.projectId==projectId}&&
                store.queuedRuns().none{it.id==runId||it.projectId==projectId})
        }
        suspend fun checkAbsence():Boolean {
            val project=store.project(projectId);val thread=store.thread(threadId);val run=store.run(runId)
            if(project!=null&&thread!=null&&run!=null)return false
            check(project==null&&thread==null&&run!=null){"PARTIAL_BOUND_FIXTURE_PRESERVED"}
            val priorSha=checkNotNull(args.getString("externalAgentNormalAuditPriorSha256"))
            check(priorSha.matches(Regex("[a-f0-9]{64}")))
            val priorFile=File(directory,"external-agent-normal-failure-"+expected.take(16)+"-audit.json")
            check(priorFile.isFile&&priorFile.length() in 1..32_768&&ExternalAgentContract.sha(priorFile.readText())==priorSha)
            val prior=ExternalAgentContract.parseObject(priorFile.readText())
            check(prior["kind"]==JsonPrimitive("exact_normal_cloud_failure_audit")&&prior["auditReadOnly"]==JsonPrimitive(true)&&
                prior["auditVerified"]==JsonPrimitive(true)&&prior["originalReportUnchanged"]==JsonPrimitive(true)&&
                prior["originalReportSha256"]==JsonPrimitive(expected)&&prior["projectId"]==JsonPrimitive(projectId)&&
                prior["threadId"]==JsonPrimitive(threadId)&&prior["runId"]==JsonPrimitive(runId))
            val priorNotes=prior.getValue("notes").jsonArray.map{it.jsonObject}
            check(priorNotes.size==2&&priorNotes.count{it["matchesRequestedSave"]==JsonPrimitive(true)}==1&&
                priorNotes.count{it["matchesKeepSeed"]==JsonPrimitive(true)}==1)
            val noteIds=priorNotes.map { note->
                check(note["bodySha256"] in setOf(JsonPrimitive(expectedBodySha),JsonPrimitive(keepSha)))
                note.getValue("id").jsonPrimitive.content.also(ExternalAgentContract::uuid)
            }
            check(noteIds.distinct().size==2)
            verifyErasedFixture(checkNotNull(run),prior.getValue("runStatus").jsonPrimitive.content,noteIds)
            erasedFixtureVerified=true
            runStatus=run.status;runError=run.errorCode
            return true
        }
        suspend fun verifyErasedFixture(ledger:ChatRunRecord,expectedStatus:String,noteIds:List<String>) {
            check(ledger.id==runId&&ledger.threadId==threadId&&ledger.projectId==projectId&&ledger.status==expectedStatus&&
                ledger.status in ChatStore.TERMINAL&&ledger.contextJson.isEmpty())
            check(store.project(projectId)==null&&store.thread(threadId)==null&&store.branches(threadId).isEmpty()&&
                store.assistantForRun(runId)==null&&store.runsForThread(threadId).map{it.id}==listOf(runId))
            // Test-only inspection of exact already-audited IDs; no production database escape hatch.
            val db=LocalRepository::class.java.getDeclaredField("database").apply{isAccessible=true}.get(app.repository) as LocalDatabase
            check(db.chatDao().thread(threadId)?.let{it.projectId==projectId&&it.deletedAt!=null}==true)
            check(db.chatDao().allMessages(threadId).isEmpty()&&db.chatDao().draft(threadId)==null)
            noteIds.forEach{id->check(db.dao().noteById(id)==null&&db.chatDao().sourceScope("note",id)==null)}
            val parent=File(context.filesDir,"projects").canonicalFile
            val path=File(parent,projectId).canonicalFile
            check(path.parentFile==parent&&path.name==projectId&&!path.exists())
        }
        suspend fun cleanupExact(){
            if(fullyAbsent){check(erasedFixtureVerified);cleaned=true;progress("erased_fixture_verified");return}
            inactive();revokeAndValidateCleanup();selectMetadataFallback();deleteExactFixture();progress("cleanup_finished")
        }
        suspend fun loadBinding() {
            p=checkNotNull(store.project(projectId));t=checkNotNull(store.thread(threadId));r=checkNotNull(store.run(runId))
                        check(p.id==projectId&&p.title==prefix&&p.workspaceKey==p.id&&!p.archived&&
                            !p.settings.useOwnerMemory&&!p.settings.learnOwnerFacts&&p.settings.modelId==ExternalAgentSettings.MODEL_ID&&
                            ToolRegistry.definitions.keys-p.settings.disabledTools==setOf("save_note","search_notes","read_note"))
                        check(t.projectId==p.id&&t.title==prefix&&t.deletedAt==null&&r.projectId==p.id&&r.threadId==t.id&&
                            r.branchId==t.activeBranchId&&r.modelId==ExternalAgentSettings.MODEL_ID&&r.status in setOf("failed","paused","cancelled","completed"))
                        check(store.threads.first().filter{it.projectId==p.id}.map{it.id}==listOf(t.id)&&store.runsForThread(t.id).map{it.id}==listOf(r.id))
                        scoped=app.repository.forThread(t.id);q=QueuedChatRequest.fromJson(r.contextJson)
                        check(q.runId==r.id&&q.threadId==t.id&&q.branchId==r.branchId&&q.projectId==p.id&&q.userMessageId==r.userMessageId&&
                            q.modelId==ExternalAgentSettings.MODEL_ID&&q.allowExternalAgent&&q.externalAgentRevision!=null&&q.allowTools&&
                            q.attachments.isEmpty()&&q.continuation==null&&q.goal==null&&q.parentGoalStartRunId==null)
                        val user=checkNotNull(scoped.getMessage(checkNotNull(r.userMessageId)))
                        check(user.role=="user"&&user.threadId==t.id&&user.text==q.text)
                        val start="메모 모음 'default'에 제목 '";check(q.text.startsWith(start))
                        title=q.text.removePrefix(start).substringBefore("'로 새 메모 하나를 저장해줘.")
                        check(title.startsWith("검증 메모 "));ExternalAgentContract.uuid(title.removePrefix("검증 메모 "))
                        val body=q.text.substringAfter("\n본문 시작\n",missingDelimiterValue="").substringBeforeLast("\n본문 끝",missingDelimiterValue="")
                        check(body.isNotBlank()&&body.length<=1000&&ExternalAgentContract.sha(body)==expectedBodySha)
                        val exact="메모 모음 'default'에 제목 '$title'로 새 메모 하나를 저장해줘. 본문은 아래 본문 시작과 본문 끝 사이의 두 줄만 그대로 사용하고, "+
                            "제목이나 구분 문구를 본문에 넣지 마. 저장 전 제목과 본문을 확인받아. 저장한 뒤 그 제목으로 검색하고, "+
                            "단순 검색 결과만 보지 말고 같은 메모를 다시 열어 본문 전체가 정확히 같은지 확인해줘. 기존 메모는 수정하거나 삭제하지 마.\n"+
                            "본문 시작\n$body\n본문 끝"
                        check(q.text==exact)
                        ws=app.workspaceFor(p.id);check(!ws.state.value.external&&ws.bindingFingerprint()==q.workspaceBinding&&ws.list().isEmpty())
        }
        fun collectSteps() {
            runStatus=r.status;runError=r.errorCode;capturedRevision=q.externalAgentRevision;requestSha=ExternalAgentContract.sha(q.text)
                        check(q.previousSteps.size<=18)
                        steps=buildJsonArray{q.previousSteps.forEachIndexed{index,s->
                            check(s.tool in setOf("save_note","search_notes","read_note"))
                            add(buildJsonObject{put("index",index);put("tool",s.tool);put("state",s.evidence.state.name)
                                put("effectNotApplied",s.evidence.effectNotApplied);put("argumentsSha256",ExternalAgentContract.sha(s.arguments.toString()))})
                        }}
        }
        suspend fun readAssistant() {
            store.assistantForRun(r.id)?.let{answer->
                            check(answer.runId==r.id&&answer.threadId==t.id&&answer.role=="assistant")
                            val full=store.fullMessageText(answer.id).toByteArray(Charsets.UTF_8);check(full.size<=65_536)
                            if(companion.exists())check(companion.readBytes().contentEquals(full))else writeAtomic(companion,full)
                            assistantCaptured=true;assistantBytes=full.size;assistantSha=ExternalAgentContract.sha(String(full,Charsets.UTF_8));assistantId=answer.id
                        }
        }
        suspend fun readNotes() {
            actualNotes=scoped.notes.first();check(actualNotes.size<=4)
                        notes=buildJsonArray{actualNotes.forEach{note->
                            val hash=ExternalAgentContract.sha(note.body)
                            val target=note.title==title&&hash==expectedBodySha&&note.collection=="default"
                            val keep=note.title.startsWith("보존 메모 ")&&hash==keepSha&&note.collection=="default"&&
                                runCatching{ExternalAgentContract.uuid(note.title.removePrefix("보존 메모 "))}.isSuccess
                            if(target)matchingSaved++;if(keep)matchingKeep++
                            add(buildJsonObject{put("id",note.id);put("titleSha256",ExternalAgentContract.sha(note.title));put("bodySha256",hash)
                                put("bodyBytes",note.body.toByteArray(Charsets.UTF_8).size);put("updatedAt",note.updatedAt)
                                put("matchesRequestedSave",target);put("matchesKeepSeed",keep)})
                        }}
                        check(matchingKeep==1&&matchingSaved<=1)
        }
        suspend fun revokeAndValidateCleanup() {
            val authority=external.capture()

                            check(source["passed"]==JsonPrimitive(false)&&source["fixtureCleanupVerified"]==JsonPrimitive(false))
                            if(authority.revision==q.externalAgentRevision&&authority.enabled){
                                progress("before_permission_revoke");val changed=external.setEnabled(false);check(!changed.enabled&&changed.revision>authority.revision);revoked=true;progress("permission_revoked")
                            }
                            check(actualNotes.size==matchingKeep+matchingSaved&&scoped.listReminders().isEmpty())
                            check(app.runs.executionForRun(r.id)==null&&app.runs.activeExecutions.value.none{it.projectId==p.id}&&store.queuedRuns().none{it.projectId==p.id})
        }
        suspend fun selectMetadataFallback() {
            selectedBefore=app.runs.selectedThreadId.value
                            if(selectedBefore==t.id){
                                check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value)
                                val alternative=store.threads.first().filter{it.id!=t.id&&it.projectId!=p.id&&!it.archived&&it.deletedAt==null}.maxByOrNull{it.updatedAt}
                                if(alternative!=null){
                                    progress("before_metadata_fallback");withContext(Dispatchers.Main.immediate){app.runs.selectThread(alternative.id)}
                                    withTimeout(20_000){while(!app.runs.selectionReady.value||app.runs.selectedThreadId.value!=alternative.id)delay(50)}
                                    fallback=true;progress("metadata_fallback_applied") // Metadata choice only, never claimed as restoration of unknown original state.
                                    val preferences=context.getSharedPreferences("chat-preferences",0)
                                    val existingModel=app.runs.preferredModelId.value
                                    if(preferences.getString("model",null)==ExternalAgentSettings.MODEL_ID&&existingModel!=ExternalAgentSettings.MODEL_ID){
                                        check(preferences.edit().putString("model",existingModel).commit());qaModelOverrideCleared=true
                                    }
                                }else cleanupBlockedBySelection=true
                            }
                            selectedAfter=app.runs.selectedThreadId.value
        }
        suspend fun deleteExactFixture() {
            if(!cleanupBlockedBySelection){
                                check(selectedAfter!=t.id&&store.project(p.id)==p&&store.thread(t.id)?.let{
                                    it.projectId==t.projectId&&it.title==t.title&&it.activeBranchId==t.activeBranchId&&it.revision==t.revision
                                }==true&&store.run(r.id)==r)
                                check(scoped.notes.first()==actualNotes&&!ws.state.value.external&&ws.list().isEmpty())
                                progress("before_note_delete");actualNotes.forEach{scoped.deleteNote(it.id,it.updatedAt)};progress("notes_deleted")
                                progress("before_project_delete");store.deleteProject(p.id,true);ws.discard();progress("project_deleted")
                                verifyErasedFixture(checkNotNull(store.run(r.id)),r.status,actualNotes.map{it.id});erasedFixtureVerified=true;cleaned=true
                            }
        }
        fun publish(final:Boolean=false) {
            val unchanged=original.isFile&&original.readBytes().contentEquals(bytes)
                        val result=buildJsonObject{
                            put("schema",1);put("auditStage",auditStage);put("boundMetadataFullyAbsent",false);put("absenceIsMetadataOnly",false);put("erasedFixtureVerified",erasedFixtureVerified);put("retainedTerminalLedgerVerified",erasedFixtureVerified);put("kind","exact_normal_cloud_failure_audit");put("versionCode",installedExpected);put("originalVersionCode",1087);put("case",case)
                            put("originalReportSha256",expected);put("originalReportUnchanged",unchanged);put("originalStage",text("stage"));put("originalPassed",source["passed"]?:JsonNull)
                            put("projectId",projectId);put("threadId",threadId);put("runId",runId);put("auditReadOnly",!cleanup)
                            put("auditVerified",audited&&error==null);put("cleanupRequested",cleanup);put("fixtureCleanupVerified",cleaned);put("cleanupBlockedBySelection",cleanupBlockedBySelection)
                            put("runStatus",runStatus);put("terminalErrorCode",runError);put("capturedExternalRevision",capturedRevision)
                            put("observedExternalRevision",currentExternalRevision);put("observedExternalEnabled",currentExternalEnabled)
                            put("matchingCapturedAuthorityRevoked",revoked);put("authorityRevisionRolledBack",false)
                            put("unknownOriginalPreferencesRestored",false);put("unknownOriginalAuthorityRestored",false);put("unknownOriginalSelectionRestored",false)
                            put("originalSelectionRestored",false);put("qaCloudModelOverrideCleared",qaModelOverrideCleared)
                            put("fallbackSelectionApplied",fallback);put("selectionBeforeCleanup",selectedBefore);put("selectionAfterCleanup",selectedAfter)
                            put("brokerOrModelInvoked",false);put("otherOwnerContentReadByAudit",false);put("canonicalSelectionMayLoadExistingUiState",fallback)
                            put("requestSha256",requestSha);put("steps",steps);put("notes",notes)
                            put("matchingRequestedSaveCount",matchingSaved);put("matchingKeepSeedCount",matchingKeep)
                            put("assistantCaptured",assistantCaptured);put("assistantId",assistantId);put("assistantBytes",assistantBytes);put("assistantSha256",assistantSha)
                            put("assistantCompanion",if(assistantCaptured)JsonPrimitive(companion.name)else JsonNull)
                            put("errorCode",if(error==null)JsonNull else JsonPrimitive("EXACT_FAILURE_AUDIT_REJECTED"))
                        }.toString().toByteArray(Charsets.UTF_8)
                        check(result.size<=32_768);writeAtomic(output,result)
                        if(final)instrumentation.sendStatus(2,Bundle().apply{
                            putInt("externalAgentNormalAuditFinalBytes",result.size)
                            putString("externalAgentNormalAuditFinalSha256",ExternalAgentContract.sha(String(result,Charsets.UTF_8)))
                            putBoolean("externalAgentNormalAuditReadbackVerified",true);putBoolean("externalAgentNormalAuditOriginalUnchanged",unchanged)
                        });check(unchanged)
        }
    }
}
