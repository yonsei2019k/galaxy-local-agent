package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.ComputeResource
import com.user.localagent.compute.ComputeResourceBroker
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.InferencePrerequisiteReason
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production Coordinator/isolated encrypted Room, explicit null-choice negative seam only.
 * No production selector/paired-PC readiness, actual model, network or native inference proof. */
@RunWith(AndroidJUnit4::class)
class InferencePrerequisiteDeviceValidation {
    private val syntheticRequest="합성 준비 상태 검사입니다. 외부 자료나 작업을 사용하지 마세요."
    private enum class Case(val allowTools:Boolean) { TOOLS_NULL(true),READ_ONLY_NULL(false),SCOPE_CHANGED(true),CANCELLED(true) }
    private class IsolatedContext(base:Context,private val root:File,private val prefix:String):ContextWrapper(base) {
        private val preferences=java.util.Collections.synchronizedSet(mutableSetOf<String>())
        override fun getApplicationContext():Context=this
        private fun directory(name:String)=File(root,name).apply{check(isDirectory||mkdirs())}
        override fun getFilesDir():File=directory("files")
        override fun getCacheDir():File=directory("cache")
        override fun getNoBackupFilesDir():File=directory("no-backup")
        override fun getExternalFilesDir(type:String?):File=directory("external")
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            check(name.length in 1..512&&name.none{it=='/'||it=='\\'||it=='\u0000'})
            val scoped="$prefix-$name";preferences+=scoped
            return baseContext.getSharedPreferences(scoped,mode)
        }
        fun cleanPreferences(){synchronized(preferences){preferences.toList()}.forEach {
            check(it.startsWith("$prefix-"));baseContext.deleteSharedPreferences(it)
        }}
    }
    private class NoGenerationEngine:ComputeExecutionEngine {
        val loads=AtomicInteger();val counts=AtomicInteger();val generations=AtomicInteger();val unloads=AtomicInteger()
        override val state=MutableStateFlow("QA no inference")
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val computeLocation=MutableStateFlow<ComputeResource?>(null)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="synthetic-no-compute"
        override val residentModelPath:String? get()=null
        override val supportsStructuredOutput=true
        override suspend fun load(path:String,options:InferenceOptions){loads.incrementAndGet();error("QA_LOAD_FORBIDDEN")}
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int {
            counts.incrementAndGet();error("QA_COUNT_FORBIDDEN")
        }
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int {
            counts.incrementAndGet();error("QA_COUNT_FORBIDDEN")
        }
        override fun generate(prompt:String):Flow<String>{generations.incrementAndGet();error("QA_GENERATION_FORBIDDEN")}
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            generations.incrementAndGet();error("QA_GENERATION_FORBIDDEN")
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            generations.incrementAndGet();error("QA_GENERATION_FORBIDDEN")
        }
        override fun cancel()=Unit
        override suspend fun unload(){unloads.incrementAndGet()}
    }

    private suspend fun fixture(case:Case) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("inferencePrerequisiteNativeApproved=true required",args.getString("inferencePrerequisiteNativeApproved")=="true")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=checkNotNull(args.getString("inferencePrerequisiteExpectedVersion")?.toLongOrNull())
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expected)
        // Initialize the real application before any isolated AppContainer can touch shared factories.
        val owner=(base.applicationContext as LocalAgentApplication).container
        val identity=UUID.randomUUID().toString();val prefix="qa-inference-prerequisite-$identity"
        suspend fun ownerQuiet(owned:ComputeResourceBroker.Lease?=null){
            // Before acquisition no native owner is allowed. Later accept only our actual PHONE handle.
            val nativeQuiet=if(owned==null)!RuntimeGate.inference.get()&&RuntimeGate.inference.owner.value==null
                else owned.resource==ComputeResource.PHONE&&owned.owner==prefix&&
                    RuntimeGate.inference.get()&&RuntimeGate.inference.owner.value==owned.owner
            check(!owner.runs.hasActiveRuns.value&&!owner.runs.busy.value&&nativeQuiet&&!RuntimeGate.benchmark.get()){"QA_OWNER_ACTIVE"}
            check(owner.repository.chat.queuedRuns().isEmpty()){"QA_OWNER_QUEUED"}
        }
        ownerQuiet()
        val parent=base.cacheDir.canonicalFile;val root=File(parent,prefix).canonicalFile
        check(root.parentFile==parent&&root.name==prefix&&!root.exists())
        val context=IsolatedContext(base,root,prefix)
        val databaseName="$prefix.db";val databaseFile=base.getDatabasePath(databaseName).canonicalFile
        check(databaseFile.name==databaseName&&databaseFile.parentFile==base.getDatabasePath("qa-inference-prerequisite-anchor.db").canonicalFile.parentFile)
        check(!databaseFile.exists())
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        val lease=RuntimeGate.compute.tryAcquire(prefix,ComputeResource.PHONE)?:run{key.fill(0);error("QA_OWNER_RESOURCE_BUSY")}
        var db:LocalDatabase?=null;var app:AppContainer?=null;var runner:ChatRunCoordinator?=null
        var project:ProjectRecord?=null;var room:ChatThreadRecord?=null;var runId:String?=null
        try {
            check(root.mkdir())
            ownerQuiet(lease);System.loadLibrary("sqlcipher")
            val database=Room.databaseBuilder(base,LocalDatabase::class.java,databaseName)
                .openHelperFactory(SupportOpenHelperFactory(key.clone()))
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build().also{db=it}
            val repository=LocalRepository(database)
            val container=AppContainer(context,repositoryOverride=repository).also{app=it}
            val settings=ProjectSettings(modelId="auto",contextSize=4096,useOwnerMemory=false,learnOwnerFacts=false,
                disabledTools=ToolRegistry.definitions.keys)
            val p=repository.chat.createProject(prefix,settings).also{project=it}
            check(p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
            val r=repository.chat.createThread(p.id,prefix).also{room=it}
            val engine=NoGenerationEngine();val choices=AtomicInteger();val factories=AtomicInteger()
            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(context,container,restorePersistedQueue=false,
                workerEngineFactory={request->
                    check(request.runId==runId)
                    check(request.projectId==p.id&&request.threadId==r.id&&request.branchId==r.activeBranchId)
                    check(factories.incrementAndGet()==1);engine
                },inferenceChoiceOverride={request->
                    // Deliberate negative seam: never a production PC choice or a model descriptor.
                    check(choices.incrementAndGet()==1)
                    check(request.runId==runId)
                    check(request.projectId==p.id&&request.threadId==r.id&&request.branchId==r.activeBranchId)
                    check(request.allowTools==case.allowTools&&!request.allowCloudText&&request.attachments.isEmpty())
                    ownerQuiet(lease)
                    when(case){
                        Case.SCOPE_CHANGED->{
                            repository.chat.updateProject(p.id,settings=settings.copy(contextSize=8192),expectedRevision=p.revision)
                            check(repository.chat.run(request.runId)?.status=="running")
                        }
                        Case.CANCELLED->{
                            // Production store transition in an isolated fixture, not a real stop timing claim.
                            repository.chat.updateRun(request.runId,"cancelled")
                        }
                        else->Unit
                    }
                    null
                },contactLookup={_,_->error("QA_CONTACTS_FORBIDDEN")},externalIntentLauncher={error("QA_EXTERNAL_INTENT_FORBIDDEN")})}.also{runner=it}
            val text=syntheticRequest
            val id=withContext(Dispatchers.Main){
                val message=repository.chat.addMessage(r.id,r.activeBranchId,"user",text)
                val run=repository.chat.createRun(r.id,r.activeBranchId,message.id,"auto",4096,false)
                val request=QueuedChatRequest(run.id,r.id,r.activeBranchId,p.id,text,"auto",false,emptyList(),message.id,
                    allowTools=case.allowTools,mode="act",workspaceBinding=container.workspaceFor(p.id).bindingFingerprint(),
                    capturedPcRevision=null,allowCloudText=false)
                repository.chat.updateRunContext(run.id,request.toJson())
                runId=run.id
                coordinator.queueMutation.withLock{coordinator.queued.value=coordinator.queued.value+request}
                coordinator.drain();run.id
            }
            withTimeout(15_000){
                while(true){
                    ownerQuiet(lease)
                    val status=repository.chat.run(id)?.status
                    if(status in setOf("paused","failed","cancelled","completed")&&coordinator.executionForRun(id)==null&&!coordinator.hasActiveRuns.value)break
                    delay(20)
                }
            }
            ownerQuiet(lease)
            val terminal=checkNotNull(repository.chat.run(id))
            val expectedStatus=when(case){Case.SCOPE_CHANGED->"failed";Case.CANCELLED->"cancelled";else->"paused"}
            assertEquals(expectedStatus,terminal.status);assertNotEquals("completed",terminal.status)
            if(case==Case.SCOPE_CHANGED){
                assertEquals(p.revision+1,repository.chat.project(p.id)?.revision)
                assertEquals(8192,repository.chat.project(p.id)?.settings?.contextSize)
            }
            if(case==Case.SCOPE_CHANGED)assertEquals("request_failed_before_finalization",terminal.errorCode)
            else if(case!=Case.CANCELLED)assertEquals(InferencePrerequisiteReason.MODEL_SELECTION_MISSING.code,terminal.errorCode)
            val captured=QueuedChatRequest.fromJson(terminal.contextJson)
            assertEquals(id,captured.runId);assertEquals(r.id,captured.threadId);assertEquals(r.activeBranchId,captured.branchId)
            assertEquals(p.id,captured.projectId);assertEquals(text,captured.text);assertEquals(case.allowTools,captured.allowTools)
            assertFalse(captured.allowCloudText);assertNull(captured.capturedPcRevision);assertTrue(captured.previousSteps.isEmpty())
            val messages=repository.chat.messages(r.id).first()
            assertEquals(text,repository.chat.fullMessageText(messages.single{it.role=="user"}.id))
            val answers=messages.filter{it.role=="assistant"}.map{repository.chat.fullMessageText(it.id)}
            if(case in setOf(Case.TOOLS_NULL,Case.READ_ONLY_NULL))assertEquals(listOf(InferencePrerequisiteReason.MODEL_SELECTION_MISSING.notice),answers)
            else assertTrue(answers.all{it.isBlank()})
            assertEquals(1,choices.get());assertEquals(1,factories.get())
            assertEquals(0,engine.loads.get());assertEquals(0,engine.counts.get());assertEquals(0,engine.generations.get())
            assertTrue(engine.unloads.get()>0)
            assertTrue(repository.forThread(r.id).notes.first().isEmpty())
            assertTrue(repository.reminderIdsForProjectRemoval(p.id).isEmpty())
        }finally{
            withContext(NonCancellable){
                try {
                    runner?.let { c->
                        val jobs=withContext(Dispatchers.Main){room?.let{r->c.runningJobForThread(r.id).also{c.cancelThread(r.id)}}}
                        withTimeout(15_000){jobs?.join();while(c.hasActiveRuns.value)delay(20)}
                        c.scope.cancel()
                    }
                    app?.cancelIdleUnload()
                    project?.let { p->
                        val repository=checkNotNull(app).repository
                        val current=checkNotNull(repository.chat.project(p.id))
                        check(current.title==prefix&&current.workspaceKey==p.id)
                        val r=checkNotNull(room)
                        check(repository.chat.threads.first().filter{it.projectId==p.id}.map{it.id}==listOf(r.id))
                        check(repository.chat.branches(r.id).map{it.id}==listOf(r.activeBranchId))
                        check(repository.chat.runsForThread(r.id).all{it.id==runId&&it.status !in setOf("queued","running","waiting_approval")})
                        check(repository.chat.messages(r.id).first().all{
                            it.role=="user"&&it.text==syntheticRequest||it.role=="assistant"&&it.runId==runId
                        })
                        val workspace=checkNotNull(app).workspaceFor(p.id)
                        check(!workspace.state.value.external&&workspace.list().isEmpty())
                        check(repository.forThread(r.id).notes.first().isEmpty())
                        check(repository.reminderIdsForProjectRemoval(p.id).isEmpty())
                        repository.chat.deleteProject(p.id,deleteDerivedMemory=true);workspace.discard()
                        assertNull(repository.chat.project(p.id));assertNull(repository.chat.thread(r.id))
                    }
                    db?.close();key.fill(0)
                    check(base.getDatabasePath(databaseName).canonicalFile==databaseFile)
                    if(databaseFile.exists())check(base.deleteDatabase(databaseName))
                    context.cleanPreferences()
                    check(root.canonicalFile==root&&root.parentFile==parent&&root.name==prefix)
                    if(root.exists())check(root.deleteRecursively());assertFalse(root.exists())
                }finally{
                    app?.cancelIdleUnload()
                    // Never delete files or a database while an unjoined QA job can still use them.
                    key.fill(0)
                    lease.close()
                }
            }
        }
    }

    @Test fun toolsAllowedNullChoicePausesBeforeAnyGeneration()=runBlocking(Dispatchers.Default){fixture(Case.TOOLS_NULL)}
    @Test fun readOnlyNullChoicePausesAtLaterSelectionWithoutGeneration()=runBlocking(Dispatchers.Default){fixture(Case.READ_ONLY_NULL)}
    @Test fun changedQaProjectRejectsNoticeAndClosesAsFailed()=runBlocking(Dispatchers.Default){fixture(Case.SCOPE_CHANGED)}
    @Test fun alreadyCancelledQaRunIsNotOverwrittenByMissingChoice()=runBlocking(Dispatchers.Default){fixture(Case.CANCELLED)}
}
