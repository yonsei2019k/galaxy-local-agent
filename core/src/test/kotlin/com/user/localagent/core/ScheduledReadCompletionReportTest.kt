package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledReadCompletionReportTest {
    private val id = ScheduledReadPolicy.ID_PREFIX + "a".repeat(64)

    @Test fun reportShowsUnconfirmedSystemCleanupAfterAppCancellation() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "cancelled", "unconfirmed")
        assertTrue(ActionCompletionReport.manages(emptySet(), listOf(outcome)))
        val answer = ActionCompletionReport.render(setOf(outcome.call.name), listOf(outcome))
        assertTrue(answer.contains("예약 취소된 것 확인함"))
        assertTrue(answer.contains("Android 예약 정리는 아직 못 봄"))
    }

    @Test fun requestedStopIsNotASuccessfulCancellationClaim() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "stop_requested", "confirmed")
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("멈춤 요청 보냄"))
        assertFalse(answer.contains("취소된 것 확인함"))
    }

    @Test fun originalScheduleIdMustMatchTheCancellationReceipt() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "cancelled", "confirmed", ScheduledReadPolicy.ID_PREFIX + "b".repeat(64))
        val report = ActionCompletionReport.compose(emptySet(), listOf(outcome))
        assertEquals(ReceiptStatus.UNVERIFIED, report.receipts.single().status)
        assertFalse(report.complete)
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("부탁한 일이 다 끝났는지는 아직 모름"))
        assertFalse(answer.contains("취소된 것 확인함"))
    }

    @Test fun registrationReportsFutureExecutionWithoutClaimingTheRead() {
        val outcome = outcome(ScheduledReadToolCatalog.CREATE, ScheduledReadPolicy.SCHEDULED)
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("조회 예약 넣음"))
        assertTrue(answer.contains("결과는 원래 대화에"))
        assertTrue(answer.contains(id))
    }

    private fun outcome(tool: String, status: String, cleanup: String? = null, receiptId: String = id): ObservedToolOutcome {
        val call = ToolCall("test-call", tool, buildJsonObject {
            if (tool == ScheduledReadToolCatalog.CANCEL) put("scheduleId", id)
        })
        return ObservedToolOutcome(call, ToolResult(call.id, true, buildJsonObject {
            put("scheduleId", receiptId)
            put("status", status)
            put("runResultVerified", false)
            cleanup?.let { put("systemCleanup", it) }
        }.toString(), VerificationState.VERIFIED))
    }
}
