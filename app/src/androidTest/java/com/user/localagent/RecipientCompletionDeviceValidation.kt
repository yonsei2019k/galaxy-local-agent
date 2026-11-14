package com.user.localagent

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.ComputeResource
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android coordinator/storage. Scripted inference and actual paired-PC inference are separate tests. */
@RunWith(AndroidJUnit4::class)
class RecipientCompletionDeviceValidation {
    /** Observe original runtime errors without replacing the production engine or recording prompts. */
    private class RecordingEngine(private val delegate:ComputeExecutionEngine,private val diagnostic:File):ComputeExecutionEngine by delegate {
        private fun record(stage:String,error:Throwable){
            diagnostic.parentFile?.mkdirs()
            diagnostic.writeText(buildJsonObject{put("stage",stage);put("class",error.javaClass.simpleName);put("message",error.message);put("causes",JsonArray(generateSequence(error){it.cause}.take(4).map{JsonPrimitive(it.javaClass.simpleName)}.toList()))}.toString())
        }
        override suspend fun load(path:String,options:InferenceOptions){try{delegate.load(path,options)}catch(error:Throwable){record("load",error);throw error}}
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int?=try{delegate.countChatTokens(userText,systemInstruction,history)}catch(error:Throwable){record("count",error);throw error}
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int?=try{delegate.countStructuredChatTokens(userText,systemInstruction,schema,history)}catch(error:Throwable){record("count_structured",error);throw error}
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=delegate.generateChatEvents(userText,systemInstruction,history).catch{record("generate",it);throw it}
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=delegate.generateStructuredEvents(userText,systemInstruction,schema,history).catch{record("generate_structured",it);throw it}
    }
    private class ScriptEngine(private val kind:String,private val name:String,private val title:String,private val owner:String):ComputeExecutionEngine {
        override val state=MutableStateFlow("scripted QA")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        override val computeLocation=MutableStateFlow<ComputeResource?>(ComputeResource.PC)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="pc" // Emulated route only; the report marks scripted inference explicitly.
        private var turns=0
        override suspend fun load(path:String,options:InferenceOptions){}
        override suspend fun unload(){}
        override fun cancel(){}
        override fun generate(prompt:String):Flow<String> =error("typed events only")
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=1
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=events(next())
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            val tools=when(kind){"ambiguous","cancel"->listOf("search_contacts","prepare_sms");"denied"->listOf("search_contacts");else->listOf("save_note")}
            return events(when {
                schema.contains("targetType")&&schema.contains("goal")->buildJsonObject{put("action","TOOL_TASK");put("goal","요청한 QA 작업 실행");put("targetType","PERSONAL_RECORD");put("requiredTools",JsonArray(tools.map(::JsonPrimitive)))}.toString()
                schema.contains("review_complete")->"""{"type":"review_complete","summary":"실제 결과를 확인했습니다."}"""
                else->next()
            })
        }
        private fun next():String {
            val step=++turns
            fun call(tool:String,args:JsonObject)=buildJsonObject{put("type","tool_call");put("tool",tool);put("arguments",args)}.toString()
            return when {
                kind=="no_execution"->"메모 저장했습니다. QA_FALSE_COMPLETION"
                kind in setOf("ambiguous","cancel","denied")&&step==1->call("search_contacts",buildJsonObject{put("query",name);put("limit",1)})
                kind in setOf("ambiguous","cancel")->call("prepare_sms",buildJsonObject{put("phoneNumber","00000000001");put("message","QA 테스트 초안. 보내지 마세요.")})
                kind=="denied"->"연락처를 조회했습니다. QA_FALSE_COMPLETION"
                step==1->call("save_note",buildJsonObject{put("title",title);put("scope","__qa_recipient__");put("contentQuoteId",OwnerTextSources.quotes(owner).first().id)})
                else->"메모에 17 번으로 저장했습니다. QA_FALSE_COMPLETION"
            }
        }
        private fun events(output:String)=flow {
            emit(GenerationEvent.Delta(output))
            val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,512,visibleChars=output.length)
            lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
        }
    }

    @Test fun repeatedScriptedMisbehaviorIsStoppedOnPhone()=runBlocking(Dispatchers.Default){
        assumeTrue(InstrumentationRegistry.getArguments().getString("recipientValidationApproved")=="true")
        runCases(false,listOf("ambiguous","cancel","no_execution","saved","denied"))
    }
    @Test fun repeatedActualPcModelWorkflowsThroughPhone()=runBlocking(Dispatchers.Default){
        assumeTrue(InstrumentationRegistry.getArguments().getString("recipientRealModelApproved")=="true")
        runCases(true,listOf("ambiguous","explicit_sms","contacts_only","saved","time"))
    }

    private suspend fun runCases(real:Boolean,kinds:List<String>){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&app.repository.chat.queuedRuns().isEmpty()){"Owner work is active"}
        val repeats=InstrumentationRegistry.getArguments().getString("recipientRepeats")?.toIntOrNull()?:2
        require(repeats in 1..3)
        val token=UUID.randomUUID().toString()
        val name="대상검증"+token.take(6)
        val report=File(context.getExternalFilesDir(null),"diagnostics/recipient-completion-${if(real)"real"else"scripted"}.json")
        val rows=mutableListOf<JsonObject>();val rawIds=mutableListOf<Long>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
        val contacts=listOf(ContactCandidate(name,"00000000001"),ContactCandidate(name,"00000000002"))
        try {
            if(real)contacts.forEach{candidate->
                val resolver=context.contentResolver
                val raw=checkNotNull(resolver.insert(ContactsContract.RawContacts.CONTENT_URI,ContentValues().apply{putNull(ContactsContract.RawContacts.ACCOUNT_NAME);putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)}))
                val id=ContentUris.parseId(raw);rawIds+=id
                resolver.insert(ContactsContract.Data.CONTENT_URI,ContentValues().apply{put(ContactsContract.Data.RAW_CONTACT_ID,id);put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,candidate.name)})
                resolver.insert(ContactsContract.Data.CONTENT_URI,ContentValues().apply{put(ContactsContract.Data.RAW_CONTACT_ID,id);put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);put(ContactsContract.CommonDataKinds.Phone.NUMBER,candidate.phoneNumber);put(ContactsContract.CommonDataKinds.Phone.TYPE,ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)})
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity() // Inference/network/Keystore run with the ordinary app identity.
            repeat(repeats){round->kinds.forEach{kind->
                val row=try{runCase(app,name,contacts,real,kind,round,token)}catch(error:Throwable){buildJsonObject{put("kind",kind);put("round",round);put("passed",false);put("error",error.javaClass.simpleName);put("message",error.message.orEmpty().take(1200))}}
                rows+=row;report.parentFile?.mkdirs();report.writeText(buildJsonObject{put("actualModel",real);put("rows",JsonArray(rows))}.toString())
            }}
        }finally {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_CONTACTS")
            try {
            rawIds.forEach{id->
                val names=mutableListOf<String>()
                context.contentResolver.query(ContactsContract.Data.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),"${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",arrayOf(id.toString(),ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),null)?.use{cursor->while(cursor.moveToNext())names+=cursor.getString(0)}
                check(names.all{it==name}){"QA contact changed; preserving it"}
                context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,id),null,null)
            }
            }finally{instrumentation.uiAutomation.dropShellPermissionIdentity()}
        }
        assertEquals("One or more cases failed; see private diagnostic report",0,rows.count{it["passed"]?.jsonPrimitive?.booleanOrNull!=true})
    }

    private suspend fun runCase(app:AppContainer,name:String,contacts:List<ContactCandidate>,real:Boolean,kind:String,round:Int,token:String):JsonObject {
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"Owner work became active"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=app.repository;val store=repo.chat
        val title="qa-recipient-$token-$round-$kind"
        val payload="B2 기둥 17번\n두  칸 😀"
        val owner=when(kind){
            "ambiguous","cancel"->"연락처에서 $name 번호를 찾아 'QA 테스트 초안. 보내지 마세요.'라는 문자 초안을 준비해줘. 발송은 하지 마."
            "explicit_sms"->"00000000002 번호로 'QA 테스트 초안. 보내지 마세요.'라는 문자 초안을 준비해줘. 발송은 하지 마."
            "contacts_only"->"연락처에서 $name 이름의 연락처 목록만 보여줘. 여러 명이어도 목록만 알려줘. 문자 초안을 준비하거나 발송하지 마."
            "denied"->"연락처에서 $name 번호를 확인해줘. 접근이 안 되면 조회했다고 하지 마."
            "time"->"현재 날짜와 시각을 도구로 실제 확인해서 알려줘. 확인하지 않았으면 확인했다고 하지 마."
            else->"메모에 '$payload'를 공백과 줄바꿈까지 그대로 저장해줘. 제목은 '$title'이야."
        }
        val allowed=when(kind){"ambiguous","cancel","contacts_only"->setOf("search_contacts","prepare_sms");"explicit_sms"->setOf("prepare_sms");"denied"->setOf("search_contacts");"time"->setOf("get_current_datetime");else->setOf("save_note")}
        val project=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed))
        val room=store.createThread(project.id,title)
        val launches=mutableListOf<Intent>()
        var coordinator:ChatRunCoordinator?=null
        var pcSeen=false;var selectionSeen=false;var runId:String?=null
        val started=System.currentTimeMillis()
        try {
            app.models.refresh()
            val model=if(real)checkNotNull(app.models.models.value.firstOrNull{it.id=="gemma-e2b"&&it.verified}?:app.models.models.value.firstOrNull{it.verified&&!it.id.contains("mmproj")&&it.runtime in setOf("gguf","litertlm")})else InstalledModel("synthetic-qa","Scripted QA","/no-model-is-loaded","0".repeat(64),true,"gguf")
            val choice=ChosenInference(model,InferenceOptions(contextSize=8192,maxOutputTokens=512,thinking=false),if(real)"Actual paired-PC local inference"else"Scripted QA")
            val runner=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                workerEngineFactory={request->if(real)RecordingEngine(AutomaticComputeEngine(context,ownerRunId=request.runId,beforePhoneLoad={app.engine.unloadPhoneResidency()}),File(context.getExternalFilesDir(null),"diagnostics/recipient-engine-$kind-$round.json"))else ScriptEngine(kind,name,title,owner)},inferenceChoiceOverride={choice},
                contactLookup={query,limit->
                    require(query==name){"Only exact QA contact lookup is permitted"}
                    if(kind=="denied")throw SecurityException("QA 연락처 권한 거부")
                    if(!real)contacts else mutableListOf<ContactCandidate>().also{items->
                        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),"${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}=?",arrayOf(name),null)?.use{cursor->while(cursor.moveToNext()&&items.size<limit)items+=ContactCandidate(cursor.getString(0),cursor.getString(1))}
                    }
                },externalIntentLauncher={intent->
                    assertEquals("Only a synthetic SMS intent is allowed",Intent.ACTION_SENDTO,intent.action)
                    assertEquals("00000000002",intent.data?.schemeSpecificPart)
                    launches+=intent
                    if(real&&InstrumentationRegistry.getArguments().getString("recipientRealIntentApproved")=="true")context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })}.also{coordinator=it}
            runId=withContext(Dispatchers.Main){
                val message=store.addMessage(room.id,room.activeBranchId,"user",owner)
                val run=store.createRun(room.id,room.activeBranchId,message.id,model.id,8192,false)
                val request=QueuedChatRequest(run.id,room.id,room.activeBranchId,project.id,owner,model.id,false,emptyList(),message.id,allowTools=true,mode="act",workspaceBinding=app.workspaceFor(project.id).bindingFingerprint())
                store.updateRunContext(run.id,request.toJson());runner.queueMutation.withLock{runner.queued.value=runner.queued.value+request};runner.drain();run.id
            }
            val ownRun=checkNotNull(runId)
            withTimeout(if(real)480_000 else 45_000){
                while(true){
                    check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"Owner work became active; stopping only the QA request"}
                    runner.executionForRun(ownRun)?.let{if(it.executionEngine.computeLocation.value==ComputeResource.PC&&real)pcSeen=true}
                    runner.pendingActions.value.firstOrNull{it.runId==ownRun&&!it.decision.isCompleted}?.let{action->
                        assertFalse("Listing-only request proposed an unwanted SMS action",kind=="contacts_only"&&action.call.name=="prepare_sms")
                        assertTrue(launches.isEmpty())
                        if(action.recipientChoices.isNotEmpty()){
                            selectionSeen=true;assertEquals(2,action.recipientChoices.size);assertNull(action.selectedRecipient.value)
                            if(kind=="cancel")action.decision.complete(false)else {action.selectedRecipient.value="00000000002";action.decision.complete(true)}
                        }else action.decision.complete(true)
                    }
                    val status=store.run(ownRun)?.status
                    if(status !in setOf("queued","running","waiting_approval")&&runner.executionForRun(ownRun)==null)break
                    delay(50)
                }
            }
            val run=checkNotNull(store.run(ownRun));val actual=QueuedChatRequest.fromJson(run.contextJson).previousSteps
            val bodies=mutableListOf<String>()
            for(message in store.messages(room.id).first().filter{it.role=="assistant"})bodies+=store.fullMessageText(message.id)
            val body=bodies.joinToString("\n")
            assertFalse("Unverified model completion leaked into durable chat",body.contains("QA_FALSE_COMPLETION"))
            when(kind){
                "ambiguous"->{
                    if(real&&launches.isEmpty()&&run.errorCode==NextActionQuestion.PAUSE_CODE){assertEquals("paused",run.status)}
                    else {assertTrue("No owner recipient choice was requested",selectionSeen);assertEquals(1,launches.size);assertTrue(body.contains("00000000002"));assertTrue(body.contains("실제 화면 표시"));assertEquals("paused",run.status)}
                    assertFalse(body.contains("발송했습니다"))
                }
                "explicit_sms"->{assertEquals(1,launches.size);assertTrue(body.contains("00000000002"));assertTrue(body.contains("실제 화면 표시"));assertFalse(body.contains("발송했습니다"));assertEquals("paused",run.status)}
                "contacts_only"->{assertTrue(launches.isEmpty());assertFalse(selectionSeen);assertTrue(actual.any{it.tool=="search_contacts"&&it.evidence.state==VerificationState.VERIFIED});assertEquals("completed",run.status)}
                "cancel"->{assertTrue(selectionSeen);assertTrue(launches.isEmpty());assertEquals("cancelled",run.status)}
                "no_execution"->{assertTrue(actual.none{it.tool=="save_note"});assertTrue(repo.forThread(room.id).searchNotes(title).none{it.title==title});assertFalse(body.contains("메모 저장했습니다"));assertEquals("paused",run.status)}
                "saved"->{val notes=repo.forThread(room.id).searchNotes(title);val note=notes.single{it.title==title};assertEquals("Owner's exact requested payload was changed",payload,note.body);assertEquals(note,repo.forThread(room.id).getNote(note.id));assertTrue("Stored text was rewritten in the final response",body.contains("본문:\n"+note.body));assertTrue(actual.any{it.tool=="save_note"&&it.evidence.state==VerificationState.VERIFIED})}
                "denied"->{assertTrue(body.contains("QA 연락처 권한 거부"));assertFalse(body.contains("연락처를 조회했습니다"));assertEquals("paused",run.status)}
                "time"->{assertTrue(actual.any{it.tool=="get_current_datetime"&&it.evidence.state==VerificationState.VERIFIED});assertTrue(body.contains("기기에서 조회한 시각:"))}
            }
            if(real)assertTrue("No actual PC compute route was observed",pcSeen)
            return buildJsonObject{put("kind",kind);put("round",round);put("passed",true);put("actualModel",real);put("pcSeen",pcSeen);put("selectionSeen",selectionSeen);put("intentRequests",launches.size);put("status",run.status);put("elapsedMs",System.currentTimeMillis()-started);put("body",body)}
        }catch(error:Throwable){
            val run=runId?.let{store.run(it)}
            val texts=mutableListOf<String>()
            for(message in store.messages(room.id).first().filter{it.role=="assistant"})texts+=store.fullMessageText(message.id)
            val diagnostic=File(context.getExternalFilesDir(null),"diagnostics/recipient-failure-${if(real)"real"else"scripted"}-$kind-$round.json")
            diagnostic.parentFile?.mkdirs();diagnostic.writeText(buildJsonObject{put("status",run?.status);put("errorCode",run?.errorCode);put("context",run?.contextJson);put("answers",JsonArray(texts.map(::JsonPrimitive)))}.toString())
            throw AssertionError("$kind run=${run?.status} error=${run?.errorCode}: ${error.message}",error)
        }finally {
            withContext(NonCancellable){
                coordinator?.let{runner->val job=withContext(Dispatchers.Main){runner.runningJobForThread(room.id).also{runner.cancelThread(room.id)}};withTimeout(30_000){job?.join();while(runner.hasActiveRuns.value)delay(50)};runner.scope.cancel()}
                val scoped=repo.forThread(room.id)
                check(store.threads.first().filter{it.projectId==project.id}.all{it.id==room.id})
                scoped.notes.first().forEach{scoped.deleteNote(it.id)}
                check(store.thread(room.id)?.title==title);store.deleteThread(room.id,deleteDerivedMemory=true);store.archiveProject(project.id)
                assertNull(store.thread(room.id))
            }
        }
    }
}
