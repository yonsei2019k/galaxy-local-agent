package com.user.localagent

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.audio.AudioChunkDecoder
import com.user.localagent.audio.LocalAudioTranscriber
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.models.AsrCatalog
import com.user.localagent.skills.PhoneMediaTools
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic public Korean fixtures only. A smoke does not establish general Korean accuracy. */
@RunWith(AndroidJUnit4::class)
class AsrDeviceValidation {
    @Test fun installAndValidateQwen()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("asrApproved")=="true")
        val id=args.getString("asrModel")?:error("asrModel required")
        require(id in AsrCatalog.modelIds)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        for(key in listOf(id,"$id-mmproj")){
            val pinned=AsrCatalog.descriptors.getValue(key)
            val actual=app.models.resolve(app.models.candidates.single {it.id==key})
            assertEquals(pinned,actual)
            stage("$key download_and_sha256")
            withTimeout(30*60_000L){app.models.download(actual)}
        }
        app.models.selectAsrLanguage("ko");app.models.selectAsr(id)
        validate(context,id)
    }

    @Test fun validateDeviceAsr()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("asrApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        app.models.selectAsrLanguage("ko")
        app.models.prepareDeviceAsr(download=args.getString("prepareSystemAi")=="true")
        val device=app.models.deviceAsrStatus.value
        report(context,AsrCatalog.DEVICE_MODEL,buildJsonObject {
            put("available",device.available);put("status",device.message);put("passed",false);put("phase","support_check")
        })
        assumeTrue("Runtime feature unavailable: ${device.message}",device.available)
        app.models.selectAsr(AsrCatalog.DEVICE_MODEL)
        validate(context,AsrCatalog.DEVICE_MODEL)
    }

    private suspend fun validate(context:Context,id:String)=coroutineScope {
        val app=(context.applicationContext as LocalAgentApplication).container
        val tools=PhoneMediaTools(context)
        val notes=mutableListOf<String>();val files=mutableListOf<File>()
        val responses=mutableListOf<String>();val times=mutableListOf<Long>()
        val peak=AtomicLong(0);var failure:Throwable?=null;var owns=false
        val fixtures=listOf(
            "테스트 회의는 내일 오후 세 시입니다.",
            "서울역에서 부산역까지 기차표 두 장을 예약해 주세요.",
            "사진을 삭제하지 말고 두 장의 차이를 비교해 주세요.")
        val memory=launch(Dispatchers.Default){while(isActive){peak.updateAndGet{maxOf(it,Debug.getPss())};delay(500)}}
        try {
            app.cancelIdleUnload();app.updates.setBusy(true)
            owns=RuntimeGate.inference.compareAndSet(false,true);check(owns)
            for((index,fixture) in fixtures.withIndex()){
                stage("$id Korean fixture ${index+1}")
                val speech=tools.synthesizeToFile(fixture);files+=speech.file
                val started=SystemClock.elapsedRealtime()
                val result=withTimeout(12*60_000L){LocalAudioTranscriber(app,AudioChunkDecoder(context)).transcribe(FileProvider.getUriForFile(context,"${context.packageName}.files",speech.file))}
                notes+=result.noteIds
                assertEquals(id,result.engineId);assertTrue(result.chunkCount>0)
                assertNull("ASR must unload before Gemma analysis",app.engine.residentModelPath)
                val transcriptParts=mutableListOf<String>()
                for(noteId in result.noteIds)transcriptParts+=app.repository.getNote(noteId)?.body?:error("Missing transcript")
                val text=transcriptParts.joinToString("\n")
                val compact=text.replace(Regex("\\s+"),"")
                when(index){
                    0->{assertTrue(text.contains("회의"));assertTrue(text.contains("내일"));assertTrue(Regex("세시|3시").containsMatchIn(compact))}
                    1->{assertTrue(text.contains("서울"));assertTrue(text.contains("부산"));assertTrue(Regex("두장|2장").containsMatchIn(compact))}
                    2->{assertTrue(text.contains("사진"));assertTrue(compact.contains("삭제하지말"));assertTrue(text.contains("비교"))}
                }
                responses+=text;times+=SystemClock.elapsedRealtime()-started
            }
        }catch(error:Throwable){failure=error;throw error}
        finally{withContext(NonCancellable){
            memory.cancelAndJoin();app.engine.unload();if(owns)RuntimeGate.inference.set(false)
            notes.forEach{app.repository.deleteNote(it)}
            files.forEach{check(it.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())||it.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()));it.delete()}
            tools.close();app.updates.setBusy(false)
            report(context,id,buildJsonObject {
                put("model_id",id);put("language","ko");put("passed",failure==null);put("available",true)
                put("synthetic_transcripts",JsonArray(responses.map{JsonPrimitive(it)}))
                put("end_to_end_ms",JsonArray(times.map{JsonPrimitive(it)}));put("sampled_app_peak_pss_kib",peak.get())
                put("metric_scope",if(id==AsrCatalog.DEVICE_MODEL)"App PSS excludes the separate AICore system service"else"App PSS includes the local ASR runtime")
                put("failure",failure?.javaClass?.name);put("message",failure?.message?.take(500))
                put("fixture_kind","local synthetic Korean TTS; not a general accuracy benchmark")
            })
        }}
    }
    private fun stage(value:String){InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","ASR: $value\n")})}
    private fun report(context:Context,id:String,value:JsonObject){
        val dir=File(context.getExternalFilesDir(null),"benchmarks").apply{mkdirs()}
        File(dir,"$id-validation.json").writeText(value.toString())
    }
}
