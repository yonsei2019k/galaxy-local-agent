package com.user.localagent

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.inference.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
        val settings=PcComputeSettings.get(app.applicationContext);settings.automaticText(true)
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            withTimeout(180_000){app.engine.load(model.path,InferenceOptions(maxOutputTokens=80))}
            assertEquals("pc",app.engine.executionLocation)
            val count=app.engine.countChatTokens("Reply with schema values.","Follow the JSON schema.",emptyList());assertTrue(count!=null&&count>0)
            val schema="""{"type":"object","properties":{"ok":{"type":"boolean","const":true},"label":{"type":"string","enum":["한글😀"]}},"required":["ok","label"],"additionalProperties":false}"""
            val text=StringBuilder();var terminal:GenerationSummary?=null
            withTimeout(180_000){app.engine.generateStructuredEvents("Return ok true and label 한글😀.","Follow the requested JSON schema.",schema).collect{when(it){is GenerationEvent.Delta->text.append(it.text);is GenerationEvent.Finished->terminal=it.summary}}}
            assertEquals(true,Json.parseToJsonElement(text.toString()).jsonObject["ok"]?.jsonPrimitive?.boolean)
            assertEquals("한글😀",Json.parseToJsonElement(text.toString()).jsonObject["label"]?.jsonPrimitive?.content)
            assertEquals(GenerationStopReason.EOG,terminal?.reason)
            assertEquals(GenerationEvidence.SDK,terminal?.evidence)
            report("pc-text.json",buildJsonObject{put("passed",true);put("execution_location",app.engine.executionLocation);put("model_sha256",model.sha256);put("native_tokenizer_count",count);put("synthetic_output",text.toString())})
        }finally{withContext(NonCancellable){app.engine.unload();RuntimeGate.inference.set(false)}}
    }
    @Test fun pcOffUsesPhone()=runBlocking {
        val app=app();app.models.refresh()
        val settings=PcComputeSettings.get(app.applicationContext);settings.automaticText(true)
        assertNull("Harness must stop the actual PC worker before this test",PcTransport(app.applicationContext).available())
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            withTimeout(180_000){app.engine.load(model.path,InferenceOptions(maxOutputTokens=16))}
            assertEquals("phone",app.engine.executionLocation)
            val text=StringBuilder();withTimeout(180_000){app.engine.generateChat("Reply exactly OK.","Return a short answer.").collect{text.append(it)}}
            assertTrue(text.toString().contains("OK",ignoreCase=true))
            report("pc-off-fallback.json",buildJsonObject{put("passed",true);put("execution_location",app.engine.executionLocation);put("synthetic_output",text.toString())})
        }finally{withContext(NonCancellable){app.engine.unload();RuntimeGate.inference.set(false)}}
    }
    @Test fun pcDisconnectDuringReplyRetriesOnPhone()=runBlocking {
        val app=app();app.models.refresh()
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            withTimeout(180_000){app.engine.load(model.path,InferenceOptions(maxOutputTokens=128))}
            assertEquals("pc",app.engine.executionLocation)
            report("pc-disconnect-ready.json",buildJsonObject{put("phase","ready_for_worker_shutdown")})
            // The external harness stops the real worker after this checkpoint; no simulated backend.
            val text=StringBuilder()
            withTimeout(240_000){app.engine.generateChat("List the numbers from one to one thousand, one per line.","Continue the requested sequence.").collect{text.append(it)}}
            assertEquals("phone",app.engine.executionLocation)
            assertTrue(text.isNotBlank())
            assertTrue(app.engine.lastGeneration.value?.reason in setOf(GenerationStopReason.EOG,GenerationStopReason.MAX_OUTPUT))
            report("pc-midreply-fallback.json",buildJsonObject{put("passed",true);put("execution_location",app.engine.executionLocation);put("synthetic_output",text.toString())})
        }finally{withContext(NonCancellable){app.engine.unload();RuntimeGate.inference.set(false)}}
    }
    @Test fun explicitPcCancelDoesNotRestartOnPhone()=runBlocking {
        val app=app();app.models.refresh()
        val model=app.models.models.value.single{it.id=="gemma-e2b"&&it.verified}
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            withTimeout(180_000){app.engine.load(model.path,InferenceOptions(maxOutputTokens=1024))}
            assertEquals("pc",app.engine.executionLocation)
            var deltas=0
            val task=async{app.engine.generateChatEvents("List the numbers from one to one thousand, one per line.","Continue the requested sequence.").collect{if(it is GenerationEvent.Delta)deltas++}}
            delay(500);app.engine.cancel()
            val stopped=runCatching{withTimeout(30_000){task.await()}}.exceptionOrNull()
            assertTrue("Cancellation must finish the request; a timeout is not a pass",stopped is CancellationException&&task.isCancelled)
            assertEquals("pc",app.engine.executionLocation);assertEquals(0,deltas)
            report("pc-cancel.json",buildJsonObject{put("passed",true);put("execution_location",app.engine.executionLocation);put("published_deltas",deltas);put("phone_retry",false)})
        }finally{withContext(NonCancellable){app.engine.unload();RuntimeGate.inference.set(false)}}
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
        val app=app();val settings=PcComputeSettings.get(app.applicationContext)
        assertNotNull(PcTransport(app.applicationContext).available());settings.preferPcImages(true)
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            val result=withTimeout(45*60_000L){app.imageStudio.generateInCurrentRequest("나무 테이블 왼쪽에는 빨간 도자기 머그잔, 오른쪽에는 파란 유리병이 있는 사실적인 제품 사진. 부드러운 창문 빛, 선명한 물체와 도자기 질감. 글자는 넣지 마세요.",steps=50,seed=12345)}
            assertEquals(1024,result.width);assertEquals(1024,result.height);assertTrue(result.file.isFile)
            val note=checkNotNull(app.repository.latestNote("generated-images"));val metadata=Json.parseToJsonElement(note.body).jsonObject
            assertEquals("pc",metadata["computeLocation"]?.jsonPrimitive?.content)
            assertTrue(metadata["effectivePrompt"]!!.jsonPrimitive.content.none{it in '\uAC00'..'\uD7A3'})
            assertEquals(RemoteImageClient.MODEL_SHA,metadata["modelSha256"]?.jsonPrimitive?.content)
            val gallery=app.imageStudio.exportToGallery()
            val bytes=app.applicationContext.contentResolver.openInputStream(gallery)!!.use{it.readBytes()}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            assertEquals(1024,bounds.outWidth);assertEquals(1024,bounds.outHeight)
            fun sha(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt()and 255)}
            assertEquals(sha(result.file.readBytes()),sha(bytes))
            assertNull(app.engine.residentModelPath);assertNull(app.imageStudio.residentImageModelPath)
            report("pc-image-phone-save.json",buildJsonObject{put("passed",true);put("width",1024);put("height",1024);put("phone_file",result.file.name);put("gallery_readback",true);put("sha256",sha(bytes));put("model_sha256",RemoteImageClient.MODEL_SHA)})
            // Keep this owner-requested sample on the phone for viewing; PC scratch must be released.
        }finally{RuntimeGate.inference.set(false)}
    }
    private fun report(name:String,value:JsonObject){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"benchmarks").apply{mkdirs()}
        File(folder,name).writeText(value.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","PC compute validation saved: $name\n")})
    }
}
