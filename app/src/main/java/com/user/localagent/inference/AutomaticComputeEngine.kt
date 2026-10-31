package com.user.localagent.inference

import android.content.Context
import com.user.localagent.compute.PcComputeSettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Phone owns every request. A paired PC is an optional compute backend with one local retry. */
class AutomaticComputeEngine(context:Context):InferenceEngine {
    private val local=LocalInferenceEngine(context.applicationContext)
    private val pc=PcInferenceEngine(context.applicationContext)
    private val settings=PcComputeSettings.get(context)
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    @Volatile private var active:InferenceEngine?=null
    private var observer:Job?=null
    private var requestedPath:String?=null
    private var requestedOptions:InferenceOptions?=null
    private var retryAfter=0L
    private val status=MutableStateFlow("모델 없음")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    private val generation=MutableStateFlow<GenerationSummary?>(null)
    override val state:StateFlow<String> = status.asStateFlow()
    override val lastMetrics:StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val lastGeneration:StateFlow<GenerationSummary?> = generation.asStateFlow()
    override val residentModelPath:String? get()=active?.residentModelPath
    override val supportsStructuredOutput:Boolean get()=active?.supportsStructuredOutput==true
    val executionLocation:String get()=if(active===pc)"pc"else"phone"
    private suspend fun bind(next:InferenceEngine){
        if(active===next)return
        active?.unload();observer?.cancelAndJoin();active=next
        observer=scope.launch {
            launch{next.state.collect{status.value=it}}
            launch{next.lastMetrics.collect{metrics.value=it}}
            launch{next.lastGeneration.collect{generation.value=it}}
        }
    }
    override suspend fun load(path:String,options:InferenceOptions)=mutex.withLock {
        requestedPath=path;requestedOptions=options
        val eligible=settings.state.value.automaticText&&settings.snapshot()!=null&&!options.deviceOnly&&!options.audioEnabled&&!options.visionEnabled&&!options.thinking&&Regex("gemma-e[24]b-[a-f0-9]{12}\\.gguf").matches(File(path).name)
        if(eligible&&System.currentTimeMillis()>=retryAfter){
            try {if(!pc.preflight())throw com.user.localagent.compute.PcUnavailable();currentCoroutineContext().ensureActive();bind(pc);pc.load(path,options);snapshot();return@withLock}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){retryAfter=System.currentTimeMillis()+30_000;settings.report("PC 응답 없음 · 휴대폰에서 이어서 처리")}
        }
        bind(local);local.load(path,options);snapshot()
    }
    private fun snapshot(){status.value=active?.state?.value?:"모델 없음";metrics.value=active?.lastMetrics?.value;generation.value=active?.lastGeneration?.value}
    private suspend fun fallback(){
        retryAfter=System.currentTimeMillis()+30_000
        settings.report("PC 연결이 끊겨 휴대폰에서 다시 계산합니다.")
        bind(local);local.load(checkNotNull(requestedPath),checkNotNull(requestedOptions))
    }
    private fun events(block:(InferenceEngine)->Flow<GenerationEvent>):Flow<GenerationEvent> = flow {
        mutex.withLock {
            metrics.value=null;generation.value=null
            try {
                if(active===pc){
                    val buffered=mutableListOf<GenerationEvent>()
                    try {block(pc).collect{buffered+=it};currentCoroutineContext().ensureActive()}
                    catch(cancel:CancellationException){throw cancel}
                    catch(_:Exception){buffered.clear();fallback()}
                    if(active===pc)buffered.forEach{emit(it)}else emitAll(block(local))
                }else emitAll(block(checkNotNull(active)))
            }finally{metrics.value=active?.lastMetrics?.value;generation.value=active?.lastGeneration?.value;status.value=active?.state?.value?:"모델 없음"}
        }
    }
    override fun generate(prompt:String):Flow<String> = generateChat(prompt,"",emptyList())
    override fun generateChat(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<String> = generateChatEvents(userText,systemInstruction,history).textDeltas()
    override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> = events{it.generateChatEvents(userText,systemInstruction,history)}
    override fun generateStructured(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<String> = generateStructuredEvents(userText,systemInstruction,schema,history).textDeltas()
    override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> = events{it.generateStructuredEvents(userText,systemInstruction,schema,history)}
    override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int?=mutex.withLock {
        try {checkNotNull(active).countChatTokens(userText,systemInstruction,history)}
        catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){if(active!==pc)throw error;fallback();local.countChatTokens(userText,systemInstruction,history)}
    }
    override fun transcribeAudio(wav:File):Flow<String> = flow{mutex.withLock{try{emitAll(checkNotNull(active).transcribeAudio(wav))}finally{snapshot()}}}
    override fun describeImage(image:File,prompt:String):Flow<String> = flow{mutex.withLock{try{emitAll(checkNotNull(active).describeImage(image,prompt))}finally{snapshot()}}}
    override fun cancel(){active?.cancel()}
    override suspend fun unload(){active?.cancel();mutex.withLock{active?.unload();observer?.cancelAndJoin();observer=null;active=null;requestedPath=null;requestedOptions=null;metrics.value=null;generation.value=null;status.value="계산 모델 메모리 해제됨"}}
}
