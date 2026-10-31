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
    private val asr by lazy {QwenAsrEngine(context.applicationContext)}
    private val deviceAsr by lazy {SystemAsrEngine(context.applicationContext)}
    private val replacement=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    @Volatile private var active:InferenceEngine?=null
    private var observer:Job?=null
    private val status=MutableStateFlow("모델 없음")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    private val generation=MutableStateFlow<GenerationSummary?>(null)
    override val state:StateFlow<String> = status.asStateFlow()
    override val lastMetrics:StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val lastGeneration:StateFlow<GenerationSummary?> = generation.asStateFlow()
    override val residentModelPath:String? get()=active?.residentModelPath
    override val supportsStructuredOutput:Boolean get()=active?.supportsStructuredOutput==true
    override suspend fun load(path:String,options:InferenceOptions)=replacement.withLock {
        val next=when(File(path).extension.lowercase()) {"aicore"->deviceAsr;"litertlm"->liteRt;"gguf"->if(File(path).name.startsWith("asr-"))asr else llama;else->error("지원하지 않는 모델 형식입니다.")}
        if(active!==next){
            active?.unload()
            observer?.cancelAndJoin()
            active=next
            observer=scope.launch {
                launch {next.state.collect {status.value=it}}
                launch {next.lastMetrics.collect {metrics.value=it}}
                launch {next.lastGeneration.collect {generation.value=it}}
            }
        }
        next.load(path,options)
        status.value=next.state.value;metrics.value=next.lastMetrics.value;generation.value=next.lastGeneration.value
    }
    private fun <T> request(block:(InferenceEngine)->Flow<T>):Flow<T> = flow {
        replacement.withLock {
            val selected=checkNotNull(active){"모델을 먼저 선택해주세요."}
            metrics.value=null
            generation.value=null
            try {emitAll(block(selected))}finally{metrics.value=selected.lastMetrics.value;generation.value=selected.lastGeneration.value;status.value=selected.state.value}
        }
    }
    override fun generate(prompt:String):Flow<String> = request {it.generate(prompt)}
    override fun generateChat(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<String> = request {it.generateChat(userText,systemInstruction,history)}
    override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> = request {it.generateChatEvents(userText,systemInstruction,history)}
    override fun generateStructured(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<String> = request {it.generateStructured(userText,systemInstruction,schema,history)}
    override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> = request {it.generateStructuredEvents(userText,systemInstruction,schema,history)}
    override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int? = replacement.withLock {
        checkNotNull(active){"모델을 먼저 선택해주세요."}.countChatTokens(userText,systemInstruction,history)
    }
    override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int?=replacement.withLock {
        checkNotNull(active){"모델을 먼저 선택해주세요."}.countStructuredChatTokens(userText,systemInstruction,schema,history)
    }
    override fun transcribeAudio(wav:File):Flow<String> = request {it.transcribeAudio(wav)}
    override fun describeImage(image:File,prompt:String):Flow<String> = request {it.describeImage(image,prompt)}
    override fun cancel(){active?.cancel()}
    override suspend fun unload(){
        active?.cancel()
        replacement.withLock {
            active?.unload();observer?.cancelAndJoin();observer=null;active=null;metrics.value=null;generation.value=null;status.value="모델 메모리 해제됨"
        }
    }
}
