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
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real coordinator/encrypted Room, fake PC/phone backends. No actual PC/native/accuracy claim. */
@RunWith(AndroidJUnit4::class)
class ChatComputeConcurrencyValidation {
    private class IsolatedContext(base:Context,private val root:File,private val prefix:String):ContextWrapper(base) {
        private val preferences=java.util.Collections.synchronizedSet(mutableSetOf<String>())
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=File(root,"files").apply {check(isDirectory||mkdirs())}
        override fun getCacheDir():File=File(root,"cache").apply {check(isDirectory||mkdirs())}
        override fun getExternalFilesDir(type:String?):File=File(root,"external").apply {check(isDirectory||mkdirs())}
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val scoped="$prefix-$name";preferences+=scoped
            return baseContext.getSharedPreferences(scoped,mode)
        }
        fun cleanPreferences(){preferences.toList().forEach {baseContext.deleteSharedPreferences(it)}}
    }
    private class FakeEngine(val route:ComputeResource,val text:String,private val awaitRelease:Boolean,
        private val proposal:Boolean=false,private val autonomous:Boolean=false,private val reviewAlways:Boolean=false):ComputeExecutionEngine {
        override val state=MutableStateFlow("synthetic idle")
        override val computeLocation=MutableStateFlow<ComputeResource?>(null)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation:String get()=if(route==ComputeResource.PC)"pc"else"phone"
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        override val residentModelPath:String? get()=null
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val cancellations=AtomicInteger();val generations=AtomicInteger()
        val prompts=java.util.Collections.synchronizedList(mutableListOf<String>())
        private val reviews=AtomicInteger()
        private var options=InferenceOptions()
        override suspend fun load(path:String,options:InferenceOptions){this.options=options;computeLocation.value=route;state.value="synthetic ${route.name}"}
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int=8
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int=8
        override fun generate(prompt:String):Flow<String> = error("Use typed synthetic events")
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> = flow {
            val call=generations.incrementAndGet();prompts+=userText
            state.value="synthetic generation";started.complete(Unit)
            if(awaitRelease&&call==1)release.await()
            currentCoroutineContext().ensureActive()
            val output=if(autonomous)when(call){
                1->"""{"action":"TOOL_TASK","goal":"17 곱하기 19 계산과 다른 식을 사용한 독립 검산","targetType":"GENERAL","requiredTools":["calculate"],"constraints":["다른 식으로 검산 후 최종 결론"]}"""
                2->"""{"type":"tool_call","tool":"calculate","arguments":{"expression":"17*19"}}"""
                else->"17×19=323이며 17×20−17=323으로 독립 검산했습니다."
            }else if(proposal)when(call){
                1->buildJsonObject {
                    put("action","TOOL_TASK");put("goal","승인한 합성 메모 한 개 저장");put("targetType","GENERAL")
                    put("requiredTools",JsonArray(listOf(JsonPrimitive("save_note"))))
                }.toString()
                2->buildJsonObject {
                    put("type","tool_call");put("tool","save_note");put("arguments",buildJsonObject {put("title",text);put("content",text);put("scope","default")})
                }.toString()
                else->"합성 메모를 저장하고 다시 확인했습니다: $text"
            }else text
            emit(GenerationEvent.Delta(output))
            val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,
                options.maxOutputTokens,prefillTokens=8,decodeTokens=8,visibleChars=output.length)
            lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> =
            if(autonomous&&schema.contains("review_complete"))flow {
                generations.incrementAndGet();prompts+=userText
                val reviewNumber=reviews.incrementAndGet()
                val output=if(reviewNumber==1||reviewAlways)
                    """{"type":"review_next","summary":"다른 식의 검산이 필요합니다.","call":{"type":"tool_call","tool":"calculate","arguments":{"expression":"${if(reviewNumber==1)"17*20-17"else"17*18+17"}"}}}"""
                    else """{"type":"review_complete","summary":"두 식의 실제 결과323을 확인했습니다."}"""
                emit(GenerationEvent.Delta(output))
                val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,
                    options.maxOutputTokens,prefillTokens=8,decodeTokens=8,visibleChars=output.length)
                lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
            }else if(proposal&&schema.contains("review_complete"))flow {
                generations.incrementAndGet();prompts+=userText
                val output="""{"type":"review_complete","summary":"합성 도구의 실제 저장 근거를 확인했습니다."}"""
                emit(GenerationEvent.Delta(output))
                val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,
                    options.maxOutputTokens,prefillTokens=8,decodeTokens=8,visibleChars=output.length)
                lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
            }else generateChatEvents(userText,systemInstruction,history)
        override fun cancel(){cancellations.incrementAndGet()}
        override suspend fun unload(){computeLocation.value=null;state.value="synthetic unloaded"}
    }
    private data class Fixture(val context:Context,val app:AppContainer,val chat:ChatRunCoordinator,
        val roomA:ChatThreadRecord,val roomB:ChatThreadRecord,val engines:ConcurrentHashMap<String,FakeEngine>)

    private suspend fun fixture(proposals:Boolean=false,block:suspend(Fixture)->Unit){
        assumeTrue("computeConcurrencyValidationApproved=true required",InstrumentationRegistry.getArguments().getString("computeConcurrencyValidationApproved")=="true")
        var benchmark=false
        try {
            benchmark=RuntimeGate.benchmark.compareAndSet(false,true)
            check(benchmark&&!RuntimeGate.inference.get()){"An actual runtime task owns the phone"}
            isolatedFixture(proposals,block=block)
        }finally{if(benchmark)RuntimeGate.benchmark.set(false)}
    }

    private suspend fun isolatedFixture(proposals:Boolean,autonomous:Boolean=false,reviewAlways:Boolean=false,block:suspend(Fixture)->Unit){
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val identity=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"compute-concurrency-qa-$identity").canonicalFile
        check(root.parentFile==base.cacheDir.canonicalFile&&(root.isDirectory||root.mkdirs()))
        val isolated=IsolatedContext(base,root,"compute-qa-$identity")
        val name="compute-concurrency-qa-$identity.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(base,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var coordinator:ChatRunCoordinator?=null
        var app:AppContainer?=null
        val createdThreads=mutableListOf<String>()
        try {
            val repository=LocalRepository(db)
            val container=AppContainer(isolated,repositoryOverride=repository).also {app=it}
            val settings=ProjectSettings(modelId="synthetic-no-weights",memoryMode="project",useOwnerMemory=false,learnOwnerFacts=false,
                disabledTools=ToolRegistry.definitions.keys-(if(autonomous)setOf("calculate","list_skills","read_skill")else setOf("save_note")))
            val projectA=repository.chat.createProject("SYNTHETIC_PROJECT_A",settings)
            val projectB=repository.chat.createProject("SYNTHETIC_PROJECT_B",settings)
            val roomA=repository.chat.createThread(projectA.id,"SYNTHETIC_ROOM_A")
            val roomB=repository.chat.createThread(projectB.id,"SYNTHETIC_ROOM_B")
            createdThreads+=listOf(roomA.id,roomB.id)
            val engines=ConcurrentHashMap<String,FakeEngine>()
            val model=InstalledModel("synthetic-no-weights","Synthetic backend, not a real model","/no-model-file","0".repeat(64),true,"gguf")
            val choice=ChosenInference(model,InferenceOptions(maxOutputTokens=128),"synthetic fixture")
            val chat=withContext(Dispatchers.Main){ChatRunCoordinator(isolated,container,
                workerEngineFactory={request->
                    val route=if(request.threadId==roomA.id)ComputeResource.PC else ComputeResource.PHONE
                    FakeEngine(route,"SYNTHETIC_${route.name}_${request.userMessageId}",awaitRelease=!proposals,proposal=proposals,autonomous=autonomous,reviewAlways=reviewAlways)
                        .also {engines[request.runId]=it}
                },restorePersistedQueue=false,inferenceChoiceOverride={choice})}.also {coordinator=it}
            block(Fixture(isolated,container,chat,roomA,roomB,engines))
        }finally{
            withContext(NonCancellable){
                coordinator?.let {chat->
                    val jobs=withContext(Dispatchers.Main){createdThreads.mapNotNull(chat::runningJobForThread).also {createdThreads.forEach(chat::cancelThread)}}
                    withTimeout(10_000){jobs.forEach {it.join()};while(chat.hasActiveRuns.value)delay(20)}
                    chat.scope.cancel()
                }
                app?.cancelIdleUnload()
                db.close();key.fill(0);base.deleteDatabase(name)
                isolated.cleanPreferences()
                check(root.parentFile==base.cacheDir.canonicalFile&&root.name=="compute-concurrency-qa-$identity")
                root.deleteRecursively()
            }
        }
    }
    private suspend fun enqueue(f:Fixture,room:ChatThreadRecord,text:String,allowTools:Boolean):String = withContext(Dispatchers.Main) {
        val message=f.app.repository.chat.addMessage(room.id,room.activeBranchId,"user",text)
        val run=f.app.repository.chat.createRun(room.id,room.activeBranchId,message.id,"synthetic-no-weights",4096,false)
        val request=QueuedChatRequest(run.id,room.id,room.activeBranchId,room.projectId,text,"synthetic-no-weights",false,emptyList(),message.id,
            allowTools=allowTools,mode="act",workspaceBinding=f.app.workspaceFor(room.projectId).bindingFingerprint())
        f.app.repository.chat.updateRunContext(run.id,request.toJson())
        f.chat.queueMutation.withLock {f.chat.queued.value=f.chat.queued.value+request}
        f.chat.drain();run.id
    }
    private suspend fun awaitCondition(condition:suspend()->Boolean){withTimeout(20_000){while(!condition())delay(10)}}

    @Test fun differentRoomsOverlapButSameRoomWaitsAndStoppingPhoneKeepsPcRunAlive()=runBlocking(Dispatchers.IO){fixture {f->
        val a=enqueue(f,f.roomA,"SYNTHETIC_OWNER_A_ONLY",false)
        awaitCondition {f.engines[a]?.started?.isCompleted==true}
        val b=enqueue(f,f.roomB,"SYNTHETIC_OWNER_B_ONLY",false)
        awaitCondition {f.engines[b]?.started?.isCompleted==true}
        assertEquals(setOf(ComputeResource.PC,ComputeResource.PHONE),f.engines.values.mapNotNull {it.computeLocation.value}.toSet())
        val aLater=enqueue(f,f.roomA,"SYNTHETIC_OWNER_A_LATER",false)
        assertFalse(f.engines.containsKey(aLater))
        withContext(Dispatchers.Main){f.chat.stopRun(b,f.roomB.id)}
        awaitCondition {f.app.repository.chat.run(b)?.status !in setOf("running","queued")}
        assertTrue(f.chat.isRunLive(a));assertEquals(0,f.engines.getValue(a).cancellations.get())
        assertFalse(f.engines.containsKey(aLater))
        f.engines.getValue(a).release.complete(Unit)
        awaitCondition {f.engines[aLater]?.started?.isCompleted==true}
        f.engines.getValue(aLater).release.complete(Unit)
        awaitCondition {!f.chat.hasActiveRuns.value}
        assertEquals("completed",f.app.repository.chat.run(a)?.status)
        assertEquals("completed",f.app.repository.chat.run(aLater)?.status)
        assertEquals("cancelled",f.app.repository.chat.run(b)?.status)
        for(id in listOf(a,b,aLater))assertFalse(QueuedChatRequest.fromJson(checkNotNull(f.app.repository.chat.run(id)).contextJson).allowTools)
        val aText=f.app.repository.chat.messages(f.roomA.id,f.roomA.activeBranchId).first().joinToString("\n"){it.text}
        val bText=f.app.repository.chat.messages(f.roomB.id,f.roomB.activeBranchId).first().joinToString("\n"){it.text}
        assertTrue(aText.contains(f.engines.getValue(a).text));assertFalse(aText.contains("SYNTHETIC_OWNER_B_ONLY"))
        assertFalse(bText.contains("SYNTHETIC_OWNER_A_ONLY"));assertFalse(bText.contains(f.engines.getValue(a).text))
    }}

    @Test fun scriptedNextActionRunsIndependentCalculationAndFinalReview()=runBlocking(Dispatchers.IO){
        scriptedAutonomousRun(false)
    }
    @Test fun reviewLimitStillWritesVerifiedResultsWithoutAdditionalActions()=runBlocking(Dispatchers.IO){
        scriptedAutonomousRun(true)
    }
    private suspend fun scriptedAutonomousRun(reviewAlways:Boolean){
        assumeTrue("computeConcurrencyValidationApproved=true required",InstrumentationRegistry.getArguments().getString("computeConcurrencyValidationApproved")=="true")
        val owns=RuntimeGate.benchmark.compareAndSet(false,true)
        try {
            check(owns&&!RuntimeGate.inference.get()){"An actual runtime task owns the phone"}
            isolatedFixture(proposals=true,autonomous=true,reviewAlways=reviewAlways){f->
                val request="계산 도구로17 곱하기19를 구하고 다른 식으로 독립 검산한 뒤 최종 결론을 알려줘."
                val id=enqueue(f,f.roomA,request,true)
                awaitCondition{f.app.repository.chat.run(id)?.status in ChatStore.TERMINAL||f.app.repository.chat.run(id)?.status=="paused"}
                awaitCondition{!f.chat.hasActiveRuns.value}
                val rows=f.app.repository.chat.toolRunsForThread(f.roomA.id,id).first()
                val primary=f.app.repository.chat.assistantForRun(id)
                val body=primary?.let{f.app.repository.chat.fullMessageText(it.id)}.orEmpty()
                val calculations=rows.count{it.name=="calculate"&&it.success&&it.summary.startsWith("VERIFIED")}
                assertEquals("Scripted host flow ended with: $body",if(reviewAlways)3 else 2,calculations)
                assertEquals("completed",f.app.repository.chat.run(id)?.status)
                val events=f.app.repository.workTrace.events(f.roomA.id,id)
                assertTrue(events.any{it.event.code==WorkTraceCode.PLAN_READY&&it.event.publicSummary?.startsWith("발견한 확인 과제:")==true})
                if(reviewAlways){
                    assertTrue(body.contains(com.user.localagent.core.AutonomousReviewPolicy.LIMITED_FINAL_NOTICE))
                    val context=Json.parseToJsonElement(checkNotNull(f.app.repository.chat.run(id)).contextJson).jsonObject
                    assertEquals(true,context["reviewBudgetFinalization"]?.jsonPrimitive?.booleanOrNull)
                    assertEquals(5,f.engines.getValue(id).generations.get())
                }else assertTrue(events.any{it.event.code==WorkTraceCode.PLAN_READY&&it.event.publicSummary?.startsWith("후속 검토 요약:")==true})
                assertTrue(body.contains("323"))
                assertEquals(listOf(request),f.app.repository.chat.messages(f.roomA.id).first().filter{it.role=="user"}.map{it.text})
            }
        }finally{if(owns)RuntimeGate.benchmark.set(false)}
    }

    @Test fun concurrentApprovalsStayBoundToTheirOriginalRoomAndProject()=runBlocking(Dispatchers.IO){fixture(proposals=true){f->
        val a=enqueue(f,f.roomA,"합성 업무 문구 A를 검토 후 메모에 정리해주세요.",true)
        val b=enqueue(f,f.roomB,"합성 업무 문구 B를 검토 후 메모에 정리해주세요.",true)
        awaitCondition {f.chat.pendingActions.value.size==2}
        val actionA=f.chat.pendingActions.value.single {it.runId==a}
        val actionB=f.chat.pendingActions.value.single {it.runId==b}
        assertEquals(f.roomA.id,actionA.threadId);assertEquals(f.roomA.projectId,actionA.projectId)
        assertEquals(f.roomB.id,actionB.threadId);assertEquals(f.roomB.projectId,actionB.projectId)
        withContext(Dispatchers.Main){f.chat.selectThread(f.roomB.id)}
        awaitCondition {f.chat.selectionReady.value&&f.chat.selectedThreadId.value==f.roomB.id}
        assertSame(actionB,f.chat.approval.value)
        actionA.decision.complete(true)
        actionB.decision.complete(false)
        awaitCondition {!f.chat.hasActiveRuns.value}
        val aNotes=f.app.repository.forThread(f.roomA.id).notes.first()
        val bNotes=f.app.repository.forThread(f.roomB.id).notes.first()
        assertTrue(aNotes.any {it.body==f.engines.getValue(a).text})
        assertFalse(aNotes.any {it.body==f.engines.getValue(b).text})
        assertTrue(bNotes.isEmpty())
        assertEquals("completed",f.app.repository.chat.run(a)?.status)
        assertEquals("cancelled",f.app.repository.chat.run(b)?.status)
    }}
}
