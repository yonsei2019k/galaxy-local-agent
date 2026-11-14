package com.user.localagent

import com.user.localagent.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ExactStructuredReviewInputTest {
    private val options = InferenceOptions(contextSize = 4096, maxOutputTokens = 768)
    private val schema = """{"type":"object","properties":{"type":{"const":"review_complete"}},"required":["type"]}"""
    private data class Received(
        val prompt: String, val system: String, val schema: String, val history: List<InferenceTurn>,
        val options: InferenceOptions,
    )

    private class FakeEngine : InferenceEngine {
        override val state = MutableStateFlow("fake")
        override val residentModelPath: String? = "synthetic-model"
        override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
        override var supportsStructuredOutput = true
        var tokens: Int? = 100
        var countError: Exception? = null
        var afterCount: suspend () -> Unit = {}
        var reason = GenerationStopReason.EOG
        var capReached: Boolean? = false
        var finishCap: Int? = null
        var includeFinish = true
        var loadedOptions: InferenceOptions? = null
        val loads = mutableListOf<InferenceOptions>()
        val counts = mutableListOf<Received>()
        val generations = mutableListOf<Received>()
        var countHistoryReference: List<InferenceTurn>? = null
        var generationHistoryReference: List<InferenceTurn>? = null

        override suspend fun load(path: String, options: InferenceOptions) {
            loadedOptions = options
            loads += options
        }
        override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>): Int? =
            error("The exact helper must not use a plain/estimated count")
        override suspend fun countStructuredChatTokens(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>): Int? {
            counts += Received(userText, systemInstruction, schema, history.toList(), loadedOptions!!)
            countHistoryReference = history
            countError?.let { throw it }
            afterCount()
            return tokens
        }
        override fun generateStructuredEvents(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>): Flow<GenerationEvent> {
            generations += Received(userText, systemInstruction, schema, history.toList(), loadedOptions!!)
            generationHistoryReference = history
            return flow {
                emit(GenerationEvent.Delta("""{"type":"review_complete","summary":"합성 조회 결과"}"""))
                if (includeFinish) emit(GenerationEvent.Finished(GenerationSummary(
                    "synthetic-generation", reason, GenerationEvidence.HOST, capReached,
                    finishCap ?: loadedOptions!!.maxOutputTokens,
                )))
            }
        }
        override fun generate(prompt: String): Flow<String> = error("No plain generation")
        override suspend fun unload() = Unit
        override fun cancel() = Unit
    }

    private fun input(
        prompt: String = "원래 사용자 요청과 현재의 전체 host receipt",
        system: String = "원래 프로젝트 지침",
        history: List<InferenceTurn> = listOf(InferenceTurn("user", "원래 조건")),
        selectedOptions: InferenceOptions = options,
        selectedSchema: String = schema,
    ) = ExactStructuredReviewInput.capture(prompt, system, history, selectedSchema, selectedOptions)

    private fun rejected(code: ExactReviewInputPause.Code, operation: suspend () -> Unit): ExactReviewInputPause {
        try {
            runBlocking { operation() }
            fail("Expected a fixed-code pause")
        } catch (pause: ExactReviewInputPause) {
            assertEquals(code, pause.code)
            return pause
        }
        error("unreachable")
    }

    /** Same structured child/receive boundary used by the writer, without Android/DB/model calls. */
    @OptIn(FlowPreview::class)
    private suspend fun receiveAsWriter(input: ExactStructuredReviewInput, engine: FakeEngine) = coroutineScope {
        val events = input.events(engine, "synthetic-model").produceIn(this)
        val received = mutableListOf<GenerationEvent>()
        while (true) {
            val next = withTimeoutOrNull(100) { events.receiveCatching() } ?: continue
            if (next.isClosed) { next.exceptionOrNull()?.let { throw it }; break }
            received += next.getOrThrow()
        }
        received.toList()
    }

    @Test fun actualCountAndGenerationReceiveExactlyTheFrozenFinalInput() = runBlocking {
        val engine = FakeEngine()
        val frozen = input()
        val events = frozen.events(engine, "synthetic-model").toList()
        assertEquals(2, events.size)
        assertEquals(engine.counts.single(), engine.generations.single())
        assertSame(engine.countHistoryReference, engine.generationHistoryReference)
        assertSame(frozen.options, engine.loads.single())
        assertEquals(options, engine.generations.single().options)
    }

    @Test fun finalOptionalContextAndQuotedBranchSummaryAreCountedAsActuallyGenerated() = runBlocking {
        val engine = FakeEngine()
        val finalSystem = "프로젝트 조건\n기억 문맥\nintent 기록\nfeedback 기록\n" +
            "이전 대화 발췌: {\"untrustedData\":true,\"text\":\"원래 날짜 조건\"}"
        input(system = finalSystem).events(engine, "synthetic-model").toList()
        assertEquals(finalSystem, engine.counts.single().system)
        assertEquals(engine.counts.single(), engine.generations.single())
    }

    @Test fun lateRoundHintAndSelectedHistoryAreInBothActualCalls() = runBlocking {
        val engine = FakeEngine()
        val finalPrompt = "원래 요청\n이번이 마지막 단계다. 새 도구를 부르지 마라."
        val finalHistory = listOf(InferenceTurn("user", "유지된 정확한 조건"), InferenceTurn("assistant", "관찰한 자료"))
        input(prompt = finalPrompt, history = finalHistory).events(engine, "synthetic-model").toList()
        assertEquals(finalPrompt, engine.counts.single().prompt)
        assertEquals(finalHistory, engine.counts.single().history)
        assertEquals(engine.counts.single(), engine.generations.single())
    }

    @Test fun sourceMutationDuringCountCannotChangeFrozenHistory() = runBlocking {
        val original = mutableListOf(InferenceTurn("user", "초기 조건"))
        val engine = FakeEngine().apply { afterCount = { original.clear(); original += InferenceTurn("user", "다른 조건") } }
        val frozen = input(history = original)
        frozen.events(engine, "synthetic-model").toList()
        assertEquals(listOf(InferenceTurn("user", "초기 조건")), engine.generations.single().history)
        assertEquals(engine.counts.single(), engine.generations.single())
        try {
            @Suppress("UNCHECKED_CAST")
            (frozen.history as MutableList<InferenceTurn>).clear()
            fail("Frozen history must be unmodifiable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun ownerReceiptUnicodeWhitespaceAndFullStringsAreNotClipped() = runBlocking {
        val original = "원문  두 칸🙂\n".repeat(1400)
        val engine = FakeEngine()
        input(prompt = original).events(engine, "synthetic-model").toList()
        assertEquals(original, engine.counts.single().prompt)
        assertEquals(original, engine.generations.single().prompt)
    }

    @Test fun finalSchemaAndOutputCapAreNotSubstitutedAfterCount() = runBlocking {
        val engine = FakeEngine()
        val selected = options.copy(maxOutputTokens = 384, thinking = true, backend = "CPU", cpuThreads = 2)
        val selectedSchema = """{"type":"object","required":["literal"]}"""
        val frozen = input(selectedOptions = selected, selectedSchema = selectedSchema)
        frozen.events(engine, "synthetic-model").toList()
        assertNotSame(selected, frozen.options)
        assertEquals(selectedSchema, engine.generations.single().schema)
        assertEquals(selected, engine.counts.single().options)
        assertEquals(engine.counts.single(), engine.generations.single())
    }

    @Test fun nullCountPausesBeforeAnyGeneration() {
        val engine = FakeEngine().apply { tokens = null }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_UNAVAILABLE) { input().events(engine, "synthetic-model").toList() }
        assertEquals(1, engine.counts.size)
        assertTrue(engine.generations.isEmpty())
    }

    @Test fun negativeCountPausesBeforeAnyGeneration() {
        val engine = FakeEngine().apply { tokens = -1 }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_INVALID) { input().events(engine, "synthetic-model").toList() }
        assertTrue(engine.generations.isEmpty())
    }

    @Test fun countFailureHasFixedPublicCodeRetainsPrivateCauseAndNeverGenerates() {
        val backendError = IllegalStateException("synthetic-private-backend-detail")
        val engine = FakeEngine().apply { countError = backendError }
        val pause = rejected(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_FAILED) { input().events(engine, "synthetic-model").toList() }
        assertEquals("EXACT_REVIEW_COUNT_FAILED", pause.message)
        assertSame(backendError, pause.cause)
        assertTrue(engine.generations.isEmpty())
    }

    @Test fun outputReserveBoundaryAllowsExactlyTheSameFinalCap() = runBlocking {
        val engine = FakeEngine().apply { tokens = options.contextSize - options.maxOutputTokens - ExactStructuredReviewInput.RESERVED_TOKENS }
        input().events(engine, "synthetic-model").toList()
        assertEquals(options.maxOutputTokens, engine.generations.single().options.maxOutputTokens)
    }

    @Test fun oneTokenOverBudgetAndHugeCountDoNotShrinkInputOrOutputToGenerate() {
        for (count in listOf(options.contextSize - options.maxOutputTokens - ExactStructuredReviewInput.RESERVED_TOKENS + 1, Int.MAX_VALUE)) {
            val engine = FakeEngine().apply { tokens = count }
            rejected(ExactReviewInputPause.Code.EXACT_REVIEW_CONTEXT_LIMIT) { input().events(engine, "synthetic-model").toList() }
            assertEquals(options, engine.loads.single())
            assertTrue(engine.generations.isEmpty())
        }
    }

    @Test fun cancellationDuringCountPropagatesWithoutGeneratingOrRetrying() = runBlocking {
        val engine = FakeEngine().apply { afterCount = { currentCoroutineContext().cancel(CancellationException("synthetic stop")) } }
        val task = async { input().events(engine, "synthetic-model").toList() }
        try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, engine.counts.size)
        assertTrue(engine.generations.isEmpty())
    }

    @Test fun alreadyCancelledCollectionDoesNotEvenLoad() = runBlocking {
        val engine = FakeEngine()
        val task = async(start = CoroutineStart.LAZY) { input().events(engine, "synthetic-model").toList() }
        task.cancel()
        try { task.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(engine.loads.isEmpty()); assertTrue(engine.generations.isEmpty())
    }

    @Test fun aFailedOrSuccessfulInputCannotBeCollectedAgain() {
        for (failure in listOf(false, true)) {
            val engine = FakeEngine().apply { if (failure) tokens = null }
            val frozen = input()
            if (failure) rejected(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_UNAVAILABLE) { frozen.events(engine, "synthetic-model").toList() }
            else runBlocking { frozen.events(engine, "synthetic-model").toList() }
            val previousCounts = engine.counts.size; val previousGenerations = engine.generations.size
            rejected(ExactReviewInputPause.Code.EXACT_REVIEW_INPUT_REUSED) { frozen.events(engine, "synthetic-model").toList() }
            assertEquals(previousCounts, engine.counts.size); assertEquals(previousGenerations, engine.generations.size)
        }
    }

    @Test fun unavailableStructuredEngineCannotCountOrGenerate() {
        val engine = FakeEngine().apply { supportsStructuredOutput = false }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_UNAVAILABLE) { input().events(engine, "synthetic-model").toList() }
        assertTrue(engine.counts.isEmpty()); assertTrue(engine.generations.isEmpty())
    }

    @Test fun truncatedUnknownErroredOrCapMismatchedFinishCannotBeACompleteReview() {
        for (reason in listOf(GenerationStopReason.MAX_OUTPUT, GenerationStopReason.SDK_DONE_UNKNOWN, GenerationStopReason.CANCELLED,
            GenerationStopReason.ENGINE_ERROR, GenerationStopReason.CONTEXT_REJECTED, GenerationStopReason.RESOURCE_LIMIT)) {
            val engine = FakeEngine().apply { this.reason = reason }
            rejected(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED) { input().events(engine, "synthetic-model").toList() }
        }
        for (cap in listOf<Boolean?>(null, true)) {
            val engine = FakeEngine().apply { capReached = cap }
            rejected(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED) { input().events(engine, "synthetic-model").toList() }
        }
        val engine = FakeEngine().apply { finishCap = 99 }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED) { input().events(engine, "synthetic-model").toList() }
    }

    @Test fun missingTerminalCannotReturnACompleteReview() {
        val engine = FakeEngine().apply { includeFinish = false }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED) { input().events(engine, "synthetic-model").toList() }
    }

    @Test fun optInRejectsCloudVisibleProseContinuationsAndMissingSchemas() {
        val invalid = listOf(
            listOf(true, false, false), listOf(false, true, false), listOf(false, false, true),
        )
        for (flags in invalid) rejected(ExactReviewInputPause.Code.EXACT_REVIEW_NOT_APPLICABLE) {
            ExactStructuredReviewInput.requireApplicable(true, flags[0], schema, flags[1], flags[2])
        }
        rejected(ExactReviewInputPause.Code.EXACT_REVIEW_NOT_APPLICABLE) {
            ExactStructuredReviewInput.requireApplicable(true, false, null, false, false)
        }
        ExactStructuredReviewInput.requireApplicable(true, false, schema, false, false)
    }

    @Test fun disabledOptInLeavesCloudProseAndContinuationUnchanged() {
        ExactStructuredReviewInput.requireApplicable(false, true, null, true, true)
    }

    @Test fun typedPauseSurvivesTheActualWriterProducerBoundaryAndKeepsParentUsable() = runBlocking {
        val engine = FakeEngine().apply { countError = IllegalStateException("synthetic backend cause") }
        var published = false
        var paused: ExactReviewInputPause? = null
        try { receiveAsWriter(input(), engine); published = true } catch (error: ExactReviewInputPause) { paused = error }
        assertFalse(published)
        assertEquals(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_FAILED, paused?.code)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(engine.generations.isEmpty())
    }

    @Test fun successfulProducerBoundaryStillUsesTheSameActualCountAndGenerationInput() = runBlocking {
        val engine = FakeEngine()
        assertEquals(2, receiveAsWriter(input(), engine).size)
        assertEquals(engine.counts.single(), engine.generations.single())
    }

    @Test fun cancellationAtTheWriterProducerBoundaryCannotReturnACompletedReview() = runBlocking {
        val engine = FakeEngine().apply { afterCount = { currentCoroutineContext().cancel() } }
        var published = false
        try { receiveAsWriter(input(), engine); published = true } catch (_: CancellationException) { }
        assertFalse(published)
        assertTrue(engine.generations.isEmpty())
    }
}
