package com.user.localagent.inference

import android.content.Context
import com.user.localagent.compute.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** The paired PC computes text only. Request state, authority and persistence stay on the phone. */
class PcInferenceEngine(context:Context):InferenceEngine {
    private val context=context.applicationContext
    private val settings=PcComputeSettings.get(this.context)
    private val transport=PcTransport(this.context)
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val status=MutableStateFlow("PC 계산 대기")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    private val generation=MutableStateFlow<GenerationSummary?>(null)
    private var config:PcConnection?=null
    private var alias:String?=null
    private var options:InferenceOptions?=null
    @Volatile private var path:String?=null
    @Volatile private var activeConnection:HttpsURLConnection?=null
    @Volatile private var activeRequest:String?=null
    private var structured=false
    private val cancellation=AtomicLong()
    override val state:StateFlow<String> = status.asStateFlow()
    override val lastMetrics:StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val lastGeneration:StateFlow<GenerationSummary?> = generation.asStateFlow()
    override val residentModelPath:String? get()=path
    override val supportsStructuredOutput:Boolean get()=structured&&path!=null
    suspend fun preflight():Boolean=transport.available()!=null
    override suspend fun load(path:String,options:InferenceOptions)=withContext(Dispatchers.IO){mutex.withLock {
        require(!options.audioEnabled&&!options.visionEnabled&&!options.thinking)
        val file=File(path).canonicalFile
        require(file.parentFile==File(context.filesDir,"models").canonicalFile&&file.isFile)
        val match=Regex("(gemma-e[24]b)-([a-f0-9]{12})\\.gguf").matchEntire(file.name)?:throw PcUnavailable()
        val connection=transport.available()?:throw PcUnavailable()
        val body=buildJsonObject{put("modelId",match.groupValues[1]);put("modelShaPrefix",match.groupValues[2]);put("contextSize",options.contextSize)}
        val reply=transport.json(connection,"/v1/llm/load","POST",body,100_000)
        require(reply["modelId"]?.jsonPrimitive?.content==match.groupValues[1])
        require(reply.getValue("modelSha256").jsonPrimitive.content.startsWith(match.groupValues[2]))
        require(reply["contextSize"]?.jsonPrimitive?.int==options.contextSize)
        require(reply["structuredOutput"]?.jsonPrimitive?.boolean==true)
        currentCoroutineContext().ensureActive()
        config=connection;alias=match.groupValues[1];this@PcInferenceEngine.options=options;this@PcInferenceEngine.path=file.path;structured=true
        status.value="PC 계산 준비됨 · ${match.groupValues[1]}";metrics.value=null;generation.value=null
    }}
    private fun messages(user:String,system:String,history:List<InferenceTurn>):JsonArray=buildJsonArray {
        if(system.isNotBlank())add(buildJsonObject{put("role","system");put("content",system)})
        require(history.size<=79)
        history.forEach{require(it.role in setOf("user","assistant"));add(buildJsonObject{put("role",it.role);put("content",it.text)})}
        add(buildJsonObject{put("role","user");put("content",user)})
    }
    override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int=withContext(Dispatchers.IO){mutex.withLock {
        val revision=cancellation.get()
        val connection=checkNotNull(config)
        val reply=try{transport.json(connection,"/v1/llm/tokens","POST",buildJsonObject {put("modelId",checkNotNull(alias));put("messages",messages(userText,systemInstruction,history))},60_000)}catch(error:Throwable){if(cancellation.get()!=revision)throw CancellationException("PC 계산 중단");throw error}
        if(cancellation.get()!=revision)throw CancellationException("PC 계산 중단")
        reply.getValue("count").jsonPrimitive.int.also {require(it in 0..100_000)}
    }}
    override fun generate(prompt:String):Flow<String> = generateChat(prompt,"",emptyList())
    override fun generateChat(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<String> = generateChatEvents(userText,systemInstruction,history).textDeltas()
    override fun generateStructured(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<String> = generateStructuredEvents(userText,systemInstruction,schema,history).textDeltas()
    override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> = generateEvents(userText,systemInstruction,history,null)
    override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> = generateEvents(userText,systemInstruction,history,schema)
    private fun generateEvents(user:String,system:String,history:List<InferenceTurn>,schema:String?):Flow<GenerationEvent> = flow {
        mutex.withLock {
            val id=UUID.randomUUID().toString();activeRequest=id
            val revision=cancellation.get()
            val started=System.nanoTime();val config=checkNotNull(config);val selected=checkNotNull(options)
            require(selected.maxOutputTokens in 1..4096)
            val body=buildJsonObject {
                put("requestId",id);put("modelId",checkNotNull(alias));put("messages",messages(user,system,history))
                put("maxTokens",selected.maxOutputTokens);put("thinking",false)
                schema?.let{require(it.toByteArray(Charsets.UTF_8).size<=65536);put("schema",Json.parseToJsonElement(it).jsonObject)}
            }
            status.value="PC에서 계산 중 · 결과는 스마트폰에 저장";metrics.value=null;generation.value=null
            var visible=0
            try {
                val connection=transport.open(config,"/v1/llm/chat","POST",body,300_000);activeConnection=connection
                val text=StringBuilder();var finish:String?=null;var promptTokens:Int?=null;var outputTokens:Int?=null;var done=false;var timing:JsonObject?=null
                transport.cancellable(connection){
                    require(connection.responseCode==200){"PC 계산 응답을 확인하지 못했습니다."}
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use {reader->
                        var total=0
                        while(true){
                            val line=reader.readLine()?:break;total+=line.length
                            require(line.length<=131072&&total<=4*1024*1024)
                            if(!line.startsWith("data: "))continue
                            val data=line.substring(6)
                            if(data=="[DONE]"){done=true;break}
                            val event=Json.parseToJsonElement(data).jsonObject
                            event["timings"]?.takeUnless{it is JsonNull}?.jsonObject?.let {timing=it}
                            event["error"]?.let{error("PC 계산 오류")}
                            event["usage"]?.takeUnless{it is JsonNull}?.jsonObject?.let {usage->promptTokens=usage["prompt_tokens"]?.jsonPrimitive?.intOrNull;outputTokens=usage["completion_tokens"]?.jsonPrimitive?.intOrNull}
                            event["choices"]?.jsonArray?.forEach {choice->
                                val item=choice.jsonObject
                                item["delta"]?.jsonObject?.get("content")?.takeUnless{it is JsonNull}?.jsonPrimitive?.content?.let {delta->require(text.length+delta.length<=128000);text.append(delta)}
                                item["finish_reason"]?.takeUnless{it is JsonNull}?.jsonPrimitive?.content?.let {finish=it}
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                require(done&&finish in setOf("stop","length")){"PC 계산의 완료 신호를 확인하지 못했습니다."}
                val seconds=(System.nanoTime()-started)/1e9
                val input=promptTokens?.takeIf{it>=0};val output=outputTokens?.takeIf{it>=0}
                // Completion is buffered before publication so a dropped PC cannot mix two replies.
                visible=text.length
                val prefillRate=timing?.get("prompt_per_second")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0}
                val decodeRate=timing?.get("predicted_per_second")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0}
                val measured=if(input!=null&&output!=null&&prefillRate!=null&&decodeRate!=null)InferenceMetrics(if(visible>0)seconds else -1.0,input,output,prefillRate,decodeRate)else null
                metrics.value=measured
                val summary=GenerationSummary(id,if(finish=="length")GenerationStopReason.MAX_OUTPUT else GenerationStopReason.EOG,GenerationEvidence.SDK,finish=="length",selected.maxOutputTokens,input,output,visible,measured,elapsedMillis=(seconds*1000).toLong(),thinkingTokenBudget=0,observedTimeToFirstVisibleTokenSeconds=if(visible>0)seconds else null)
                generation.value=summary
                if(text.isNotEmpty())emit(GenerationEvent.Delta(text.toString()))
                emit(GenerationEvent.Finished(summary));status.value="PC 계산 완료"
            }catch(error:Throwable){
                val failure=if(cancellation.get()!=revision&&error !is CancellationException)CancellationException("PC 계산 중단").apply{initCause(error)}else error
                generation.value=GenerationSummary(id,if(failure is CancellationException)GenerationStopReason.CANCELLED else GenerationStopReason.ENGINE_ERROR,GenerationEvidence.HOST,null,selected.maxOutputTokens,visibleChars=visible,errorCode=if(failure is CancellationException)"CANCELLED"else"PC_CONNECTION_OR_COMPLETION_FAILED",elapsedMillis=(System.nanoTime()-started)/1_000_000)
                throw failure
            }finally{activeConnection?.disconnect();activeConnection=null;activeRequest=null}
        }
    }.flowOn(Dispatchers.IO)
    override fun cancel(){
        cancellation.incrementAndGet()
        activeConnection?.disconnect();transport.cancel()
        val id=activeRequest;val connection=config
        if(id!=null&&connection!=null)scope.launch {runCatching{PcTransport(context).json(connection,"/v1/llm/cancel","POST",buildJsonObject{put("requestId",id)},2000)}}
    }
    override suspend fun unload(){cancel();withContext(Dispatchers.IO){mutex.withLock {
        config?.let {runCatching{transport.json(it,"/v1/llm/unload","POST",buildJsonObject{},2000)}}
        path=null;alias=null;options=null;config=null;structured=false;metrics.value=null;generation.value=null;status.value="PC 계산 메모리 해제 요청됨"
    }}}
}
