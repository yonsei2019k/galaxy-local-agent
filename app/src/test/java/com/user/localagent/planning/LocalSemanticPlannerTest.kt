package com.user.localagent.planning

import com.user.localagent.compute.PcBusy
import com.user.localagent.compute.PcUnavailable
import com.user.localagent.inference.*
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalSemanticPlannerTest {
    private val chosen=ChosenInference(InstalledModel("fixture","fixture","fixture.gguf","a".repeat(64),true,"gguf"),InferenceOptions(),"fixture")
    private val budget=PlanningBudget(2_000,200,200,40,200,5)
    private class Engine : InferenceEngine {
        override val state=MutableStateFlow("")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val supportsStructuredOutput=true
        var countStructured=true
        var counts=0;var plainCalls=0;var attempts=0;var cancels=0
        var loadDelay=0L;var tokenDelay=0L;var alwaysStall=false;var firstStall=false
        var invalid=false
        var loadFailure:Exception?=null;var tokenFailure:Exception?=null;var generationFailure:Exception?=null
        val inputs=mutableListOf<JsonObject>()
        override suspend fun load(path:String,options:InferenceOptions){delay(loadDelay);loadFailure?.let{throw it}}
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=200
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int? {
            counts++;delay(tokenDelay);tokenFailure?.let{throw it};return if(countStructured)200 else null
        }
        override fun generate(prompt:String):Flow<String> = error("unused")
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            plainCalls++;return events(userText)
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=events(userText)
        private fun events(input:String):Flow<GenerationEvent> = flow {
            attempts++;inputs+=Json.parseToJsonElement(input).jsonObject
            generationFailure?.let{throw it}
            if(alwaysStall||(firstStall&&attempts==1)){emit(GenerationEvent.Delta("{\"action\":"));delay(5_000)}
            emit(GenerationEvent.Delta(if(invalid)"{\"action\":\"TOOL_TASK\",\"goal\":\"확인\",\"targetType\":\"GENERAL\",\"requiredTools\":[\"unknown_tool\"]}" else "{\"action\":\"ANSWER\",\"goal\":\"확인\",\"targetType\":\"GENERAL\"}"))
            emit(GenerationEvent.Finished(GenerationSummary("fixture",GenerationStopReason.EOG,GenerationEvidence.NATIVE,false,384)))
        }
        override fun cancel(){cancels++}
        override suspend fun unload(){}
    }
    @Test fun timeoutDiscardsPartialOutputAndRetriesWithoutLosingOwnerGoalOrLatestCorrections()=runBlocking {
        val engine=Engine().apply{firstStall=true}
        val telemetry=mutableListOf<SemanticPlanningTelemetry>()
        val history=(1..8).map{InferenceTurn(if(it%2==0)"user"else"assistant","correction-$it")}
        val goal=buildJsonObject{put("criteria","keep all owner conditions")}
        val result=LocalSemanticPlanner(engine,{telemetry+=it},budget).plan("full current request",history,chosen,setOf("calculate"),buildJsonObject{
            put("ownerStartedGoal",goal);put("approvedProfileContext","optional profile");put("permissionBinding","unchanged")
        })
        assertEquals(2,result.attempts);assertTrue(engine.cancels>0)
        val retry=engine.inputs.last()
        assertEquals("full current request",retry.getValue("currentOwnerRequest").jsonPrimitive.content)
        assertEquals(goal,retry.getValue("localContextData").jsonObject["ownerStartedGoal"])
        assertEquals("unchanged",retry.getValue("localContextData").jsonObject.getValue("permissionBinding").jsonPrimitive.content)
        assertEquals(history.takeLast(4).map{it.text},retry.getValue("recentConversation").jsonArray.map{it.jsonObject.getValue("text").jsonPrimitive.content})
        assertTrue(telemetry.any{it.stage=="retrying_timeout"});assertTrue(telemetry.count{it.stage=="generating"}>2)
    }
    @Test fun loadAndTokenizerTimeoutHaveDistinctCodesWithoutGeneration()=runBlocking {
        for(stage in listOf("loading","tokenizing")){
            val engine=Engine().apply{if(stage=="loading")loadDelay=5_000 else tokenDelay=5_000}
            val error=runCatching{LocalSemanticPlanner(engine,{},budget).plan("request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull()
            assertEquals("planning_${stage}_timeout",(error as SemanticPlanningException).reasonCode)
            assertEquals(0,engine.attempts)
        }
    }
    @Test fun repeatedGenerationStallStopsAfterOneRetry()=runBlocking {
        val engine=Engine().apply{alwaysStall=true}
        val error=runCatching{LocalSemanticPlanner(engine,{},budget).plan("request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull()
        assertEquals("planning_generating_timeout",(error as SemanticPlanningException).reasonCode);assertEquals(2,engine.attempts)
    }
    @Test fun parentCancellationNeverBecomesRetryOrSemanticFailure()=runBlocking {
        val engine=Engine().apply{alwaysStall=true}
        val error=runCatching{withTimeout(20){LocalSemanticPlanner(engine,{},budget.copy(firstGenerationMs=1_000)).plan("request",emptyList(),chosen,setOf("calculate"))}}.exceptionOrNull()
        assertTrue(error is CancellationException);assertEquals(1,engine.attempts)
    }
    @Test fun unsupportedStructuredCountUsesCountedPlainGeneration()=runBlocking {
        val engine=Engine().apply{countStructured=false}
        val result=LocalSemanticPlanner(engine,{},budget).plan("request",emptyList(),chosen,setOf("calculate"))
        assertFalse(result.constrainedDecoding);assertEquals(1,engine.plainCalls)
    }
    @Test fun invalidToolNeverPromotedByRetry()=runBlocking {
        val engine=Engine().apply{invalid=true}
        val error=runCatching{LocalSemanticPlanner(engine,{},budget).plan("request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull()
        assertTrue((error as SemanticPlanningException).reasonCode.startsWith("plan_rejected_"));assertEquals(2,engine.attempts)
    }
    private fun Engine.failAt(stage:String,failure:Exception){
        when(stage){"loading"->loadFailure=failure;"tokenizing"->tokenFailure=failure;"generating"->generationFailure=failure;else->error("invalid fixture stage")}
    }
    @Test fun engineFailureRecordsActualStageAndOriginalCauseWithoutPublicPayloadOrRetry()=runBlocking {
        for(stage in listOf("loading","tokenizing","generating")){
            val marker="PRIVATE_SYNTHETIC_ENGINE_PAYLOAD"
            val failure=IllegalStateException(marker,java.io.IOException(marker))
            val engine=Engine().apply{failAt(stage,failure)}
            val records=mutableListOf<Triple<String,Throwable,String>>()
            val error=runCatching{LocalSemanticPlanner(engine,{},budget.copy(firstGenerationMs=1_000),
                {s,e,c->records+=Triple(s,e,c)}).plan("synthetic request",listOf(InferenceTurn("user","synthetic history")),chosen,setOf("calculate"))}.exceptionOrNull() as SemanticPlanningException
            assertEquals("planning_engine_failed",error.reasonCode)
            assertEquals(1,records.size);assertEquals(stage,records.single().first)
            val recordedChain=generateSequence(records.single().second){it.cause}.take(8).toList()
            assertTrue("Coroutine recovery must retain the original exception",recordedChain.any{it===failure})
            assertTrue("Original nested cause must remain reachable",recordedChain.any{it===failure.cause})
            assertEquals(error.reasonCode,records.single().third)
            assertFalse(error.message.orEmpty().contains(marker));assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("기기 내"))
            assertEquals(if(stage=="generating")1 else 0,engine.attempts);assertEquals(1,engine.cancels)
        }
    }
    @Test fun typedPcFailuresUseFixedMessagesAndNeverRetry()=runBlocking {
        val marker="PRIVATE_SYNTHETIC_PC_PAYLOAD"
        val cases=listOf(
            PcBusy() to "planning_pc_busy",
            PcUnavailable(marker) to "planning_pc_unavailable",
            PcRequired(marker) to "planning_pc_required",
            PcRequired(marker).apply{initCause(PcBusy())} to "planning_pc_busy",
        )
        for(stage in listOf("loading","tokenizing","generating"))for((failure,expectedCode) in cases){
            val engine=Engine().apply{failAt(stage,failure)}
            val records=mutableListOf<Triple<String,Throwable,String>>()
            val error=runCatching{LocalSemanticPlanner(engine,{},budget.copy(firstGenerationMs=1_000),
                {s,e,c->records+=Triple(s,e,c)}).plan("synthetic request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull() as SemanticPlanningException
            assertEquals(expectedCode,error.reasonCode);assertTrue(error.message.orEmpty().contains("PC"))
            assertFalse(error.message.orEmpty().contains(marker));assertNull(error.cause)
            assertEquals(stage,records.single().first);assertTrue(generateSequence(records.single().second){it.cause}.take(8).any{it===failure})
            assertEquals(expectedCode,records.single().third)
            assertEquals(if(stage=="generating")1 else 0,engine.attempts);assertEquals(1,engine.cancels)
        }
    }
    @Test fun diagnosticsFailureDoesNotReplaceEngineFailureOrStartAnotherAttempt()=runBlocking {
        val engine=Engine().apply{loadFailure=PcBusy()}
        val error=runCatching{LocalSemanticPlanner(engine,{},budget,
            {_,_,_->throw IllegalStateException("synthetic diagnostic sink failure")})
            .plan("synthetic request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull() as SemanticPlanningException
        assertEquals("planning_pc_busy",error.reasonCode);assertEquals(0,engine.attempts);assertEquals(1,engine.cancels)
    }
    @Test fun cancellationIsNotRecordedAsAnEngineFailure()=runBlocking {
        for(stage in listOf("loading","tokenizing","generating")){
            val failure=CancellationException("synthetic cancellation")
            val engine=Engine().apply{failAt(stage,failure)}
            var diagnostics=0
            val error=runCatching{LocalSemanticPlanner(engine,{},budget.copy(firstGenerationMs=1_000),
                {_,_,_->diagnostics++}).plan("synthetic request",emptyList(),chosen,setOf("calculate"))}.exceptionOrNull()
            assertTrue(error is CancellationException);assertEquals(0,diagnostics)
            assertEquals(if(stage=="generating")1 else 0,engine.attempts);assertEquals(1,engine.cancels)
        }
    }
}
