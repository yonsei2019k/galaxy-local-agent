package com.user.localagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.audio.AudioChunkDecoder
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.*
import com.user.localagent.models.AsrCatalog
import com.user.localagent.skills.PhoneMediaTools
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Cached-only synthetic probes. No real call/audio, model download, global ASR preference, or text report. */
@RunWith(AndroidJUnit4::class)
class CachedCallAsrValidation {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container
    private val ownerIds=listOf(AsrCatalog.DEVICE_MODEL,"asr-qwen-17b","asr-qwen-06b")
    private data class Candidate(val id:String,val ready:Boolean,val reason:String)
    private data class Resources(val pssKiB:Long,val thermal:Int,val batteryPct:Int?,val temperatureC:Double?)

    @Test fun readinessOnlyDoesNotDownloadOrChangeSelectedAsr()=runBlocking {
        approved("cachedCallAsrReadinessApproved")
        starting("cached-call-asr-readiness.json")
        val before=selection();var owns=false;var ready=emptyList<Candidate>();var failure:String?=null
        try {
            owns=RuntimeGate.benchmark.compareAndSet(false,true);check(owns&&!RuntimeGate.inference.get()){"Another runtime task is active"}
            ready=readiness()
            assertEquals("Readiness probe must preserve ASR settings",before,selection())
        }catch(error:Throwable){failure=error.javaClass.simpleName;throw error}
        finally{
            if(owns)RuntimeGate.benchmark.set(false)
            report("cached-call-asr-readiness.json",buildJsonObject {
                put("phase","readiness_only");put("candidates",JsonArray(ready.map(::candidateJson)))
                put("failure_class",failure)
                put("selection_preserved",selection()==before);put("model_download_requested",false);put("inference_executed",false)
                put("personal_audio_read",false);put("network_blocked_by_test",false)
            })
        }
    }

    @Test fun sameCachedKoreanTelephoneSamplesAcrossAvailableEngines()=runBlocking {
        approved("cachedCallAsrBenchmarkApproved")
        starting("cached-call-asr-benchmark.json")
        val before=selection();val results=mutableListOf<JsonObject>();val owned=ownedDirectory()
        var benchmark=false;var inference=false;var runtimeTouched=false;var failure:String?=null;var stage="acquire_runtime"
        try {withTimeout(25*60_000L){
            benchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(benchmark)
            check(!app.runs.busy.value&&!app.imageStudio.state.value.running)
            inference=RuntimeGate.inference.compareAndSet(false,true);check(inference)
            runtimeTouched=true;app.cancelIdleUnload();app.updates.setBusy(true);app.engine.unload()
            stage="cached_readiness";val requested=requestedIds();val ready=readiness().filter{it.id in requested}
            ready.filterNot{it.ready}.forEach{results+=buildJsonObject{put("model_id",it.id);put("status","SKIPPED_UNAVAILABLE");put("reason",it.reason)}}
            val eligible=ready.filter{it.ready}.filter {candidate->
                if(candidate.id==AsrCatalog.DEVICE_MODEL&&!RuntimeGate.foreground.get()){
                    results+=foregroundRequired(candidate.id);false
                }else true
            }
            if(eligible.isEmpty()){stage="skipped_no_eligible_cached_engine";assumeTrue("No requested cached ASR is eligible in the current foreground state; no download was attempted",false)}
            stage="local_offline_tts";val variants=createFixtures(owned)
            stage="cached_asr_probe"
            for(candidate in eligible)results+=probe(candidate.id,variants)
            val attempted=results.filterNot{it["status"]?.jsonPrimitive?.content?.startsWith("SKIPPED_")==true}
            if(attempted.isEmpty()){stage="skipped_no_measurement";assumeTrue("Every requested engine was skipped; no ASR measurement was obtained",false)}
            stage="measured_probe_not_accuracy_acceptance"
            assertTrue("Every attempted engine must complete both synthetic inputs; see metadata report",attempted.all{it["status"]?.jsonPrimitive?.content=="MEASURED"})
        }}catch(skipped:org.junit.AssumptionViolatedException){throw skipped}
        catch(error:Throwable){failure=error.javaClass.simpleName;throw error}
        finally{withContext(NonCancellable){
            if(runtimeTouched)runCatching{app.engine.unload()};val preserved=selection()==before
            try{cleanup(owned)}finally{if(runtimeTouched){app.updates.setBusy(false);app.scheduleIdleUnload()};if(inference)RuntimeGate.inference.set(false);if(benchmark)RuntimeGate.benchmark.set(false)}
            report("cached-call-asr-benchmark.json",buildJsonObject {
                put("stage",stage);put("failure_class",failure);put("results",JsonArray(results));put("selection_preserved",preserved)
                put("scope","synthetic local TTS; 8kHz resampling simulation; not real telephony or general Korean acceptance")
                put("model_download_requested",false);put("microphone_opened",false);put("personal_audio_read",false);put("plain_transcript_reported",false)
                put("app_pss_excludes_aicore",true);put("network_blocked_by_test",false);put("semantic_action_evaluation",false)
                put("max_total_ms",25*60_000L)
            })
            assertTrue("Benchmark must preserve global ASR selection and language",preserved)
        }}
    }

