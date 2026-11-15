package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.ComputeResource
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import com.user.localagent.notes.*
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Authored only. Real coordinator, approval seam, scoped Room/writeRunAnswer; scripted model, no PC/API/model calls. */
@RunWith(AndroidJUnit4::class)
class NoteReceiptFinalizationDeviceValidation {
    private class ScriptedEngine(private val direct:Boolean,private val questions:Int=0,private val current:suspend()->NoteRecord):ComputeExecutionEngine {
        override val state=MutableStateFlow("synthetic note finalization")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        override val computeLocation=MutableStateFlow<ComputeResource?>(ComputeResource.PC) // Scripted label only.
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="pc"
        val calls=AtomicInteger();val plains=AtomicInteger();val actions=AtomicInteger();val plans=AtomicInteger();val decisions=AtomicInteger()
        val asked=AtomicInteger();val feedbackInputs=AtomicInteger()
        private var questionTokens:Set<String>?=null
        override suspend fun load(path:String,options:InferenceOptions){}
        override suspend fun unload(){}
        override fun cancel(){}
        override fun generate(prompt:String):Flow<String> =error("Typed synthetic events only")
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=1
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=events {
            plains.incrementAndGet();"QA_DISCARDED_FINAL_PROSE_MUST_NOT_BE_GENERATED"
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=events {
            questionTokens?.let{tokens->
                check(userText.contains(NoteReadbackQuestionFeedback.MARKER))
                check(userText.indexOf(NoteReadbackQuestionFeedback.MARKER)==userText.lastIndexOf(NoteReadbackQuestionFeedback.MARKER))
                check(tokens.all{userText.contains(it)}){"Current page references were not preserved"}
                feedbackInputs.incrementAndGet();questionTokens=null
            }
            when{
                actions.get()==3&&asked.get()<questions->{
                    val number=asked.incrementAndGet()
                    // Exact serialized metadata extraction in a synthetic test, not natural-language question routing.
                    questionTokens=userText.split("\"readToken\":\"").drop(1).map{it.substringBefore('"')}.toSet()
                    check(questionTokens?.isNotEmpty()==true)
                    "{\"type\":\"clarification\",\"question\":\"QA 질문 $number: 계속할까요?\"}"
                }
                schema.contains("targetType")&&schema.contains("requiredTools")->{
                    check(!direct);plans.incrementAndGet()
                    """{"action":"TOOL_TASK","goal":"합성 메모의 needle만 changed로 수정하고 같은 새 버전 전체를 조회","target":"","publicQuery":"","sourceKind":"none","targetType":"CONTENT","requiredTools":["patch_note","read_note"],"constraints":["다른 문구와 줄바꿈 보존"],"usesPrivateData":true,"question":""}"""
                }
                schema.contains("review_complete")->{decisions.incrementAndGet();"""{"type":"review_complete","summary":"실제 같은 버전 전체 조회 결과를 확인했습니다."}"""}
                actions.get()>=4->{check(direct);decisions.incrementAndGet();"""{"type":"answer"}"""}
                else->{
                    val step=actions.incrementAndGet();val n=current()
                    buildJsonObject{
                        put("type","tool_call");put("tool",if(step==2)"patch_note"else"read_note")
                        put("arguments",buildJsonObject{
                            put("id",n.id);put("scope",n.collection);put("expectedUpdatedAt",n.updatedAt.toString())
                            if(step==2){put("oldText","needle");put("newText","changed")}
                            else{put("offset",if(step==4)900 else 0);put("limit",900)}
                        })
                    }.toString()
                }
            }
        }
        private fun events(value:suspend()->String):Flow<GenerationEvent> =flow{
            calls.incrementAndGet();val output=value();emit(GenerationEvent.Delta(output))
            val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,768,visibleChars=output.length)
            lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
        }
    }
    @Test fun reviewedNotePublishesTheSameStoredReportWithoutTheDiscardedFinalGeneration()=runBlocking(Dispatchers.Default){runCase(false)}
    @Test fun directAnswerPublishesTheSameStoredReportWithoutTheDiscardedFinalGeneration()=runBlocking(Dispatchers.Default){runCase(true)}

    @Test fun onePartialReadbackQuestionGetsOneModelReconsiderationAndThenTheActualTailRead()=runBlocking(Dispatchers.Default){runCase(false,1);runCase(true,1)}
    @Test fun repeatedPartialReadbackQuestionStillPausesWithoutAutomaticReadOrCompletion()=runBlocking(Dispatchers.Default){runCase(false,2);runCase(true,2)}

