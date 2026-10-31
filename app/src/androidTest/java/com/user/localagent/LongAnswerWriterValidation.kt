package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.data.ChatStore
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.ui.UiAnswerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real cached GGUF + writer + SQLCipher integration. Creates only tracked synthetic unassigned chats. */
@RunWith(AndroidJUnit4::class)
class LongAnswerWriterValidation {
    @Test fun realCappedPartsResumeAndCancellationPreserveDurableText()=runBlocking<Unit>(Dispatchers.Default){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("longAnswerValidationApproved=true required",args.getString("longAnswerValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val chat=app.repository.chat
        val ownedThreads=mutableListOf<Pair<String,String>>()
        var ownsBenchmark=false;var ownsInference=false;var busy=false
        var initialParts=0;var resumedParts=0;var persistedChars=0;var cancelledChars=0
        var firstPaused=false;var resumeVerified=false;var cancellationVerified=false;var cleanupVerified=false
        var modelId:String?=null;var modelSha:String?=null;var failure:String?=null
        var stage="initializing_coordinator";var coordinatorReady=false
        try {
            // Recovery must finish BEFORE this fixture creates any running rows. A later lazy
            // coordinator initialization would correctly recover those synthetic rows as interrupted.
            check(!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()){"Another native task is running"}
            val coordinator=withContext(Dispatchers.Main.immediate){app.runs}
            withTimeout(30_000){coordinator.selectionReady.first{it}}
            withContext(Dispatchers.Main.immediate){yield()}
            check(!coordinator.busy.value&&coordinator.activeRunId==null&&coordinator.queued.value.isEmpty()){"An owner request is active or queued"}
            check(chat.queuedRuns().isEmpty()){"A durable owner request is queued"}
            check(!app.imageStudio.state.value.running){"An image request is active"}
            coordinatorReady=true;stage="acquiring_runtime"
            ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(ownsBenchmark){"Another benchmark is running"}
            ownsInference=RuntimeGate.inference.compareAndSet(false,true);check(ownsInference){"Another inference is running"}
            app.cancelIdleUnload();app.updates.setBusy(true);busy=true
            app.engine.unload();app.models.refresh()
            val requested=args.getString("longAnswerModelId")
            val model=app.models.models.value.filter {it.verified&&it.runtime=="gguf"&&!it.id.contains("mmproj")&&File(it.path).isFile}
                .let {models->if(requested!=null)models.firstOrNull {it.id==requested}else models.sortedBy {when(it.id){"gemma-e2b"->0;"qwen-2b"->1;"qwen-4b"->2;else->3}}.firstOrNull()}
                ?:error("A cached verified GGUF model is required; this test never downloads one")
            modelId=model.id;modelSha=model.sha256
            val options=InferenceOptions(contextSize=4096,backend="CPU",thinking=false,maxOutputTokens=16,deviceOnly=true)
            val user="List the integers from 1 through 10000 in ascending order, one per line, without skipping any. Do not give an abbreviated example."
            val system="Write only the requested list of integers. Do not call tools. Continue the entire requested list."
            val title="QA writer cap ${UUID.randomUUID()}"
            val thread=chat.createThread(title=title);ownedThreads+=thread.id to title
            val userMessage=chat.addMessage(thread.id,thread.activeBranchId,"user",user)
            val run=chat.createRun(thread.id,thread.activeBranchId,userMessage.id,model.id,4096,false)
            val request=QueuedChatRequest(run.id,thread.id,thread.activeBranchId,null,user,model.id,false,emptyList(),userMessage.id,allowTools=false)
            chat.updateRunContext(run.id,request.toJson());chat.startRun(run.id)
            val lastState=AtomicReference<UiAnswerState>()
            val writer=LongAnswerWriter(app,request,{_,state->lastState.set(state)},{})
            stage="initial_write"
            val result=withTimeout(600_000){writer.write(model,options,user,system,emptyList())}
            val messageId=requireNotNull(result.messageId){"A real visible answer is required"}
            val segments=chat.segmentsForMessage(messageId)
            initialParts=segments.size
            assertTrue("A16-token cap must produce multiple real saved parts",initialParts in 2..4)
            val combined=partsText(chat,messageId)
            val canonical=chat.fullMessageText(messageId);persistedChars=canonical.length
            assertTrue(canonical.isNotBlank());assertEquals(combined,canonical);assertEquals(canonical,result.text)
            assertEquals(1,chat.messages(thread.id,thread.activeBranchId).first().count {it.role=="assistant"})
            firstPaused=result.paused&&chat.run(run.id)?.status=="paused"
            assertTrue("Token cap exhaustion is paused, not whole-task completion",firstPaused)
            assertEquals(true,lastState.get()?.canContinue)

            val stored=QueuedChatRequest.fromJson(requireNotNull(chat.run(run.id)).contextJson)
            val snapshot=requireNotNull(stored.continuation)
            assertEquals(messageId,snapshot.messageId);assertEquals(run.id,stored.runId);assertFalse(stored.allowTools)
            assertEquals(initialParts+1,snapshot.nextPart)
            chat.resumeRun(run.id)
            stage="resume_write"
            val resumed=withTimeout(600_000){LongAnswerWriter(app,stored,{_,state->lastState.set(state)},{})
                .write(model,snapshot.options,snapshot.user,snapshot.system,snapshot.history,resume=snapshot)}
            val after=chat.segmentsForMessage(messageId);resumedParts=after.size
            assertEquals(messageId,resumed.messageId)
            assertTrue("Resume must append a new numbered segment to the same answer",resumedParts>initialParts)
            assertEquals(partsText(chat,messageId),chat.fullMessageText(messageId))
            assertEquals(1,chat.messages(thread.id,thread.activeBranchId).first().count {it.role=="assistant"})
            assertEquals(run.id,chat.run(run.id)?.id);resumeVerified=true
            if(chat.run(run.id)?.status=="running")chat.updateRun(run.id,"completed")

            // Cancel only after the writer reports a DB-backed, nonempty flush.
            val cancelTitle="QA writer cancel ${UUID.randomUUID()}"
            val cancelThread=chat.createThread(title=cancelTitle);ownedThreads+=cancelThread.id to cancelTitle
            val cancelUser=chat.addMessage(cancelThread.id,cancelThread.activeBranchId,"user",user)
            val cancelRun=chat.createRun(cancelThread.id,cancelThread.activeBranchId,cancelUser.id,model.id,4096,false)
            val cancelRequest=QueuedChatRequest(cancelRun.id,cancelThread.id,cancelThread.activeBranchId,null,user,model.id,false,emptyList(),cancelUser.id,allowTools=false)
            chat.updateRunContext(cancelRun.id,cancelRequest.toJson());chat.startRun(cancelRun.id)
            val firstFlush=CompletableDeferred<String>()
            val cancelledState=AtomicReference<UiAnswerState>()
            stage="cancel_write"
            val job=async {
                LongAnswerWriter(app,cancelRequest,{id,state->
                    cancelledState.set(state)
                    if(state.parts.any {it.text.isNotEmpty()})firstFlush.complete(id)
                },{}).write(model,options.copy(maxOutputTokens=2048),user,system,emptyList())
            }
            val cancelledMessageId=withTimeout(300_000){select<String>{
                firstFlush.onAwait {it}
                job.onAwait {error("Stream completed before a persisted cancellation checkpoint")}
            }}
            app.engine.cancel();job.cancel(CancellationException("Synthetic owner stop after first durable flush"))
            withTimeout(60_000){job.join()}
            chat.updateRun(cancelRun.id,"cancelled")
            val cancelledText=chat.fullMessageText(cancelledMessageId);cancelledChars=cancelledText.length
            assertTrue("Owner stop preserves already written text",cancelledText.isNotBlank())
            val checkpoint=QueuedChatRequest.fromJson(requireNotNull(chat.run(cancelRun.id)).contextJson).continuation
            assertEquals(cancelledMessageId,checkpoint?.messageId)
            assertEquals(true,cancelledState.get()?.canContinue)
            assertEquals(partsText(chat,cancelledMessageId),cancelledText)
            cancellationVerified=true;stage="completed"
        }catch(error:Throwable){failure=error.javaClass.simpleName;throw error}
        finally {
            withContext(NonCancellable+Dispatchers.IO){
                // Capture actual synthetic lifecycle BEFORE cleanup invalidates run envelopes.
                val fixtureStates=ownedThreads.flatMap{(id,_)->chat.runsForThread(id)}.take(8)
                if(ownsInference){app.engine.cancel();app.engine.unload()}
                cleanupVerified=true
                for((id,title)in ownedThreads){
                    try {
                        val thread=chat.thread(id)
                        if(thread!=null){check(thread.title==title){"Synthetic thread title changed; preserving it"};chat.deleteThread(id,deleteDerivedMemory=true)}
                        if(chat.thread(id)!=null)cleanupVerified=false
                    }catch(_:Exception){cleanupVerified=false}
                }
                val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks")
                check(directory.isDirectory||directory.mkdirs())
                File(directory,"long-answer-writer.json").writeText(buildJsonObject {
                    put("synthetic_inputs_only",true);put("model_id",modelId);put("model_sha256",modelSha)
                    put("stage",stage);put("coordinator_initialization_finished",coordinatorReady)
                    put("fixture_runs",buildJsonArray{fixtureStates.forEach{run->add(buildJsonObject{
                        put("status",run.status)
                        put("error_code",run.errorCode?.takeIf{it.matches(Regex("[a-zA-Z0-9_.:-]{1,80}"))})
                        put("started",run.startedAt!=null);put("finished",run.finishedAt!=null)
                    })}})
                    put("initial_parts",initialParts);put("resumed_parts",resumedParts);put("persisted_chars",persistedChars)
                    put("first_paused",firstPaused);put("same_run_resume_verified",resumeVerified)
                    put("cancelled_chars",cancelledChars);put("cancellation_checkpoint_verified",cancellationVerified)
                    put("synthetic_threads_cleanup_verified",cleanupVerified);put("failure_class",failure)
                }.toString())
            }
            if(busy)app.updates.setBusy(false)
            if(ownsInference)RuntimeGate.inference.set(false)
            if(ownsBenchmark)RuntimeGate.benchmark.set(false)
            if(ownsInference)app.scheduleIdleUnload()
        }
        assertTrue("Synthetic threads must be removed",cleanupVerified)
    }
    private suspend fun partsText(chat:ChatStore,messageId:String):String {
        val text=StringBuilder()
        for(segment in chat.segmentsForMessage(messageId).sortedBy {it.partNumber})text.append(chat.segmentText(segment.id))
        return text.toString()
    }
}
