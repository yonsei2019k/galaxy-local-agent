package com.user.localagent

import com.user.localagent.ui.UiAnswerPart
import com.user.localagent.ui.UiAnswerPhase
import com.user.localagent.ui.UiAnswerState
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Caller supplies actual Room readback, never model text or unacknowledged buffers. */
internal data class CancelledAnswerReadback(val text: String, val parts: List<UiAnswerPart>)

/** UI cancellation is known before storage reconciliation; storage failure cannot leave it continuing. */
internal object CancelledAnswerFinalizer {
    enum class Stage { PUBLISH, RECONCILE }
    /** Bounded boolean flags only: cancel/body/raw/id/ack/paused/checkpoint. No content or identifiers. */
    fun finalizationCode(cancelled: Boolean, hasBody: Boolean, rawOnly: Boolean, hasMessage: Boolean,
        hasAcknowledgements: Boolean, paused: Boolean, hasCheckpoint: Boolean): String {
        val flags = listOf(cancelled, hasBody, rawOnly, hasMessage, hasAcknowledgements, paused, hasCheckpoint)
        val mask = flags.withIndex().sumOf { (index, enabled) -> if (enabled) 1 shl index else 0 }
        return "WRITER_FINAL_FLAGS_$mask"
    }

    fun preserveFailure(cancellation: CancellationException, error: Throwable, diagnostic: (Throwable) -> Unit = {}) {
        if (error !== cancellation && cancellation.suppressed.none { it === error }) cancellation.addSuppressed(error)
        try { diagnostic(error) } catch (diagnosticFailure: Throwable) {
            if (diagnosticFailure !== cancellation && cancellation.suppressed.none { it === diagnosticFailure }) {
                cancellation.addSuppressed(diagnosticFailure)
            }
        }
    }

    fun finishStatus(cancellation: CancellationException, detail: String, status: (String) -> Unit,
        diagnostic: (Throwable) -> Unit = {}) {
        try { status(detail) } catch (error: Throwable) { preserveFailure(cancellation, error, diagnostic) }
    }

    suspend fun finish(
        cancellation: CancellationException,
        acknowledgedParts: List<UiAnswerPart>,
        reconcile: suspend () -> CancelledAnswerReadback,
        publish: (UiAnswerState) -> Unit,
        diagnostic: (Stage, Throwable) -> Unit = { _, _ -> },
    ): CancelledAnswerReadback? = withContext(NonCancellable) {
        fun frozen(parts: List<UiAnswerPart>): List<UiAnswerPart> =
            Collections.unmodifiableList(parts.map { it.copy() })

        fun preserve(error: Throwable, stage: Stage) {
            preserveFailure(cancellation, error) { diagnostic(stage, it) }
        }

        fun show(view: UiAnswerState) {
            try { publish(view) } catch (error: Throwable) { preserve(error, Stage.PUBLISH) }
        }

        val knownParts = frozen(acknowledgedParts)
        val cancelled = UiAnswerState(
            phase = UiAnswerPhase.CANCELLED,
            parts = knownParts,
            detail = "요청 중단 · 마지막 확인된 본문 유지 · 최종 저장 확인 중",
            canContinue = false,
        )
        // No Room call can fail before the terminal UI state is attempted.
        show(cancelled)
        val readback = try {
            val observed = reconcile()
            CancelledAnswerReadback(observed.text, frozen(observed.parts))
        } catch (error: Throwable) {
            preserve(error, Stage.RECONCILE)
            show(cancelled.copy(detail = "요청 중단 · 마지막 확인된 본문 유지 · 최종 저장 재조회 미확인"))
            return@withContext null
        }
        show(cancelled.copy(parts = readback.parts, detail = "요청 중단 · 저장된 본문 재조회 확인"))
        readback
    }
}