    @Test fun decoderChecksActualSourceBytesBeforeAnyChunk()=runBlocking {
        approved("cachedCallAudioHashValidationApproved")
        starting("cached-call-audio-source-hash.json")
        val owned=ownedDirectory();var wrongHashCallbacks=0;var wrongSizeCallbacks=0;var goodCallbacks=0;var benchmark=false;var inference=false
        var failure:String?=null
        try {
            benchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(benchmark&&!app.runs.busy.value)
            inference=RuntimeGate.inference.compareAndSet(false,true);check(inference)
            val bytes=CachedCallAudioFixtures.wav(ShortArray(1600));val source=File(owned,"source.wav").apply{writeBytes(bytes)}
            val hash=CachedCallAudioFixtures.sha(bytes);val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",source)
            try{AudioChunkDecoder(context).decode(uri,expectedSourceSha256="0".repeat(64),expectedSourceBytes=bytes.size.toLong()){wrongHashCallbacks++};fail("Wrong SHA must reject before PCM callback")}catch(_:IllegalStateException){}
            try{AudioChunkDecoder(context).decode(uri,expectedSourceSha256=hash,expectedSourceBytes=bytes.size+1L){wrongSizeCallbacks++};fail("Wrong bytes must reject before PCM callback")}catch(_:IllegalStateException){}
            AudioChunkDecoder(context).decode(uri,expectedSourceSha256=hash,expectedSourceBytes=bytes.size.toLong()){chunk->
                goodCallbacks++;assertEquals(100L,chunk.durationMillis);assertTrue("Decoded canonical silence WAV must equal the exact synthetic source",bytes.contentEquals(chunk.file.readBytes()))
            }
            assertEquals(0,wrongHashCallbacks);assertEquals(0,wrongSizeCallbacks);assertEquals(1,goodCallbacks)
        }catch(error:Throwable){failure=error.javaClass.simpleName;throw error}
        finally{try{cleanup(owned)}finally{if(inference)RuntimeGate.inference.set(false);if(benchmark)RuntimeGate.benchmark.set(false)};report("cached-call-audio-source-hash.json",buildJsonObject{
            put("failure_class",failure);put("wrong_hash_callbacks",wrongHashCallbacks);put("wrong_size_callbacks",wrongSizeCallbacks);put("correct_source_callbacks",goodCallbacks)
            put("scope","synthetic 100ms silence WAV only");put("model_download_requested",false);put("personal_audio_read",false)
        })}
    }

