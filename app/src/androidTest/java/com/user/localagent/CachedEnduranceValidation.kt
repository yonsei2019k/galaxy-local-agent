package com.user.localagent

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.BenchmarkRunner
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceOptions
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Cached weights only. Never resolves a remote model, downloads, imports, or selects a default. */
@RunWith(AndroidJUnit4::class)
class CachedEnduranceValidation {
    @Test fun installedVerifiedGgufCompletesTenMinutesOnThePhoneWithoutChangingOwnerModels()=runBlocking<Unit>(Dispatchers.Default){
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("cachedEnduranceApproved=true required",arguments.getString("cachedEnduranceApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val started=SystemClock.elapsedRealtime()
        val directory=File(context.filesDir,"validation-reports").apply {check(isDirectory||mkdirs())}
        val receipt=File(directory,"cached-endurance-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        val preferences=context.getSharedPreferences("model-selection",0)
        val chatPreferences=context.getSharedPreferences("chat-preferences",0)
        val selectionBefore=preferences.all.toMap()
        var chatModelBefore:String?=null;var chatModelCaptured=false
        var stage="preflight";var failureClass:String?=null;var modelId:String?=null;var modelSha:String?=null
        var modelFile:File?=null;var modelBytes:Long?=null;var benchmarkFile:File?=null
        var benchmarkName:String?=null;var primaryFailure:Throwable?=null
        var hashUnchanged=false;var selectionUnchanged=false;var busyOwned=false;var runnerAttempted=false
        fun checkpoint(){
            val json=buildJsonObject {
                put("cached_only",true);put("device_only",true);put("stage",stage)
                put("elapsed_ms",SystemClock.elapsedRealtime()-started);put("model_id",modelId);put("model_sha256",modelSha)
                put("model_bytes",modelBytes);put("benchmark_filename",benchmarkFile?.name?:benchmarkName)
                put("cached_sha_unchanged",hashUnchanged);put("owner_selection_unchanged",selectionUnchanged)
                put("failure_class",failureClass)
            }.toString()
            val temporary=File(receipt.path+".tmp");temporary.writeText(json)
            check(temporary.renameTo(receipt)){"Cannot finalize private cached-endurance receipt"}
        }
        fun requireIdle(){
            check(!app.runs.busy.value&&app.runs.activeRunId==null&&app.runs.queued.value.isEmpty()){ "An owner chat request is active or queued" }
            check(!app.imageStudio.state.value.running){ "An image request is active" }
            check(!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()){ "Another native task owns the runtime" }
            check(preferences.all==selectionBefore){"Owner model selection changed before the endurance run"}
            check(!chatModelCaptured||chatPreferences.getString("model",null)==chatModelBefore){"Owner chat model choice changed before the endurance run"}
        }
        suspend fun digest(file:File):String=withContext(Dispatchers.IO){
            val sha=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(512*1024);while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;sha.update(buffer,0,count)}}
            sha.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
        }
        try {
            withTimeout(15*60_000L){
                // Resolve lazy coordinator recovery/queue restoration before trusting its idle flags.
                // This does not suppress or cancel an owner's recovered work.
                check(!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()){"Another native task is active"}
                stage="waiting_for_chat_initialization";checkpoint()
                val coordinator=withContext(Dispatchers.Main.immediate){app.runs}
                withTimeout(30_000){while(!coordinator.selectionReady.value){currentCoroutineContext().ensureActive();delay(25)}}
                withContext(Dispatchers.Main.immediate){yield()}
                check(app.repository.chat.queuedRuns().isEmpty()){"A durable owner request is queued"}
                chatModelBefore=chatPreferences.getString("model",null);chatModelCaptured=true
                requireIdle();checkpoint()
                app.cancelIdleUnload();app.updates.setBusy(true);busyOwned=true
                stage="refreshing_cached_index";checkpoint();app.models.refresh()
                check(preferences.all==selectionBefore){"Owner model selection changed during cached index refresh"}
                val requested=arguments.getString("cachedEnduranceModelId")
                val cached=app.models.models.value.filter {it.verified&&it.runtime=="gguf"&&!it.id.contains("mmproj")&&File(it.path).isFile}
                val selected=if(requested!=null)cached.firstOrNull {it.id==requested}
                    else cached.sortedBy {when(it.id){"gemma-e2b"->0;"qwen-2b"->1;"qwen-4b"->2;"gemma-e4b"->3;else->4}}.firstOrNull()
                val model=checkNotNull(selected){"A matching installed verified GGUF is required; this test never downloads one"}
                val file=File(model.path).canonicalFile
                check(file.extension.equals("gguf",true)&&file.isFile)
                modelFile=file;modelId=model.id;modelSha=model.sha256;modelBytes=file.length()
                stage="verifying_cached_sha_before";checkpoint()
                check(digest(file).equals(model.sha256,true)){"Installed model SHA-256 does not match its verified descriptor"}
                requireIdle()
                stage="running_ten_minute_native_benchmark";checkpoint();runnerAttempted=true
                benchmarkFile=coroutineScope {
                    val runner=async {BenchmarkRunner(context,app.engine).run(file.path,
                        InferenceOptions(contextSize=4096,backend="CPU",maxOutputTokens=512,thinking=false,deviceOnly=true),
                        enduranceMinutes=10,onProgress={next->stage=next.substringBefore(" ·");next.substringAfter(" · ","").takeIf {it.matches(Regex("benchmark-[0-9]+-[a-f0-9]{8}\\.json"))}?.let {benchmarkName=it};checkpoint()})}
                    val ownerActivity=launch {
                        while(runner.isActive){
                            // An idle view/OS Activity resume does not constitute owner work.
                            // Real requests and a changed owner model selection still interrupt us.
                            if(app.runs.busy.value||app.runs.activeRunId!=null||app.runs.queued.value.isNotEmpty()||app.imageStudio.state.value.running){
                                runner.cancel(CancellationException("Owner work resumed during reserved endurance run"));break
                            }
                            if(preferences.all!=selectionBefore||chatPreferences.getString("model",null)!=chatModelBefore){
                                runner.cancel(CancellationException("Owner model selection changed during reserved endurance run"));break
                            }
                            delay(250)
                        }
                    }
                    try{runner.await()}finally{withContext(NonCancellable){ownerActivity.cancelAndJoin()}}
                }
                stage="checking_actual_benchmark_report";checkpoint()
                val report=Json.parseToJsonElement(checkNotNull(benchmarkFile).readText()).jsonObject
                assertEquals("completed",report.getValue("stage").jsonPrimitive.content)
                assertEquals(10,report.getValue("requested_endurance_minutes").jsonPrimitive.int)
                assertTrue(report.getValue("elapsed_ms").jsonPrimitive.long>=10*60_000L)
                assertTrue(report.getValue("iterations").jsonPrimitive.int>0)
                assertEquals(JsonNull,report.getValue("failure"));assertEquals(JsonNull,report.getValue("unload_failure"))
                assertEquals("CPU",report.getValue("backend").jsonPrimitive.content)
                assertEquals(4096,report.getValue("context").jsonPrimitive.int)
                assertEquals(512,report.getValue("max_output_tokens").jsonPrimitive.int)
                assertFalse(report.getValue("thinking").jsonPrimitive.boolean)
                val samples=report.getValue("samples").jsonArray
                assertEquals(report.getValue("iterations").jsonPrimitive.int,samples.size)
                assertTrue(samples.all {it.jsonObject.getValue("status").jsonPrimitive.content=="completed"&&it.jsonObject.getValue("failure")==JsonNull})
                stage="verifying_cached_sha_after";checkpoint()
                hashUnchanged=file.isFile&&file.length()==modelBytes&&digest(file).equals(model.sha256,true)
                selectionUnchanged=preferences.all==selectionBefore&&chatPreferences.getString("model",null)==chatModelBefore
                check(hashUnchanged){"Cached model changed during the endurance validation"}
                check(selectionUnchanged){"Owner model selection changed during the endurance validation"}
                stage="completed";checkpoint()
            }
        }catch(error:Throwable){primaryFailure=error;failureClass=error.javaClass.simpleName;stage=if(error is CancellationException)"cancelled"else"failed";throw error}
        finally {
            withContext(NonCancellable+Dispatchers.IO){
                // The runner releases its own gates. Never clear a gate or unload a model belonging
                // to an owner request that started after that release.
                var cleanupFailure:Throwable?=null
                try{if(runnerAttempted&&!RuntimeGate.benchmark.get()&&
                    !app.runs.busy.value&&app.runs.activeRunId==null&&!app.imageStudio.state.value.running&&
                    RuntimeGate.inference.compareAndSet(false,true)){
                    try{if(modelFile?.canonicalPath==app.engine.residentModelPath?.let {File(it).canonicalPath}){app.engine.cancel();app.engine.unload()}}
                    finally{RuntimeGate.inference.set(false)}
                }}catch(error:Throwable){cleanupFailure=error;if(primaryFailure==null){failureClass=error.javaClass.simpleName;stage="cleanup_failed"}else primaryFailure!!.addSuppressed(error)}
                if(busyOwned&&!app.runs.busy.value&&!app.imageStudio.state.value.running)app.updates.setBusy(false)
                if(busyOwned)app.scheduleIdleUnload()
                checkpoint()
                if(primaryFailure==null)cleanupFailure?.let {throw it}
            }
        }
    }
}
