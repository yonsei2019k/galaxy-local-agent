package com.user.localagent.inference

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Serializes runtime replacement: native model memory is released before another runtime loads. */
class LocalInferenceEngine(context:Context):InferenceEngine {
    private val liteRt=LiteRtInferenceEngine(context.applicationContext)
    private val llama by lazy {LlamaCppEngine(context.applicationContext)}
    private val replacement=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    @Volatile private var active:InferenceEngine?=null
    private var observer:Job?=null
    private val status=MutableStateFlow("모델 없음")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    override val state:StateFlow<String> = status.asStateFlow()
    override val lastMetrics:StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val residentModelPath:String? get()=active?.residentModelPath
    override suspend fun load(path:String,options:InferenceOptions)=replacement.withLock {
        val next=when(File(path).extension.lowercase()) {"litertlm"->liteRt;"gguf"->llama;else->error("지원하지 않는 모델 형식입니다.")}
        if(active!==next){
            active?.unload()
            observer?.cancelAndJoin()
            active=next
            observer=scope.launch {
                launch {next.state.collect {status.value=it}}
                launch {next.lastMetrics.collect {metrics.value=it}}
            }
        }
        next.load(path,options)
        status.value=next.state.value;metrics.value=next.lastMetrics.value
    }
    private fun request(block:(InferenceEngine)->Flow<String>):Flow<String> = flow {
        replacement.withLock {
            val selected=checkNotNull(active){"모델을 먼저 선택해주세요."}
            metrics.value=null
            try {emitAll(block(selected))}finally{metrics.value=selected.lastMetrics.value;status.value=selected.state.value}
        }
    }
    override fun generate(prompt:String):Flow<String> = request {it.generate(prompt)}
    override fun generateChat(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<String> = request {it.generateChat(userText,systemInstruction,history)}
    override fun transcribeAudio(wav:File):Flow<String> = request {it.transcribeAudio(wav)}
    override fun cancel(){active?.cancel()}
    override suspend fun unload(){
        active?.cancel()
        replacement.withLock {
            active?.unload();observer?.cancelAndJoin();observer=null;active=null;metrics.value=null;status.value="모델 메모리 해제됨"
        }
    }
}
