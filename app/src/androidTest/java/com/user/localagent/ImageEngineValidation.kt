package com.user.localagent

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.images.ImageModelManager
import com.user.localagent.inference.ImageGenerationEngine
import com.user.localagent.inference.ImageSampler
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual local diffusion smoke, using a fixed benign prompt and no personal images/providers. */
@RunWith(AndroidJUnit4::class)
class ImageEngineValidation {
    @Test fun generateSyntheticPhotograph()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit imageSmokeApproved=true is required",args.getString("imageSmokeApproved")=="true")
        val steps=args.getString("imageSteps","1").toInt()
        val size=args.getString("imageSize","512").toInt()
        require(steps in 1..8 && size in setOf(256,512))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val engine=ImageGenerationEngine(context)
        val power=context.getSystemService(PowerManager::class.java)
        val folder=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {check(isDirectory||mkdirs())}
        val stem="image-engine-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val reportFile=File(folder,"$stem.json")
        val imageFile=File(folder,"$stem.png")
        val reportLock=Any()
        val started=SystemClock.elapsedRealtime()
        val peakPss=AtomicLong(0)
        val maximumThermal=AtomicInteger(power.currentThermalStatus)
        val downloaded=AtomicLong(0)
        var ownsBenchmark=false;var ownsInference=false;var markedBusy=false
        var sampler:Job?=null;var downloadObserver:Job?=null
        var stage="starting";var failure:Throwable?=null;var modelName:String?=null
        var loadMillis:Long?=null;var generationMillis:Long?=null
        var bitmap:Bitmap?=null;var outputSaved=false
        var pixelStats:JsonObject?=null
        val batteryStart=battery(context)

