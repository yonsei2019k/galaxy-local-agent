package com.user.localagent

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Keeps one explicitly requested synthetic sample, without publishing to Gallery or other apps. */
@RunWith(AndroidJUnit4::class)
class ImageWorkflowValidation {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container

    @Test fun koreanPromptControllerStorageAndRelease()=runBlocking {
        assumeTrue("Explicit imageWorkflowApproved=true is required",InstrumentationRegistry.getArguments().getString("imageWorkflowApproved")=="true")
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks")
        val reportFile=File(directory,"image-workflow.json")
        val started=SystemClock.elapsedRealtime()
        var stage="starting";var failure:Throwable?=null;var accepted=false
        var outputName:String?=null;var width:Int?=null;var height:Int?=null
        var previewName:String?=null
        var metadataMatches=false;var llmUnloaded=false;var computeLeaseReleased=false
        val prompt="아침 햇살이 비치는 숲속 여우를 사실적인 야생동물 사진으로 만들어줘"
        fun save(next:String?=null){
            if(next!=null){stage=next;instrumentation.sendStatus(0,Bundle().apply{putString("stream","Image workflow QA: $stage\n")})}
            check(directory.isDirectory||directory.mkdirs())
            val json=buildJsonObject {
                put("timestamp",Instant.now().toString());put("scope","synthetic_example")
                put("stage",stage);put("elapsed_ms",SystemClock.elapsedRealtime()-started)
                put("prompt",prompt);put("seed",42);put("steps",4)
                put("accepted",accepted);put("outputFileName",outputName);put("width",width);put("height",height)
                put("syntheticPreviewFileName",previewName)
                put("metadata_file_name_matches",metadataMatches);put("llm_unloaded",llmUnloaded);put("compute_lease_released",computeLeaseReleased)
                put("gallery_published",false);put("shared_to_other_apps",false)
                put("failure",failure?.let {error->buildJsonObject {put("class",error.javaClass.name);put("message",error.message.orEmpty().replace(context.filesDir.path,"[app-files]").replace(Regex("https?://\\S+"),"[url]").take(1500));put("stack",error.stackTrace.take(12).joinToString("\n"))}}?:JsonNull)
            }.toString()
            val temporary=File(reportFile.path+".tmp");temporary.writeText(json);check(temporary.renameTo(reportFile))
        }
        try {
            save("verify_cached_model")
            assertFalse("Another benchmark is active",RuntimeGate.benchmark.get())
            assertFalse("Another inference request is active",RuntimeGate.inference.get())
            app.imageStudio.models.refresh()
            assertNotNull("Install and verify the image model before this explicit test",app.imageStudio.models.verifiedModelFile())
            val previousOutput=app.imageStudio.state.value.outputPath
            save("request_generation_from_visible_activity")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {accepted=app.imageStudio.generate(prompt,steps=4,seed=42L)}
                assertTrue("The controller must accept the synthetic image request",accepted)
            }
            // The actual controller/service owns the work. A Compose hierarchy or continued UI
            // focus is deliberately not required; the owner may return to ChatGPT immediately.
            save("await_controller_generation_in_background")
            withTimeout(10*60_000L){
                while(app.imageStudio.state.value.running||RuntimeGate.inference.get())delay(200)
            }
            val state=app.imageStudio.state.value
            assertNull("Controller must finish without a synthetic generation error",state.error)
            val output=File(requireNotNull(state.outputPath){"Controller did not return an output path"}).canonicalFile
            assertNotEquals("A new image must be generated",previousOutput,output.path)
            assertEquals(File(context.filesDir,"generated-images").canonicalFile,output.parentFile)
            assertTrue("Output must be a nonempty PNG",output.isFile&&output.length()>44&&output.extension=="png")
            outputName=output.name
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(output.path,bounds)
            width=bounds.outWidth;height=bounds.outHeight
            assertEquals(512,width);assertEquals(512,height)
            save("verify_encrypted_output_metadata")
            val note=app.repository.latestNote("generated-images")
            assertNotNull("A generated-image metadata record must be saved",note)
            val metadata=Json.parseToJsonElement(note!!.body).jsonObject
            metadataMatches=metadata["fileName"]?.jsonPrimitive?.content==output.name
            assertTrue("Metadata must reference the generated PNG",metadataMatches)
            assertEquals(prompt,metadata["prompt"]?.jsonPrimitive?.content)
            assertEquals(42L,metadata["seed"]?.jsonPrimitive?.long)
            val effective=metadata["effectivePrompt"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Local prompt preparation must produce nonblank text",effective.isNotBlank())
            llmUnloaded=app.engine.residentModelPath==null
            computeLeaseReleased=!RuntimeGate.inference.get()
            assertTrue("LLM weights must be released after the workflow",llmUnloaded)
            assertTrue("The controller compute lease must be released",computeLeaseReleased)
            // A single named synthetic diagnostic preview can be pulled through paired ADB.
            // It is not published to Gallery or shared with another application.
            withContext(Dispatchers.IO){
                val preview=File(directory,"image-workflow-example.png")
                output.inputStream().use {input->preview.outputStream().use {input.copyTo(it)}}
                previewName=preview.name
            }
            save("passed")
        }catch(error:Throwable){failure=error;stage="failed";throw error}
        finally{withContext(NonCancellable+Dispatchers.IO){
            if(failure!=null&&accepted&&app.imageStudio.state.value.running&&app.imageStudio.state.value.prompt==prompt){
                app.imageStudio.cancel()
                withTimeoutOrNull(30_000){while(app.imageStudio.state.value.running||RuntimeGate.inference.get())delay(200)}
            }
            save();instrumentation.sendStatus(0,Bundle().apply{putString("stream","Image workflow report saved: image-workflow.json\n")})
        }}
    }
}
