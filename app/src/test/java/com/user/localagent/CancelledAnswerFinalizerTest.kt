package com.user.localagent

import com.user.localagent.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancelledAnswerFinalizerTest {
    private val known = (1..4).map { UiAnswerPart(it, "실제 ACK된 합성 문단 $it 🙂\n") }

    @Test fun terminalIsPublishedBeforeAnyStorageReadAndNeverAllowsContinuation() = runBlocking {
        val order = mutableListOf<String>(); val views = mutableListOf<UiAnswerState>()
        val result = CancelledAnswerFinalizer.finish(CancellationException("synthetic cancel"), known,
            reconcile = { order += "read"; assertEquals(UiAnswerPhase.CANCELLED, views.last().phase); CancelledAnswerReadback(known.joinToString("") { it.text }, known) },
            publish = { order += "publish"; views += it })
        assertEquals(listOf("publish", "read", "publish"), order)
        assertNotNull(result); assertTrue(views.all { it.phase == UiAnswerPhase.CANCELLED && !it.canContinue })
    }

    @Test fun failedReconciliationPreservesOnlyAcknowledgedTextAndOriginalCancellation() = runBlocking {
        val cancellation = CancellationException("synthetic cancel")
        val failure = IllegalStateException("synthetic private storage cause")
        val views = mutableListOf<UiAnswerState>(); val diagnostics = mutableListOf<Throwable>()
        val result = CancelledAnswerFinalizer.finish(cancellation, known, reconcile = { throw failure },
            publish = { views += it }, diagnostic = { _, error -> diagnostics += error })
        assertNull(result); assertEquals(known, views.last().parts)
        assertEquals(known.joinToString("") { it.text }, views.last().parts.joinToString("") { it.text })
        assertSame(failure, cancellation.suppressed.single()); assertSame(failure, diagnostics.single())
        assertEquals(UiAnswerPhase.CANCELLED, views.last().phase); assertFalse(views.last().canContinue)
    }

    @Test fun partialMultiReadFailureCannotReplaceFallbackWithAFragment() = runBlocking {
        val views = mutableListOf<UiAnswerState>()
        val result = CancelledAnswerFinalizer.finish(CancellationException("synthetic"), known,
            reconcile = { val partial = known.take(1); assertEquals(1, partial.size); throw IllegalStateException("second read failed") },
            publish = { views += it })
        assertNull(result); assertTrue(views.all { it.parts == known })
    }

    @Test fun successfulReadbackReplacesFallbackWithActuallyObservedDurableText() = runBlocking {
        val durable = known + UiAnswerPart(5, "독립 저장소 조회로 확인한 추가 바이트")
        val views = mutableListOf<UiAnswerState>()
        val result = CancelledAnswerFinalizer.finish(CancellationException("synthetic"), known,
            reconcile = { CancelledAnswerReadback(durable.joinToString("") { it.text }, durable) }, publish = { views += it })!!
        assertEquals(known, views.first().parts); assertEquals(durable, views.last().parts)
        assertEquals(durable.joinToString("") { it.text }, result.text)
    }

    @Test fun mutableCallerListsCannotCorruptPublishedAcknowledgements() = runBlocking {
        val original = known.toMutableList(); val views = mutableListOf<UiAnswerState>()
        CancelledAnswerFinalizer.finish(CancellationException("synthetic"), original,
            reconcile = { original.clear(); throw IllegalStateException("synthetic") }, publish = { views += it })
        assertEquals(known, views.first().parts); assertEquals(known, views.last().parts)
        try { (views.last().parts as MutableList<UiAnswerPart>).clear(); fail("Frozen") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun initialUiCallbackFailureDoesNotPreventReconciliationOrLaterTerminal() = runBlocking {
        val cancellation = CancellationException("synthetic"); val callback = IllegalStateException("synthetic UI error")
        var calls = 0; var reconciled = false; var last: UiAnswerState? = null
        CancelledAnswerFinalizer.finish(cancellation, known, reconcile = { reconciled = true; CancelledAnswerReadback("body", known) },
            publish = { if (++calls == 1) throw callback; last = it })
        assertTrue(reconciled); assertEquals(UiAnswerPhase.CANCELLED, last!!.phase)
        assertSame(callback, cancellation.suppressed.single())
    }

    @Test fun failingDiagnosticsAndUiCannotReplaceTheOriginalCancellation() = runBlocking {
        val cancellation = CancellationException("synthetic"); val ui = IllegalStateException("ui"); val db = IllegalStateException("db"); val log = IllegalStateException("log")
        val result = CancelledAnswerFinalizer.finish(cancellation, known, reconcile = { throw db }, publish = { throw ui }, diagnostic = { _, _ -> throw log })
        assertNull(result); assertEquals(setOf(ui, db, log), cancellation.suppressed.toSet())
    }

    @Test fun originalCancellationAsCleanupFailureDoesNotCauseSelfSuppression() = runBlocking {
        val cancellation = CancellationException("synthetic")
        assertNull(CancelledAnswerFinalizer.finish(cancellation, known, reconcile = { throw cancellation }, publish = {}))
        assertTrue(cancellation.suppressed.isEmpty())
    }

    @Test fun realCancelledParentStillPublishesTerminalBeforeBlockedStorageCompletes() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val views = mutableListOf<UiAnswerState>()
        val job = launch {
            try { awaitCancellation() } catch (cancel: CancellationException) {
                CancelledAnswerFinalizer.finish(cancel, known,
                    reconcile = { entered.complete(Unit); release.await(); CancelledAnswerReadback("body", known) }, publish = { views += it })
                throw cancel
            }
        }
        yield(); job.cancel(); entered.await()
        assertEquals(UiAnswerPhase.CANCELLED, views.single().phase); assertFalse(job.isCompleted)
        release.complete(Unit); job.join()
        assertTrue(job.isCancelled); assertTrue(views.all { !it.canContinue && it.phase == UiAnswerPhase.CANCELLED })
    }

    @Test fun emptyAcknowledgementsDoNotInventBodyOrCompletion() = runBlocking {
        val views = mutableListOf<UiAnswerState>()
        assertNull(CancelledAnswerFinalizer.finish(CancellationException("synthetic"), emptyList(), reconcile = { throw IllegalStateException("unknown") }, publish = { views += it }))
        assertTrue(views.all { it.parts.isEmpty() && it.phase == UiAnswerPhase.CANCELLED && !it.canContinue })
    }

    @Test fun diagnosticsSeparateUiCallbackFaultFromStorageReconciliationFault() = runBlocking {
        val seen = mutableListOf<CancelledAnswerFinalizer.Stage>()
        CancelledAnswerFinalizer.finish(CancellationException("synthetic"), known,
            reconcile = { throw IllegalStateException("storage") }, publish = { throw IllegalStateException("UI") },
            diagnostic = { stage, _ -> seen += stage })
        assertEquals(listOf(CancelledAnswerFinalizer.Stage.PUBLISH, CancelledAnswerFinalizer.Stage.RECONCILE,
            CancelledAnswerFinalizer.Stage.PUBLISH), seen)
    }
}
