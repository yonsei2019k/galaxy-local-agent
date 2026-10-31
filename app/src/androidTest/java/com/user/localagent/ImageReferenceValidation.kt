package com.user.localagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same prompt/seed, different synthetic RGB references must change native img2img output.
 * Reads no real photos, does not download models, publish to Gallery or send any message.
 */
@RunWith(AndroidJUnit4::class)
class ImageReferenceValidation {
    @Test fun distinctReferencesAffectSameSeedAndOriginalsStayIntact()=runBlocking {
        assumeTrue("Explicit imageReferenceApproved=true is required",InstrumentationRegistry.getArguments().getString("imageReferenceApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val sources=mutableListOf<File>();val cases=mutableListOf<JsonObject>()
        val started=SystemClock.elapsedRealtime();var stage="starting";var failure:String?=null;var lease=false
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {check(isDirectory||mkdirs())}
        fun checkpoint(){File(directory,"image-reference.json").writeText(buildJsonObject {
            put("scope","synthetic_reference_images_only");put("stage",stage);put("failureClass",failure);put("elapsedMillis",SystemClock.elapsedRealtime()-started)
            put("mode","native_img2img");put("seed",42);put("requestedSteps",4);put("strength",.65f);put("cases",JsonArray(cases));put("personalPhotosRead",false)
        }.toString())}
        try {
            stage="verify_cached_model"
            checkpoint()
            app.imageStudio.models.refresh()
            assertNotNull("A cached verified image model is required; no automatic download",app.imageStudio.models.verifiedModelFile())
            assertFalse(RuntimeGate.benchmark.get())
            lease=RuntimeGate.inference.compareAndSet(false,true);assertTrue("Another local model is active",lease)
            val fixtureRoot=File(context.filesDir,"exports").apply {check(isDirectory||mkdirs())}.canonicalFile
            val hashes=mutableListOf<String>()
            for(index in 0..1){
                stage="create_synthetic_reference_$index"
                checkpoint()
                val source=File(fixtureRoot,"reference-${UUID.randomUUID()}.png");sources+=source
                val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawColor(if(index==0)Color.rgb(90,180,245)else Color.rgb(235,110,175))
                    paint.color=if(index==0)Color.rgb(25,150,50)else Color.rgb(90,40,165);canvas.drawRect(0f,330f,512f,512f,paint)
                    paint.color=Color.rgb(250,215,90);canvas.drawRect(145f,220f,365f,425f,paint)
                    paint.color=if(index==0)Color.RED else Color.CYAN;canvas.drawRect(125f,185f,385f,230f,paint)
                    paint.color=Color.rgb(70,45,25);canvas.drawRect(235f,315f,285f,425f,paint)
                    source.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                }finally {bitmap.recycle()}
                val original=sha(source)
                stage="native_img2img_$index"
                checkpoint()
                val result=withTimeout(10*60_000L){app.imageStudio.generateInCurrentRequest(
                    "a watercolor illustration of a small yellow house with a colorful roof, preserve the simple composition",steps=4,seed=42,
                    referenceUri=FileProvider.getUriForFile(context,"${context.packageName}.files",source),strength=.65f)}
                assertEquals("Original selected synthetic PNG must remain unchanged",original,sha(source))
                assertEquals(512,result.width);assertEquals(512,result.height);assertTrue(result.file.length()>44)
                val metadataNote=app.repository.latestNote("generated-images")
                val metadata=Json.parseToJsonElement(requireNotNull(metadataNote).body).jsonObject
                assertEquals(result.file.name,metadata.getValue("fileName").jsonPrimitive.content)
                assertEquals("img2img",metadata.getValue("mode").jsonPrimitive.content)
                assertEquals(original,metadata.getValue("referenceSha256").jsonPrimitive.content)
                assertEquals(512,metadata.getValue("referenceWidth").jsonPrimitive.int)
                assertEquals(512,metadata.getValue("referenceHeight").jsonPrimitive.int)
                assertEquals(4,metadata.getValue("steps").jsonPrimitive.int)
                assertEquals(3,metadata.getValue("samplingSteps").jsonPrimitive.int)
                assertEquals(.65f,metadata.getValue("strength").jsonPrimitive.float,0.0001f)
                assertTrue(metadata.getValue("changedPixelFraction").jsonPrimitive.double>0.0)
                val actualHash=metadata.getValue("outputPixelSha256").jsonPrimitive.content
                assertNotEquals(metadata.getValue("referencePixelSha256").jsonPrimitive.content,actualHash)
                hashes+=actualHash
                assertNull("Text model must be released",app.engine.residentModelPath)
                assertNull("Image weights must be released after each operation",app.imageStudio.residentImageModelPath)
                assertFalse(app.imageStudio.state.value.running)
                cases+=buildJsonObject {put("sourceSha256",original);put("outputPixelSha256",actualHash);put("fileName",result.file.name);put("elapsedMillis",result.elapsedMillis);put("changedPixelFraction",metadata.getValue("changedPixelFraction"));put("originalUnchanged",true)}
                checkpoint()
                if(index==1)withContext(Dispatchers.IO){
                    source.inputStream().use {input->File(directory,"image-reference-before.png").outputStream().use {input.copyTo(it)}}
                    result.file.inputStream().use {input->File(directory,"image-reference-after.png").outputStream().use {input.copyTo(it)}}
                }
            }
            assertEquals(2,hashes.size)
            assertNotEquals("Different input pixels with identical prompt and seed must influence native output",hashes[0],hashes[1])
            stage="passed"
        }catch(error:Throwable){failure=error.javaClass.name;stage="failed";throw error}
        finally {withContext(NonCancellable+Dispatchers.IO){
            var cleanupFailure:Throwable?=null
            if(lease)try {app.imageStudio.cancel();app.engine.unload()}catch(error:Throwable){cleanupFailure=error}finally{RuntimeGate.inference.set(false)}
            val fixtureRoot=File(context.filesDir,"exports").canonicalFile
            for(source in sources)try {check(source.canonicalFile.parentFile==fixtureRoot&&source.name.startsWith("reference-"));check(!source.exists()||source.delete())}catch(error:Throwable){if(cleanupFailure==null)cleanupFailure=error else cleanupFailure!!.addSuppressed(error)}
            if(cleanupFailure!=null){stage="cleanup_failed";failure=cleanupFailure!!.javaClass.name}
            File(directory,"image-reference.json").writeText(buildJsonObject {
                put("scope","synthetic_reference_images_only");put("stage",stage);put("failureClass",failure);put("elapsedMillis",SystemClock.elapsedRealtime()-started)
                put("mode","native_img2img");put("seed",42);put("requestedSteps",4);put("strength",.65f);put("cases",JsonArray(cases))
                put("modelUnloaded",app.imageStudio.residentImageModelPath==null&&app.engine.residentModelPath==null);put("leaseReleased",!RuntimeGate.inference.get())
                put("sourceFixturesRemoved",sources.none {it.exists()});put("galleryPublished",false);put("personalPhotosRead",false);put("networkInference",false)
            }.toString())
            cleanupFailure?.let {throw it}
        }}
    }
    private fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(32768);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
