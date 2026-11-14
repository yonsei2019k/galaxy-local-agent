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

private const val PC_TEXT_MODEL="pc-text"
private const val PC_TEXT_SHA_PREFIX="03b74727a860a563"
private const val PC_CLAUDE_MODEL="claude"

data class PcCleanupEvidence(val operation:String,val requestId:String?,val cleanupComplete:Boolean?,val acknowledged:Boolean)

/** The paired PC computes text only. Request state, authority and persistence stay on the phone. */
class PcInferenceEngine(context:Context,private val authorityMatches:(PcConnection)->Boolean={true},private val computeOwner:String?=null):InferenceEngine {
    private val context=context.applicationContext
    private val settings=PcComputeSettings.get(this.context)
    private val transport=PcTransport(this.context)
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val status=MutableStateFlow("PC 계산 대기")
    private val metrics=MutableStateFlow<InferenceMetrics?>(null)
    private val generation=MutableStateFlow<GenerationSummary?>(null)
    private val cleanup=MutableStateFlow<PcCleanupEvidence?>(null)
    val lastCleanup:StateFlow<PcCleanupEvidence?> = cleanup.asStateFlow()
    private val lifecycle=AtomicLong()
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
    @Volatile private var reasoning:String?=null
    /** nanoTime of the last PC confirmation that the text model is resident (load reply or completed chat); 0 = unknown. */
    @Volatile private var verifiedAt=0L
    override val lastReasoning:String? get()=reasoning
    private fun requireAuthority(connection:PcConnection){if(!authorityMatches(connection))throw PcUnavailable()}
    suspend fun preflight():Boolean=transport.available(computeOwner)?.let {authorityMatches(it)}==true
    override suspend fun load(path:String,options:InferenceOptions)=withContext(Dispatchers.IO){mutex.withLock {
        lifecycle.incrementAndGet();cleanup.value=null
        // The PC owns the engine choice; the phone's selected model no longer picks the weights.
        val file=File(path)
        // Every decision round calls load; skip the health + load round trips while the same model is known resident.
        val textModel=!options.audioEnabled&&!options.visionEnabled&&!(settings.state.value.claudeText&&!options.imagePreparation)
        val wanted=if(options.imagePreparation)com.user.localagent.core.GemmaImagePreparation.modelId(file.name)else PC_TEXT_MODEL
        config?.let{resident->if(textModel&&PcResidency.reusable(alias,wanted,this@PcInferenceEngine.options,options,this@PcInferenceEngine.path,file.path,verifiedAt,System.nanoTime())){
            requireAuthority(resident);status.value="PC 계산 준비됨 · $wanted";return@withLock}}
        verifiedAt=0L
        val connection=transport.available(computeOwner,waitForRestart=true)?:throw PcUnavailable()
        requireAuthority(connection)
        if(options.audioEnabled||options.visionEnabled){
            // Release only this request owner's cached text model before loading a media model.
            val released=transport.json(connection,"/v1/llm/unload","POST",buildJsonObject {put("requestId",UUID.randomUUID().toString())},60_000,computeOwner)
            require(released["cleanup_complete"]?.jsonPrimitive?.boolean==true){"PC의 이전 모델 정리가 끝나지 않았습니다."}
            // Speech and image engines start per request on the PC; no resident text model is needed.
            config=connection;alias=null;this@PcInferenceEngine.options=options;this@PcInferenceEngine.path=file.path;structured=false
            status.value="PC 계산 준비됨";metrics.value=null;generation.value=null
            return@withLock
        }
        if(settings.state.value.claudeText&&!options.imagePreparation){
            config=connection;alias=PC_CLAUDE_MODEL;this@PcInferenceEngine.options=options;this@PcInferenceEngine.path=file.path;structured=true
            status.value="Claude 사용 · 내용이 Anthropic으로 전송됩니다";metrics.value=null;generation.value=null
            return@withLock
        }
        val requestedModel=if(options.imagePreparation)com.user.localagent.core.GemmaImagePreparation.modelId(file.name)else PC_TEXT_MODEL
        val requestedSha=if(options.imagePreparation)com.user.localagent.core.GemmaImagePreparation.hashes.getValue(requestedModel).take(16)else PC_TEXT_SHA_PREFIX
        val body=buildJsonObject{put("modelId",requestedModel);put("modelShaPrefix",requestedSha);put("contextSize",options.contextSize)}
        val reply=transport.json(connection,"/v1/llm/load","POST",body,100_000,computeOwner)
        requireAuthority(connection)
        require(reply["modelId"]?.jsonPrimitive?.content==requestedModel)
        require(reply.getValue("modelSha256").jsonPrimitive.content.startsWith(requestedSha))
        require(reply["contextSize"]?.jsonPrimitive?.int==options.contextSize)
        require(reply["structuredOutput"]?.jsonPrimitive?.boolean==true)
        currentCoroutineContext().ensureActive()
        config=connection;alias=requestedModel;this@PcInferenceEngine.options=options;this@PcInferenceEngine.path=file.path;structured=true
        status.value="PC 계산 준비됨 · $requestedModel";metrics.value=null;generation.value=null
        verifiedAt=System.nanoTime()
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
        requireAuthority(connection)
        // Claude's window far exceeds the app's budget; a Korean-safe character estimate keeps the check conservative.
        if(alias==PC_CLAUDE_MODEL)return@withLock (userText.length+systemInstruction.length+history.sumOf{it.text.length})/2
        val reply=try{transport.json(connection,"/v1/llm/tokens","POST",buildJsonObject {put("modelId",checkNotNull(alias));put("messages",messages(userText,systemInstruction,history))},60_000,computeOwner)}catch(error:Throwable){if(cancellation.get()!=revision)throw CancellationException("PC 계산 중단");throw error}
        if(cancellation.get()!=revision)throw CancellationException("PC 계산 중단")
        requireAuthority(connection)
        reply.getValue("count").jsonPrimitive.int.also {require(it in 0..100_000)}
    }}
    // Without this the planner drops schema-constrained decoding. The PC counts the template without the
    // grammar, which adds no prompt tokens on this server.
    override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int? =
        countChatTokens(userText,systemInstruction,history)
    private fun media(path:String,file:File,extra:JsonObjectBuilder.()->Unit):Flow<String> = flow {
        val connection=checkNotNull(config);requireAuthority(connection)
        status.value="PC에서 계산 중 · 결과는 스마트폰에 저장"
        val reply=withContext(Dispatchers.IO){
            val body=buildJsonObject{put("contentBase64",android.util.Base64.encodeToString(file.readBytes(),android.util.Base64.NO_WRAP));extra()}
            transport.json(connection,path,"POST",body,600_000,computeOwner)
        }
        requireAuthority(connection)
        if(path=="/v1/media/describe")require(reply["engine"]?.jsonPrimitive?.content==com.user.localagent.core.GemmaImagePreparation.modelId(File(checkNotNull(this@PcInferenceEngine.path)).name)){"선택한 Gemma와 실제 사진 분석 엔진이 다릅니다."}
        emit(reply.getValue("text").jsonPrimitive.content)
        status.value="PC 계산 완료"
    }
    override fun transcribeAudio(wav:File):Flow<String> = media("/v1/media/transcribe",wav){put("language","ko")}
    override fun describeImage(image:File,prompt:String):Flow<String> = media("/v1/media/describe",image){
        put("prompt",prompt)
        put("modelId",com.user.localagent.core.GemmaImagePreparation.modelId(File(checkNotNull(path)).name))
    }
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
            requireAuthority(config)
            require(selected.maxOutputTokens in 1..4096)
            val body=buildJsonObject {
                put("requestId",id);put("modelId",checkNotNull(alias));put("messages",messages(user,system,history))
                put("maxTokens",selected.maxOutputTokens);put("thinking",false)
                schema?.let{require(it.toByteArray(Charsets.UTF_8).size<=65536);put("schema",Json.parseToJsonElement(it).jsonObject)}
            }
            status.value="PC에서 계산 중 · 결과는 스마트폰에 저장";metrics.value=null;generation.value=null
            var visible=0
            try {
                val connection=transport.open(config,if(alias==PC_CLAUDE_MODEL)"/v1/cloud/chat" else "/v1/llm/chat","POST",body,300_000,computeOwner);activeConnection=connection
                reasoning=null
                val thought=StringBuilder()
                val text=StringBuilder();var finish:String?=null;var promptTokens:Int?=null;var outputTokens:Int?=null;var done=false;var timing:JsonObject?=null
                transport.cancellable(connection){
                    require(connection.responseCode==200){"PC 계산 응답을 확인하지 못했습니다."}
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use {reader->
                        var total=0;var lastProgress=0L
                        while(true){
                            requireAuthority(config)
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
                                item["delta"]?.jsonObject?.get("reasoning_content")?.takeUnless{it is JsonNull}?.jsonPrimitive?.content?.let {part->if(thought.length<16000)thought.append(part)}
                                item["delta"]?.jsonObject?.get("content")?.takeUnless{it is JsonNull}?.jsonPrimitive?.content?.let {delta->require(text.length+delta.length<=128000);text.append(delta)}
                                item["finish_reason"]?.takeUnless{it is JsonNull}?.jsonPrimitive?.content?.let {finish=it}
                            }
                            val progressAt=System.nanoTime()
                            if(text.isNotEmpty()&&(lastProgress==0L||progressAt-lastProgress>=1_000_000_000L)){
                                status.value="PC에서 답변 작성 중 · ${text.length}자 준비 · 완료 확인 대기"
                                lastProgress=progressAt
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
                reasoning=thought.toString().takeIf {it.isNotBlank()}
                val prefillRate=timing?.get("prompt_per_second")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0}
                val decodeRate=timing?.get("predicted_per_second")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0}
                val measured=if(input!=null&&output!=null&&prefillRate!=null&&decodeRate!=null)InferenceMetrics(if(visible>0)seconds else -1.0,input,output,prefillRate,decodeRate)else null
                metrics.value=measured
                val summary=GenerationSummary(id,if(finish=="length")GenerationStopReason.MAX_OUTPUT else GenerationStopReason.EOG,GenerationEvidence.SDK,finish=="length",selected.maxOutputTokens,input,output,visible,measured,elapsedMillis=(seconds*1000).toLong(),thinkingTokenBudget=0,observedTimeToFirstVisibleTokenSeconds=if(visible>0)seconds else null)
                generation.value=summary
                if(text.isNotEmpty())emit(GenerationEvent.Delta(text.toString()))
                if(alias!=PC_CLAUDE_MODEL)verifiedAt=System.nanoTime()
                emit(GenerationEvent.Finished(summary));status.value="PC 계산 완료"
            }catch(error:Throwable){
                verifiedAt=0L
                val failure=if(cancellation.get()!=revision&&error !is CancellationException)CancellationException("PC 계산 중단").apply{initCause(error)}else error
                generation.value=GenerationSummary(id,if(failure is CancellationException)GenerationStopReason.CANCELLED else GenerationStopReason.ENGINE_ERROR,GenerationEvidence.HOST,null,selected.maxOutputTokens,visibleChars=visible,errorCode=if(failure is CancellationException)"CANCELLED"else"PC_CONNECTION_OR_COMPLETION_FAILED",elapsedMillis=(System.nanoTime()-started)/1_000_000)
                throw failure
            }finally{activeConnection?.disconnect();activeConnection=null;activeRequest=null}
        }
    }.flowOn(Dispatchers.IO)
    override fun cancel(){
        val id=activeRequest;val connection=config;val epoch=lifecycle.get()
        cancellation.incrementAndGet()
        activeConnection?.disconnect();transport.cancel()
        if(id!=null&&connection!=null)scope.launch {
            val reply=runCatching{PcTransport(context).json(connection,"/v1/llm/cancel","POST",buildJsonObject{put("requestId",id)},2000,computeOwner)}.getOrNull()
            if(lifecycle.get()==epoch)cleanup.value=PcCleanupEvidence("cancel",id,reply?.get("cleanup_complete")?.jsonPrimitive?.booleanOrNull,reply!=null)
        }
    }
    override suspend fun unload(){cancel();withContext(Dispatchers.IO){mutex.withLock {
        lifecycle.incrementAndGet() // A late cancel reply cannot replace this unload's evidence.
        val previous=config
        if(previous!=null){
            val reply=runCatching{transport.json(previous,"/v1/llm/unload","POST",buildJsonObject{},2000,computeOwner)}.getOrNull()
            cleanup.value=PcCleanupEvidence("unload",null,reply?.get("cleanup_complete")?.jsonPrimitive?.booleanOrNull,reply!=null)
        }
        path=null;alias=null;options=null;config=null;structured=false;metrics.value=null;generation.value=null;verifiedAt=0L
        status.value=if(cleanup.value?.cleanupComplete==true)"PC 계산 정리 확인됨"else"PC 정리 요청의 완료 여부는 확인이 필요합니다."
    }}}
}

/** The PC unloads an idle model after 300 s; reuse well inside that window, and only for the identical request. */
internal object PcResidency {
    const val REUSE_NANOS=60_000_000_000L
    fun reusable(alias:String?,wanted:String,current:InferenceOptions?,requested:InferenceOptions,currentPath:String?,requestedPath:String,verifiedAt:Long,now:Long):Boolean =
        verifiedAt!=0L&&alias==wanted&&current==requested&&currentPath==requestedPath&&now-verifiedAt in 0 until REUSE_NANOS
}
