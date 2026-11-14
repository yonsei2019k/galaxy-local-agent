package com.user.localagent

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.ComputeResource
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Verifies Android wiring of post-read plan repair and captured disabled/read-only scopes; scripted model only. */
class PostReadActionDeviceValidation {
    private class Engine(private val planMode:Boolean):ComputeExecutionEngine {
        override val state=MutableStateFlow("scripted post-read QA")
        override val computeLocation=MutableStateFlow<ComputeResource?>(ComputeResource.PC)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="pc"
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        private var actions=0
        override suspend fun load(path:String,options:InferenceOptions){}
        override suspend fun unload(){}
        override fun cancel(){}
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=1
        override fun generate(prompt:String):Flow<String> =error("typed events required")
        private val sms="""{"type":"tool_call","tool":"prepare_sms","arguments":{"phoneNumber":"00000000001","message":"QA 후속 초안"}}"""
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=output(
            if(planMode&&++actions>1)sms else if(planMode||++actions==1)"""{"type":"tool_call","tool":"search_contacts","arguments":{"query":"QA 후속","limit":1}}""" else "문자 발송 완료 QA_UNSUPPORTED_CLAIM")
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=when {
            schema.contains("targetType")->output("""{"action":"TOOL_TASK","goal":"원래 요청 확인","targetType":"PERSONAL_RECORD","requiredTools":["search_contacts"]}""")
            schema.contains("review_complete")->output("""{"type":"review_next","summary":"원래 요청의 문자 초안 단계가 누락되었습니다.","call":$sms}""")
            else->generateChatEvents(userText,systemInstruction,history)
        }
        private fun output(text:String)=flow {
            emit(GenerationEvent.Delta(text));val end=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,512,visibleChars=text.length)
            lastGeneration.value=end;emit(GenerationEvent.Finished(end))
        }
    }
    @Test fun repeatedPostReadRepairCannotEscapeCapturedScopes()=runBlocking(Dispatchers.Default){
        assumeTrue(InstrumentationRegistry.getArguments().getString("recipientValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.READ_CONTACTS")
        try{repeat(2){round->for(kind in listOf("linked","disabled","read_only"))runOne(context,app,kind,round)}}finally{automation.dropShellPermissionIdentity()}
    }
    private suspend fun runOne(context:Context,app:AppContainer,kind:String,round:Int){
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value)
        val store=app.repository.chat
        val title="qa-postread-$round-$kind-"+UUID.randomUUID()
        val offered=if(kind=="disabled")setOf("search_contacts")else setOf("search_contacts","prepare_sms")
        val project=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-offered))
        val room=store.createThread(project.id,title)
        var coordinator:ChatRunCoordinator?=null
        val intents=mutableListOf<Intent>();var selected=false
        try {
            val model=InstalledModel("synthetic-postread","Scripted post-read QA","/no-model-file","0".repeat(64),true,"gguf")
            val choice=ChosenInference(model,InferenceOptions(maxOutputTokens=512),"scripted only")
            val runner=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,workerEngineFactory={Engine(kind=="read_only")},inferenceChoiceOverride={choice},
                contactLookup={query,_->require(query=="QA 후속");listOf(ContactCandidate("QA 후속","00000000001"),ContactCandidate("QA 후속","00000000002"))},
                externalIntentLauncher={intent->assertEquals("linked",kind);assertEquals("00000000002",intent.data?.schemeSpecificPart);intents+=intent})}.also{coordinator=it}
            val runId=withContext(Dispatchers.Main){
                val owner=if(kind=="linked")"QA 후속 연락처를 찾아 'QA 후속 초안'이라는 문자 초안 준비. 실제 발송 금지."else"QA 후속 연락처 목록만 조회. 문자 준비·발송 금지."
                val message=store.addMessage(room.id,room.activeBranchId,"user",owner)
                val run=store.createRun(room.id,room.activeBranchId,message.id,model.id,4096,false)
                val request=QueuedChatRequest(run.id,room.id,room.activeBranchId,project.id,owner,model.id,false,emptyList(),message.id,
                    allowTools=true,mode=if(kind=="read_only")"plan"else"act",workspaceBinding=app.workspaceFor(project.id).bindingFingerprint())
                store.updateRunContext(run.id,request.toJson());runner.queueMutation.withLock{runner.queued.value=runner.queued.value+request};runner.drain();run.id
            }
            withTimeout(45_000){while(true){
                runner.pendingActions.value.firstOrNull{it.runId==runId&&!it.decision.isCompleted}?.let{action->
                    assertEquals("linked",kind);assertEquals(2,action.recipientChoices.size);assertNull(action.selectedRecipient.value);assertTrue(intents.isEmpty())
                    selected=true;action.selectedRecipient.value="00000000002";action.decision.complete(true)
                }
                if(store.run(runId)?.status !in setOf("running","queued","waiting_approval")&&runner.executionForRun(runId)==null)break
                delay(25)
            }}
            val run=checkNotNull(store.run(runId));val steps=QueuedChatRequest.fromJson(run.contextJson).previousSteps
            val bodies=mutableListOf<String>();for(message in store.messages(room.id).first().filter{it.role=="assistant"})bodies+=store.fullMessageText(message.id)
            assertFalse(bodies.joinToString().contains("QA_UNSUPPORTED_CLAIM"))
            if(kind=="linked"){
                assertTrue(selected);assertEquals(1,intents.size);assertEquals("paused",run.status)
                assertTrue(steps.any{it.tool=="prepare_sms"&&it.arguments["phoneNumber"]?.jsonPrimitive?.content=="00000000002"&&it.evidence.state==VerificationState.PREPARED})
            }else{assertFalse(selected);assertTrue(intents.isEmpty());assertTrue(steps.none{it.tool=="prepare_sms"});assertTrue(steps.any{it.tool=="search_contacts"})}
        }finally{withContext(NonCancellable){
            coordinator?.let{runner->val job=withContext(Dispatchers.Main){runner.runningJobForThread(room.id).also{runner.cancelThread(room.id)}};withTimeout(30_000){job?.join();while(runner.hasActiveRuns.value)delay(25)};runner.scope.cancel()}
            check(store.thread(room.id)?.title==title);store.deleteThread(room.id,deleteDerivedMemory=true);store.archiveProject(project.id)
        }}
    }
}
