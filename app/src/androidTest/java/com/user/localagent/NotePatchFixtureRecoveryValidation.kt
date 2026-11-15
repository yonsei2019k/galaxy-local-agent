package com.user.localagent

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.models.PairedPcTextProfile
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exact QA locator only. Default audit; deletion needs its separate opt-in. No model/transport calls. */
@RunWith(AndroidJUnit4::class)
class NotePatchFixtureRecoveryValidation {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun hash(text:String)=sha(text.toByteArray(Charsets.UTF_8))
    private fun atomic(file:File,text:String){check(file.parentFile?.isDirectory==true||file.parentFile?.mkdirs()==true)
        val target=AtomicFile(file);val output=target.startWrite()
        try{output.write(text.toByteArray(Charsets.UTF_8));target.finishWrite(output)}catch(error:Throwable){target.failWrite(output);throw error}}
    @Test fun auditOrRecoverOnlyTheByteBoundNoteFixture()=runBlocking(Dispatchers.Default){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("noteFixtureRecoveryApproved")=="true")
        val delete=args.getString("noteFixtureRecoveryDeleteApproved")=="true"
        val expectedVersion=checkNotNull(args.getString("noteFixtureRecoveryExpectedVersion")?.toLongOrNull())
        val originalVersion=checkNotNull(args.getString("noteFixtureRecoveryOriginalVersion")?.toLongOrNull())
        val reportSha=checkNotNull(args.getString("noteFixtureRecoveryReportSha256"));check(Regex("[a-f0-9]{64}").matches(reportSha))
        val direct=when(args.getString("noteFixtureRecoveryDirect")){"true"->true;"false"->false;else->error("QA_RECOVERY_VARIANT")}
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expectedVersion)
        val directory=File(checkNotNull(base.getExternalFilesDir(null)),"diagnostics")
        val original=File(directory,if(direct)"note-patch-actual-pc-canary-direct-next-action.json"else"note-patch-actual-pc-canary.json")
        val result=File(directory,if(direct)"note-patch-fixture-recovery-direct.json"else"note-patch-fixture-recovery-original.json")
        val bytes=original.readBytes();check(bytes.size in 1..32768&&sha(bytes)==reportSha){"QA_RECOVERY_ORIGINAL_REPORT_SHA"}
        val report=Json.parseToJsonElement(String(bytes,Charsets.UTF_8)).jsonObject
        fun s(o:JsonObject,k:String)=(o.getValue(k) as JsonPrimitive).also{check(it.isString)}.content
        val loc=report.getValue("locator").jsonObject
        fun uuid(k:String)=s(loc,k).also{check(UUID.fromString(it).toString()==it)}
        val projectId=uuid("projectId");val threadId=uuid("threadId");val branchId=uuid("branchId");val runId=uuid("runId")
        val seedId=uuid("seedId");val keepId=uuid("keepId");check(seedId!=keepId)
        val prefix=s(loc,"prefix");check(prefix.startsWith("qa-note-pc-")&&UUID.fromString(prefix.removePrefix("qa-note-pc-")).toString()==prefix.removePrefix("qa-note-pc-"))
        val collection=s(loc,"collection");check(collection.startsWith("qa-note-")&&UUID.fromString(collection.removePrefix("qa-note-")).toString()==collection.removePrefix("qa-note-"))
        check(s(loc,"workspaceKey")==projectId&&report.getValue("schema").jsonPrimitive.int==1&&report.getValue("versionCode").jsonPrimitive.long==originalVersion)
        check(s(report,"kind")=="actual_pc_normal_send_note_patch_canary"&&report.getValue("passed").jsonPrimitive.boolean==false)
        check(s(report,"requestLoopVariant")==if(direct)"DIRECT_NEXT_ACTION_OPT_IN"else"ORIGINAL_SEMANTIC")
        check(report.getValue("normalSendSelectorUsed").jsonPrimitive.boolean&&!report.getValue("choiceOverrideUsed").jsonPrimitive.boolean&&!report.getValue("modelOutputOrPlanInjected").jsonPrimitive.boolean)
        check(!report.getValue("allowCloudText").jsonPrimitive.boolean&&!loc.getValue("useOwnerMemory").jsonPrimitive.boolean&&!loc.getValue("learnOwnerFacts").jsonPrimitive.boolean)
        val revision=loc.getValue("capturedPcRevision").jsonPrimitive.long;val seedRevision=loc.getValue("seedBeforeUpdatedAt").jsonPrimitive.long
        check(s(loc,"modelSelection")==PairedPcTextProfile.SELECTION_ID)
        val before="인수 시각: 목요일 14시\r\n담당: 운영팀\r\n두  공백 😀 e\u0301\r\n"+"보존 구간  원문 😀 e\u0301\r\n".repeat(75)+"마지막 줄은 유지"
        val after=before.replaceFirst("목요일 14시","금요일 16시")
        val keepBody="보존 메모\r\n인수 시각: 목요일 14시\r\n두  공백 😀 e\u0301"
        val owner="제목 '$prefix-target' 메모를 찾아 현재 원문을 읽고, 인수 시각의 '목요일 14시'만 '금요일 16시'로 고쳐줘. "+
            "제목과 다른 줄, 줄바꿈, 연속 공백과 이모지는 그대로 유지해. 다른 메모는 바꾸지 마. 저장된 같은 메모를 다시 읽어 수정 결과를 확인하고 알려줘."
        check(hash(before)==s(loc,"seedBeforeSha256")&&hash(after)==s(loc,"seedAfterSha256")&&hash(keepBody)==s(loc,"keepSha256")&&hash(owner)==s(loc,"ownerRequestSha256"))
        val app=(base.applicationContext as LocalAgentApplication).container;val chat=app.repository.chat
        val settings=PcComputeSettings.get(base);val connection=checkNotNull(settings.snapshot())
        val recoveryOwner=UUID.randomUUID().toString();var phone:ComputeResourceBroker.Lease?=null;var pc:ComputeResourceBroker.Lease?=null;var update:Closeable?=null
        var stage="initial";var passed=false;var outcome="UNFINISHED";var cleanup=false;var deletionStarted=false
        var runStatus:String?=null;var tools=emptyList<String>();var effect:String?=null;var keepUnchanged:Boolean?=null;var retainedTerminalLedger=false;var primaryFailure:Throwable?=null
        var runBinding:JsonObject?=null
        fun verify(code:String,ok:Boolean){stage=code;check(ok){code}}
        fun authority(){val current=settings.snapshot();verify("QA_RECOVERY_AUTHORITY",settings.authorizationRevision==revision&&!settings.state.value.claudeText&&current!=null&&
            current.serverId==connection.serverId&&current.token==connection.token&&current.certificateSha256==connection.certificateSha256)}
        suspend fun quiet(){fun ours(held:ComputeResourceBroker.Lease?,resource:ComputeResource,gate:OwnedComputeGate)=
            if(held==null)!gate.get()&&gate.owner.value==null else held.resource==resource&&held.owner==recoveryOwner&&gate.get()&&gate.owner.value==recoveryOwner
            verify("QA_RECOVERY_QUIET",!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&!app.imageStudio.state.value.running&&!RuntimeGate.benchmark.get()&&
                ours(phone,ComputeResource.PHONE,RuntimeGate.inference)&&ours(pc,ComputeResource.PC,RuntimeGate.pc)&&chat.queuedRuns().isEmpty())
        }
        val root=File(base.filesDir,"projects/$projectId");val parent=File(base.filesDir,"projects").canonicalFile
        verify("QA_RECOVERY_ROOT",root.canonicalFile.parentFile==parent&&root.canonicalFile.name==projectId&&!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val prefsName="$prefix-chat-preferences";val prefs=base.getSharedPreferences(prefsName,0);val workspacePrefsName="project-workspace-v1-$projectId"
        val workspacePrefs=base.getSharedPreferences(workspacePrefsName,0)
        val prefsDirectory=File(base.applicationInfo.dataDir,"shared_prefs").canonicalFile
        fun prefsAbsent(name:String)=listOf(".xml",".xml.bak").all{!File(prefsDirectory,name+it).exists()}&&base.getSharedPreferences(name,0).all.isEmpty()
        fun emit(){atomic(result,buildJsonObject{
            put("schema",1);put("kind","exact_note_fixture_recovery");put("passed",passed);put("outcome",outcome);put("stage",stage)
            put("deleteApproved",delete);put("deletionStarted",deletionStarted);put("cleanupVerified",cleanup);put("originalReportUnchanged",sha(original.readBytes())==reportSha)
            put("actualRunStatus",runStatus);put("verifiedTools",JsonArray(tools.map(::JsonPrimitive)));put("observedEffect",effect)
            put("runBinding",runBinding?:JsonNull)
            put("keepUnchanged",keepUnchanged);put("retainedTerminalLedger",retainedTerminalLedger)
            put("modelInvoked",false);put("pcTransportInvoked",false);put("otherOwnerConversationRead",false);put("originalFailureRegraded",false)
        }.toString())}
        try{
            emit();authority();quiet()
            phone=RuntimeGate.compute.tryAcquire(recoveryOwner,ComputeResource.PHONE)?:error("QA_RECOVERY_PHONE_BUSY")
            pc=RuntimeGate.compute.tryAcquire(recoveryOwner,ComputeResource.PC)?:error("QA_RECOVERY_PC_BUSY")
            update=app.updates.acquireBusy(recoveryOwner);authority();quiet()
            val project=chat.project(projectId);val room=chat.thread(threadId);val run=chat.run(runId)
            if(run!=null){
                runStatus=run.status.takeIf{it in setOf("queued","running","waiting_approval","completed","paused","cancelled","failed","interrupted")}?:"UNKNOWN"
                runBinding=buildJsonObject{
                    put("project",run.projectId==projectId);put("thread",run.threadId==threadId);put("branch",run.branchId==branchId)
                    put("model",run.modelId==PairedPcTextProfile.SELECTION_ID);put("storedContextSize",run.contextSize)
                    put("threadRevisionZero",run.threadRevision==0L);put("projectRevisionZero",run.projectRevision==0L)
                    put("contextEmpty",run.contextJson.isBlank());put("projectPresent",project!=null);put("threadPresent",room!=null)
                };emit()
                // Enqueue persists4096; the project's8192 and actual withPcContext receipt are separate.
                verify("QA_RECOVERY_RUN_BINDING",run.projectId==projectId&&run.threadId==threadId&&run.branchId==branchId&&run.modelId==PairedPcTextProfile.SELECTION_ID&&run.contextSize==4096&&run.threadRevision==0L&&run.projectRevision==0L&&run.status in setOf("completed","paused","cancelled","failed","interrupted"))
                runStatus=run.status;retainedTerminalLedger=project==null&&room==null
                if(run.contextJson.isBlank()){
                    verify("QA_RECOVERY_ERASED_LEDGER",project==null&&room==null&&run.status=="cancelled")
                }else{
                val request=QueuedChatRequest.fromJson(run.contextJson)
                verify("QA_RECOVERY_REQUEST",request.runId==runId&&request.projectId==projectId&&request.threadId==threadId&&request.branchId==branchId&&request.text==owner&&
                    hash(request.text)==s(loc,"ownerRequestSha256")&&request.allowTools&&!request.allowCloudText&&request.capturedPcRevision==revision&&request.mode=="act"&&!request.thinking&&
                    request.modelId==PairedPcTextProfile.SELECTION_ID&&request.userMessageId==run.userMessageId&&request.attachments.isEmpty()&&request.goal==null&&request.workspaceBinding==s(loc,"workspaceBinding"))
                tools=request.previousSteps.filter{it.evidence.state==VerificationState.VERIFIED}.map{it.tool}
                verify("QA_RECOVERY_TOOLS",request.previousSteps.all{it.tool in setOf("search_notes","read_note","patch_note")&&it.evidence.state !in setOf(VerificationState.PREPARED,VerificationState.UNVERIFIED)})
                }
            }
            if(project==null&&room==null){
                verify("QA_RECOVERY_ALREADY_ABSENT",app.repository.getNote(seedId)==null&&app.repository.getNote(keepId)==null&&!root.exists()&&prefsAbsent(prefsName)&&prefsAbsent(workspacePrefsName))
                cleanup=true;passed=true;outcome="ALREADY_ABSENT";stage="complete";emit();return@runBlocking
            }
            val p=checkNotNull(project);val r=checkNotNull(room);val originalRun=checkNotNull(run)
            val allowed=setOf("search_notes","read_note","patch_note")
            verify("QA_RECOVERY_PROJECT",p.id==projectId&&p.title==prefix&&p.workspaceKey==projectId&&p.revision==0L&&!p.archived&&p.settings==ProjectSettings(modelId=PairedPcTextProfile.SELECTION_ID,contextSize=8192,useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed))
            verify("QA_RECOVERY_THREAD",r.projectId==projectId&&r.activeBranchId==branchId&&r.title==prefix&&r.revision==0L&&!r.archived&&!r.allowOwnerLearning&&r.deletedAt==null&&app.runs.selectedProjectId.value!=projectId)
            val roster=chat.observeThreads(projectId,includeArchived=true).first();val branches=chat.branches(threadId);val runs=chat.runsForThread(threadId)
            verify("QA_RECOVERY_ROSTER",roster==listOf(r)&&branches.size==1&&branches.single().id==branchId&&runs==listOf(originalRun))
            val messages=chat.messages(threadId,branchId).first()
            verify("QA_RECOVERY_MESSAGES",messages.size in 1..16&&messages.count{it.role=="user"}==1&&messages.all{it.threadId==threadId&&it.branchId==branchId&&
                (it.role=="user"&&it.id==originalRun.userMessageId&&chat.fullMessageText(it.id)==owner||it.role=="assistant"&&it.runId==runId)})
            val messageHashes=messages.associate{it.id to hash(chat.fullMessageText(it.id))}
            val scoped=app.repository.forThread(threadId);val access=chat.accessScope(threadId)
            verify("QA_RECOVERY_SCOPE",access.projectId==projectId&&access.scopeKey=="project:$projectId"&&!access.useOwnerMemory&&access.threadRevision==0L)
            val notes=scoped.notes.first();val seed=notes.singleOrNull{it.id==seedId};val keep=notes.singleOrNull{it.id==keepId}
            verify("QA_RECOVERY_NOTES",notes.map{it.id}.toSet()==setOf(seedId,keepId)&&seed!=null&&keep!=null)
            val target=checkNotNull(seed);val preserved=checkNotNull(keep)
            verify("QA_RECOVERY_BODIES",target.title=="$prefix-target"&&target.collection==collection&&
                (target.body==before&&target.updatedAt==seedRevision||target.body==after&&target.updatedAt>seedRevision)&&
                preserved.title=="$prefix-keep"&&preserved.collection==collection&&preserved.body==keepBody&&hash(preserved.body)==s(loc,"keepSha256"))
            effect=if(target.body==after)"EXACT_EXPECTED_AFTER"else"UNCHANGED_BEFORE";keepUnchanged=true
            verify("QA_RECOVERY_REMINDERS",scoped.listReminders().isEmpty()&&app.repository.reminderIdsForProjectRemoval(projectId).isEmpty())
            verify("QA_RECOVERY_PRIVATE_FILES",root.isDirectory&&root.listFiles()?.isEmpty()==true&&workspacePrefs.all.isEmpty())
            val workspace=app.workspaceFor(projectId)
            verify("QA_RECOVERY_WORKSPACE",!workspace.state.value.external&&workspace.list().isEmpty()&&workspace.bindingFingerprint()==s(loc,"workspaceBinding"))
            val prefSnapshot=prefs.all.toMap()
            val expectedPreferences=buildMap<String,Any>{put("thread",threadId);if(direct)put("direct-next-action",true)}
            verify("QA_RECOVERY_PREFS",prefSnapshot.isEmpty()||prefSnapshot==expectedPreferences)
            if(!delete){passed=true;outcome="AUDIT_ONLY_MATCH";stage="complete";emit();return@runBlocking}
            withContext(NonCancellable){
                authority();quiet();verify("QA_RECOVERY_RECHECK",sha(original.readBytes())==reportSha&&chat.project(projectId)==p&&chat.thread(threadId)==r&&chat.run(runId)==originalRun&&
                    chat.observeThreads(projectId,true).first()==roster&&chat.branches(threadId)==branches&&chat.runsForThread(threadId)==runs&&chat.messages(threadId,branchId).first()==messages&&
                    messages.all{hash(chat.fullMessageText(it.id))==messageHashes[it.id]}&&scoped.notes.first()==notes&&chat.accessScope(threadId)==access&&
                    root.listFiles()?.isEmpty()==true&&!workspace.state.value.external&&workspace.bindingFingerprint()==s(loc,"workspaceBinding")&&prefs.all==prefSnapshot&&workspacePrefs.all.isEmpty())
                deletionStarted=true;stage="deleting_exact_fixture";emit()
                notes.forEach{scoped.deleteNote(it.id,expectedUpdatedAt=it.updatedAt)}
                verify("QA_RECOVERY_NOTES_GONE",scoped.notes.first().isEmpty())
                chat.deleteProject(projectId,true);workspace.discard()
                verify("QA_RECOVERY_PREF_CLEAR",prefs.edit().clear().commit()&&prefs.all.isEmpty())
                base.deleteSharedPreferences(prefsName);base.deleteSharedPreferences(workspacePrefsName)
                verify("QA_RECOVERY_ABSENCE",chat.project(projectId)==null&&chat.thread(threadId)==null&&scoped.notes.first().isEmpty()&&!root.exists()&&prefsAbsent(prefsName)&&prefsAbsent(workspacePrefsName)&&sha(original.readBytes())==reportSha)
                retainedTerminalLedger=chat.run(runId)!=null;cleanup=true;passed=true;outcome="RECOVERED_EXACT_FIXTURE";stage="complete";emit()
            }
        }catch(error:Throwable){primaryFailure=error;outcome="FAILED_CLOSED";passed=false;runCatching{emit()}.exceptionOrNull()?.let{error.addSuppressed(it)};throw error}
        finally{withContext(NonCancellable){var release:Throwable?=null;listOf(update,pc,phone).forEach{lease->try{lease?.close()}catch(error:Throwable){if(release==null)release=error else release?.addSuppressed(error)}}
            release?.let{error->passed=false;outcome="FAILED_RELEASE";stage="release_owned_leases";runCatching{emit()}.exceptionOrNull()?.let{error.addSuppressed(it)}
                val prior=primaryFailure;if(prior!=null)prior.addSuppressed(error)else throw error}
        }}
    }
}
