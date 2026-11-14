package com.user.localagent

import com.user.localagent.inference.*
import com.user.localagent.models.*
import kotlinx.coroutines.flow.*

/** Synthetic test recorder only. No transport, native process, model, Android singleton or tool execution. */
internal class PairedPcTargetRecordingEngine : InferenceEngine, PairedPcTextLoader {
    override val state = MutableStateFlow("synthetic paired-target recorder")
    override val residentModelPath: String? = null
    override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
    override val lastGeneration = MutableStateFlow<GenerationSummary?>(null)
    override val supportsStructuredOutput = true
    var pathLoads = 0
    var typedLoads = 0
    var generations = 0
    var cancels = 0
    var receiptForDifferentRevision = false
    var lastTarget: PairedPcText? = null
    override suspend fun load(path: String, options: InferenceOptions) { pathLoads++; error("Unexpected local path load") }
    override suspend fun loadPairedPcText(target: PairedPcText, options: InferenceOptions): PairedPcLoadReceipt {
        typedLoads++; lastTarget = target
        val receiptTarget = if (!receiptForDifferentRevision) target else
            checkNotNull(PairedPcText.forRequest("auto",target.authorizationRevision+1,false,target.authorizationRevision+1,true))
        return PairedPcLoadReceipt.fromAuthenticatedLoad(receiptTarget,PairedPcTextProfile.MODEL_ID,
            PairedPcTextProfile.MODEL_SHA256,options.contextSize,true,options.contextSize)
    }
    override suspend fun countChatTokens(userText: String,systemInstruction: String,history: List<InferenceTurn>) = 100
    override suspend fun countStructuredChatTokens(userText: String,systemInstruction: String,schema: String,history: List<InferenceTurn>) = 100
    override fun generate(prompt: String): Flow<String> = flowOf("합성 응답")
    override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> = flow {
        check(lastTarget!=null)
        generations++
        emit(GenerationEvent.Delta("{\"action\":\"ANSWER\",\"goal\":\"확인\",\"targetType\":\"GENERAL\"}"))
        val end=GenerationSummary("synthetic-paired-target",GenerationStopReason.EOG,GenerationEvidence.SDK,false,384)
        lastGeneration.value=end
        emit(GenerationEvent.Finished(end))
    }
    override suspend fun unload() { lastTarget=null }
    override fun cancel() { cancels++ }
}
