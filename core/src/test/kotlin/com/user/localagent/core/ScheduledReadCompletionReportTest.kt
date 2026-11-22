package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledReadCompletionReportTest {
    private val id = ScheduledReadPolicy.ID_PREFIX + "a".repeat(64)

    @Test fun reportShowsUnconfirmedSystemCleanupAfterAppCancellation() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "cancelled", "unconfirmed")
        assertTrue(ActionCompletionReport.manages(emptySet(), listOf(outcome)))
        val answer = ActionCompletionReport.render(setOf(outcome.call.name), listOf(outcome))
        assertTrue(answer.contains("취소된 상태"))
        assertTrue(answer.contains("정리는 아직 확인하지 못했습니다"))
    }

    @Test fun requestedStopIsNotASuccessfulCancellationClaim() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "stop_requested", "confirmed")
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("중지를 요청했습니다"))
        assertFalse(answer.contains("취소된 상태"))
    }

    @Test fun originalScheduleIdMustMatchTheCancellationReceipt() {
        val outcome = outcome(ScheduledReadToolCatalog.CANCEL, "cancelled", "confirmed", ScheduledReadPolicy.ID_PREFIX + "b".repeat(64))
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("상태를 읽지 못했습니다"))
        assertFalse(answer.contains("취소된 상태"))
    }

    @Test fun registrationReportsFutureExecutionWithoutClaimingTheRead() {
        val outcome = outcome(ScheduledReadToolCatalog.CREATE, ScheduledReadPolicy.SCHEDULED)
        val answer = ActionCompletionReport.render(emptySet(), listOf(outcome))
        assertTrue(answer.contains("조회 예약을 등록했습니다"))
        assertTrue(answer.contains("실행 결과는 원래 대화에서 확인"))
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
