package com.user.localagent.inference

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ImageSampler(val nativeId:Int) { LCM(0), EULER(1), EULER_A(2) }
data class ImageGenerationProgress(val phase:String,val step:Int=0,val totalSteps:Int=0,val fraction:Float=0f,val stepSeconds:Float=0f)
data class ImageGenerationResult(val bitmap:Bitmap,val seed:Long,val width:Int,val height:Int,val elapsedMillis:Long)

/**
 * CPU image runtime. The host owns RuntimeGate and unloads LLM inference before load().
 * Pass only a model whose source/SHA-256 was verified by the model manager. No network,
 * prompt logging, model download, gallery publication or action tools exist in this class.
 */
class ImageGenerationEngine(context:Context) {
    private val modelsDirectory=File(context.applicationContext.filesDir,"image-models").canonicalFile
    private val mutex=Mutex()
    private val handleLock=Any()
    private val cancellationRevision=AtomicLong()
    private val random=SecureRandom()
    @Volatile private var handle=0L
    @Volatile var loadedModelPath:String?=null
        private set
    private var loadedThreads=0
    private val mutableState=MutableStateFlow("이미지 모델 없음")
    val state:StateFlow<String> = mutableState.asStateFlow()
    private val mutableProgress=MutableStateFlow(ImageGenerationProgress("대기"))
    val progress:StateFlow<ImageGenerationProgress> = mutableProgress.asStateFlow()

    suspend fun load(modelPath:String,threads:Int=4)=withContext(Dispatchers.IO) {
        mutex.withLock {
            require(threads in 1..4){"초기 이미지 CPU 프로필은 1–4개 스레드를 지원합니다."}
            val model=File(modelPath).canonicalFile
            require(model.parentFile==modelsDirectory && model.isFile && model.extension.lowercase() in setOf("safetensors","gguf")){
                "앱 전용 폴더에서 검증한 이미지 모델이 필요합니다."
            }
            require(model.length() in 16..8L*1024*1024*1024){"이미지 모델 파일 크기가 유효하지 않습니다."}
            if(handle!=0L && loadedModelPath==model.path && loadedThreads==threads)return@withLock
            release()
            val revision=cancellationRevision.get()
            mutableState.value="이미지 모델 로딩 중 · CPU"
            mutableProgress.value=ImageGenerationProgress("모델 로딩")
            try {
                val created=ImageNative.nativeCreate()
                check(created!=0L){"이미지 추론 엔진을 만들지 못했습니다."}
                synchronized(handleLock){handle=created}
                checkCancellation(revision)
                nativeCall {ImageNative.nativeLoad(created,model.path,threads)}
                currentCoroutineContext().ensureActive()
                checkCancellation(revision)
                loadedModelPath=model.path;loadedThreads=threads
                mutableState.value="이미지 생성 준비됨 · CPU"
                mutableProgress.value=ImageGenerationProgress("준비됨")
            }catch(error:Throwable){
                release()
                mutableState.value=if(error is CancellationException)"이미지 모델 로딩 중단" else "이미지 모델 로딩 실패"
                throw error
            }
        }
    }

