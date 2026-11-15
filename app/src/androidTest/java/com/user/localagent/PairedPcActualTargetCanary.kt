package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import com.user.localagent.workspace.ProjectWorkspace
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ROOT-ONLY opt-in actual PC canary. Not a clean installation or an empty-owner-model test.
 * Normal send/capture/selector/planner/writer are used; the observer delegates to the real PC engine.
 * No fake choice/verified file, phone model mutation, imported credential or automatic approval exists. */
@RunWith(AndroidJUnit4::class)
class PairedPcActualTargetCanary {
    /** Only fixed host codes enter the report. Unknown wire values and exception messages never do. */
    private enum class SafeError(val wire:String?) {
        NONE(null),UNKNOWN(null),
        EMPTY_REQUEST("empty_request"),UNVERIFIED_MODEL("unverified_model"),UNKNOWN_AVAILABLE_TOOL("unknown_available_tool"),
        CURRENT_REQUEST_EXCEEDS_INPUT_LIMIT("current_request_exceeds_input_limit"),PLAN_UNAVAILABLE("plan_unavailable"),
        PLANNING_LOADING_TIMEOUT("planning_loading_timeout"),PLANNING_TOKENIZING_TIMEOUT("planning_tokenizing_timeout"),
        PLANNING_GENERATING_TIMEOUT("planning_generating_timeout"),PLANNING_TOTAL_TIMEOUT("planning_total_timeout"),
        PLANNING_PC_BUSY("planning_pc_busy"),PLANNING_PC_REQUIRED("planning_pc_required"),
        PLANNING_PC_UNAVAILABLE("planning_pc_unavailable"),PLANNING_ENGINE_FAILED("planning_engine_failed"),
        OWNER_REQUESTED_CANCEL("owner_requested_cancel"),EXECUTION_NOT_VERIFIED("execution_not_verified"),
        REASONING_BUDGET_EXHAUSTED("reasoning_budget_exhausted"),REQUEST_FAILED_BEFORE_FINALIZATION("request_failed_before_finalization"),
        READBACK_OBLIGATION_UNMET("readback_obligation_unmet"),TOOL_CALL_TRUNCATED("tool_call_truncated"),
        FINAL_SYNTHESIS_NEEDS_ACTION("final_synthesis_needs_action"),AWAITING_OWNER_INPUT("awaiting_owner_input"),
        INVALID_CLARIFICATION("invalid_clarification"),INFERENCE_MODEL_NOT_READY("inference_model_not_ready"),
        INFERENCE_PC_PAIRING_REQUIRED("inference_pc_pairing_required"),
        INFERENCE_ORIGINAL_PC_AUTHORITY_MISSING("inference_original_pc_authority_missing"),
        INFERENCE_PC_AUTHORITY_CHANGED("inference_pc_authority_changed"),INFERENCE_PC_LOADER_UNAVAILABLE("inference_pc_loader_unavailable"),
        INFERENCE_PC_ANALYSIS_PROVENANCE_UNAVAILABLE("inference_pc_analysis_provenance_unavailable"),
        PLAN_REJECTED_MISSING_CONNECTION_TARGET("plan_rejected_missing_connection_target"),
        LEGACY_ENGINE_ERROR("LEGACY_ENGINE_ERROR");
        companion object {
            fun of(raw:String?):SafeError=if(raw==null)NONE else entries.firstOrNull{it.wire==raw}?:UNKNOWN
        }
    }
    private enum class SafeStatus(val wire:String) {
        QUEUED("queued"),RUNNING("running"),WAITING_APPROVAL("waiting_approval"),COMPLETED("completed"),
        PAUSED("paused"),FAILED("failed"),CANCELLED("cancelled"),INTERRUPTED("interrupted"),UNKNOWN("");
        companion object {fun of(raw:String?)=entries.firstOrNull{it!=UNKNOWN&&it.wire==raw}?:UNKNOWN}
    }
    private enum class BenchPhase { LOAD,COUNT_CHAT,COUNT_STRUCTURED,GENERATE_CHAT,GENERATE_STRUCTURED }
    /** Bounded host clocks only. Missing server/DB/UI boundaries remain null, never zero-duration work. */
    private class Benchmark(private val declaredResidency:String) {
        private val origin=SystemClock.elapsedRealtimeNanos()
        private data class Span(val phase:BenchPhase,val start:Long,var end:Long?=null,
            var returned:String="UNFINISHED",var firstDelta:Long?=null,var terminal:JsonObject?=null)
        private val spans=mutableListOf<Span>()
        private var attempted=0
        private var submitted:Long?=null
        private var terminalObserved:Long?=null
        @Synchronized fun submitted(){submitted=SystemClock.elapsedRealtimeNanos()}
        @Synchronized fun terminalObserved(){terminalObserved=SystemClock.elapsedRealtimeNanos()}
        @Synchronized fun begin(phase:BenchPhase):Int {
            attempted++
            if(spans.size>=64)return -1
            spans+=Span(phase,SystemClock.elapsedRealtimeNanos());return spans.lastIndex
        }
        @Synchronized fun end(index:Int,result:String){if(index>=0){spans[index].end=SystemClock.elapsedRealtimeNanos();spans[index].returned=result}}
        @Synchronized fun firstDelta(index:Int){if(index>=0&&spans[index].firstDelta==null)spans[index].firstDelta=SystemClock.elapsedRealtimeNanos()}
        @Synchronized fun summary(index:Int,value:GenerationSummary){
            if(index<0)return
            fun measured(rate:Double?)=rate?.takeIf{it.isFinite()&&it>=0}?.let(::JsonPrimitive)?:JsonNull
            spans[index].terminal=buildJsonObject {
                put("reason",value.reason.name);put("evidence",value.evidence.name)
                put("prefillTokens",value.prefillTokens?.let(::JsonPrimitive)?:JsonNull)
                put("decodeTokens",value.decodeTokens?.let(::JsonPrimitive)?:JsonNull)
                put("reportedElapsedMs",value.elapsedMillis?.let(::JsonPrimitive)?:JsonNull)
                put("promptTokensPerSecond",measured(value.metrics?.prefillTokensPerSecond))
                put("decodeTokensPerSecond",measured(value.metrics?.decodeTokensPerSecond))
                // The current PC adapter's reported TTFT is completion-buffered. Do not relabel it as server TTFT.
                put("serverTtftMs",JsonNull);put("serverPrefillMs",JsonNull);put("serverDecodeMs",JsonNull)
            }
        }
        @Synchronized fun json(receipts:List<PairedPcLoadReceipt>):JsonObject=buildJsonObject {
            fun ms(value:Long)=value/1_000_000.0
            put("schemaVersion",1);put("scenarioId","NORMAL_PAIRED_PC_TEXT_RESPONSE_V1")
            put("declaredModelResidency",declaredResidency);put("verifiedModelResidency","UNKNOWN")
            put("osFileCacheCondition","UNKNOWN");put("uiProcessCondition","UNKNOWN")
            put("actualModelSha256s",JsonArray(receipts.map{it.modelSha256}.distinct().sorted().map(::JsonPrimitive)))
            put("actualContexts",JsonArray(receipts.map{it.contextSize}.distinct().sorted().map(::JsonPrimitive)))
            put("requestToTerminalObservedMs",submitted?.let{start->terminalObserved?.let{JsonPrimitive(ms(it-start))}}?:JsonNull)
            put("terminalPollIntervalMs",100);put("attemptedSpans",attempted);put("droppedSpans",attempted-spans.size)
            put("spans",buildJsonArray {spans.forEach{span->add(buildJsonObject {
                put("phase",span.phase.name);put("startOffsetMs",ms(span.start-origin))
                put("durationMs",span.end?.let{JsonPrimitive(ms(it-span.start))}?:JsonNull);put("operationResult",span.returned)
                put("firstHostModelDeltaMs",span.firstDelta?.let{JsonPrimitive(ms(it-span.start))}?:JsonNull)
                put("terminal",span.terminal?:JsonNull)
            })}})
            put("unmeasured",buildJsonArray {listOf("PLAN","PREFILL","DECODE","TOOL","DB","FILE","PERSIST","FIRST_USER_VISIBLE_UI").forEach{phase->add(buildJsonObject {
                put("phase",phase);put("durationMs",JsonNull);put("availability","NOT_INSTRUMENTED")
            })}})
        }
    }
    private class ObservedEngine(private val real:AutomaticComputeEngine,private val expected:QueuedChatRequest,
        private val receipts:MutableList<PairedPcLoadReceipt>,private val typedLoads:AtomicInteger,
        private val receiptMatches:AtomicInteger,private val benchmark:Benchmark):ComputeExecutionEngine by real,PairedPcTextLoader {
        private suspend fun <T> timed(phase:BenchPhase,block:suspend()->T):T {
            val index=benchmark.begin(phase);var result="THREW"
            try{val value=block();result=if(value==null)"NO_RESULT"else"RETURNED";return value}
            catch(cancel:CancellationException){result="CANCELLED";throw cancel}
            finally{benchmark.end(index,result)}
        }
        override suspend fun load(path:String,options:InferenceOptions){error("Logical PC canary reached a file loader")}
        override suspend fun loadPairedPcText(target:PairedPcText,options:InferenceOptions):PairedPcLoadReceipt {
            check(expected.modelId==PairedPcTextProfile.SELECTION_ID&&!expected.allowCloudText)
            check(expected.capturedPcRevision!=null&&expected.capturedPcRevision==target.authorizationRevision)
            typedLoads.incrementAndGet()
            return timed(BenchPhase.LOAD){
                val receipt=real.loadPairedPcText(target,options)
                check(receipt.matches(target,options.contextSize));receipts+=receipt
                receiptMatches.incrementAndGet()
                receipt
            }
        }
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int? =
            timed(BenchPhase.COUNT_CHAT){real.countChatTokens(userText,systemInstruction,history)}
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int? =
            timed(BenchPhase.COUNT_STRUCTURED){real.countStructuredChatTokens(userText,systemInstruction,schema,history)}
        private fun observed(events:Flow<GenerationEvent>,phase:BenchPhase):Flow<GenerationEvent> = flow {
            val index=benchmark.begin(phase);var result="THREW";var first=false
            try{
                events.collect{event->
                    if(event is GenerationEvent.Delta&&!first&&event.text.isNotEmpty()){first=true;benchmark.firstDelta(index)}
                    if(event is GenerationEvent.Finished)benchmark.summary(index,event.summary)
                    emit(event)
                }
                result="RETURNED"
            }catch(cancel:CancellationException){result="CANCELLED";throw cancel}
            finally{benchmark.end(index,result)}
        }
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> =
            observed(real.generateChatEvents(userText,systemInstruction,history),BenchPhase.GENERATE_CHAT)
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> =
            observed(real.generateStructuredEvents(userText,systemInstruction,schema,history),BenchPhase.GENERATE_STRUCTURED)
    }

