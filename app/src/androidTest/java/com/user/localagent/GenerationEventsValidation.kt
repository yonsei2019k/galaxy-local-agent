package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-model terminal proof. No downloads, private sources, tool actions, or text exports. */
@RunWith(AndroidJUnit4::class)
class GenerationEventsValidation {
    @Test fun actualCapNaturalStopAndCancellationHaveDifferentEvidence()=runBlocking<Unit> {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("generationEventsApproved=true is required",arguments.getString("generationEventsApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val records=mutableListOf<JsonObject>()
        var ownsBenchmark=false;var ownsInference=false;var busy=false
        var modelId:String?=null;var modelSha:String?=null;var failure:String?=null
        fun record(summary:GenerationSummary) {records+=buildJsonObject {
            put("reason",summary.reason.name);put("evidence",summary.evidence.name)
            put("cap",summary.configuredMaxOutputTokens);summary.capReached?.let {put("cap_reached",it)}
            summary.prefillTokens?.let {put("prefill_tokens",it)};summary.decodeTokens?.let {put("decode_tokens",it)}
            put("visible_chars",summary.visibleChars);put("history_turns_dropped",summary.historyTurnsDropped)
            summary.timeToFirstVisibleTokenSeconds?.let {put("first_visible_seconds",it)}
        }}
        suspend fun request(prompt:String,utf8Cap:Int?=null):GenerationSummary {
            var finishes=0;var terminal:GenerationSummary?=null;var visible=0
            withTimeout(180000) {app.engine.generateChatEvents(prompt,"답변은 한국어로 간단히 작성한다.").collect {event->when(event){
                is GenerationEvent.Delta->{
                    visible+=event.text.length;check(visible<=64000)
                    if(utf8Cap!=null) {
                        check('\uFFFD' !in event.text){"Native UTF-8 output silently replaced a partial character"}
                        var index=0
                        while(index<event.text.length) {
                            val char=event.text[index]
                            if(char.isHighSurrogate()) {
                                check(index+1<event.text.length&&event.text[index+1].isLowSurrogate())
                                index+=2
                            } else {check(!char.isLowSurrogate());index++}
                        }
                    }
                }
                is GenerationEvent.Finished->{finishes++;terminal=event.summary}
            }}}
            assertEquals("One terminal event is required",1,finishes)
            val summary=checkNotNull(terminal)
            assertEquals(summary.requestId,app.engine.lastGeneration.value?.requestId)
            assertEquals(visible,summary.visibleChars)
            record(summary)
            return summary
        }
        try {
            ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(ownsBenchmark)
            ownsInference=RuntimeGate.inference.compareAndSet(false,true);check(ownsInference)
            app.updates.setBusy(true);busy=true;app.cancelIdleUnload()
            app.engine.unload();app.models.refresh()
            val runtime=arguments.getString("generationRuntime")?:"gguf"
            require(runtime in setOf("gguf","litertlm"))
            val requestedId=arguments.getString("generationModelId")
            val model=app.models.models.value.filter {it.verified&&it.runtime==runtime&& !it.id.contains("mmproj")}
                .let {available->if(requestedId!=null)available.firstOrNull {it.id==requestedId}
                    else available.sortedBy {if(it.id=="gemma-e2b")0 else if(it.id=="qwen-4b")1 else 2}.firstOrNull()}
                ?:error("A cached verified model for the requested runtime is required")
            modelId=model.id;modelSha=model.sha256
            val options=InferenceOptions(contextSize=4096,backend="CPU",thinking=false,maxOutputTokens=16,deviceOnly=true)
            withTimeout(180000){app.engine.load(model.path,options)}
            val capped=request("1부터 200까지 정수를 한 줄에 하나씩 빠짐없이 나열해줘. 서론이나 설명은 쓰지 마.")
            if(runtime=="gguf") {
                assertEquals(GenerationStopReason.MAX_OUTPUT,capped.reason)
                assertEquals(GenerationEvidence.NATIVE,capped.evidence)
                assertEquals(true,capped.capReached)
                assertEquals(16,capped.decodeTokens)
            } else {
                assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN,capped.reason)
                assertNotEquals(GenerationStopReason.EOG,capped.reason)
            }
            // Only a per-request cap changes; adapters must retain the resident weight allocation.
            withTimeout(180000){app.engine.load(model.path,options.copy(maxOutputTokens=128))}
            assertEquals(File(model.path).canonicalPath,File(checkNotNull(app.engine.residentModelPath)).canonicalPath)
            val natural=request("인사말 '안녕하세요.' 한 문장만 답해줘.")
            if(runtime=="gguf") {
                assertEquals(GenerationStopReason.EOG,natural.reason)
                assertEquals(false,natural.capReached)
            } else assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN,natural.reason)

            if(runtime=="gguf")for(cap in 1..4) {
                withTimeout(180000){app.engine.load(model.path,options.copy(maxOutputTokens=cap))}
                val boundary=request("'한글 강아지🙂'를 스무 번 그대로 이어 써줘. 설명이나 다른 글은 쓰지 마.",utf8Cap=cap)
                val decoded=checkNotNull(boundary.decodeTokens)
                if(boundary.reason==GenerationStopReason.MAX_OUTPUT) {
                    assertTrue("UTF-8 completion must stay within the reserved eight tokens",decoded in cap..cap+8)
                    assertEquals(true,boundary.capReached)
                } else {
                    assertEquals(GenerationStopReason.EOG,boundary.reason)
                    assertTrue(decoded<cap)
                }
            }

            withTimeout(180000){app.engine.load(model.path,options.copy(maxOutputTokens=2048))}
            val firstDelta=CompletableDeferred<Unit>()
            val cancelled=async(Dispatchers.Default) {runCatching {
                app.engine.generateChatEvents("1부터 10000까지 숫자를 한 줄에 하나씩 계속 작성해줘.","숫자만 출력한다.").collect {event->
                    if(event is GenerationEvent.Delta&&event.text.isNotEmpty())firstDelta.complete(Unit)
                }
            }}
            withTimeout(180000){select<Unit> {
                firstDelta.onAwait { }
                cancelled.onAwait { error("Synthetic stream ended before the cancellation checkpoint") }
            }}
            app.engine.cancel()
            val cancelledResult=withTimeout(30000){cancelled.await()}
            assertTrue("Cancellation must preserve the old throwing Flow contract",cancelledResult.exceptionOrNull() is CancellationException)
            val cancellation=checkNotNull(app.engine.lastGeneration.value)
            assertEquals(GenerationStopReason.CANCELLED,cancellation.reason)
            record(cancellation)
        } catch(error:Throwable) {failure=error.javaClass.simpleName;throw error}
        finally {
            withContext(NonCancellable+Dispatchers.IO) {
                if(ownsInference)app.engine.unload()
                val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks")
                check(directory.isDirectory||directory.mkdirs())
                File(directory,"generation-events-${System.currentTimeMillis()}.json").writeText(buildJsonObject {
                    put("synthetic_inputs_only",true);put("model_id",modelId);put("model_sha256",modelSha)
                    put("events",JsonArray(records));put("failure_class",failure)
                }.toString())
            }
            if(busy)app.updates.setBusy(false)
            if(ownsInference)RuntimeGate.inference.set(false)
            if(ownsBenchmark)RuntimeGate.benchmark.set(false)
        }
    }
}
