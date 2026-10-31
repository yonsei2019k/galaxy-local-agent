package com.user.localagent.callfollowup

import com.user.localagent.inference.*
import com.user.localagent.models.InstalledModel
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CallMapDiagnosticsTest {
    private class Disk {
        val data = mutableMapOf<String, String>()
        fun reopen() = CallMapAttemptStore.forTest { key, max -> object : CallMapAttemptStore.Record {
            override fun read() = data[key].orEmpty()
            override fun write(value: String) { require(value.length <= max); data[key] = value }
        } }
    }

    @Test fun progressThrottlesToOneSecondButPhasesPersistImmediately() {
        val disk = Disk(); val store = disk.reopen(); val id = UUID.randomUUID().toString()
        var clock = 1_000_000L; var tick = 0L
        val recorder = CallMapAttemptRecorder(store, id, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64), now = { clock }, monotonic = { tick })
        val first = checkNotNull(store.latest(id)); assertEquals(0, first.sequence)
        recorder.progress(1); clock += 500; tick += 500; recorder.progress(2)
        assertEquals(0, store.latest(id)?.sequence)
        clock += 500; tick += 500; recorder.heartbeat()
        assertEquals(2, store.latest(id)?.outputCharacters); assertEquals(1000L, store.latest(id)?.elapsedMillis)
        recorder.phase(CallMapPhase.COUNT)
        assertEquals(CallMapPhase.COUNT, store.latest(id)?.phase)
        assertEquals(first, disk.reopen().event(first.attemptId, 0))
    }

    @Test fun cancellationStoresMeasuredStopBeforeEngineMetricsWouldBeUnloaded() {
        val disk = Disk(); val store = disk.reopen(); val id = UUID.randomUUID().toString(); var tick = 1000L
        val recorder = CallMapAttemptRecorder(store, id, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64), now = { tick }, monotonic = { tick })
        recorder.phase(CallMapPhase.COUNT); recorder.counted(1300, "STRUCTURED_RENDERED"); recorder.phase(CallMapPhase.GENERATE)
        tick += 330_000
        val summary = GenerationSummary("synthetic", GenerationStopReason.CANCELLED, GenerationEvidence.NATIVE, false, 1536,
            prefillTokens = 1300, decodeTokens = 42, visibleChars = 28, elapsedMillis = 330_000)
        recorder.progress(28); recorder.finish(CallMapPhase.CANCELLED, summary, CallMapValidationCategory.TIME_LIMIT_OR_CANCELLED)
        val saved = checkNotNull(disk.reopen().latest(id))
        assertEquals("CANCELLED", saved.stopReason); assertEquals(1300, saved.prefillTokens); assertEquals(42, saved.decodeTokens)
        assertEquals("STRUCTURED_RENDERED", saved.inputCountKind); assertEquals(28, saved.outputCharacters)
        assertFalse(saved.capReached!!); assertEquals(CallMapPhase.CANCELLED, saved.phase)
        recorder.heartbeat(); recorder.phase(CallMapPhase.GENERATE)
        assertEquals(saved, store.latest(id))
    }

    @Test fun immutableAttemptCannotBeOverwrittenOrContinueAfterTerminal() {
        val disk = Disk(); val store = disk.reopen(); val id = UUID.randomUUID().toString()
        val recorder = CallMapAttemptRecorder(store, id, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64), now = { 1000 }, monotonic = { 1000 })
        val original = checkNotNull(store.latest(id))
        assertThrows(IllegalArgumentException::class.java) { store.append(original.copy(outputCharacters = 10)) }
        recorder.finish(CallMapPhase.FAILED, null, CallMapValidationCategory.ENGINE_FAILURE)
        val terminal = checkNotNull(store.latest(id))
        assertThrows(IllegalArgumentException::class.java) { store.append(terminal.copy(sequence = terminal.sequence + 1)) }
        assertEquals(original, store.event(original.attemptId, 0))
    }

    @Test fun forgetFencePreventsLateFinallyFromRecreatingDiagnosticHistory() {
        val disk = Disk(); val store = disk.reopen(); val id = UUID.randomUUID().toString(); var tick = 1000L
        val recorder = CallMapAttemptRecorder(store, id, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64), now = { tick }, monotonic = { tick })
        store.forget(id); tick += 1000
        recorder.progress(20); recorder.finish(CallMapPhase.CANCELLED, null)
        assertNull(disk.reopen().latest(id))
        assertEquals("forgotten", disk.data["forgotten.$id"])
    }

    @Test fun legacyContractAdoptionIsLimitedToZeroValidatedOrOrphanMaps() {
        val actual = "a".repeat(64)
        assertEquals(CallAnalysisContractPolicy.Decision.ADOPT_UNPROCESSED_LEGACY, CallAnalysisContractPolicy.decide(null, actual, 0, false))
        assertEquals(CallAnalysisContractPolicy.Decision.REQUIRES_EXPLICIT_REANALYSIS, CallAnalysisContractPolicy.decide(null, actual, 1, false))
        assertEquals(CallAnalysisContractPolicy.Decision.REQUIRES_EXPLICIT_REANALYSIS, CallAnalysisContractPolicy.decide(null, actual, 0, true))
        assertEquals(CallAnalysisContractPolicy.Decision.REQUIRES_EXPLICIT_REANALYSIS, CallAnalysisContractPolicy.decide("b".repeat(64), actual, 0, false))
        assertEquals(CallAnalysisContractPolicy.Decision.SAME_CONTRACT, CallAnalysisContractPolicy.decide(actual, actual, 3, true))
        assertFalse(CallAnalysisContractPolicy.permitsRetry("ANALYSIS_CONTRACT_CHANGED"))
        assertTrue(CallAnalysisContractPolicy.permitsRetry("STAGE_TIME_LIMIT"))
    }

    @Test fun queuePinsAndAdoptedContractCannotSilentlyChangeAfterResume() {
        val disk = mutableMapOf<String, String>()
        val store = CallFollowUpQueueStore.forTest { key, max -> object : CallFollowUpQueueStore.Record {
            override fun read() = disk[key].orEmpty()
            override fun write(value: String) { require(value.length <= max); disk[key] = value }
        } }
        val source = "a".repeat(64); val audio = "b".repeat(64); val id = UUID.randomUUID().toString()
        val snapshot = StableCallRecording(callDigest("$source\n$audio"), source, "c".repeat(64), "content://synthetic/1", audio, 6000, 1000, 15_000, 2000)
        val asr = InstalledModel("asr-qwen-17b", "synthetic", "/synthetic/asr.gguf", "d".repeat(64), true, "gguf")
        val text = InstalledModel("gemma-e2b", "synthetic", "/synthetic/text.gguf", "e".repeat(64), true, "gguf")
        store.insert(CallPipelineJob(id, snapshot, CallFollowUpAuthorization(1, snapshot.sourceGrantId, 1000),
            CallFollowUpEnqueueReceipt(snapshot.snapshotId, id, store.projectId(), UUID.randomUUID().toString()), CallAsrPin(asr, language = "ko", threads = 2), text))
        store.update(id, 1) { it.copy(analysisContractSha256 = "f".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { store.update(id, 1) { it.copy(analysisContractSha256 = "0".repeat(64)) } }
        assertThrows(IllegalArgumentException::class.java) { store.update(id, 1) { it.copy(textModel = text.copy(sha256 = "0".repeat(64))) } }
        assertThrows(IllegalArgumentException::class.java) { store.update(id, 1) { it.copy(asr = it.asr.copy(language = "en")) } }
    }

    @Test fun secondAttemptCountFailureNeverBorrowsPreviousAttemptEogOrTokenCounts() {
        val first = GenerationSummary("first", GenerationStopReason.EOG, GenerationEvidence.NATIVE, false, 1536, prefillTokens = 1000, decodeTokens = 200)
        assertNull(CallMapGenerationProof.current(false, first.requestId, null, first))
        assertNull(CallMapGenerationProof.current(true, first.requestId, null, first))
        val second = GenerationSummary("second", GenerationStopReason.CANCELLED, GenerationEvidence.NATIVE, false, 1536, prefillTokens = 1100, decodeTokens = 10)
        assertEquals(second, CallMapGenerationProof.current(true, first.requestId, null, second))
        assertEquals(second, CallMapGenerationProof.current(true, first.requestId, second, first))
    }

    @Test fun nativeAttemptRejectsPriorRequestAndNeverRebindsToAnotherGeneration() {
        val prior = UUID.randomUUID().toString(); val current = UUID.randomUUID().toString(); val next = UUID.randomUUID().toString()
        val gate = CallMapNativeProgressGate(setOf(prior))
        assertNull(gate.accept(native(prior, NativeGenerationPhase.FINISHED, 704, 704, 200)))
        val preparation = native(current, NativeGenerationPhase.PREPARE)
        assertEquals(preparation, gate.accept(preparation))
        assertNull(gate.accept(native(prior, NativeGenerationPhase.FINISHED, 704, 704, 200)))
        assertNull(gate.accept(native(next, NativeGenerationPhase.PREPARE)))
        assertEquals(current, gate.accept(native(current, NativeGenerationPhase.PREFILL, 300, 704))?.requestId)
    }

    @Test fun actualPrefillAndDecodeAreDurableAndCancellationFlushesTheFinalThrottledSnapshot() {
        val disk = Disk(); val store = disk.reopen(); val queue = UUID.randomUUID().toString(); val request = UUID.randomUUID().toString()
        var clock = 1_000_000L; var tick = 0L
        val recorder = CallMapAttemptRecorder(store, queue, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
            now = { clock }, monotonic = { tick })
        recorder.phase(CallMapPhase.GENERATE)
        recorder.nativeProgress(native(request, NativeGenerationPhase.PREFILL, 300, 704, elapsed = 170_000))
        assertNull(store.latest(queue)?.nativePhase) // No extra write inside the one-second window.
        clock += 1000; tick += 1000; recorder.heartbeat()
        val savedPrefill = checkNotNull(disk.reopen().latest(queue))
        assertEquals("PREFILL", savedPrefill.nativePhase); assertEquals(300, savedPrefill.nativePromptProcessed)
        assertEquals(704, savedPrefill.nativePromptTotal); assertEquals(0, savedPrefill.nativeSampledTokens)
        clock += 10; tick += 10
        recorder.nativeProgress(native(request, NativeGenerationPhase.DECODE, 704, 704, 3, 12, 4, 170_010))
        // A decreasing or different-request snapshot cannot erase actual progress.
        recorder.nativeProgress(native(request, NativeGenerationPhase.PREFILL, 100, 704, elapsed = 170_005))
        recorder.nativeProgress(native(UUID.randomUUID().toString(), NativeGenerationPhase.PREPARE))
        recorder.finish(CallMapPhase.CANCELLED, null, CallMapValidationCategory.TIME_LIMIT_OR_CANCELLED)
        val cancelled = checkNotNull(disk.reopen().latest(queue))
        assertEquals("DECODE", cancelled.nativePhase); assertEquals(704, cancelled.nativePromptProcessed)
        assertEquals(3, cancelled.nativeSampledTokens); assertEquals(12L, cancelled.nativeRawUtf8Bytes)
        assertEquals(4L, cancelled.nativeVisibleUtf8Bytes); assertEquals(170_010L, cancelled.nativeElapsedMillis)
        assertEquals(savedPrefill, store.event(savedPrefill.attemptId, savedPrefill.sequence))
    }

    @Test fun malformedNativeTupleFailsClosedWithoutChangingOldMetadata() {
        val store = Disk().reopen(); val queue = UUID.randomUUID().toString()
        CallMapAttemptRecorder(store, queue, 0, 1, "a".repeat(64), "b".repeat(64), "c".repeat(64), now = { 1000 }, monotonic = { 1000 })
        val old = checkNotNull(store.latest(queue))
        assertThrows(IllegalArgumentException::class.java) { store.append(old.copy(sequence = old.sequence + 1, nativePhase = "PREFILL")) }
        assertEquals(old, store.latest(queue))
    }

    private fun native(id: String, phase: NativeGenerationPhase, done: Int = 0, total: Int = 0, sampled: Int = 0,
        rawBytes: Long = 0, visibleBytes: Long = 0, elapsed: Long = 0) =
        NativeGenerationProgress(id, phase, done, total, sampled, rawBytes, visibleBytes, elapsed)
}