    suspend fun generate(
        prompt:String,
        negativePrompt:String="",
        seed:Long=-1,
        steps:Int=4,
        width:Int=512,
        height:Int=512,
        guidanceScale:Float=1f,
        sampler:ImageSampler=ImageSampler.LCM,
        onProgress:(ImageGenerationProgress)->Unit={},
    ):ImageGenerationResult=withContext(Dispatchers.IO) {
        validatePrompt(prompt,4000,true);validatePrompt(negativePrompt,2000,false)
        require(width in 256..512 && height in 256..512 && width%64==0 && height%64==0){"초기 이미지 크기는 256–512px이며 64의 배수여야 합니다."}
        require(steps in 1..8){"초기 이미지 프로필은 1–8단계를 지원합니다."}
        require(seed>=-1 && guidanceScale.isFinite() && guidanceScale in 1f..15f){"시드 또는 가이던스 값이 유효하지 않습니다."}
        val actualSeed=if(seed==-1L)random.nextLong().ushr(1)else seed
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val revision=cancellationRevision.get()
            val active=handle
            check(active!=0L && loadedModelPath!=null){"이미지 모델을 먼저 준비해주세요."}
            ImageNative.nativeResetCancellation(active)
            checkCancellation(revision)
            mutableState.value="이미지 생성 중 · CPU"
            mutableProgress.value=ImageGenerationProgress("장면 설명 인코딩 중")
            val started=android.os.SystemClock.elapsedRealtime()
            val callbackState=Any()
            var samplingStarted=false
            var samplingFinished=false
            var lastSamplingStep=-1
            var lastPhase=""
            try {
                val pixels=nativeCall {
                    ImageNative.nativeGenerate(active,prompt,negativePrompt,actualSeed,steps,width,height,guidanceScale,sampler.nativeId,
                        NativeImageProgress {step,total,seconds->
                            // Upstream uses this callback for both graph-node counts and
                            // denoising steps. Graph nodes are not image completion percent.
                            synchronized(callbackState){
                                val update:ImageGenerationProgress?=when {
                                    total==steps && step in 0..steps -> {
                                        if(step<=lastSamplingStep)null else {
                                            samplingStarted=true;lastSamplingStep=step
                                            samplingFinished=step==steps
                                            ImageGenerationProgress(if(samplingFinished)"샘플링 완료"else "이미지 샘플링 중",
                                                step,steps,0f,seconds)
                                        }
                                    }
                                    samplingFinished -> if(lastPhase=="이미지 픽셀 복원 중")null else ImageGenerationProgress("이미지 픽셀 복원 중")
                                    samplingStarted -> null // Ignore internal UNet graph-node progress.
                                    else -> if(lastPhase=="장면 설명 인코딩 중")null else ImageGenerationProgress("장면 설명 인코딩 중")
                                }
                                if(update!=null){
                                    lastPhase=update.phase
                                    mutableProgress.value=update
                                    onProgress(update)
                                }
                            }
                        })
                }
                currentCoroutineContext().ensureActive();checkCancellation(revision)
                check(pixels.size==width*height){"이미지 픽셀 크기가 요청과 다릅니다."}
                val bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
                mutableProgress.value=ImageGenerationProgress("완료",steps,steps,1f)
                ImageGenerationResult(bitmap,actualSeed,width,height,android.os.SystemClock.elapsedRealtime()-started)
            }catch(cancel:CancellationException){
                mutableProgress.value=ImageGenerationProgress("취소됨");throw cancel
            }catch(error:Throwable){
                mutableProgress.value=ImageGenerationProgress("생성 실패");throw error
            }finally {
                mutableState.value=if(handle==0L)"이미지 모델 없음" else "이미지 생성 준비됨 · CPU"
            }
        }
    }

    fun cancel(){
        cancellationRevision.incrementAndGet()
        synchronized(handleLock){if(handle!=0L)ImageNative.nativeCancel(handle)}
    }
    suspend fun unload(){
        cancel()
        withContext(Dispatchers.IO){mutex.withLock {release()}}
    }
    private fun release(){
        val old=synchronized(handleLock){handle.also {handle=0L}}
        loadedModelPath=null;loadedThreads=0
        if(old!=0L)ImageNative.nativeDestroy(old)
        mutableState.value="이미지 모델 메모리 해제됨"
        mutableProgress.value=ImageGenerationProgress("대기")
    }
    private fun checkCancellation(revision:Long){if(revision!=cancellationRevision.get())throw CancellationException("이미지 작업을 중단했습니다.")}
    private suspend fun <T> nativeCall(operation:()->T):T=suspendCancellableCoroutine {continuation->
        continuation.invokeOnCancellation {cancel()}
        if(continuation.isActive){
            try {continuation.resumeWith(Result.success(operation()))}
            catch(error:Throwable){continuation.resumeWith(Result.failure(error))}
        }
    }
    private fun validatePrompt(value:String,maxChars:Int,required:Boolean){
        require((!required || value.isNotBlank()) && value.length<=maxChars && '\u0000' !in value){"이미지 프롬프트 길이 또는 내용이 유효하지 않습니다."}
        var i=0
        while(i<value.length){
            val char=value[i]
            if(Character.isHighSurrogate(char)){require(i+1<value.length&&Character.isLowSurrogate(value[i+1])){"문자 인코딩이 유효하지 않습니다."};i+=2}
            else {require(!Character.isLowSurrogate(char)){"문자 인코딩이 유효하지 않습니다."};i++}
        }
    }
}

@Keep private object ImageNative {
    init {System.loadLibrary("ziphap_image")}
    external fun nativeCreate():Long
    external fun nativeLoad(handle:Long,path:String,threads:Int)
    external fun nativeResetCancellation(handle:Long)
    external fun nativeCancel(handle:Long)
    external fun nativeDestroy(handle:Long)
    external fun nativeGenerate(handle:Long,prompt:String,negativePrompt:String,seed:Long,steps:Int,width:Int,height:Int,guidance:Float,sampler:Int,progress:NativeImageProgress):IntArray
}
@Keep private class NativeImageProgress(private val callback:(Int,Int,Float)->Unit){
    @Keep fun onStep(step:Int,total:Int,seconds:Float)=callback(step,total,seconds)
}
