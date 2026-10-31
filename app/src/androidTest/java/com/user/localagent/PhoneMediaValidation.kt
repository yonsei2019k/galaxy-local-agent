package com.user.localagent

import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.audio.AudioChunkDecoder
import com.user.localagent.audio.LocalAudioTranscriber
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.skills.BuiltinSkills
import com.user.localagent.skills.PhoneMediaTools
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, synthetic-only probes. Never opens the microphone or any existing personal file. */
@RunWith(AndroidJUnit4::class)
class PhoneMediaValidation {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container

    @Test fun installedFacilitiesAndEmbeddedPdfText()=runBlocking {
        approved("phoneMediaApproved")
        val tools=PhoneMediaTools(context)
        val parent=File(context.filesDir,"exports").apply {check(isDirectory||mkdirs())}
        val fixture=File(parent,"qa-media-${UUID.randomUUID()}.pdf")
        var stage="starting";var failure:String?=null
        var voiceCount=0;var voiceNames=emptyList<String>();var ttsEngine:String?=null
        var onDeviceRecognizer=false;var textSupported=false;var pageCount=0;var embeddedMatch=false
        try {
            stage="inventory"
            val available=tools.capabilities()
            voiceCount=available.offlineKoreanVoices.size;voiceNames=available.offlineKoreanVoices.map {it.name}
            ttsEngine=available.ttsEngine;onDeviceRecognizer=available.onDeviceRecognitionAvailable
            assertFalse("OCR is not implemented by PDF embedded-text extraction",available.imageOcrAvailable)
            stage="synthetic_pdf"
            check(fixture.createNewFile())
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",fixture)
            val pages=BuiltinSkills(context).writePdf(uri,"ZipHap synthetic document\n기기 내 PDF 텍스트 확인","Local PDF")
            val inspected=tools.inspectPdf(uri)
            pageCount=inspected.pageCount;textSupported=inspected.textSupported
            assertEquals(pages,inspected.pageCount)
            assertEquals(Build.VERSION.SDK_INT>=35,textSupported)
            if(textSupported){
                embeddedMatch=inspected.pages.joinToString("\n"){it.text.orEmpty()}.contains("ZipHap")
                assertTrue("Embedded synthetic text must actually be extracted",embeddedMatch)
            }else assertTrue(inspected.pages.all {it.text==null})
            stage="passed"
        }catch(error:Throwable){failure=error.javaClass.name;stage="failed";throw error}
        finally {withContext(NonCancellable+Dispatchers.IO){
            tools.close()
            check(fixture.canonicalFile.parentFile==parent.canonicalFile)
            check(!fixture.exists()||fixture.delete())
            report("phone-media.json",buildJsonObject {
                put("scope","synthetic_facility_probe");put("stage",stage);put("failureClass",failure)
                put("offlineKoreanVoiceCount",voiceCount);put("voiceNames",buildJsonArray {voiceNames.forEach {add(it)}});put("ttsEngine",ttsEngine)
                put("onDeviceRecognitionAvailable",onDeviceRecognizer);put("pdfTextSupported",textSupported);put("pageCount",pageCount);put("embeddedTextMatched",embeddedMatch)
                put("ocrAvailable",false);put("microphoneOpened",false);put("downloadExecuted",false);put("personalFilesRead",false)
            })
        }}
    }

    @Test fun localKoreanSynthesisDecodesToPrivatePcm()=runBlocking {
        approved("phoneMediaApproved")
        val tools=PhoneMediaTools(context)
        var generated:File?=null;var stage="starting";var failure:String?=null
        var chunks=0;var duration=0L;var bytes=0L;var voice:String?=null
        try {
            stage="synthesize"
            val result=tools.synthesizeToFile(SYNTHETIC_SPEECH)
            generated=result.file;bytes=result.bytes;voice=result.voice.name
            stage="decode"
            AudioChunkDecoder(context).decode(FileProvider.getUriForFile(context,"${context.packageName}.files",result.file)){chunk->
                assertTrue(chunk.file.isFile&&chunk.file.length()>44)
                assertTrue(chunk.durationMillis>0)
                chunks++;duration=maxOf(duration,chunk.startMillis+chunk.durationMillis)
            }
            assertTrue("Real synthesized speech must yield PCM chunks",chunks>0&&duration>0)
            assertTrue(context.getSharedPreferences("validated-tools",android.content.Context.MODE_PRIVATE).edit().putBoolean("audio.speak_local",true).commit())
            stage="passed"
        }catch(error:Throwable){failure=error.javaClass.name;stage="failed";throw error}
        finally {withContext(NonCancellable+Dispatchers.IO){
            tools.close();generated?.let(::deleteOwnedSpeech)
            report("local-speech-decode.json",buildJsonObject {
                put("scope","synthetic_audio");put("stage",stage);put("failureClass",failure);put("voice",voice)
                put("synthesizedBytes",bytes);put("decodedChunks",chunks);put("durationMillis",duration)
                put("microphoneOpened",false);put("networkFallback",false);put("downloadExecuted",false);put("personalFilesRead",false)
            })
        }}
    }

