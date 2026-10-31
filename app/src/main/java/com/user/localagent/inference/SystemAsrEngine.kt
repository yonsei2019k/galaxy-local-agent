package com.user.localagent.inference

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.*
import com.user.localagent.models.AsrCatalog
import com.user.localagent.models.DeviceAsrStatus
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public ML Kit on-device API, distinct from Samsung Voice Recorder's Transcript Assist. */
class SystemAsrEngine(context:Context, private val languageOverride:String?=null):InferenceEngine {
    init { require(languageOverride==null||languageOverride in setOf("ko","en")) }
    private val context=context.applicationContext
    private val mutex=Mutex()
    private var client:SpeechRecognizer?=null
    @Volatile private var running:Job?=null
    @Volatile private var loaded:String?=null
    private val status=MutableStateFlow("기기 내 음성 AI 없음")
    override val state:StateFlow<String> = status.asStateFlow()
    override val residentModelPath:String? get()=loaded
    override val lastMetrics:StateFlow<InferenceMetrics?> = MutableStateFlow(null)

    override suspend fun load(path:String,options:InferenceOptions)=withContext(Dispatchers.IO){mutex.withLock {
        require(path==AsrCatalog.DEVICE_PATH&&options.audioEnabled&&!options.visionEnabled)
        closeClient()
        checkPressure()
        val language=languageOverride?:context.getSharedPreferences("model-selection",Context.MODE_PRIVATE).getString("asr-language","ko")?:"ko"
        val created=newClient(language)
        try {
            check(withTimeout(20_000){created.checkStatus()}==FeatureStatus.AVAILABLE){"모델 관리에서 기기 내 음성 AI의 지원 확인·준비를 먼저 실행해주세요."}
            currentCoroutineContext().ensureActive()
            client=created;loaded=path;status.value="기기 내 음성 AI 준비됨"
        }catch(error:Throwable){created.close();throw error}
    }}
    override fun generate(prompt:String):Flow<String> = flow {error("기기 내 음성 AI는 녹음 전사에 사용합니다.")}
    override fun transcribeAudio(wav:File):Flow<String> = flow {
        val file=wav.canonicalFile
        require(file.isFile&&file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())&&file.length() in 44..1_000_000)
        mutex.withLock {
            val selected=checkNotNull(client){"기기 내 음성 AI를 먼저 준비해주세요."}
            checkPressure()
            running=currentCoroutineContext()[Job]
            status.value="기기 내 음성 AI로 인식 중"
            val pressureGuard=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default){
                while(isActive){delay(1_000);checkPressure()}
            }
            try {
                val text=StringBuilder();var completed=false
                AudioSource.fromFile(file).use {source->
                    val request=speechRecognizerRequest {audioSource=source}
                    withTimeout(180_000){
                        selected.startRecognition(request).collect {response->
                            currentCoroutineContext().ensureActive();checkPressure()
                            when(response){
                                is SpeechRecognizerResponse.FinalTextResponse->{
                                    require(text.length+response.text.length<=10_000){"음성 전사 출력 제한을 넘었습니다."}
                                    if(text.isNotEmpty()&&response.text.isNotBlank())text.append(' ')
                                    text.append(response.text)
                                }
                                is SpeechRecognizerResponse.CompletedResponse->completed=true
                                is SpeechRecognizerResponse.ErrorResponse->throw IllegalStateException("기기 내 음성 인식이 완료되지 않았습니다.",response.e)
                                else->Unit // Revisions/partial hypotheses never become saved transcript data.
                            }
                        }
                    }
                }
                check(completed){"음성 인식의 완료 신호를 확인하지 못했습니다."}
                emit(text.toString().trim())
            }finally{
                running=null
                withContext(NonCancellable){pressureGuard.cancelAndJoin();withTimeoutOrNull(5_000){runCatching{selected.stopRecognition()}}}
                status.value="기기 내 음성 AI 준비됨"
            }
        }
    }.flowOn(Dispatchers.IO)
    override fun cancel(){running?.cancel(CancellationException("음성 인식을 중단했습니다."))}
    override suspend fun unload(){cancel();withContext(Dispatchers.IO){mutex.withLock{closeClient()}}}
    private fun closeClient(){client?.close();client=null;loaded=null;status.value="기기 내 음성 AI 연결 해제됨"}
    private fun checkPressure(){
        val memory=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory){"메모리 여유가 생긴 뒤 음성을 처리해주세요."}
        check(context.getSystemService(PowerManager::class.java).currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"기기가 식은 뒤 음성을 처리해주세요."}
    }
    companion object {
        private fun newClient(language:String):SpeechRecognizer {
            require(language in setOf("ko","en")){"기기 내 음성 AI는 한국어 또는 영어를 지정해주세요. 자동 감지는 Qwen3-ASR에서 사용할 수 있습니다."}
            return SpeechRecognition.getClient(speechRecognizerOptions {
                locale=if(language=="ko")Locale.KOREA else Locale.US
                preferredMode=SpeechRecognizerOptions.Mode.MODE_ADVANCED
            })
        }
        /** Explicit model-management action only; never downloads during ordinary transcription. */
        suspend fun prepare(language:String,download:Boolean=false):DeviceAsrStatus=withContext(Dispatchers.IO){
            val recognizer=newClient(language)
            try {
                var feature=withTimeout(20_000){recognizer.checkStatus()}
                if(download&&feature==FeatureStatus.DOWNLOADABLE){
                    withTimeout(900_000){recognizer.download().collect {if(it is DownloadStatus.DownloadFailed)throw it.e}}
                    feature=withTimeout(20_000){recognizer.checkStatus()}
                }
                when(feature){
                    FeatureStatus.AVAILABLE->DeviceAsrStatus(true,false,"이 기기에서 사용 가능")
                    FeatureStatus.DOWNLOADABLE->DeviceAsrStatus(false,true,"시스템 AI 파일 준비 필요")
                    FeatureStatus.DOWNLOADING->DeviceAsrStatus(false,false,"시스템 AI 파일 준비 중 · 잠시 뒤 다시 확인")
                    else->DeviceAsrStatus(false,false,"현재 기기·언어 구성에서 사용 불가 · Qwen3-ASR 사용 가능")
                }
            }finally{recognizer.close()}
        }
    }
}