    private suspend fun runCase(direct:Boolean,questions:Int=0){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("noteReceiptFinalizationApproved=true required",args.getString("noteReceiptFinalizationApproved")=="true")
        args.getString("noteReceiptFinalizationExpectedVersion")?.toLongOrNull()?.let{expected->
            val base=InstrumentationRegistry.getInstrumentation().targetContext
            check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expected)
        }
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(base.applicationContext as LocalAgentApplication).container;val store=app.repository.chat
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()&&!app.imageStudio.state.value.running)
        val originalProject=app.runs.selectedProjectId.value
        val title="qa-note-final-"+UUID.randomUUID();val collection="qa-note-final-"+UUID.randomUUID();val prefs="$title-preferences"
        val context=object:ContextWrapper(base){override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=
            if(name=="chat-preferences")base.getSharedPreferences(prefs,mode)else super.getSharedPreferences(name,mode)}
        val privatePreferences=context.getSharedPreferences("chat-preferences",Context.MODE_PRIVATE)
        check(privatePreferences.all.isEmpty());check(privatePreferences.edit().putBoolean("direct-next-action",direct).commit())
        val allowed=setOf("search_notes","read_note","patch_note");val required=setOf("patch_note","read_note")
        val before="needle\r\n"+"보존 구간  원문 😀 e\u0301\r\n".repeat(75)+"마지막 줄은 유지"
        val after=before.replaceFirst("needle","changed")
        val model=InstalledModel("synthetic-note-final","Scripted note finalization","/no-model-is-loaded","0".repeat(64),true,"gguf")
        val choice=ChosenInference(model,InferenceOptions(contextSize=8192,maxOutputTokens=768,thinking=false),"Scripted, no real model")
        var project:ProjectRecord?=null;var room:ChatThreadRecord?=null;var repository:LocalRepository?=null
        var workspace:ProjectWorkspace?=null;var runner:ChatRunCoordinator?=null;var ownRun:String?=null;var seed:NoteRecord?=null
        try{
            val p=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed)).also{project=it}
            check(p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
            val r=store.createThread(p.id,title).also{room=it};val scoped=app.repository.forThread(r.id).also{repository=it}
            val ws=app.workspaceFor(p.id).also{workspace=it};check(!ws.state.value.external&&ws.list().isEmpty())
            val initial=scoped.saveNote("$title-note",before,collection).also{seed=it}
            val engine=ScriptedEngine(direct,questions){checkNotNull(scoped.getNote(initial.id))}
            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                inferenceChoiceOverride={choice},workerEngineFactory={request->
                    check(request.threadId==r.id&&request.projectId==p.id&&request.branchId==r.activeBranchId&&!request.allowCloudText);engine
                },contactLookup={_,_->error("Contacts forbidden")},externalIntentLauncher={error("External intents forbidden")})}.also{runner=it}
            val owner="제목 '${initial.title}' 메모에서 'needle'만 'changed'로 고치고, 다른 모든 문구를 보존한 뒤 같은 메모 전체를 다시 확인해줘."
            val id=withContext(Dispatchers.Main){
                val user=store.addMessage(r.id,r.activeBranchId,"user",owner);val run=store.createRun(r.id,r.activeBranchId,user.id,model.id,8192,false)
                ownRun=run.id
                val request=QueuedChatRequest(run.id,r.id,r.activeBranchId,p.id,owner,model.id,false,emptyList(),user.id,workspaceBinding=ws.bindingFingerprint(),pcImageRecoveryAllowed=true)
                check(request.pcImageRecoveryAllowed) // Same captured value as normal Coordinator.send; no flag rewrite.
                store.updateRunContext(run.id,request.toJson());coordinator.queueMutation.withLock{coordinator.queued.value+=request};coordinator.drain();run.id
            }
            var approvals=0
            withTimeout(60_000){while(true){
                check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value)
                coordinator.pendingActions.value.filter{it.runId==id&&!it.decision.isCompleted}.forEach{pending->
                    check(approvals==0&&pending.call.name=="patch_note"&&pending.threadId==r.id&&pending.projectId==p.id)
                    check(pending.call.arguments["id"]?.jsonPrimitive?.content==initial.id&&pending.call.arguments["scope"]?.jsonPrimitive?.content==collection)
                    check(pending.call.arguments["oldText"]?.jsonPrimitive?.content=="needle"&&pending.call.arguments["newText"]?.jsonPrimitive?.content=="changed")
                    check(checkNotNull(pending.reviewText).contains("beforeSha256"));pending.remember.value=false
                    check(pending.decision.complete(true));approvals++
                }
                if(store.run(id)?.status !in setOf("queued","running","waiting_approval")&&coordinator.executionForRun(id)==null)break
                delay(25)
            }}
            val completed=checkNotNull(store.run(id));val stopped=questions==2
            assertEquals(completed.errorCode,if(stopped)"paused"else"completed",completed.status)
            assertEquals(1,approvals);assertEquals(if(stopped)0 else 1,engine.decisions.get());assertEquals(if(direct)0 else 1,engine.plans.get())
            assertEquals(questions,engine.asked.get());assertEquals(if(questions==0)0 else 1,engine.feedbackInputs.get())
            assertEquals(if(stopped)3 else 4,engine.actions.get());assertEquals(0,engine.plains.get())
            assertEquals((if(direct)5 else 6)+(if(questions==1)1 else 0),engine.calls.get())
            val captured=QueuedChatRequest.fromJson(completed.contextJson);val steps=captured.previousSteps
            assertEquals(1,steps.count{it.tool=="patch_note"});assertEquals(if(stopped)2 else 3,steps.count{it.tool=="read_note"})
            val actual=checkNotNull(scoped.getNote(initial.id));assertEquals(after,actual.body);assertEquals(initial.title,actual.title)
            if(stopped){
                assertEquals(NextActionQuestion.PAUSE_CODE,completed.errorCode)
                val answer=checkNotNull(store.assistantForRun(id));val saved=store.fullMessageText(answer.id)
                assertTrue(saved.contains("QA 질문 2"));assertFalse(saved.contains("QA 질문 1"))
                val proof=checkNotNull(captured.readbackObligations)
                assertFalse(RequiredReadbackObligations.assess(proof,proof.context,steps,0,allowed,18-steps.size,1,true).readbackSatisfied)
                return
            }
            val binding=NoteReadBinding(id,r.id,r.activeBranchId,store.accessScope(r.id).scopeKey)
            val host=NoteReadPatchHost(binding,NotePatchStore(binding,scoped.notePatchTransactions(binding))){}
            val session=NoteReadPatchSession(binding)
            val outcomes=steps.mapIndexed{index,step->
                val call=ToolCall("expected-$index",step.tool,step.arguments)
                val result=if(step.tool=="patch_note")checkNotNull(host.recover(call))else{
                    val record=if(call.arguments["expectedUpdatedAt"]?.jsonPrimitive?.content==initial.updatedAt.toString())initial else actual
                    val page=session.read(NoteSource(record.id,binding.scopeKey,record.collection,record.title,record.body,record.updatedAt),NoteReadPatchToolCatalog.readRequest(call))
                    ToolResult(call.id,true,page.json().toString(),VerificationState.VERIFIED,"Actual fixture source page")
                }
                ObservedToolOutcome(call,result)
            }
            val answer=checkNotNull(store.assistantForRun(id));val saved=store.fullMessageText(answer.id)
            assertEquals(ActionCompletionReport.render(required,outcomes),saved)
            assertFalse(saved.contains("QA_DISCARDED_FINAL_PROSE_MUST_NOT_BE_GENERATED"))
            val proof=checkNotNull(captured.readbackObligations)
            assertTrue(RequiredReadbackObligations.assess(proof,proof.context,steps,0,allowed,18-steps.size,1,true).readbackSatisfied)
            assertEquals(id,proof.context.runId);assertEquals(r.id,proof.context.threadId);assertEquals(p.id,proof.context.projectId)
        }finally{withContext(NonCancellable){try{
            runner?.let{coordinator->val job=room?.let{r->withContext(Dispatchers.Main){coordinator.runningJobForThread(r.id).also{coordinator.cancelThread(r.id)}}}
                withTimeout(30_000){job?.join();while(coordinator.hasActiveRuns.value)delay(25)};coordinator.scope.cancel()}
            project?.let{p->
                check(store.project(p.id)?.let{it.title==title&&it.settings==p.settings&&it.workspaceKey==p.id}==true&&p.workspaceKey==p.id)
                check(store.threads.first().filter{it.projectId==p.id}.map{it.id}.toSet()==listOfNotNull(room?.id).toSet())
                room?.let{r->check(store.runsForThread(r.id).all{it.id==ownRun&&it.status !in setOf("queued","running","waiting_approval")})}
                workspace?.let{check(!it.state.value.external&&it.list().isEmpty())}
                repository?.let{scoped->val notes=scoped.notes.first();check(notes.map{it.id}.toSet()==listOfNotNull(seed?.id).toSet())
                    notes.forEach{note->check(note.title=="$title-note"&&note.collection==collection&&note.body in setOf(before,after));scoped.deleteNote(note.id,note.updatedAt)}
                    check(scoped.listReminders().isEmpty())}
                store.deleteProject(p.id,true);workspace?.discard();assertNull(store.project(p.id));room?.let{assertNull(store.thread(it.id))}
            }
            }finally{
                if(app.runs.selectedProjectId.value==originalProject)app.selectProjectWorkspace(originalProject.takeIf{it.isNotBlank()})
                check(privatePreferences.edit().clear().commit());check(privatePreferences.all.isEmpty());check(base.deleteSharedPreferences(prefs))
            }
        }}
    }
}