    @Test fun cachedE2bTranscribesSyntheticLocalSpeech()=runBlocking {
        approved("localAudioRoundTripApproved")
        val tools=PhoneMediaTools(context)
        val saved=mutableListOf<String>()
        var generated:File?=null;var stage="starting";var failure:String?=null
        var lease=false;var chunks=0;var duration=0L;var anchorCount=0;var meetingMatched=false
        try {
            stage="verify_cached_model"
            app.models.refresh()
            assertTrue("A verified cached E2B is required; this test never downloads models",app.models.models.value.any {it.id=="gemma-e2b"&&it.verified})
            assertFalse("Another benchmark is active",RuntimeGate.benchmark.get())
            app.cancelIdleUnload()
            lease=RuntimeGate.inference.compareAndSet(false,true)
            assertTrue("Another inference is active",lease)
            stage="synthesize"
            val result=tools.synthesizeToFile(SYNTHETIC_SPEECH);generated=result.file
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",result.file)
            stage="decode_and_transcribe"
            val transcribed=withTimeout(8*60_000L){LocalAudioTranscriber(app,AudioChunkDecoder(context)).transcribe(uri)}
            saved+=transcribed.noteIds;chunks=transcribed.chunkCount;duration=transcribed.durationMillis
            assertTrue(chunks>0&&duration>0&&saved.isNotEmpty())
            stage="verify_own_synthetic_transcripts"
            val text=StringBuilder()
            for(id in saved){
                val note=app.repository.getNote(id)
                assertNotNull("Synthetic transcript must actually be stored",note)
                assertEquals("audio-transcripts",note!!.collection)
                // Read only the exact new IDs returned by this synthetic request.
                text.append(note.body.substringAfter('\n',""))
            }
            meetingMatched=text.contains("회의")
            anchorCount=listOf("오늘","오전","회의").count {text.contains(it)}
            assertTrue("Synthetic Korean recognition must preserve 회의 and at least two known words",meetingMatched&&anchorCount>=2)
            assertNull("Audio workflow must release model weights",app.engine.residentModelPath)
            assertTrue(context.getSharedPreferences("validated-tools",android.content.Context.MODE_PRIVATE).edit().putBoolean("audio.transcribe",true).commit())
            stage="passed"
        }catch(error:Throwable){failure=error.javaClass.name;stage="failed";throw error}
        finally {withContext(NonCancellable+Dispatchers.IO){
            tools.close()
            if(lease){app.engine.unload();RuntimeGate.inference.set(false)}
            for(id in saved){app.repository.deleteNote(id);check(app.repository.getNote(id)==null)}
            generated?.let(::deleteOwnedSpeech)
            report("local-audio-roundtrip.json",buildJsonObject {
                put("scope","synthetic_audio");put("stage",stage);put("failureClass",failure);put("syntheticSpeech",SYNTHETIC_SPEECH)
                put("chunks",chunks);put("durationMillis",duration);put("matchedAnchorCount",anchorCount);put("meetingWordMatched",meetingMatched)
                put("modelUnloaded",app.engine.residentModelPath==null);put("leaseReleased",!RuntimeGate.inference.get())
                put("microphoneOpened",false);put("downloadExecuted",false);put("personalFilesRead",false);put("syntheticNotesRemoved",true)
            })
        }}
    }

    private fun approved(key:String){assumeTrue("Explicit $key=true is required",InstrumentationRegistry.getArguments().getString(key)=="true")}
    private fun deleteOwnedSpeech(file:File){
        check(file.canonicalFile.parentFile==File(context.cacheDir,"shared/local-speech").canonicalFile)
        check(file.name.startsWith("speech-")&&file.extension=="wav")
        check(!file.exists()||file.delete())
    }
    private fun report(name:String,value:JsonObject){
        require(name in setOf("phone-media.json","local-speech-decode.json","local-audio-roundtrip.json"))
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {check(isDirectory||mkdirs())}
        File(directory,name).writeText(value.toString())
    }
    private companion object {const val SYNTHETIC_SPEECH="오늘 오전 열 시에 회의가 있습니다."}
}
