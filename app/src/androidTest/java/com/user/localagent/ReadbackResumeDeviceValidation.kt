package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.ComputeResource
import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as Ledger
import com.user.localagent.data.*
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.PhoneRecords
import com.user.localagent.inference.*
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import com.user.localagent.tools.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android coordinator/Room/note scope; scripted inference only. No PC/model/account calls. */
@RunWith(AndroidJUnit4::class)
class ReadbackResumeDeviceValidation {
    private enum class Case(val durable:Boolean,val cancel:Boolean=false,val empty:Boolean=false) {
        LEGACY(false), MATCH(true), EMPTY(true,empty=true), CANCEL_LEGACY(false,true), CANCEL_DURABLE(true,true)
    }

    private class ScriptedInferenceEngine(private val case:Case,private val query:String,private val collection:String):ComputeExecutionEngine {
        override val state=MutableStateFlow("scripted readback QA")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        // Match the established scripted engine seam. This is an emulated label, never real PC evidence.
        override val computeLocation=MutableStateFlow<ComputeResource?>(ComputeResource.PC)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="pc"
        val plans=AtomicInteger();val actions=AtomicInteger();val reviews=AtomicInteger()
        override suspend fun load(path:String,options:InferenceOptions){}
        override suspend fun unload(){}
        override fun cancel(){}
        override fun generate(prompt:String):Flow<String> =error("Typed events only")
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=1
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>)=events(next())
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=events(when {
            schema.contains("targetType")&&schema.contains("requiredTools")-> {
                plans.incrementAndGet()
                buildJsonObject {
                    put("action",if(case.cancel)"CANCEL"else"TOOL_TASK");put("goal",if(case.cancel)"QA 현재 요청 중지"else"QA 저장 대상 재조회")
                    put("target","");put("publicQuery","");put("sourceKind","none");put("targetType",if(case.cancel)"GENERAL"else"PERSONAL_RECORD")
                    put("requiredTools",buildJsonArray{if(!case.cancel)add("search_notes")});put("constraints",buildJsonArray{})
                    put("usesPrivateData",!case.cancel);put("question","")
                }.toString()
            }
            schema.contains("review_complete")-> {
                check(!case.cancel){"CANCEL reached a review generation"};reviews.incrementAndGet()
                """{"type":"review_complete","summary":"QA 실제 조회 결과를 확인했습니다."}"""
            }
            else->next()
        })
        private fun next():String {
            check(!case.cancel){"CANCEL reached a tool/final generation"}
            if(actions.incrementAndGet()==1)return buildJsonObject {
                put("type","tool_call");put("tool","search_notes");put("arguments",buildJsonObject{put("query",query);put("scope",collection);put("limit",5)})
            }.toString()
            return if(case==Case.MATCH)"QA 메모의 같은 대상과 본문을 조회했습니다."else"전체 작업을 완료했습니다. QA_READBACK_FALSE_COMPLETION"
        }
        private fun events(output:String):Flow<GenerationEvent> = flow {
            emit(GenerationEvent.Delta(output))
            val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,512,visibleChars=output.length)
            lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
        }
    }

    @Test fun legacyVerifiedWriteCannotFinishAfterReadOnlyReplan()=runBlocking(Dispatchers.Default){runCase(Case.LEGACY)}
    @Test fun durableActualTargetCanFinishWithOneReadAndNoNewWrite()=runBlocking(Dispatchers.Default){runCase(Case.MATCH)}
    @Test fun durableEmptyReadCannotReplaceActualTargetProof()=runBlocking(Dispatchers.Default){runCase(Case.EMPTY)}
    @Test fun validatedCancelStopsLegacyAndDurableRunsWithoutMoreEffects()=runBlocking(Dispatchers.Default){
        runCase(Case.CANCEL_LEGACY);runCase(Case.CANCEL_DURABLE)
    }

    private suspend fun runCase(case:Case) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readbackValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()){"Owner work is active"}
        val title="qa-readback-"+UUID.randomUUID().toString()
        val collection="qa-readback"
        val noteTitle=title+"-note"
        val payload="QA preserved body\nTwo  spaces 🙂"
        val model=InstalledModel("synthetic-readback-qa","Scripted readback QA","/no-model-is-loaded","0".repeat(64),true,"gguf")
        val choice=ChosenInference(model,InferenceOptions(contextSize=8192,maxOutputTokens=512,thinking=false),"Scripted readback QA; no inference provider")
        val engine=ScriptedInferenceEngine(case,if(case.empty||case==Case.LEGACY)noteTitle+"-absent"else noteTitle,collection)
        var project:ProjectRecord?=null;var room:ChatThreadRecord?=null
        var repository:LocalRepository?=null;var runner:ChatRunCoordinator?=null
        var workspace:com.user.localagent.workspace.ProjectWorkspace?=null
        try {
            val p=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,
                disabledTools=ToolRegistry.definitions.keys-setOf("save_note","search_notes"))).also{project=it}
            check(p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
            val r=store.createThread(p.id,title).also{room=it}
            val scoped=app.repository.forThread(r.id).also{repository=it}
            val ws=app.workspaceFor(p.id).also{workspace=it};check(!ws.state.value.external)
            val binding=ws.bindingFingerprint();val access=store.accessScope(r.id)
            check(access.projectId==p.id&&access.scopeKey=="project:"+p.id)

            // Fixture setup is a real scoped save, not a model-approved parent workflow.
            // Persist an actual parent run/context and derive the target solely from its host receipt.
            val original="제목 '$noteTitle' 메모에 다음 본문을 그대로 저장한 뒤 조회해주세요:\n$payload"
            val parentMessage=store.addMessage(r.id,r.activeBranchId,"user",original)
            val parent=store.createRun(r.id,r.activeBranchId,parentMessage.id,model.id,8192,false)
            val from=Ledger.Context(parent.id,r.id,p.id,access.scopeKey,binding)
            val selected=checkNotNull(Ledger.select(null,from,setOf("save_note","search_notes")))
            val parentRequest=QueuedChatRequest(parent.id,r.id,r.activeBranchId,p.id,original,model.id,false,emptyList(),parentMessage.id,
                workspaceBinding=binding,readbackObligations=if(case.durable)selected else null)
            store.updateRunContext(parent.id,parentRequest.toJson());store.startRun(parent.id)
            val save=ToolCall("qa-parent-save","save_note",buildJsonObject{put("title",noteTitle);put("scope",collection);put("content",payload)})
            val fixtureTools=ToolExecutor(context,scoped,app.scheduler,ConversationImport(context,scoped),PhoneRecords(context),projectWorkspaceProvider={ws})
            fixtureTools.bindAvailableTools(setOf("save_note","search_notes"))
            val result=fixtureTools.execute(save)
            assertTrue("QA parent save failed",result.success);assertEquals(VerificationState.VERIFIED,result.verification)
            val seed=scoped.notes.first().single()
            assertEquals(noteTitle,seed.title);assertEquals(payload,seed.body);assertEquals(seed,scoped.getNote(seed.id))
            val prior=listOf(HarnessStep(save,StepEvidence("save_note",VerificationState.VERIFIED,"QA actual parent save receipt")))
            val collected=ChatReadbackEvidence.collect(from.proofBinding(),selected.selectedTools(),prior,listOf(ObservedToolOutcome(save,result)))
            assertTrue(collected.aligned);assertEquals(seed.id,collected.mutations.single().after.resource.stableId)
            assertEquals(seed.updatedAt.toString(),collected.mutations.single().after.revision)
            val captured=checkNotNull(Ledger.capture(selected,from,prior,collected.mutations,collected.reads,true))
            store.updateRunContext(parent.id,parentRequest.copy(previousSteps=prior,readbackObligations=if(case.durable)captured else null).toJson())
            store.updateRun(parent.id,"paused",errorCode="qa_fixture_pause")
            val savedParent=QueuedChatRequest.fromJson(checkNotNull(store.run(parent.id)).contextJson)
            assertEquals(1,savedParent.previousSteps.size)

            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                inferenceChoiceOverride={choice},workerEngineFactory={request->
                    check(request.threadId==r.id&&request.projectId==p.id&&!request.allowCloudText);engine
                },contactLookup={_,_->error("Contacts are forbidden in readback QA")},externalIntentLauncher={error("External intents are forbidden in readback QA")})}.also{runner=it}
            val child=withContext(Dispatchers.Main){
                val owner=if(case.cancel)"현재 요청을 중지해줘. 이미 저장한 메모는 보존하고 추가 작업을 하지 마."else"이미 저장한 '$noteTitle' 메모의 같은 대상을 검색으로 확인해줘. 새 메모를 쓰거나 기존 본문을 고치지 마."
                val message=store.addMessage(r.id,r.activeBranchId,"user",owner)
                val run=store.createRun(r.id,r.activeBranchId,message.id,model.id,8192,false)
                val to=from.copy(runId=run.id)
                val carried=savedParent.readbackObligations?.let{Ledger.carry(it,from,to,savedParent.previousSteps,savedParent.previousSteps,prior.indices.associateWith{index->index})}
                val request=QueuedChatRequest(run.id,r.id,r.activeBranchId,p.id,owner,model.id,false,emptyList(),message.id,
                    mode="act",allowTools=true,previousSteps=savedParent.previousSteps,workspaceBinding=binding,readbackObligations=carried)
                store.updateRunContext(run.id,request.toJson())
                coordinator.queueMutation.withLock{coordinator.queued.value=coordinator.queued.value+request};coordinator.drain();run.id
            }
            withTimeout(45_000){while(true){
                check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"Owner work became active; stop only QA"}
                coordinator.pendingActions.value.firstOrNull{it.runId==child&&!it.decision.isCompleted}?.let{action->
                    action.decision.complete(false);error("Read-only/cancel QA unexpectedly requested approval: "+action.call.name)
                }
                val status=store.run(child)?.status
                if(status !in setOf("queued","running","waiting_approval")&&coordinator.executionForRun(child)==null)break
                delay(25)
            }}
            val completed=checkNotNull(store.run(child))
            val restored=QueuedChatRequest.fromJson(completed.contextJson)
            val added=restored.previousSteps.drop(prior.size)
            val body=store.messages(r.id).first().filter{it.role=="assistant"}.map{store.fullMessageText(it.id)}.joinToString("\n")
            assertTrue("Meaning plan was not requested",engine.plans.get()>0)
            assertEquals("Original parent run changed","paused",store.run(parent.id)?.status)
            assertEquals("Original VERIFIED source step was lost","save_note",restored.previousSteps.firstOrNull()?.tool)
            assertEquals(prior.single().arguments,restored.previousSteps.firstOrNull()?.arguments)
            assertEquals(VerificationState.VERIFIED,restored.previousSteps.firstOrNull()?.evidence?.state)
            assertEquals("A duplicate/new write was executed",0,added.count{it.tool in setOf("save_note","update_note","delete_note")})
            assertTrue("Unexpected new tool",added.all{it.tool=="search_notes"})
            assertEquals("Original note or revision changed",seed,scoped.getNote(seed.id))
            assertEquals("A duplicate note was created",listOf(seed),scoped.notes.first())
            assertFalse("Unproved completion leaked into durable chat",body.contains("QA_READBACK_FALSE_COMPLETION"))
            val to=from.copy(runId=child)
            val remaining=(WorkSegments.STEP_BUDGET-restored.previousSteps.size).coerceAtLeast(0)
            when(case){
                Case.MATCH->{
                    assertEquals("Same actual target read did not finish: "+completed.errorCode,"completed",completed.status)
                    assertEquals(1,added.size);assertEquals(VerificationState.VERIFIED,added.single().evidence.state)
                    val ledger=checkNotNull(restored.readbackObligations)
                    val proof=ledger.applied.single();assertEquals(seed.id,proof.after.resource.stableId)
                    assertEquals(proof.after,proof.latestRead?.returned)
                    assertTrue(Ledger.assess(ledger,to,restored.previousSteps,prior.size,emptySet(),remaining,1,requestedFinish=true).readbackSatisfied)
                }
                Case.LEGACY,Case.EMPTY->{
                    assertEquals("Missing target proof became completed: "+completed.errorCode,"paused",completed.status)
                    val assessment=Ledger.assess(restored.readbackObligations,to,restored.previousSteps,prior.size,setOf("search_notes"),remaining,1,requestedFinish=true)
                    assertFalse(assessment.readbackSatisfied)
                    if(case==Case.EMPTY){assertEquals(1,added.size);assertEquals(RequiredReadbackPolicy.Reason.TARGET_NOT_RETURNED,assessment.pending.single().reason)}
                    else {assertNull(restored.readbackObligations);assertTrue(RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF in assessment.blockers)}
                }
                Case.CANCEL_LEGACY,Case.CANCEL_DURABLE->{
                    assertEquals("Typed CANCEL was replaced by the completion fence","cancelled",completed.status)
                    assertEquals("owner_requested_cancel",completed.errorCode)
                    assertTrue(added.isEmpty());assertEquals(0,engine.actions.get());assertEquals(0,engine.reviews.get())
                    assertTrue(body.contains("요청을 중지했습니다"));assertNull(restored.goal)
                    assertEquals(case.durable,restored.readbackObligations!=null)
                    assertEquals(savedParent.readbackObligations?.selected,restored.readbackObligations?.selected)
                    assertEquals(savedParent.readbackObligations?.applied?.map{it.after},restored.readbackObligations?.applied?.map{it.after})
                }
            }
        } finally {
            withContext(NonCancellable){
                runner?.let{coordinator->
                    val r=checkNotNull(room)
                    val job=withContext(Dispatchers.Main){coordinator.runningJobForThread(r.id).also{coordinator.cancelThread(r.id)}}
                    withTimeout(30_000){job?.join();while(coordinator.hasActiveRuns.value)delay(25)}
                    coordinator.scope.cancel()
                }
                project?.let{p->
                    check(p.title==title&&store.project(p.id)?.title==title&&p.workspaceKey==p.id)
                    check(store.threads.first().filter{it.projectId==p.id}.all{it.id==room?.id})
                    repository?.notes?.first()?.forEach{note->check(note.title.startsWith(title)&&note.collection==collection);repository?.deleteNote(note.id)}
                    check(app.repository.reminderIdsForProjectRemoval(p.id).isEmpty())
                    store.deleteProject(p.id,deleteDerivedMemory=true);workspace?.discard()
                    assertNull(store.project(p.id));room?.let{assertNull(store.thread(it.id))}
                }
            }
        }
    }
}
