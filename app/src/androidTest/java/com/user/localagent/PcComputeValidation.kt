package com.user.localagent

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.inference.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcComputeValidation {
    private fun app():AppContainer {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pcComputeApproved")=="true")
        return (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalAgentApplication).container
    }
    @Test fun registerApprovedPc()=runBlocking {
        val app=app();val context=app.applicationContext
        assertTrue("Grant Local network through Android's consent dialog first",PcLocalNetwork.allowed(context))
        val file=File(context.getExternalFilesDir(null),"diagnostics/pc-compute-pairing.json").canonicalFile
        require(file.isFile&&file.length() in 50..8192)
        try{PcComputeSettings.get(context).importPairing(file.readText())}finally{check(file.delete())}
        assertNotNull(PcTransport(context).available())
        assertTrue(PcComputeSettings.get(context).state.value.paired)
    }
    @Test fun pcSchemaUnicodeAndTokenizer()=runBlocking {
        val app=app();app.models.refresh()
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        
        val owned=openOwnedText(app);val engine=owned.engine
        try {
            withTimeout(180_000){engine.load(model.path,InferenceOptions(maxOutputTokens=80))}
            assertEquals("pc",engine.executionLocation)
            val count=engine.countChatTokens("Reply with schema values.","Follow the JSON schema.",emptyList());assertTrue(count!=null&&count>0)
            val schema="""{"type":"object","properties":{"ok":{"type":"boolean","const":true},"label":{"type":"string","enum":["한글😀"]}},"required":["ok","label"],"additionalProperties":false}"""
            val text=StringBuilder();var terminal:GenerationSummary?=null
            withTimeout(180_000){engine.generateStructuredEvents("Return ok true and label 한글😀.","Follow the requested JSON schema.",schema).collect{when(it){is GenerationEvent.Delta->text.append(it.text);is GenerationEvent.Finished->terminal=it.summary}}}
            assertEquals(true,Json.parseToJsonElement(text.toString()).jsonObject["ok"]?.jsonPrimitive?.boolean)
            assertEquals("한글😀",Json.parseToJsonElement(text.toString()).jsonObject["label"]?.jsonPrimitive?.content)
            assertEquals(GenerationStopReason.EOG,terminal?.reason)
            assertEquals(GenerationEvidence.SDK,terminal?.evidence)
            report("pc-text.json",buildJsonObject{put("passed",true);put("execution_location",engine.executionLocation);put("model_sha256",model.sha256);put("native_tokenizer_count",count);put("synthetic_output",text.toString())})
        }finally{owned.close()}
    }
    @Test fun pcOffUsesPhone()=runBlocking {
        val app=app();app.models.refresh()
        
        assertNull("Harness must stop the actual PC worker before this test",PcTransport(app.applicationContext).available())
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        val owned=openOwnedText(app);val engine=owned.engine
        try {
            withTimeout(180_000){engine.load(model.path,InferenceOptions(maxOutputTokens=16))}
            assertEquals("phone",engine.executionLocation)
            val text=StringBuilder();withTimeout(180_000){engine.generateChat("Reply exactly OK.","Return a short answer.").collect{text.append(it)}}
            assertTrue(text.toString().contains("OK",ignoreCase=true))
            report("pc-off-fallback.json",buildJsonObject{put("passed",true);put("execution_location",engine.executionLocation);put("synthetic_output",text.toString())})
        }finally{owned.close()}
    }
    @Test fun pcDisconnectDuringReplyRetriesOnPhone()=runBlocking {
        val app=app();app.models.refresh()
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        val owned=openOwnedText(app);val engine=owned.engine
        try {
            withTimeout(180_000){engine.load(model.path,InferenceOptions(maxOutputTokens=128))}
            assertEquals("pc",engine.executionLocation)
            report("pc-disconnect-ready.json",buildJsonObject{put("phase","ready_for_worker_shutdown")})
            // The external harness stops the real worker after this checkpoint; no simulated backend.
            val text=StringBuilder()
            withTimeout(240_000){engine.generateChat("List the numbers from one to one thousand, one per line.","Continue the requested sequence.").collect{text.append(it)}}
            assertEquals("phone",engine.executionLocation)
            assertTrue(text.isNotBlank())
            assertTrue(engine.lastGeneration.value?.reason in setOf(GenerationStopReason.EOG,GenerationStopReason.MAX_OUTPUT))
            report("pc-midreply-fallback.json",buildJsonObject{put("passed",true);put("execution_location",engine.executionLocation);put("synthetic_output",text.toString())})
        }finally{owned.close()}
    }
    @Test fun explicitPcCancelDoesNotRestartOnPhone()=runBlocking {
        val app=app();app.models.refresh()
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        val owned=openOwnedText(app);val engine=owned.engine
        try {
            withTimeout(180_000){engine.load(model.path,InferenceOptions(maxOutputTokens=1024))}
            assertEquals("pc",engine.executionLocation)
            var deltas=0
            val task=async{engine.generateChatEvents("List the numbers from one to one thousand, one per line.","Continue the requested sequence.").collect{if(it is GenerationEvent.Delta)deltas++}}
            delay(500);engine.cancel()
            val stopped=runCatching{withTimeout(30_000){task.await()}}.exceptionOrNull()
            assertTrue("Cancellation must finish the request; a timeout is not a pass",stopped is CancellationException&&task.isCancelled)
            assertEquals("pc",engine.executionLocation);assertEquals(0,deltas)
            report("pc-cancel.json",buildJsonObject{put("passed",true);put("execution_location",engine.executionLocation);put("published_deltas",deltas);put("phone_retry",false)})
        }finally{owned.close()}
    }
    @Test fun phoneOnlyIgnoresAvailablePc()=runBlocking {
        val app=app();app.models.refresh()
        assertNotNull(PcTransport(app.applicationContext).available())
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        check(RuntimeGate.inference.compareAndSet(false,true))
        try{withTimeout(180_000){app.engine.load(model.path,InferenceOptions(deviceOnly=true))};assertEquals("phone",app.engine.executionLocation)}
        finally{withContext(NonCancellable){app.engine.unload();RuntimeGate.inference.set(false)}}
    }
    @Test fun pcImageIsStoredAndExportedOnPhone()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pcImageApproved")=="true")
        val app=app()
        val imageSettings=PcComputeSettings.get(app.applicationContext)
        val originalImageModel=imageSettings.state.value.imageModelId
        val selectedImageModel=InstrumentationRegistry.getArguments().getString("pcImageModelId")?:com.user.localagent.core.PcImageModelCatalog.QUALITY_ID
        val imagePin=com.user.localagent.core.PcImageModelCatalog.selection(selectedImageModel)
        imageSettings.imageModel(selectedImageModel)
        val owned=openOwnedText(app,enableImages=true);val engine=owned.engine
        try {
            val studio=com.user.localagent.images.ImageStudioController(app.applicationContext,app,requestEngine=engine,ownerRunId=owned.owner)
            assertNotNull(PcTransport(app.applicationContext).available(owned.owner))
            val monitor=launch {
                while(isActive){
                    val state=studio.state.value
                    report("pc-image-progress.json",buildJsonObject {put("modelId",selectedImageModel);put("phase",state.phase);put("step",state.step);put("steps",state.totalSteps);put("running",state.running)})
                    delay(5000)
                }
            }
            val result=try {withTimeout(4*60*60_000L){studio.generateInCurrentRequest("A photorealistic still life on a wooden desk. A red ceramic mug is on the left and a blue glass bottle is on the right. A small cream card between them reads ZIPHAP. Soft window light, realistic reflections, fine ceramic texture, natural perspective.",steps=50,seed=12345)}}finally{monitor.cancelAndJoin()}
            assertEquals(1024,result.width);assertEquals(1024,result.height);assertTrue(result.file.isFile)
            val note=checkNotNull(app.repository.latestNote("generated-images"));val metadata=Json.parseToJsonElement(note.body).jsonObject
            assertEquals("pc",metadata["computeLocation"]?.jsonPrimitive?.content)
            assertTrue(metadata["effectivePrompt"]!!.jsonPrimitive.content.none{it in '\uAC00'..'\uD7A3'})
            assertEquals(imagePin.sha256,metadata["modelSha256"]?.jsonPrimitive?.content)
            val gallery=studio.exportToGallery()
            val bytes=app.applicationContext.contentResolver.openInputStream(gallery)!!.use{it.readBytes()}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            assertEquals(1024,bounds.outWidth);assertEquals(1024,bounds.outHeight)
            fun sha(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt()and 255)}
            assertEquals(sha(result.file.readBytes()),sha(bytes))
            assertNull(engine.residentModelPath);assertNull(studio.residentImageModelPath)
            report("pc-image-"+(if(selectedImageModel==com.user.localagent.core.PcImageModelCatalog.SDXL_ID)"sdxl"else"qwen")+"-phone-save.json",buildJsonObject{put("passed",true);put("width",1024);put("height",1024);put("phone_file",result.file.name);put("gallery_readback",true);put("sha256",sha(bytes));put("model_sha256",imagePin.sha256);put("model_id",selectedImageModel)})
            // Keep this owner-requested sample on the phone for viewing; PC scratch must be released.
        }finally{owned.close();if(imageSettings.state.value.imageModelId==selectedImageModel)imageSettings.imageModel(originalImageModel)}
    }
    @Test fun actualPcComputationsOverlapOnePhoneGeneration()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pcDualConcurrencyApproved")=="true")
        val app=app();app.models.refresh()
        val model=app.models.models.value.single {it.id=="gemma-e2b"&&it.verified}
        val owned=openOwnedText(app)
        val pc=owned.engine
        val phone=AutomaticComputeEngine(app.applicationContext,UUID.randomUUID().toString(),beforePhoneLoad={app.engine.unloadPhoneResidency()})
        val phoneFinished=CompletableDeferred<Unit>()
        val pcStarted=CompletableDeferred<Unit>()
        val intervals=java.util.concurrent.CopyOnWriteArrayList<Pair<Long,Long>>()
        var pcTask:Deferred<Unit>?=null
        var phoneStart=0L;var phoneEnd=0L;var phoneChars=0
        try {
            withTimeout(180_000){pc.load(model.path,InferenceOptions(maxOutputTokens=48))}
            assertEquals("pc",pc.executionLocation)
            val runningPc=async(Dispatchers.IO){
                var round=0
                while(isActive&&!phoneFinished.isCompleted){
                    val start=SystemClock.elapsedRealtime();var chars=0;var finish:GenerationSummary?=null
                    pcStarted.complete(Unit)
                    pc.generateChatEvents("Public CPU overlap check ${round++}. Write a short list of eight ordinary household objects.",
                        "Return only a brief list. This is public synthetic test text.").collect {event->
                        when(event){is GenerationEvent.Delta->chars+=event.text.length;is GenerationEvent.Finished->finish=event.summary}
                    }
                    check(pc.executionLocation=="pc"&&chars>0&&finish?.reason in setOf(GenerationStopReason.EOG,GenerationStopReason.MAX_OUTPUT))
                    intervals+=start to SystemClock.elapsedRealtime()
                }
            }
            pcTask=runningPc
            withTimeout(30_000){pcStarted.await()}
            withTimeout(180_000){phone.load(model.path,InferenceOptions(maxOutputTokens=24))}
            assertEquals("phone",phone.executionLocation)
            phoneStart=SystemClock.elapsedRealtime()
            var finish:GenerationSummary?=null
            withTimeout(180_000){phone.generateChatEvents("Say briefly in Korean that this is a local test.","Return one short sentence.").collect {event->
                when(event){is GenerationEvent.Delta->phoneChars+=event.text.length;is GenerationEvent.Finished->finish=event.summary}
            }}
            phoneEnd=SystemClock.elapsedRealtime();phoneFinished.complete(Unit)
            assertTrue(phoneChars>0)
            assertTrue(finish?.reason in setOf(GenerationStopReason.EOG,GenerationStopReason.MAX_OUTPUT))
            withTimeout(60_000){runningPc.await()}
            assertTrue("A real completed PC generation must overlap the phone generation interval",
                intervals.any {it.first<phoneEnd&&it.second>phoneStart})
            report("pc-phone-overlap.json",buildJsonObject {
                put("passed",true);put("pc_completed_generations",intervals.size);put("phone_chars",phoneChars)
                put("phone_generation_ms",phoneEnd-phoneStart);put("pc_phone_interval_overlap",true)
                put("pc_route","pc");put("phone_route","phone");put("model_sha256",model.sha256)
                put("private_input_used",false);put("external_actions_executed",false);put("app_phone_gate_owner_verified",true)
            })
        }finally{
            phoneFinished.complete(Unit)
            withContext(NonCancellable){pc.cancel();pcTask?.cancelAndJoin();try{phone.unload()}finally{owned.close()}}
        }
    }

    /** The external test harness disconnects the paired worker after the ready checkpoint. */
    @Test fun actualPcDisconnectWaitsForPhoneAndCancelsWithoutDisturbingIt()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pcDualFaultInjectionApproved")=="true")
        val app=app();app.models.refresh()
        val model=app.models.models.value.single {it.id=="gemma-e2b"&&it.verified}
        val owned=openOwnedText(app);val pc=owned.engine
        val phoneOwner=UUID.randomUUID().toString()
        val phone=AutomaticComputeEngine(app.applicationContext,phoneOwner,beforePhoneLoad={app.engine.unloadPhoneResidency()})
        var task:Deferred<Unit>?=null;var published=0
        try {
            withTimeout(180_000){pc.load(model.path,InferenceOptions(maxOutputTokens=64));phone.load(model.path,InferenceOptions(maxOutputTokens=16))}
            assertEquals("pc",pc.executionLocation);assertEquals("phone",phone.executionLocation)
            report("pc-dual-disconnect-ready.json",buildJsonObject {put("phase","disconnect_owned_pc_worker_now");put("phone_model_resident",true)})
            withTimeout(90_000){while(PcTransport(app.applicationContext).available(owned.owner)!=null)delay(500)}
            val waiting=async(Dispatchers.IO){pc.generateChatEvents("List ordinary household objects.","Public synthetic text only.").collect {if(it is GenerationEvent.Delta)published+=it.text.length}}
            task=waiting
            withTimeout(240_000){pc.waitingForResource.first {it==ComputeResource.PHONE}}
            assertEquals(phoneOwner,RuntimeGate.inference.owner.value);assertEquals(model.path,phone.residentModelPath)
            assertEquals(0,published)
            pc.cancel();waiting.cancelAndJoin();pc.unload()
            assertEquals(phoneOwner,RuntimeGate.inference.owner.value)
            val text=StringBuilder()
            withTimeout(180_000){phone.generateChat("Say briefly OK.","Return a short answer.").collect {text.append(it)}}
            assertTrue(text.isNotBlank())
            report("pc-dual-fallback-cancel.json",buildJsonObject {
                put("passed",true);put("fallback_waited_for_phone",true);put("cancelled_waiter_started_no_phone_model",true)
                put("other_phone_run_remained_usable",true);put("discarded_pc_output_not_published",published==0)
                put("private_input_used",false);put("external_actions_executed",false)
            })
        }finally{withContext(NonCancellable){pc.cancel();task?.cancelAndJoin();try{phone.unload()}finally{owned.close()}}}
    }

    private class OwnedText(val owner:String,val engine:AutomaticComputeEngine,private val app:AppContainer,
        private val previousText:Boolean,private val previousImages:Boolean,private val busy:java.io.Closeable,private val admission:Any){
        suspend fun close()=withContext(NonCancellable){
            try {engine.unload();busy.close()}
            finally {
                val settings=PcComputeSettings.get(app.applicationContext)
                try {settings.automaticText(previousText);settings.preferPcImages(previousImages)}
                finally {app.runs.queueMutation.unlock(admission);app.scheduleIdleUnload()}
            }
        }
    }
    private fun openOwnedText(app:AppContainer,enableImages:Boolean=false):OwnedText {
        val owner=UUID.randomUUID().toString();val admission=Any()
        check(app.runs.queueMutation.tryLock(admission)){"A queue change is active"}
        val settings=PcComputeSettings.get(app.applicationContext);val previous=settings.state.value
        var busy:java.io.Closeable?=null
        try {
            check(!app.runs.hasActiveRuns.value&&app.runs.queued.value.isEmpty()&&!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()&&!RuntimeGate.pc.get()){"Existing owner work has priority"}
            busy=app.updates.acquireBusy(owner);app.cancelIdleUnload()
            settings.automaticText(true);if(enableImages)settings.preferPcImages(true)
            val engine=AutomaticComputeEngine(app.applicationContext,owner,beforePhoneLoad={app.engine.unloadPhoneResidency()})
            return OwnedText(owner,engine,app,previous.automaticText,previous.preferPcImages,busy,admission)
        }catch(error:Throwable){
            try {busy?.close();settings.automaticText(previous.automaticText);settings.preferPcImages(previous.preferPcImages)}
            finally {app.runs.queueMutation.unlock(admission)}
            throw error
        }
    }
    private fun report(name:String,value:JsonObject){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"benchmarks").apply{mkdirs()}
        File(folder,name).writeText(value.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","PC compute validation saved: $name\n")})
    }
}
