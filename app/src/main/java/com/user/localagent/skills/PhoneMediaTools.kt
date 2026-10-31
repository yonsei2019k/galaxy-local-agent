package com.user.localagent.skills

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalKoreanVoice(val name:String,val locale:String,val quality:Int,val latency:Int,val engine:String?)
data class PhoneMediaCapabilities(
    val offlineKoreanVoices:List<LocalKoreanVoice>,
    val onDeviceRecognitionAvailable:Boolean,
    val pdfEmbeddedTextAvailable:Boolean,
    val imageOcrAvailable:Boolean=false,
    val ttsEngine:String?=null,
    val ttsError:String?=null,
)
data class LocalSpeechResult(val voice:LocalKoreanVoice,val completed:Boolean=true)
data class LocalSpeechFile(val file:File,val voice:LocalKoreanVoice,val bytes:Long)

/** Installed Android facilities only. No downloads, cloud fallback or persistent microphone.
 * Voice metadata is the installed engine's declaration, not proof of every engine's internals.
 * PDF text is embedded document text; bitmap-only/scanned pages require a separate OCR tool.
 */
class PhoneMediaTools(context:Context):Closeable {
    private val app=context.applicationContext
    private val speechMutex=Mutex()
    private val closed=AtomicBoolean(false)
    private val mainHandler=Handler(Looper.getMainLooper())
    @Volatile private var activeTts:TextToSpeech?=null
    @Volatile private var activeSpeechJob:Job?=null
    private var recognizer:SpeechRecognizer?=null
    private var recognitionDeadline:Runnable?=null
    private val mutableListening=MutableStateFlow(false)
    val listening:StateFlow<Boolean> = mutableListening.asStateFlow()

    suspend fun capabilities():PhoneMediaCapabilities {
        val recognition=withContext(Dispatchers.Main.immediate){
            !closed.get()&&runCatching {SpeechRecognizer.isOnDeviceRecognitionAvailable(app)}.getOrDefault(false)
        }
        return try {
            withTts {tts->PhoneMediaCapabilities(offlineVoices(tts).map {voiceInfo(tts,it)},recognition,Build.VERSION.SDK_INT>=35,ttsEngine=tts.defaultEngine)}
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){PhoneMediaCapabilities(emptyList(),recognition,Build.VERSION.SDK_INT>=35,ttsError="설치된 로컬 한국어 음성을 확인하지 못했습니다.")}
    }

    suspend fun inspectPdf(uri:Uri,includeText:Boolean=true):BuiltinSkills.PdfInspection =
        BuiltinSkills(app).inspectPdf(uri,includeText)

    suspend fun speak(text:String,voiceName:String?=null):LocalSpeechResult = withTts {tts->
        validateSpeech(text)
        val voice=selectVoice(tts,voiceName)
        awaitUtterance(tts){id->tts.speak(text,TextToSpeech.QUEUE_FLUSH,offlineParameters(),id)}
        LocalSpeechResult(voiceInfo(tts,voice))
    }

    /** Caller owns the returned app-private file and may export it only through explicit UI. */
    suspend fun synthesizeToFile(text:String,voiceName:String?=null):LocalSpeechFile = withTts {tts->
        validateSpeech(text)
        val voice=selectVoice(tts,voiceName)
        val file=withContext(Dispatchers.IO){
            val directory=File(app.cacheDir,"shared/local-speech").apply {check(isDirectory||mkdirs())}
            File.createTempFile("speech-",".wav",directory)
        }
        var complete=false
        try {
            awaitUtterance(tts){id->tts.synthesizeToFile(text,offlineParameters(),file,id)}
            val bytes=withContext(Dispatchers.IO){file.length()}
            check(bytes>44){"로컬 음성 엔진이 유효한 오디오 파일을 만들지 못했습니다."}
            complete=true
            LocalSpeechFile(file,voiceInfo(tts,voice),bytes)
        }finally {if(!complete)withContext(NonCancellable+Dispatchers.IO){file.delete()}}
    }

