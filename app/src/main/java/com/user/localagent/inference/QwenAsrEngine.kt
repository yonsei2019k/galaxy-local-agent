package com.user.localagent.inference

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.annotation.Keep
import com.user.localagent.core.AsrProtocol
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Audio-only local engine. LocalInferenceEngine unloads chat/image encoders before selecting it. */
class QwenAsrEngine(context:Context, private val languageOverride:String?=null):InferenceEngine {
    init { require(languageOverride==null||languageOverride in setOf("ko","en","auto")) }
    private val context=context.applicationContext
    private val modelRoot=File(this.context.filesDir,"models").canonicalFile
    private val mutex=Mutex()
    private val handleLock=Any()
    @Volatile private var handle=0L
    @Volatile private var loaded:String?=null
    private var loadedOptions:InferenceOptions?=null
    private val status=MutableStateFlow("음성 인식 모델 없음")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    override val state:StateFlow<String> = status.asStateFlow()
    override val lastMetrics:StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val residentModelPath:String? get()=loaded

    override suspend fun load(path:String,options:InferenceOptions)=withContext(Dispatchers.IO){mutex.withLock {
        require(options.backend=="CPU"&&options.audioEnabled&&!options.visionEnabled&&!options.thinking)
        require(options.contextSize==2048&&options.cpuThreads in setOf(2,4))
        val model=File(path).canonicalFile
        val projector=File(requireNotNull(options.projectorPath)).canonicalFile
        require(model.parentFile==modelRoot&&projector.parentFile==modelRoot&&model.isFile&&projector.isFile)
        require(Regex("asr-qwen-(?:06b|17b)-[a-f0-9]{12}\\.gguf").matches(model.name))
        require(projector.name.startsWith(model.name.substringBeforeLast('-')+"-mmproj-")&&projector.extension=="gguf")
        if(loaded==model.path&&loadedOptions==options&&handle!=0L)return@withLock
        release()
        val info=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val requiredMb=(model.length()+projector.length())/(1024L*1024)+512L
        require(!info.lowMemory&&info.availMem/(1024L*1024)>=requiredMb){"음성 인식용 메모리 여유가 부족합니다. 다른 작업을 마친 뒤 시도해주세요."}
        require(context.getSystemService(PowerManager::class.java).currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"기기가 식은 뒤 음성을 처리해주세요."}
        status.value="전용 음성 인식 모델 준비 중"
        val created=QwenAsrNative.create();check(created!=0L)
        synchronized(handleLock){handle=created}
        try {
            nativeCall(created){QwenAsrNative.load(created,model.path,projector.path,options.cpuThreads)}
            currentCoroutineContext().ensureActive();loaded=model.path;loadedOptions=options;status.value="음성 인식 준비됨"
        }catch(error:Throwable){release();throw error}
    }}

    override fun generate(prompt:String):Flow<String> = flow {error("전용 음성 인식 엔진은 녹음 전사에 사용합니다.")}

    override fun transcribeAudio(wav:File):Flow<String> = flow {
        val file=wav.canonicalFile
        require(file.isFile&&file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())&&file.length() in 44..1_000_000)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val current=handle;check(current!=0L&&loaded!=null)
            val language=languageOverride?:context.getSharedPreferences("model-selection",Context.MODE_PRIVATE).getString("asr-language","ko")?:"ko"
            require(language in setOf("ko","en","auto"))
            metrics.value=null;status.value="기기 안에서 음성 인식 중"
            QwenAsrNative.resetCancellation(current)
            val pressure=AtomicBoolean(false)
            coroutineScope {
                val guard=launch(Dispatchers.Default){
                    while(isActive){
                        delay(1000)
                        val info=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                        if(info.lowMemory||context.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE){pressure.set(true);QwenAsrNative.cancel(current);break}
                    }
                }
                try {
                    val result=nativeCall(current){QwenAsrNative.transcribe(current,file.path,language)}
                    require(result.size==5)
                    val input=result[1].toInt();val output=result[2].toInt();val prefill=result[3].toDouble();val decode=result[4].toDouble()
                    require(input in 1..2048&&output in 0..512&&prefill.isFinite()&&decode.isFinite()&&prefill>=0&&decode>=0)
                    metrics.value=InferenceMetrics((prefill+decode)/1000,input,output,if(prefill>0)input*1000/prefill else 0.0,if(decode>0)output*1000/decode else 0.0)
                    emit(AsrProtocol.transcript(result[0]))
                }catch(error:Throwable){
                    if(pressure.get())throw IllegalStateException("과열 또는 메모리 부족으로 음성 처리를 보류했습니다.",error)
                    throw error
                }finally{withContext(NonCancellable){guard.cancelAndJoin()};status.value="음성 인식 준비됨"}
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(){synchronized(handleLock){if(handle!=0L)runCatching{QwenAsrNative.cancel(handle)}}}
    override suspend fun unload(){cancel();withContext(Dispatchers.IO){mutex.withLock{release()}}}
    private fun release(){
        val prior=synchronized(handleLock){handle.also{handle=0L}}
        loaded=null;loadedOptions=null;metrics.value=null
        if(prior!=0L)QwenAsrNative.destroy(prior)
        status.value="음성 인식 메모리 해제됨"
    }
    private suspend fun <T> nativeCall(current:Long,block:()->T):T=suspendCancellableCoroutine{continuation->
        continuation.invokeOnCancellation{runCatching{QwenAsrNative.cancel(current)}}
        if(continuation.isActive)try{continuation.resumeWith(Result.success(block()))}catch(error:Throwable){continuation.resumeWith(Result.failure(error))}
    }
}

@Keep private object QwenAsrNative {
    init {System.loadLibrary("ziphap_asr")}
    external fun create():Long
    external fun load(handle:Long,model:String,projector:String,threads:Int)
    external fun transcribe(handle:Long,wav:String,language:String):Array<String>
    external fun resetCancellation(handle:Long)
    external fun cancel(handle:Long)
    external fun destroy(handle:Long)
}