    private fun approved(key:String)=assumeTrue("$key=true required",InstrumentationRegistry.getArguments().getString(key)=="true")
    private fun selection():Pair<String?,String?> {val p=context.getSharedPreferences("model-selection",Context.MODE_PRIVATE);return p.getString("asr-id",null) to p.getString("asr-language",null)}
    private fun requestedIds():Set<String> {
        val values=InstrumentationRegistry.getArguments().getString("cachedAsrModels")?.split(',')?.map{it.trim()}?:ownerIds
        require(values.isNotEmpty()&&values.size<=3&&values.distinct().size==values.size&&values.all{it in ownerIds});return values.toSet()
    }
    private suspend fun readiness():List<Candidate> {
        app.models.refresh()
        val device=try{SystemAsrEngine.prepare("ko",download=false)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
        return ownerIds.map{id->if(id==AsrCatalog.DEVICE_MODEL)Candidate(id,device?.available==true,if(device?.available==true)"AVAILABLE"else"SYSTEM_FEATURE_NOT_AVAILABLE")
            else Candidate(id,app.models.asrReady(id),if(app.models.asrReady(id))"VERIFIED_CACHED_MODEL_PAIR"else"MODEL_OR_ENCODER_NOT_VERIFIED_CACHED")}
    }
    private fun candidateJson(value:Candidate)=buildJsonObject{
        put("model_id",value.id);put("available",value.ready);put("reason",value.reason)
        put("foreground_required",value.id==AsrCatalog.DEVICE_MODEL);put("foreground_at_check",RuntimeGate.foreground.get())
        put("background_inference_eligible",value.ready&&value.id!=AsrCatalog.DEVICE_MODEL)
    }
    private fun foregroundRequired(id:String)=buildJsonObject{
        put("model_id",id);put("status","SKIPPED_FOREGROUND_REQUIRED");put("reason","MLKIT_BACKGROUND_USE_BLOCKED")
        put("foreground_required",true);put("background_inference_eligible",false);put("general_accuracy_proven",false)
    }
    private suspend fun createFixtures(directory:File):List<CachedCallAudioFixtures.Variant> {
        val tools=PhoneMediaTools(context);var generated:File?=null
        try {
            val speech=withTimeout(90_000){tools.synthesizeToFile(CachedCallAudioFixtures.SCRIPT)};generated=speech.file
            check(speech.file.length() in 44..10L*1024*1024){"Synthetic TTS exceeds the fixture size bound"}
            val sourceBytes=speech.file.readBytes();var decoded:ShortArray?=null;var count=0
            AudioChunkDecoder(context).decode(FileProvider.getUriForFile(context,"${context.packageName}.files",speech.file),chunkSeconds=30,
                expectedSourceSha256=CachedCallAudioFixtures.sha(sourceBytes),expectedSourceBytes=sourceBytes.size.toLong()){chunk->
                count++;check(count==1){"Synthetic TTS exceeds the bounded single-utterance probe"};decoded=CachedCallAudioFixtures.pcm(chunk.file.readBytes())
            }
            return CachedCallAudioFixtures.variants(checkNotNull(decoded),directory)
        }finally{tools.close();generated?.let{check(it.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()));it.delete()}}
    }
    private suspend fun probe(id:String,variants:List<CachedCallAudioFixtures.Variant>):JsonObject {
        if(id==AsrCatalog.DEVICE_MODEL&&!RuntimeGate.foreground.get())return foregroundRequired(id)
        val engine:InferenceEngine=if(id==AsrCatalog.DEVICE_MODEL)SystemAsrEngine(context,languageOverride="ko")else QwenAsrEngine(context,languageOverride="ko")
        val peak=AtomicLong(Debug.getPss());val thermal=AtomicLong(0);val samples=mutableListOf<JsonObject>();var failed:String?=null;var loadMs:Long?=null
        var foregroundSkipped=false;val foregroundAtStart=RuntimeGate.foreground.get()
        return coroutineScope {
            val monitor=launch(Dispatchers.Default){while(isActive){peak.updateAndGet{maxOf(it,Debug.getPss())};thermal.updateAndGet{maxOf(it,context.getSystemService(PowerManager::class.java).currentThermalStatus.toLong())};delay(250)}}
            try {
                val started=SystemClock.elapsedRealtime()
                if(id==AsrCatalog.DEVICE_MODEL)withTimeout(60_000){engine.load(AsrCatalog.DEVICE_PATH,InferenceOptions(contextSize=2048,audioEnabled=true))}
                else {val model=app.models.models.value.single{it.id==id&&it.verified};verifyPinned(id);withTimeout(120_000){engine.load(model.path,app.models.asrOptions(model))}}
                loadMs=SystemClock.elapsedRealtime()-started
                for((index,variant)in variants.withIndex()){
                    if(id==AsrCatalog.DEVICE_MODEL&&!RuntimeGate.foreground.get()){foregroundSkipped=true;break}
                    samples+=sample(engine,variant,index)
                }
            }catch(timeout:TimeoutCancellationException){if(id==AsrCatalog.DEVICE_MODEL&&!RuntimeGate.foreground.get())foregroundSkipped=true else failed=timeout.javaClass.simpleName}
            catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){if(id==AsrCatalog.DEVICE_MODEL&&!RuntimeGate.foreground.get())foregroundSkipped=true else failed=error.javaClass.simpleName}
            finally{withContext(NonCancellable){runCatching{engine.unload()};monitor.cancelAndJoin()}}
            buildJsonObject{
                put("model_id",id);put("status",if(foregroundSkipped)"SKIPPED_FOREGROUND_REQUIRED"else if(failed==null&&samples.size==variants.size)"MEASURED"else"FAILED");put("failure_class",failed)
                if(foregroundSkipped)put("reason","MLKIT_BACKGROUND_USE_BLOCKED")
                put("foreground_at_start",foregroundAtStart);put("foreground_required",id==AsrCatalog.DEVICE_MODEL)
                put("background_inference_eligible",id!=AsrCatalog.DEVICE_MODEL)
                put("load_and_verify_ms",loadMs);put("sampled_app_peak_pss_kib",peak.get());put("samples",JsonArray(samples));put("asr_language_override","ko")
                put("sampled_max_thermal_status",thermal.get());put("resource_sample_interval_ms",250)
                put("app_pss_excludes_system_aicore",id==AsrCatalog.DEVICE_MODEL);put("general_accuracy_proven",false)
                put("provider_managed_weights",id==AsrCatalog.DEVICE_MODEL)
                put("model_artifacts",buildJsonArray{if(id!=AsrCatalog.DEVICE_MODEL)for(key in listOf(id,"$id-mmproj")){
                    val artifact=AsrCatalog.descriptors.getValue(key)
                    add(buildJsonObject{put("id",key);put("sha256",artifact.sha256);put("bytes",artifact.sizeBytes);put("commit",artifact.revision)})
                }})
            }
        }
    }
    private suspend fun verifyPinned(id:String)=withContext(Dispatchers.IO){
        for(key in listOf(id,"$id-mmproj")){
            val pinned=AsrCatalog.descriptors.getValue(key);val actual=app.models.models.value.single{it.id==key&&it.verified};val file=File(actual.path)
            check(actual.sha256==pinned.sha256&&file.length()==pinned.sizeBytes)
            val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(64*1024);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            check(digest.digest().joinToString(""){"%02x".format(it)}==pinned.sha256)
        }
    }
    private suspend fun sample(engine:InferenceEngine,variant:CachedCallAudioFixtures.Variant,index:Int):JsonObject {
        val before=resources();val start=SystemClock.elapsedRealtime();val output=StringBuilder()
        val seconds=(InstrumentationRegistry.getArguments().getString("cachedAsrPerSampleTimeoutSeconds")?.toLongOrNull()?:180L).also{require(it in 30..600)}
        withTimeout(seconds*1000){engine.transcribeAudio(variant.file).collect{part->require(output.length+part.length<=10_000);output.append(part)}}
        val elapsed=SystemClock.elapsedRealtime()-start;val after=resources()
        return buildJsonObject {
            put("condition",variant.id);put("sample_ordinal",index);put("source_pcm_sha256",variant.sha256);put("audio_ms",variant.durationMillis);put("wall_ms",elapsed)
            put("rtf",elapsed.toDouble()/variant.durationMillis);put("measured_added_noise_snr_db",variant.measuredSnrDb?.let{JsonPrimitive(it)}?:JsonNull)
            put("scores",CachedCallAudioFixtures.scores(output.toString()));put("before",resourcesJson(before));put("after",resourcesJson(after))
            put("word_timing_verified",false);put("asr_confidence_available",false)
        }
    }
    private fun resources():Resources {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1;val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        return Resources(Debug.getPss(),context.getSystemService(PowerManager::class.java).currentThermalStatus,
            if(scale>0&&level>=0)level*100/scale else null,battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)?.takeIf{it!=Int.MIN_VALUE}?.div(10.0))
    }
    private fun resourcesJson(r:Resources)=buildJsonObject{put("app_pss_kib",r.pssKiB);put("thermal_status",r.thermal);put("battery_pct",r.batteryPct);put("battery_temperature_c",r.temperatureC)}
    private fun ownedDirectory()=File(context.cacheDir,"shared/cached-call-asr-${UUID.randomUUID()}").apply{check(mkdirs())}
    private fun cleanup(directory:File){val root=File(context.cacheDir,"shared").canonicalFile;check(directory.canonicalFile.parentFile==root&&Regex("cached-call-asr-[a-f0-9-]{36}").matches(directory.name));directory.deleteRecursively()}
    private fun starting(name:String)=report(name,buildJsonObject{put("stage","starting");put("started_elapsed_ms",SystemClock.elapsedRealtime());put("model_download_requested",false);put("app_version",BuildConfig.VERSION_CODE)})
    private fun report(name:String,value:JsonObject){val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply{check(isDirectory||mkdirs())};File(directory,name).writeText(value.toString())}
}