        fun checkpoint(next:String?=null){
            synchronized(reportLock){
                if(next!=null){stage=next;instrumentation.sendStatus(0,Bundle().apply {putString("stream","Image QA: $next\n")})}
                val payload=buildJsonObject {
                    put("stage",stage);put("device_model",Build.MODEL);put("android_api",Build.VERSION.SDK_INT)
                    put("synthetic_inputs_only",true);put("network_during_generation",false)
                    put("model_file",modelName);put("model_sha256",ImageModelManager.DEFAULT_SHA256)
                    put("backend","stable-diffusion.cpp upstream-compatible GGML CPU");put("threads",4)
                    put("width",size);put("height",size);put("steps",steps);put("seed",42)
                    put("sampler","LCM");put("guidance_scale",1.0)
                    put("synthetic_prompt",PROMPT)
                    put("elapsed_ms",SystemClock.elapsedRealtime()-started);put("load_ms",loadMillis)
                    put("generation_ms",generationMillis);put("peak_pss_kb",peakPss.get())
                    put("max_thermal_status",maximumThermal.get());put("downloaded_bytes",downloaded.get())
                    put("start_battery",batteryStart);put("end_battery",battery(context))
                    put("png_file",if(outputSaved)imageFile.name else null);put("pixel_stats",pixelStats?:JsonNull)
                    put("failure",failure?.let {buildJsonObject {put("class",it.javaClass.name);put("message",it.message?.replace(context.filesDir.path,"[app-files]")?.take(500))}}?:JsonNull)
                }
                val temporary=File(reportFile.path+".tmp")
                temporary.writeText(payload.toString());check(temporary.renameTo(reportFile))
            }
        }
        try {
            ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true)
            check(ownsBenchmark){"A benchmark is already running"}
            ownsInference=RuntimeGate.inference.compareAndSet(false,true)
            check(ownsInference){"Another local inference request is running"}
            app.cancelIdleUnload();app.updates.setBusy(true);markedBusy=true
            sampler=launch(Dispatchers.IO){
                while(isActive){
                    peakPss.updateAndGet {maxOf(it,Debug.getPss().toLong())}
                    val thermal=power.currentThermalStatus
                    maximumThermal.updateAndGet {maxOf(it,thermal)}
                    if(thermal>=PowerManager.THERMAL_STATUS_SEVERE)engine.cancel()
                    delay(400)
                }
            }
            checkpoint("unload_text_model_before_image_weights")
            app.engine.unload()
            checkpoint("refresh_image_model_integrity")
            val manager=app.imageStudio.models
            manager.refresh()
            if(manager.verifiedModelFile()?.name!=ImageModelManager.DEFAULT_FILENAME){
                require(args.getString("downloadImageModelApproved")=="true"){
                    "Install the verified default image checkpoint or explicitly enable its download"
                }
                downloadObserver=launch(Dispatchers.IO){
                    var percentBefore=-5
                    manager.state.collect {state->
                        downloaded.set(state.downloadedBytes)
                        val percent=if(state.totalBytes>0)(100*state.downloadedBytes/state.totalBytes).toInt()else 0
                        if(state.downloading&&percent>=percentBefore+5){
                            percentBefore=percent
                            checkpoint("download_${state.downloadedBytes}_of_${state.totalBytes}_bytes")
                        }
                    }
                }
                checkpoint("download_authorized_default_checkpoint")
                manager.downloadDefault()
                downloadObserver?.cancelAndJoin();downloadObserver=null
            }
            val model=requireNotNull(manager.verifiedModelFile()){"Image checkpoint has no current verification proof"}
            require(model.name==ImageModelManager.DEFAULT_FILENAME && model.length()==ImageModelManager.DEFAULT_BYTES)
            modelName=model.name
            check(power.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"Cool the device before a diffusion benchmark"}
            checkpoint("native_load")
            val loadStarted=SystemClock.elapsedRealtime()
            withTimeout(600_000){engine.load(model.path,threads=4)}
            loadMillis=SystemClock.elapsedRealtime()-loadStarted
            assertNull("Text inference must be unloaded while image weights are resident",app.engine.residentModelPath)
            checkpoint("native_generate")
            val generated=withTimeout(600_000){engine.generate(PROMPT,seed=42,steps=steps,width=size,height=size,
                guidanceScale=1f,sampler=ImageSampler.LCM,onProgress={progress->checkpoint("sampling_${progress.step}_of_${progress.totalSteps}")})}
            bitmap=generated.bitmap;generationMillis=generated.elapsedMillis
            assertEquals(size,generated.width);assertEquals(size,generated.height);assertEquals(42L,generated.seed)
            assertTrue(generated.elapsedMillis>0)
            checkpoint("validate_pixels")
            val pixels=IntArray(size*size)
            generated.bitmap.getPixels(pixels,0,size,0,0,size,size)
            var minimum=255;var maximum=0
            val colors=HashSet<Int>()
            for(i in pixels.indices step 31){
                val value=pixels[i];colors+=value
                val light=(((value ushr 16)and 255)+((value ushr 8)and 255)+(value and 255))/3
                minimum=minOf(minimum,light);maximum=maxOf(maximum,light)
            }
            pixelStats=buildJsonObject {put("sampled_colors",colors.size);put("min_luminance",minimum);put("max_luminance",maximum)}
            assertTrue("Image must contain nonuniform visible pixels (not a quality score)",maximum-minimum>8&&colors.size>16)
            checkpoint("write_synthetic_png")
            imageFile.outputStream().use {assertTrue(generated.bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            outputSaved=true
            assertTrue(imageFile.length()>100)
            checkpoint("passed")
        }catch(error:Throwable){
            failure=error;stage="failed";throw error
        }finally {
            withContext(NonCancellable){
                downloadObserver?.cancelAndJoin();sampler?.cancelAndJoin()
                try{engine.unload()}catch(cleanup:Throwable){
                    if(failure==null){failure=cleanup;stage="cleanup_failed"}else failure?.addSuppressed(cleanup)
                }
                bitmap?.recycle()
                try{checkpoint()}finally{
                    if(markedBusy)app.updates.setBusy(false)
                    if(ownsInference)RuntimeGate.inference.set(false)
                    if(ownsBenchmark)RuntimeGate.benchmark.set(false)
                    if(ownsInference)app.scheduleIdleUnload()
                }
                instrumentation.sendStatus(0,Bundle().apply {putString("stream","Image QA report saved: ${reportFile.name}\n")})
            }
        }
        failure?.let {throw it}
    }
    private fun battery(context:android.content.Context):JsonObject {
        val intent=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun value(name:String)=intent?.getIntExtra(name,-1)?.takeIf {it>=0}
        return buildJsonObject {put("level",value(BatteryManager.EXTRA_LEVEL));put("scale",value(BatteryManager.EXTRA_SCALE));put("temperature_c",value(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0))}
    }
    companion object {private const val PROMPT="Professional wildlife photograph of a red fox in a sunlit forest, realistic fur, natural colors, detailed eyes, soft morning light, sharp focus"}
}