    @Test fun explicitPairedTargetPassesNormalCaptureSelectionAndActualLoad()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pairedPcActualCanaryApproved")=="true")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val expectedVersion=checkNotNull(args.getString("pairedPcCanaryExpectedVersion")?.toLongOrNull())
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expectedVersion)
        val app=(base.applicationContext as LocalAgentApplication).container
        check(!app.runs.hasActiveRuns.value&&!app.imageStudio.state.value.running&&!RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get())
        val settings=PcComputeSettings.get(base)
        check(settings.snapshot()!=null&&!settings.state.value.claudeText)
        val originalRevision=settings.authorizationRevision
        val originalProject=app.runs.selectedProjectId.value
        val prefix="paired-pc-canary-"+UUID.randomUUID()
        val started=SystemClock.elapsedRealtime()
        val prefName="$prefix-chat-preferences"
        val context=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
                if(name=="chat-preferences")base.getSharedPreferences(prefName,mode)else super.getSharedPreferences(name,mode)
        }
        val receipts=CopyOnWriteArrayList<PairedPcLoadReceipt>()
        val captured=CopyOnWriteArrayList<QueuedChatRequest>()
        val typedLoads=AtomicInteger()
        val receiptMatches=AtomicInteger()
        val declaredResidency=args.getString("pairedPcBenchmarkResidency")?.takeIf{it in setOf("COLD","WARM")}?:"UNKNOWN"
        val benchmark=Benchmark(declaredResidency)
        var project:ProjectRecord?=null
        var room:ChatThreadRecord?=null
        var workspace:ProjectWorkspace?=null
        var privateRoot:File?=null
        var binding:String?=null
        var coordinator:ChatRunCoordinator?=null
        var primary:Throwable?=null
        var observedRun:ChatRunRecord?=null
        var runSnapshotSource="UNKNOWN"
        var runRefreshSucceeded=false
        var terminalElapsedMillis:Long?=null
        var assertionsPassed=false
        var cleanupFinished=false
        var cleanupSuccess=false
        var diagnosticWriteFailed=false
        val privateExternal=checkNotNull(base.getExternalFilesDir(null)).canonicalFile
        val reportDirectory=File(privateExternal,"diagnostics").canonicalFile
        val report=File(reportDirectory,"$prefix.json")
        fun publish() {
            check(reportDirectory.parentFile==privateExternal&&report.parentFile==reportDirectory)
            check(reportDirectory.mkdirs()||reportDirectory.isDirectory)
            val row=observedRun
            val status=SafeStatus.of(row?.status)
            val terminal=status in setOf(SafeStatus.COMPLETED,SafeStatus.PAUSED,SafeStatus.FAILED,SafeStatus.CANCELLED,SafeStatus.INTERRUPTED)
            val data=buildJsonObject {
                put("schemaVersion",1);put("test","PAIRED_PC_NORMAL_TARGET_CANARY")
                put("expectedVersion",expectedVersion);put("status",status.name)
                put("terminalStatus",if(terminal)status.name else"UNKNOWN")
                put("errorCode",SafeError.of(row?.errorCode).name)
                put("runSnapshotSource",runSnapshotSource);put("runRefreshSucceeded",runRefreshSucceeded)
                put("capturedCount",captured.size);put("typedLoadCount",typedLoads.get())
                put("receiptMatchCount",receiptMatches.get());put("assertionsPassed",assertionsPassed)
                put("cleanupFinished",cleanupFinished);put("cleanupSuccess",if(cleanupFinished)JsonPrimitive(cleanupSuccess)else JsonNull)
                put("diagnosticWriteFailed",diagnosticWriteFailed)
                put("failureKind",when(primary){null->"NONE";is AssertionError->"ASSERTION";is TimeoutCancellationException->"TIMEOUT";is CancellationException->"CANCELLED";else->"OTHER"})
                put("terminalElapsedMillis",terminalElapsedMillis?.let(::JsonPrimitive)?:JsonNull)
                put("elapsedMillis",(SystemClock.elapsedRealtime()-started).coerceAtLeast(0))
                put("benchmark",benchmark.json(receipts))
                // Only this test's existing owned fixture recovery locators.
                put("recovery",buildJsonObject {
                    put("projectId",project?.id?.let(::JsonPrimitive)?:JsonNull)
                    put("threadId",room?.id?.let(::JsonPrimitive)?:JsonNull)
                    put("workspaceKey",project?.workspaceKey?.let(::JsonPrimitive)?:JsonNull)
                    put("runId",captured.singleOrNull()?.runId?.let(::JsonPrimitive)?:JsonNull)
                    put("preferencesName",prefName)
                })
            }
            val temporary=File(reportDirectory,report.name+".tmp")
            FileOutputStream(temporary).use{stream->stream.write(data.toString().toByteArray(Charsets.UTF_8));stream.fd.sync()}
            Os.rename(temporary.path,report.path)
            InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply{
                putString("pairedPcCanaryReportBasename",report.name)
            })
        }
        try {
            project=app.repository.chat.createProject(prefix,ProjectSettings(modelId=PairedPcTextProfile.SELECTION_ID,
                useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys))
            val ownedProject=checkNotNull(project)
            room=app.repository.chat.createThread(ownedProject.id,prefix)
            val ownedRoom=checkNotNull(room)
            val privateParent=File(base.filesDir,"projects").canonicalFile
            privateRoot=File(privateParent,ownedProject.workspaceKey).canonicalFile
            check(privateRoot?.parentFile==privateParent&&privateRoot?.name==ownedProject.workspaceKey)
            val ownedSpace=app.workspaceFor(ownedProject.id)
            workspace=ownedSpace;binding=ownedSpace.bindingFingerprint()
            check(!ownedSpace.state.value.external)
            coordinator=withContext(Dispatchers.Main.immediate){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                workerEngineFactory={request->
                    check(request.projectId==ownedProject.id&&request.threadId==ownedRoom.id&&request.modelId==PairedPcTextProfile.SELECTION_ID)
                    check(request.capturedPcRevision==originalRevision&&!request.allowCloudText&&request.allowTools)
                    captured+=request
                    ObservedEngine(AutomaticComputeEngine(context,ownerRunId=request.runId,
                        beforePhoneLoad={app.engine.unloadPhoneResidency()},capturedPcRevision=request.capturedPcRevision,
                        allowCloudText=request.allowCloudText),request,receipts,typedLoads,receiptMatches,benchmark)
                })}
            val own=checkNotNull(coordinator)
            withContext(Dispatchers.Main.immediate){own.selectThread(ownedRoom.id)}
            withTimeout(15_000){while(!own.selectionReady.value)delay(50)}
            assertEquals(PairedPcTextProfile.SELECTION_ID,own.preferredModelId.value)
            benchmark.submitted()
            check(withContext(Dispatchers.Main.immediate){own.send("짧게 연결 확인이라고만 답해주세요.",ownedRoom.id)})
            val run=withTimeout(600_000){
                var result:ChatRunRecord?=null
                while(result==null){
                    val rows=app.repository.chat.runsForThread(ownedRoom.id)
                    check(rows.size<=1)
                    val row=rows.singleOrNull()
                    if(row!=null){observedRun=row;runSnapshotSource="TERMINAL_POLL"}
                    if(row!=null&&row.status !in setOf("queued","running","waiting_approval")&&!own.hasActiveRuns.value)result=row
                    else delay(100)
                }
                checkNotNull(result)
            }
            terminalElapsedMillis=(SystemClock.elapsedRealtime()-started).coerceAtLeast(0)
            benchmark.terminalObserved()
            assertEquals("completed",run.status)
            assertEquals(1,captured.size)
            val request=captured.single()
            val saved=Json.parseToJsonElement(run.contextJson).jsonObject
            assertEquals(PairedPcTextProfile.SELECTION_ID,saved["modelId"]?.jsonPrimitive?.content)
            assertEquals(originalRevision,saved.getValue("capturedPcRevision").jsonPrimitive.long)
            assertFalse(saved.getValue("allowCloudText").jsonPrimitive.boolean)
            assertTrue(receipts.isNotEmpty())
            assertTrue(receipts.all{it.profileId==PairedPcTextProfile.SELECTION_ID&&it.authorizationRevision==request.capturedPcRevision&&it.modelSha256==PairedPcTextProfile.MODEL_SHA256})
            val answer=checkNotNull(app.repository.chat.assistantForRun(run.id))
            assertTrue(app.repository.chat.fullMessageText(answer.id).isNotBlank())
            assertEquals(originalRevision,settings.authorizationRevision)
            assertionsPassed=true
        }catch(error:Throwable){primary=error;throw error}
        finally{withContext(NonCancellable){
            // Capture the owned run before stop/delete can erase or alter terminal diagnostics.
            room?.let{ownedRoom->
                runCatching {
                    val row=app.repository.chat.runsForThread(ownedRoom.id).singleOrNull()
                    if(row!=null&&row.threadId==ownedRoom.id&&row.projectId==project?.id){
                        observedRun=row;runSnapshotSource="PRE_CLEANUP_READ";runRefreshSucceeded=true
                    }
                }
            }
            var reportFailure:Throwable?=null
            try{publish()}catch(_:Throwable){
                diagnosticWriteFailed=true;reportFailure=AssertionError("QA_PRIVATE_DIAGNOSTIC_WRITE_FAILED")
            }
            try {
                coordinator?.let{own->withContext(Dispatchers.Main.immediate){own.stop()}
                    withTimeout(30_000){while(own.hasActiveRuns.value)delay(50)}
                    withTimeout(30_000){own.scope.coroutineContext[Job]?.cancelAndJoin()}
                    check(!own.hasActiveRuns.value)}
                project?.let{expected->
                    val current=checkNotNull(app.repository.chat.project(expected.id))
                    check(current.title==prefix&&current.workspaceKey==expected.workspaceKey&&current.settings==expected.settings&&!current.archived)
                    val rooms=app.repository.chat.threads.first().filter{it.projectId==expected.id}
                    check(rooms.map{it.id}==listOfNotNull(room?.id))
                    room?.let{ownedRoom->
                        val scoped=app.repository.forThread(ownedRoom.id)
                        check(scoped.notes.first().isEmpty()&&scoped.listReminders().isEmpty())
                    }
                    workspace?.let{space->check(!space.state.value.external&&space.bindingFingerprint()==binding&&space.list().isEmpty())}
                    app.repository.chat.deleteProject(expected.id,true)
                    workspace?.discard()
                    assertNull(app.repository.chat.project(expected.id))
                    privateRoot?.let{assertFalse(it.exists())}
                }
                if(app.runs.selectedProjectId.value==originalProject)app.selectProjectWorkspace(originalProject.takeIf{it.isNotBlank()})
                check(base.deleteSharedPreferences(prefName))
                assertEquals(originalRevision,settings.authorizationRevision)
                cleanupSuccess=true
            }catch(cleanup:Throwable){
                val original=primary
                if(original!=null&&original!==cleanup)original.addSuppressed(cleanup)
                else{primary=cleanup;throw cleanup}
            }finally{
                cleanupFinished=true
                try{publish()}catch(_:Throwable){
                    diagnosticWriteFailed=true
                    if(reportFailure==null)reportFailure=AssertionError("QA_PRIVATE_DIAGNOSTIC_WRITE_FAILED")
                }
                reportFailure?.let{failure->
                    val original=primary
                    if(original!=null){if(original!==failure)original.addSuppressed(failure)}else throw failure
                }
            }
        }}
    }
}
