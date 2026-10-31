package com.user.localagent.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class GenerationEventsTest {
    private fun measured(decode: Int = 768, ttft: Double = 0.3) =
        measuredInference(doubleArrayOf(ttft, 100.0, decode.toDouble(), 23.0, 6.0))!!
    private fun summary(reason: GenerationStopReason = GenerationStopReason.MAX_OUTPUT) =
        GenerationSummary("synthetic-request", reason, GenerationEvidence.NATIVE,
            reason == GenerationStopReason.MAX_OUTPUT, 768, visibleChars = 2, metrics = measured())

    @Test fun hiddenOnlyCapRetainsRealCountsAndNullableVisibleTtft() {
        val metrics = measured(ttft = -1.0)
        assertEquals(768, metrics.decodeTokens)
        assertEquals(100, metrics.prefillTokens)
        assertEquals(6.0, metrics.decodeTokensPerSecond, 0.0)
        assertNull(metrics.visibleTimeToFirstTokenSeconds)
        assertNull(summary().copy(visibleChars = 0, metrics = metrics).timeToFirstVisibleTokenSeconds)
    }

    @Test fun nativeEogAndOutputCapRemainDistinct() {
        assertEquals(GenerationStopReason.EOG, nativeStopReason(1))
        assertEquals(GenerationStopReason.MAX_OUTPUT, nativeStopReason(2))
        assertFalse(summary(GenerationStopReason.EOG).mayBeTruncated)
        assertTrue(summary().mayBeTruncated)
        assertTrue(runCatching { nativeStopReason(0) }.isFailure)
    }

    @Test fun sdkDoneAtCapIsAnInferenceAndNeverAClaimOfEog() {
        val result = sdkGenerationSummary("sdk", 768, measured(), 20, 1000, 256, 0.4)
        assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN, result.reason)
        assertEquals(GenerationEvidence.INFERRED, result.evidence)
        assertEquals(true, result.capReached)
        assertTrue(result.mayBeTruncated)
        assertFalse(result.requiresManualContinuation)
        assertEquals(0.4, result.timeToFirstVisibleTokenSeconds!!, 0.0)
    }
    @Test fun unknownSdkFinishNeedsOwnerChoiceRatherThanAutomaticRepeatOrFalseEog(){
        for(capReached in listOf<Boolean?>(null,false)){
            val result=GenerationSummary("unknown",GenerationStopReason.SDK_DONE_UNKNOWN,GenerationEvidence.SDK,capReached,768,visibleChars=20)
            assertTrue(result.requiresManualContinuation);assertFalse(result.mayBeTruncated)
        }
        assertFalse(summary(GenerationStopReason.EOG).requiresManualContinuation)
        assertFalse(summary().requiresManualContinuation)
    }

    @Test fun sdkMissingBenchmarkDoesNotInventTokensOrNaturalCompletion() {
        val result = sdkGenerationSummary("sdk", 768, null, 0, 1000, 0)
        assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN, result.reason)
        assertEquals(GenerationEvidence.SDK, result.evidence)
        assertNull(result.capReached)
        assertNull(result.decodeTokens)
        assertNull(result.timeToFirstVisibleTokenSeconds)
    }

    @Test fun sdkBelowCapStillDoesNotProveEog() {
        val result = sdkGenerationSummary("sdk", 768, measured(123), 4, 1000, 0)
        assertEquals(false, result.capReached)
        assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN, result.reason)
        assertEquals(GenerationEvidence.SDK, result.evidence)
    }

    @Test fun malformedNativeMetricsDoNotBecomeEstimatedMeasurements() {
        assertNull(measuredInference(doubleArrayOf(0.2, 1.0, Double.NaN, 1.0, 1.0)))
        assertNull(measuredInference(doubleArrayOf(0.2, 1.0, -2.0, 1.0, 1.0)))
        assertNull(measuredInference(doubleArrayOf(0.2, 1.5, 2.0, 1.0, 1.0)))
        assertNull(measuredInference(doubleArrayOf(0.2, 1.0, Int.MAX_VALUE.toDouble() + 1, 1.0, 1.0)))
        assertNull(measuredInference(doubleArrayOf(1.0)))
    }

    @Test fun terminalIsRecordedOnceAcrossFinishAndCancellation() {
        val recorded = mutableListOf<GenerationSummary>()
        val terminal = GenerationTerminal { recorded += it }
        assertTrue(terminal.finish(summary()))
        assertFalse(terminal.finish(summary(GenerationStopReason.CANCELLED)))
        assertEquals(1, recorded.size)
        assertEquals(GenerationStopReason.MAX_OUTPUT, recorded.single().reason)
    }

    @Test fun concurrentTerminalRaceStillPublishesExactlyOneResult() {
        val count = AtomicInteger()
        val gate = CountDownLatch(1)
        val terminal = GenerationTerminal { count.incrementAndGet() }
        val threads = (1..12).map { Thread { gate.await(); terminal.finish(summary()) }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, count.get())
    }

    @Test fun cancellationContextRejectionAndResourcePressureAreNotCompletion() {
        assertEquals(GenerationStopReason.CANCELLED, failureStopReason(CancellationException()))
        assertEquals(GenerationStopReason.CONTEXT_REJECTED,
            failureStopReason(IllegalArgumentException("Current request exceeds model context")))
        assertEquals(GenerationStopReason.RESOURCE_LIMIT,
            failureStopReason(CancellationException(), resourcePressure = true))
        assertEquals(GenerationStopReason.ENGINE_ERROR, failureStopReason(IllegalStateException("decode failed")))
    }

    @Test fun oldTextFlowRetainsAllDeltasWithoutTerminalMetadataInTheAnswer() = runBlocking {
        val text = flowOf(GenerationEvent.Delta("한글 "), GenerationEvent.Delta("🙂"),
            GenerationEvent.Finished(summary())).textDeltas().toList()
        assertEquals(listOf("한글 ", "🙂"), text)
    }

    @Test fun thinkingBudgetPreservesAnAnswerBudgetEvenForShortSmokeRuns() {
        assertEquals(256, thinkingBudgetFor(true, 2048))
        assertEquals(256, thinkingBudgetFor(true, 768))
        assertEquals(8, thinkingBudgetFor(true, 16))
        assertEquals(0, thinkingBudgetFor(false, 1536))
        assertTrue(runCatching { thinkingBudgetFor(true, 0) }.isFailure)
    }

    @Test fun legacyFallbackReportsUnknownRatherThanEog() = runBlocking {
        val events=legacyGenerationEvents(flowOf("complete sentence")).toList()
        assertEquals(2,events.size)
        assertEquals(GenerationStopReason.SDK_DONE_UNKNOWN,
            (events.last() as GenerationEvent.Finished).summary.reason)
    }

    @Test fun downstreamFailureIsNotReplacedWithAFalseEngineTerminal() = runBlocking {
        val expected=IllegalStateException("synthetic collector failure")
        val failure=runCatching {legacyGenerationEvents(flowOf("a")).collect {throw expected}}.exceptionOrNull()
        assertSame(expected,failure)
    }

    @Test fun upstreamFailureHasOneTerminalAndPreservesTheOriginalError() = runBlocking {
        val expected=IllegalStateException("synthetic engine failure")
        val events=mutableListOf<GenerationEvent>()
        val failure=runCatching {legacyGenerationEvents(flow {emit("a");throw expected}).collect {events+=it}}.exceptionOrNull()
        assertSame(expected,failure)
        assertEquals(1,events.filterIsInstance<GenerationEvent.Finished>().size)
        assertEquals(GenerationStopReason.ENGINE_ERROR,
            events.filterIsInstance<GenerationEvent.Finished>().single().summary.reason)
    }
}
