package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.inference.*
import com.user.localagent.models.InstalledModel
import com.user.localagent.ui.UiAnswerPhase
import com.user.localagent.ui.UiAnswerState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted chunk storage with synthetic event streams only; no models, providers or network. */
@RunWith(AndroidJUnit4::class)
class LongAnswerFinishValidation {
    private class ScriptedEngine(private val script:List<GenerationEvent>,private val following:List<List<GenerationEvent>> =emptyList(),private val onCall:(Int)->Unit={}):InferenceEngine {
        override val state=MutableStateFlow("synthetic")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        var calls=0;var cancellations=0;val prompts=mutableListOf<String>()
        var structuredTokenCount:Int?=null;var structuredCounts=0
        override suspend fun load(path:String,options:InferenceOptions){}
        override fun generate(prompt:String):Flow<String> = error("Use typed synthetic events")
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> = flow {
            calls++;onCall(calls);prompts+=userText
            val current=if(calls==1)script else following.getOrNull(calls-2)?:script
            for(event in current){if(event is GenerationEvent.Finished)lastGeneration.value=event.summary;emit(event)}
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=generateChatEvents(userText,systemInstruction,history)
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>):Int=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Int? {
            structuredCounts++;return structuredTokenCount
        }
        override suspend fun unload(){}
        override fun cancel(){cancellations++}
    }
    private fun terminal(reason:GenerationStopReason,cap:Boolean?,visible:Int)=GenerationEvent.Finished(
        GenerationSummary(UUID.randomUUID().toString(),reason,if(reason==GenerationStopReason.SDK_DONE_UNKNOWN)GenerationEvidence.SDK else GenerationEvidence.NATIVE,cap,128,visibleChars=visible))
    private suspend fun fixture(block:suspend(AppContainer,QueuedChatRequest,InstalledModel,String)->Unit){
        assumeTrue("longAnswerFinishValidationApproved=true required",InstrumentationRegistry.getArguments().getString("longAnswerFinishValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val title="QA synthetic finish ${UUID.randomUUID()}";val chat=app.repository.chat
        val thread=chat.createThread(title=title)
        val user="사용자 원문 조건 🙂\n".repeat(700)+"마지막 조건 그대로 보존"
        assertTrue(user.length in 8000..12000)
        try {
            val message=chat.addMessage(thread.id,thread.activeBranchId,"user",user)
            val run=chat.createRun(thread.id,thread.activeBranchId,message.id,"synthetic-no-weights",4096,false)
            val request=QueuedChatRequest(run.id,thread.id,thread.activeBranchId,null,user,"synthetic-no-weights",false,emptyList(),message.id,allowTools=false)
            chat.updateRunContext(run.id,request.toJson());chat.startRun(run.id)
            block(app,request,InstalledModel("synthetic-no-weights","Synthetic event fixture","/no-model-is-loaded","0".repeat(64),true,"gguf"),user)
            assertEquals(user,chat.fullMessageText(message.id))
        }finally{
            withContext(NonCancellable){
                val live=chat.thread(thread.id)
                if(live!=null){check(live.title==title);chat.deleteThread(thread.id,deleteDerivedMemory=true)}
                assertNull(chat.thread(thread.id))
            }
        }
    }

    @Test fun exactReviewMissingCountPausesWithoutGeneration()=runBlocking<Unit>{fixture {app,request,model,user->
        val engine=ScriptedEngine(emptyList())
        val answer=LongAnswerWriter(app,request,{_,_->fail("No visible structured review")},{},engine)
            .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 구조 검토",emptyList(),
                structuredSchema="""{"type":"object"}""",display=false,exactManagedReview=true)
        assertTrue(answer.paused);assertFalse(answer.exactReviewInputVerified);assertNull(answer.messageId)
        assertEquals(0,engine.calls);assertEquals(1,engine.structuredCounts)
        val run=requireNotNull(app.repository.chat.run(request.runId))
        assertEquals("paused",run.status);assertEquals("EXACT_REVIEW_COUNT_UNAVAILABLE",run.errorCode)
    }}

    @Test fun exactReviewEogReturnsOnlyTheCountedStructuredResult()=runBlocking<Unit>{fixture {app,request,model,user->
        val json="""{"type":"review_complete","summary":"합성 검토"}"""
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(json),terminal(GenerationStopReason.EOG,false,json.length)))
            .apply {structuredTokenCount=1}
        val answer=LongAnswerWriter(app,request,{_,_->fail("No visible structured review")},{},engine)
            .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 구조 검토",emptyList(),
                structuredSchema="""{"type":"object"}""",display=false,exactManagedReview=true)
        assertFalse(answer.paused);assertTrue(answer.exactReviewInputVerified);assertTrue(answer.toolEnvelope)
        assertEquals(json,answer.text);assertNull(answer.messageId)
        assertEquals(1,engine.calls);assertEquals(1,engine.structuredCounts)
        assertEquals("running",app.repository.chat.run(request.runId)?.status)
    }}

    @Test fun resourcePauseBeforeFirstVisibleMessagePersistsTheRunPause()=runBlocking<Unit>{fixture {app,request,model,user->
        val engine=ScriptedEngine(emptyList())
        val answer=LongAnswerWriter(app,request,{_,_->},{},engine,resourcePressureOverride={true})
            .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 일시정지 검증",emptyList())
        assertTrue(answer.paused);assertNull(answer.messageId);assertEquals(0,engine.calls)
        assertEquals("paused",app.repository.chat.run(request.runId)?.status)
        // A reserved empty progress row is legitimate; no generated body or segment is.
        app.repository.chat.assistantForRun(request.runId)?.let{placeholder->
            assertEquals("",app.repository.chat.fullMessageText(placeholder.id))
            assertTrue(app.repository.chat.segmentsForMessage(placeholder.id).isEmpty())
        }
    }}

    @Test fun eightCappedPartsCompleteAutomaticallyInOneDurableAnswerWithoutResume()=runBlocking<Unit>{fixture {app,request,model,user->
        val bodies=(1..8).map {"\n고유 합성 문단 $it: "+"내용 $it 🙂 ".repeat(12)}
        val scripts=bodies.mapIndexed {index,text->listOf(GenerationEvent.Delta(text),
            terminal(if(index==bodies.lastIndex)GenerationStopReason.EOG else GenerationStopReason.MAX_OUTPUT,index!=bodies.lastIndex,text.length))}
        var simulatedElapsed=0L
        val engine=ScriptedEngine(scripts.first(),scripts.drop(1),onCall={simulatedElapsed+=120_000L})
        val state=AtomicReference<UiAnswerState>()
        val statuses=mutableListOf<String>()
        val result=LongAnswerWriter(app,request,{_,view->state.set(view)},{statuses+=it},engine,clock={simulatedElapsed})
            .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 본문만 작성",emptyList())
        val id=requireNotNull(result.messageId)
        assertEquals(8,engine.calls);assertEquals(0,engine.cancellations)
        assertTrue("Simulated elapsed time crosses the former ten-minute pause",simulatedElapsed>600_000L)
        assertFalse(result.paused);assertFalse(result.toolEnvelope)
        assertEquals(UiAnswerPhase.COMPLETE,state.get().phase);assertFalse(state.get().canContinue)
        assertEquals(bodies.joinToString(""),result.text)
        assertEquals(result.text,app.repository.chat.fullMessageText(id))
        assertEquals((1..8).toList(),app.repository.chat.segmentsForMessage(id).map{it.partNumber})
        assertEquals(1,app.repository.chat.messages(request.threadId,request.branchId).first().count{it.role=="assistant"})
        assertTrue(engine.prompts.all{it.contains(user)})
        assertTrue(statuses.any{it.contains("자동으로 이어")})
    }}

    @Test fun cancellationDuringFifthAutomaticPartPreservesPriorTextAndStartsNoSixth()=runBlocking<Unit>{fixture {app,request,model,user->
        val bodies=(1..4).map {"고유 취소검사 문단 $it 🙂\n"}
        val scripts=bodies.map {listOf(GenerationEvent.Delta(it),terminal(GenerationStopReason.MAX_OUTPUT,true,it.length))}
        val engine=ScriptedEngine(scripts.first(),scripts.drop(1),onCall={if(it==5)throw CancellationException("synthetic owner stop")})
        val state=AtomicReference<UiAnswerState>()
        var cancelled=false
        try {
            LongAnswerWriter(app,request,{_,view->state.set(view)},{},engine)
                .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 본문만 작성",emptyList())
        }catch(error:CancellationException){
            cancelled=true
            // Fixed QA codes and exception types only; never write the owner request or error message.
            FailureLog.record(app.applicationContext,"qa_writer_cancellation",error,"QA_WRITER_CANCELLED")
            error.suppressed.forEach {cleanup->
                FailureLog.record(app.applicationContext,"qa_writer_cancellation_cleanup",cleanup,"QA_WRITER_CANCEL_CLEANUP")
            }
        }
        assertTrue(cancelled);assertEquals(5,engine.calls)
        val id=requireNotNull(app.repository.chat.assistantForRun(request.runId)).id
        assertEquals(bodies.joinToString(""),app.repository.chat.fullMessageText(id))
        assertEquals(UiAnswerPhase.CANCELLED,state.get().phase)
        assertFalse(state.get().canContinue)
        assertEquals(4,app.repository.chat.segmentsForMessage(id).size)
    }}

    @Test fun wholeAnswerStorageGuardPreservesReceivedTextWithoutRequestingAnotherPart()=runBlocking<Unit>{fixture {app,request,model,user->
        val body="전체 저장 예산을 검사하는 합성 본문 🙂 ".repeat(8)
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(body),terminal(GenerationStopReason.MAX_OUTPUT,true,body.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine,limits=AnswerContinuationLimits(maxSavedChars=64))
            .write(model,InferenceOptions(maxOutputTokens=128),user,"합성 본문만 작성",emptyList())
        assertTrue(result.paused);assertEquals(1,engine.calls)
        assertEquals(body,app.repository.chat.fullMessageText(requireNotNull(result.messageId)))
        assertEquals(GenerationStopReason.RESOURCE_LIMIT,result.summary?.reason)
    }}

    @Test fun unknownFinishPausesOnceAndExplicitSameRunContinuationPreservesLongOwnerRequest()=runBlocking<Unit>{fixture {app,request,model,user->
        val body="종료 근거를 확인하지 못한 합성 답변 🙂"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(body),terminal(GenerationStopReason.SDK_DONE_UNKNOWN,null,body.length)))
        val state=AtomicReference<UiAnswerState>()
        val result=LongAnswerWriter(app,request,{_,view->state.set(view)},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 본문만 작성",emptyList())
        val id=requireNotNull(result.messageId)
        assertTrue(result.paused);assertEquals(1,engine.calls)
        assertEquals(UiAnswerPhase.PAUSED,state.get().phase);assertTrue(state.get().canContinue)
        assertEquals("paused",app.repository.chat.run(request.runId)?.status)
        assertEquals(body,app.repository.chat.fullMessageText(id));assertEquals(body,result.text)
        val stored=QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson)
        val snapshot=requireNotNull(stored.continuation)
        assertEquals(user,snapshot.user);assertEquals(id,snapshot.messageId)
        assertFalse(stored.allowTools)
        app.repository.chat.queueResumeRun(request.runId);app.repository.chat.resumeRun(request.runId)
        val suffix="\n소유자가 직접 선택한 이어쓰기 끝."
        val resumedEngine=ScriptedEngine(listOf(GenerationEvent.Delta(suffix),terminal(GenerationStopReason.EOG,false,suffix.length)))
        val resumed=LongAnswerWriter(app,stored,{_,_->},{},resumedEngine).write(model,snapshot.options,snapshot.user,snapshot.system,snapshot.history,resume=snapshot)
        assertEquals(id,resumed.messageId);assertFalse(resumed.paused);assertEquals(1,resumedEngine.calls)
        assertTrue(resumedEngine.prompts.single().contains(user))
        assertEquals(body+suffix,app.repository.chat.fullMessageText(id))
        assertEquals(1,app.repository.chat.messages(request.threadId,request.branchId).first().count {it.role=="assistant"})
    }}

    @Test fun oneMiBGuardPersistsEveryReceivedCharacterIncludingBoundaryEmojiBeforePausing()=runBlocking<Unit>{fixture {app,request,model,user->
        val prefix="가".repeat(ANSWER_PART_CHAR_BUDGET-1)
        val tail="🙂한글 경계를 넘긴 마지막 수신 Delta 끝"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(prefix),GenerationEvent.Delta(tail),terminal(GenerationStopReason.EOG,false,prefix.length+tail.length)))
        val state=AtomicReference<UiAnswerState>()
        val result=withTimeout(120_000){LongAnswerWriter(app,request,{_,view->state.set(view)},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 출력 저장",emptyList())}
        val id=requireNotNull(result.messageId);val durable=app.repository.chat.fullMessageText(id)
        assertEquals(prefix+tail,durable);assertEquals(durable,result.text)
        assertTrue(result.paused);assertEquals(UiAnswerPhase.PAUSED,state.get().phase);assertTrue(state.get().canContinue)
        assertEquals(GenerationStopReason.RESOURCE_LIMIT,result.summary?.reason)
        assertEquals("ANSWER_PART_STORAGE_BUDGET",result.summary?.errorCode)
        assertEquals(1,engine.calls);assertTrue(engine.cancellations>=1)
        assertEquals("paused",app.repository.chat.run(request.runId)?.status)
        val parts=app.repository.chat.segmentsForMessage(id)
        assertEquals(1,parts.size)
        assertEquals(durable,app.repository.chat.segmentText(parts.single().id))
        assertFalse(durable.contains('\uFFFD'));assertTrue(durable.endsWith(tail))
        assertEquals(id,QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation?.messageId)
    }}

    @Test fun cappedStructuredOutputNeverAutoContinuesOrCreatesAProseContinuation()=runBlocking<Unit>{fixture {app,request,model,user->
        val partial="{\"type\":\"tool_call\",\"tool\":\"create_document\",\"arguments\":{"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(partial),terminal(GenerationStopReason.MAX_OUTPUT,true,partial.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 도구 형식 검사",emptyList(),structuredSchema="{\"type\":\"object\"}")
        assertEquals(1,engine.calls);assertTrue(result.summary?.mayBeTruncated==true)
        assertTrue(result.toolEnvelope)
        assertTrue(result.opaqueOutput)
        assertTrue(runCatching {com.user.localagent.core.ToolRegistry.parseAndValidate(result.text)}.isFailure)
        assertNull(QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation)
        assertEquals(partial,app.repository.chat.fullMessageText(requireNotNull(result.messageId)))
        assertEquals("paused",app.repository.chat.run(request.runId)?.status)
    }}

    @Test fun cappedOrdinaryJsonHasDurablePartsAndIsNeverReportedAsAToolEnvelope()=runBlocking<Unit>{fixture {app,request,model,user->
        val first="{\"items\":["+"\"한글🙂\",".repeat(1000)
        val second="\"마지막\"],\"done\":true}"
        assertTrue(first.length>4096)
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(first),terminal(GenerationStopReason.MAX_OUTPUT,true,first.length)),
            following=listOf(listOf(GenerationEvent.Delta(second),terminal(GenerationStopReason.EOG,false,second.length))))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 JSON 자료 본문",emptyList())
        val id=requireNotNull(result.messageId)
        assertEquals(2,engine.calls);assertFalse(result.toolEnvelope);assertFalse(result.paused)
        assertEquals(first+second,result.text);assertEquals(result.text,app.repository.chat.fullMessageText(id))
        assertEquals(2,app.repository.chat.segmentsForMessage(id).size)
        assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(result.text) is kotlinx.serialization.json.JsonObject)
        assertNotNull(QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation)
    }}

    @Test fun exactRepeatedContinuationCreatesNoDuplicateChunkOrEmptySecondSegment()=runBlocking<Unit>{fixture {app,request,model,user->
        val body="이미 저장한 합성 문단 🙂. ".repeat(6)
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(body),terminal(GenerationStopReason.MAX_OUTPUT,true,body.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 이어쓰기",emptyList())
        val id=requireNotNull(result.messageId)
        assertEquals(2,engine.calls);assertTrue(result.paused);assertFalse(result.toolEnvelope)
        assertEquals(body,result.text);assertEquals(body,app.repository.chat.fullMessageText(id))
        assertEquals(1,app.repository.chat.segmentsForMessage(id).size)
        assertEquals(2,QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation?.nextPart)
    }}

    @Test fun repeatedBoundaryPrefixFollowedByNewTextStoresOnlyTheNewSuffix()=runBlocking<Unit>{fixture {app,request,model,user->
        val body="경계에서 반복한 기존 합성 문단 🙂. ".repeat(4)
        val suffix="\n실제로 새로 생성한 뒷부분."
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(body),terminal(GenerationStopReason.MAX_OUTPUT,true,body.length)),
            following=listOf(listOf(GenerationEvent.Delta(body),GenerationEvent.Delta(suffix),terminal(GenerationStopReason.EOG,false,body.length+suffix.length))))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 경계 이어쓰기",emptyList())
        val id=requireNotNull(result.messageId)
        assertEquals(2,engine.calls);assertFalse(result.paused)
        assertEquals(body+suffix,result.text);assertEquals(body+suffix,app.repository.chat.fullMessageText(id))
        val segments=app.repository.chat.segmentsForMessage(id)
        assertEquals(2,segments.size);assertEquals(suffix,app.repository.chat.segmentText(segments.last().id))
    }}

    @Test fun structuredQuestionReturnsToHostWithoutDisplayingJsonOrContinuing()=runBlocking<Unit>{fixture {app,request,model,user->
        val raw="""{"type":"clarification","question":"어느 연락처인가요?"}"""
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(raw),terminal(GenerationStopReason.EOG,false,raw.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 추가 질문",emptyList(),
            structuredSchema=com.user.localagent.core.NextActionQuestion.schema(setOf("search_contacts")),display=false)
        assertTrue(result.toolEnvelope);assertFalse(result.paused);assertNull(result.messageId)
        assertEquals("어느 연락처인가요?",com.user.localagent.core.NextActionQuestion.parse(result.text))
        assertEquals(1,engine.calls)
        app.repository.chat.assistantForRun(request.runId)?.let{assertEquals("",app.repository.chat.fullMessageText(it.id))}
    }}

    @Test fun ordinaryJsonWithToolNamedDataFieldsRemainsProse()=runBlocking<Unit>{fixture {app,request,model,user->
        val data="{\"tool\":\"hammer\",\"arguments\":{\"label\":\"한글🙂\"},\"result\":\"일반 자료\"}"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(data),terminal(GenerationStopReason.EOG,false,data.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"일반 JSON 자료",emptyList())
        assertFalse(result.toolEnvelope);assertEquals(data,app.repository.chat.fullMessageText(requireNotNull(result.messageId)))
        assertEquals(1,engine.calls)
    }}

    @Test fun unknownSdkMalformedTypedEnvelopeNeverGetsJoinedOrAContinuationCheckpoint()=runBlocking<Unit>{fixture {app,request,model,user->
        val partial="{\"type\":\"tool_call\",\"tool\":\"create_document\",\"arguments\":{"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(partial),terminal(GenerationStopReason.SDK_DONE_UNKNOWN,null,partial.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 SDK 도구 응답",emptyList())
        assertEquals(1,engine.calls);assertTrue(result.toolEnvelope);assertTrue(result.opaqueOutput)
        assertTrue(runCatching {com.user.localagent.core.ToolRegistry.parseAndValidate(result.text)}.isFailure)
        assertNull(QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation)
        assertEquals(partial,app.repository.chat.fullMessageText(requireNotNull(result.messageId)))
    }}

    @Test fun fullyClosedSdkToolEnvelopeCanBeValidatedWithoutPretendingSdkReportedEog()=runBlocking<Unit>{fixture {app,request,model,user->
        val json="{\"type\":\"tool_call\",\"tool\":\"get_current_datetime\",\"arguments\":{}}"
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(json),terminal(GenerationStopReason.SDK_DONE_UNKNOWN,null,json.length)))
        val result=LongAnswerWriter(app,request,{_,_->},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 도구 형식",emptyList())
        assertTrue(result.toolEnvelope);assertEquals(1,engine.calls);assertNull(result.messageId)
        assertEquals("get_current_datetime",com.user.localagent.core.ToolRegistry.parseAndValidate(result.text).name)
        assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN,result.summary?.reason)
        assertNull(QueuedChatRequest.fromJson(requireNotNull(app.repository.chat.run(request.runId)).contextJson).continuation)
    }}

    @Test fun argumentsOnlyCappedOpaqueJsonKeepsAllReceivedTextWithoutAResumeOrExecutionPath()=runBlocking<Unit>{fixture {app,request,model,user->
        val partial="{\"arguments\":{\"payload\":\""+"가🙂".repeat(1800)
        assertTrue(partial.length>4096)
        val engine=ScriptedEngine(listOf(GenerationEvent.Delta(partial),terminal(GenerationStopReason.MAX_OUTPUT,true,partial.length)))
        val state=AtomicReference<UiAnswerState>()
        val result=LongAnswerWriter(app,request,{_,value->state.set(value)},{},engine).write(model,InferenceOptions(maxOutputTokens=128),user,"합성 불명확 JSON 보존",emptyList())
        assertEquals(1,engine.calls);assertTrue(result.opaqueOutput);assertTrue(result.toolEnvelope);assertTrue(result.paused)
        assertEquals(partial,app.repository.chat.fullMessageText(requireNotNull(result.messageId)))
        assertFalse(state.get().canContinue)
        val run=requireNotNull(app.repository.chat.run(request.runId))
        assertEquals("paused",run.status);assertEquals("opaque_output_received",run.errorCode)
        assertNull(QueuedChatRequest.fromJson(run.contextJson).continuation)
        assertTrue(runCatching {app.repository.chat.queueResumeRun(run.id)}.isFailure)
    }}
}
