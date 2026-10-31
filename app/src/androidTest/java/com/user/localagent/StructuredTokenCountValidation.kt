package com.user.localagent

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly gated native model test. Synthetic input only; no phone records, tools, mail, downloads or UI. */
@RunWith(AndroidJUnit4::class)
class StructuredTokenCountValidation {
    @Test fun exactSchemaRenderedCountMatchesActualNativeGenerationPrefill()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("structuredTokenCountApproved=true is required",args.getString("structuredTokenCountApproved")=="true")
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val output=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks/structured-token-count.json")
        val started=SystemClock.elapsedRealtime()
        var stage="acquire_runtime";var failure:String?=null;var benchmark=false;var inference=false
        var engine:LlamaCppEngine?=null;var modelId:String?=null;var modelSha:String?=null
        var plain:Int?=null;var structured:Int?=null;var observed:Int?=null;var alteredSchemaCount:Int?=null
        var stop:String?=null;var succeeded=false;var nativeProgressVerified=false
        try {withTimeout(10*60_000L) {
            check(!app.runs.busy.value&&app.runs.queued.value.isEmpty()&&!app.imageStudio.state.value.running)
            benchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(benchmark)
            inference=RuntimeGate.inference.compareAndSet(false,true);check(inference)
            app.cancelIdleUnload();app.updates.setBusy(true);app.engine.unload()
            stage="select_cached_model";app.models.refresh()
            val requested=args.getString("structuredTokenModelId")?:"gemma-e2b"
            val model=app.models.models.value.singleOrNull {it.id==requested&&it.runtime=="gguf"&&it.verified&&File(it.path).isFile}
                ?:error("Required verified cached GGUF model is unavailable; no model was downloaded")
            modelId=model.id;modelSha=model.sha256
            val native=LlamaCppEngine(context);engine=native
            native.load(model.path,InferenceOptions(contextSize=4096,thinking=false,backend="CPU",maxOutputTokens=64,cpuThreads=2,deviceOnly=true))
            val prompt="Return exactly the schema object with ok=true. This is a synthetic token-count check."
            val system="Return JSON only. Do not call tools."
            val history=listOf(InferenceTurn("user","Synthetic previous question."),InferenceTurn("assistant","Synthetic previous response."))
            fun schema(description:String)=buildJsonObject {
                put("type","object");put("additionalProperties",false);put("required",buildJsonArray {add("ok")})
                put("properties",buildJsonObject {put("ok",buildJsonObject {put("type","boolean");put("const",true);put("description",description)})})
            }.toString()
            val exactSchema=schema("Synthetic expected result")
            stage="count_schema_template"
            plain=native.countChatTokens(prompt,system,history)
            structured=native.countStructuredChatTokens(prompt,system,exactSchema,history)
            alteredSchemaCount=native.countStructuredChatTokens(prompt,system,schema("Synthetic description ".repeat(8)),history)
            assertTrue(checkNotNull(plain)>0);assertTrue(checkNotNull(structured)>0)
            assertTrue(checkNotNull(structured)+64+16<=4096)
            assertNull("Counting must not synthesize a generation result",native.lastGeneration.value)
            assertTrue(runCatching {native.countStructuredChatTokens(prompt,system,"[]",history)}.isFailure)
            stage="actual_structured_generation"
            val text=StringBuilder();var terminal:GenerationSummary?=null
            native.generateStructuredEvents(prompt,system,exactSchema,history).collect {event->when(event){
                is GenerationEvent.Delta->{check(text.length+event.text.length<=4000);text.append(event.text)}
                is GenerationEvent.Finished->terminal=event.summary
            }}
            val summary=checkNotNull(terminal?:native.lastGeneration.value)
            stop=summary.reason.name;observed=summary.prefillTokens
            assertEquals(GenerationEvidence.NATIVE,summary.evidence)
            assertEquals(0,summary.historyTurnsDropped)
            assertEquals("Exact schema count must match actual prefill",structured,observed)
            assertEquals(structured,native.lastMetrics.value?.prefillTokens)
            assertEquals(GenerationStopReason.EOG,summary.reason);assertFalse(summary.capReached==true)
            assertTrue(Json.parseToJsonElement(text.toString()).jsonObject.getValue("ok").jsonPrimitive.boolean)
            val progress=checkNotNull(native.nativeProgress.value)
            assertEquals(summary.requestId,progress.requestId)
            assertEquals(NativeGenerationPhase.FINISHED,progress.phase)
            assertEquals(observed,progress.totalPromptTokens)
            assertEquals(observed,progress.processedPromptTokens)
            assertEquals(summary.decodeTokens,progress.sampledTokens)
            assertEquals(text.toString().toByteArray(Charsets.UTF_8).size.toLong(),progress.visibleUtf8Bytes)
            nativeProgressVerified=true
            assertEquals("Counter remains stable after generation",structured,native.countStructuredChatTokens(prompt,system,exactSchema,history))
            succeeded=true;stage="complete"
        }} catch(error:Throwable) {failure=error.javaClass.simpleName;throw error}
        finally {withContext(NonCancellable+Dispatchers.IO) {
            try {engine?.cancel();engine?.unload()}finally {
                if(inference){app.updates.setBusy(false);RuntimeGate.inference.set(false);app.scheduleIdleUnload()}
                if(benchmark)RuntimeGate.benchmark.set(false)
                check(output.parentFile?.isDirectory==true||output.parentFile?.mkdirs()==true)
                output.writeText(buildJsonObject {
                    put("scope","synthetic_native_schema_count");put("app_version",BuildConfig.VERSION_CODE)
                    put("stage",stage);put("passed",succeeded);put("failure_class",failure)
                    put("elapsed_ms",SystemClock.elapsedRealtime()-started);put("model_id",modelId);put("model_sha256",modelSha)
                    put("plain_chat_tokens",plain);put("structured_chat_tokens",structured);put("altered_schema_tokens",alteredSchemaCount)
                    put("observed_generation_prefill_tokens",observed);put("stop_reason",stop)
                    put("native_progress_finished_counts_and_visible_bytes_verified",nativeProgressVerified)
                    put("personal_data_read",false);put("network_used",false);put("text_or_audio_exported",false)
                }.toString())
            }
        }}
    }
}