    /** Starts only from the owner's foreground UI action; never invoke from background jobs. */
    fun startListening(onText:(String)->Unit,onError:(String)->Unit):Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper()){onError("음성 입력은 앱 화면에서 시작해주세요.");return false}
        if(closed.get()){onError("음성 도구가 종료되었습니다.");return false}
        if(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){onError("마이크 권한이 필요합니다.");return false}
        if(!runCatching {SpeechRecognizer.isOnDeviceRecognitionAvailable(app)}.getOrDefault(false)){onError("설치된 기기 내 음성 인식기가 없습니다. 녹음 파일 전사 도구를 사용할 수 있습니다.");return false}
        if(recognizer!=null){onError("음성 입력이 이미 진행 중입니다.");return false}
        var instance:SpeechRecognizer?=null
        try {
            val local=SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            instance=local;recognizer=local
            val errorCallback=onError
            fun release(){if(recognizer===local){recognizer=null;mutableListening.value=false;recognitionDeadline?.let {mainHandler.removeCallbacks(it)};recognitionDeadline=null};runCatching {local.destroy()}}
            local.setRecognitionListener(object:RecognitionListener {
                override fun onReadyForSpeech(params:Bundle?){if(recognizer===local)mutableListening.value=true}
                override fun onBeginningOfSpeech()=Unit
                override fun onRmsChanged(rmsdB:Float)=Unit
                override fun onBufferReceived(buffer:ByteArray?)=Unit
                override fun onEndOfSpeech(){if(recognizer===local)mutableListening.value=false}
                override fun onError(error:Int){if(recognizer!==local)return;release();errorCallback(recognitionError(error))}
                override fun onResults(results:Bundle?){
                    if(recognizer!==local)return
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.take(10_000).orEmpty()
                    release();if(text.isBlank())errorCallback("인식된 음성이 없습니다.")else onText(text)
                }
                override fun onPartialResults(partialResults:Bundle?)=Unit
                override fun onEvent(eventType:Int,params:Bundle?)=Unit
            })
            recognitionDeadline=Runnable {if(recognizer===local){release();errorCallback("음성 입력 시간이 끝났습니다. 다시 눌러 말해주세요.")}}.also {mainHandler.postDelayed(it,60_000)}
            mutableListening.value=true
            local.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            })
            return true
        }catch(_:Exception){recognitionDeadline?.let {mainHandler.removeCallbacks(it)};recognitionDeadline=null;runCatching {instance?.destroy()};recognizer=null;mutableListening.value=false;onError("기기 내 음성 입력을 시작하지 못했습니다. 설치된 한국어 음성 인식 지원을 확인해주세요.");return false}
    }

    fun stopListening(){onMain {runCatching {recognizer?.stopListening()}}}
    fun cancelListening(){onMain {recognitionDeadline?.let {mainHandler.removeCallbacks(it)};recognitionDeadline=null;val local=recognizer;recognizer=null;mutableListening.value=false;runCatching {local?.cancel()};runCatching {local?.destroy()}}}
    fun stop(){activeSpeechJob?.cancel(CancellationException("Local speech stopped"));runCatching {activeTts?.stop()}}
    override fun close(){closed.set(true);stop();cancelListening()}
    fun destroy()=close()

    private fun onMain(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())block()else mainHandler.post {block()}}
    private suspend fun <T> withTts(block:suspend (TextToSpeech)->T):T = speechMutex.withLock {
        coroutineScope {withContext(Dispatchers.Main.immediate){
            check(!closed.get()){"음성 도구가 종료되었습니다."}
            val initialized=CompletableDeferred<Int>()
            val tts=TextToSpeech(app){status->initialized.complete(status)}
            activeTts=tts;activeSpeechJob=currentCoroutineContext()[Job]
            try {
                check(withTimeout(15_000){initialized.await()}==TextToSpeech.SUCCESS){"로컬 음성 엔진을 시작하지 못했습니다."}
                block(tts)
            }finally {
                withContext(NonCancellable+Dispatchers.Main.immediate){runCatching {tts.stop()};runCatching {tts.shutdown()};if(activeTts===tts){activeTts=null;activeSpeechJob=null}}
            }
        }}
    }

    private fun offlineVoices(tts:TextToSpeech):List<Voice> = tts.voices.orEmpty().filter {
        it.locale.language=="ko"&&!it.isNetworkConnectionRequired&&TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.sortedWith(compareByDescending<Voice>{it.quality}.thenBy {it.latency}.thenBy {it.name})
    private fun selectVoice(tts:TextToSpeech,name:String?):Voice {
        val voices=offlineVoices(tts)
        val voice=(if(name==null)voices.firstOrNull()else voices.firstOrNull {it.name==name})
            ?:error("설치된 오프라인 한국어 음성이 없습니다. 시스템 TTS 설정에서 한국어 기기 내 음성을 준비해주세요.")
        check(tts.setVoice(voice)==TextToSpeech.SUCCESS){"기기 내 한국어 음성을 선택하지 못했습니다."}
        check(tts.voice?.isNetworkConnectionRequired==false){"네트워크 음성으로 전환하지 않습니다."}
        return voice
    }
    private fun voiceInfo(tts:TextToSpeech,voice:Voice)=LocalKoreanVoice(voice.name,voice.locale.toLanguageTag(),voice.quality,voice.latency,tts.defaultEngine)
    @Suppress("DEPRECATION") private fun offlineParameters()=Bundle().apply {
        putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS,"true")
        putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS,"false")
    }
    private fun validateSpeech(text:String){require(text.isNotBlank()&&text.length<=TextToSpeech.getMaxSpeechInputLength()){"음성으로 읽을 텍스트가 비어 있거나 시스템 입력 한도를 넘었습니다."}}
    private suspend fun awaitUtterance(tts:TextToSpeech,start:(String)->Int){
        val id=UUID.randomUUID().toString();val done=CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(utteranceId:String?)=Unit
            override fun onDone(utteranceId:String?){if(utteranceId==id)done.complete(Unit)}
            @Deprecated("Android legacy callback") override fun onError(utteranceId:String?){if(utteranceId==id)done.completeExceptionally(IllegalStateException("로컬 음성 합성에 실패했습니다."))}
            override fun onError(utteranceId:String?,errorCode:Int){if(utteranceId==id)done.completeExceptionally(IllegalStateException("로컬 음성 합성 오류: $errorCode"))}
            override fun onStop(utteranceId:String?,interrupted:Boolean){if(utteranceId==id)done.cancel(CancellationException("Local speech stopped"))}
        })
        check(start(id)==TextToSpeech.SUCCESS){"로컬 음성 작업을 시작하지 못했습니다."}
        withTimeout(180_000){done.await()}
    }
    private fun recognitionError(code:Int):String=when(code){
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"마이크 권한이 필요합니다."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"설치된 기기 내 한국어 음성 인식 모델이 없습니다."
        SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"인식된 음성이 없습니다. 다시 눌러 말해주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"기기 내 음성 인식기가 사용 중입니다."
        else->"기기 내 음성 인식이 완료되지 않았습니다. (오류 $code)"
    }
}
