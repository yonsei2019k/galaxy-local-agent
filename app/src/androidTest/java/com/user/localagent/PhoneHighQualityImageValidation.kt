package com.user.localagent

import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.PhoneImageQuality
import com.user.localagent.compute.PcComputeSettings
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhoneHighQualityImageValidation {
    private fun app():AppContainer {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneQualityApproved")=="true")
        return (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalAgentApplication).container
    }
    @Test fun installPinnedPhoneQualityModel()=runBlocking {
        val app=app();check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        try {
            app.engine.unload()
            app.imageStudio.models.downloadDefault();app.imageStudio.models.refresh()
            assertTrue(app.imageStudio.models.isQualityModel)
            val file=checkNotNull(app.imageStudio.models.verifiedModelFile());assertEquals(PhoneImageQuality.BYTES,file.length())
            report("phone-quality-model.json",buildJsonObject{put("passed",true);put("model_id",PhoneImageQuality.MODEL_ID);put("model_sha256",PhoneImageQuality.SHA256);put("bytes",file.length())})
        }finally{RuntimeGate.inference.set(false)}
    }
    @Test fun fullQualityImageIsGeneratedAndSavedOnPhone()=runBlocking {
        val app=app();val settings=PcComputeSettings.get(app.applicationContext)
        app.imageStudio.models.refresh();assertTrue("Install and verify SDXL first",app.imageStudio.models.isQualityModel)
        check(RuntimeGate.inference.compareAndSet(false,true));app.cancelIdleUnload()
        val power=app.applicationContext.getSystemService(PowerManager::class.java)
        val wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ZipHap.Agent:phone-quality-validation").apply{setReferenceCounted(false);acquire(PhoneImageQuality.TIMEOUT_MILLIS+300_000L)}
        val previousPc=settings.state.value.preferPcImages;settings.preferPcImages(false)
        var peakPss=0L;var lowestAvailable=Long.MAX_VALUE
        var activity:ActivityScenario<MainActivity>?=null
        report("phone-quality-image.json",buildJsonObject{put("passed",false);put("phase","started")})
        val sampler=launch(Dispatchers.IO){while(isActive){
            val memory=ActivityManager.MemoryInfo().also{app.applicationContext.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
            lowestAvailable=minOf(lowestAvailable,memory.availMem)
            peakPss=maxOf(peakPss,android.os.Debug.getPss())
            val state=app.imageStudio.state.value
            report("phone-quality-progress.json",buildJsonObject{put("phase",state.phase);put("step",state.step);put("total_steps",state.totalSteps);put("peak_pss_kib",peakPss);put("minimum_available_bytes",lowestAvailable);put("thermal_status",power.currentThermalStatus)})
            delay(5000)
        }}
        try {
            activity=ActivityScenario.launch(Intent(app.applicationContext,MainActivity::class.java))
            app.engine.unload()
            val result=app.imageStudio.generateInCurrentRequest("A realistic product photograph: a red ceramic mug on the left and a tall blue glass bottle on the right, both on a wooden table. Soft window light, fine ceramic and glass texture, sharp subjects, no text.",steps=40,seed=12345)
            assertEquals(1024,result.width);assertEquals(1024,result.height);assertEquals("phone",result.computeLocation)
            val note=checkNotNull(app.repository.latestNote("generated-images"));val metadata=Json.parseToJsonElement(note.body).jsonObject
            assertEquals(PhoneImageQuality.SHA256,metadata["modelSha256"]?.jsonPrimitive?.content)
            assertEquals(40,metadata["samplingSteps"]?.jsonPrimitive?.int)
            val gallery=app.imageStudio.exportToGallery()
            val bytes=app.applicationContext.contentResolver.openInputStream(gallery)!!.use{it.readBytes()}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            fun sha(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt()and 255)}
            assertEquals(1024,bounds.outWidth);assertEquals(1024,bounds.outHeight);assertEquals(sha(result.file.readBytes()),sha(bytes))
            assertNull(app.engine.residentModelPath);assertNull(app.imageStudio.residentImageModelPath)
            File(app.applicationContext.getExternalFilesDir(null),"benchmarks/phone-quality-public-fixture.png").writeBytes(bytes)
            report("phone-quality-image.json",buildJsonObject{put("passed",true);put("compute_location","phone");put("model_id",PhoneImageQuality.MODEL_ID);put("model_sha256",PhoneImageQuality.SHA256);put("width",1024);put("height",1024);put("steps",40);put("cpu_threads",2);put("elapsed_millis",result.elapsedMillis);put("file_name",result.file.name);put("png_sha256",sha(bytes));put("gallery_readback",true);put("peak_pss_kib",peakPss);put("minimum_available_bytes",lowestAvailable)})
        }catch(error:Throwable){
            report("phone-quality-image.json",buildJsonObject{put("passed",false);put("error",error.javaClass.simpleName);put("peak_pss_kib",peakPss);put("minimum_available_bytes",lowestAvailable)})
            throw error
        }finally{sampler.cancelAndJoin();settings.preferPcImages(previousPc);activity?.close();if(wake.isHeld)wake.release();RuntimeGate.inference.set(false)}
    }
    private fun report(name:String,value:JsonObject){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),"benchmarks").apply{mkdirs()}.resolve(name).writeText(value.toString())
    }
}
