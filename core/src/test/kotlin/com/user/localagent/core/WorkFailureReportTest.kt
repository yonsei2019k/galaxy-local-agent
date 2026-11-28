package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFailureReportTest {
    @Test fun providerPollingTimeoutNamesTheMissingResponseWithoutClaimingAConnectionLoss() {
        val text = WorkFailureReport.describe("PC_BRIDGE_TIMEOUT_GET_PROVIDER", emptyList())
        assertTrue(text.contains("PC에서 작업 결과"))
        assertTrue(text.contains("답이 오지 않았어요"))
        assertFalse(text.contains("PC_BRIDGE_TIMEOUT"))
        assertFalse(text.contains("연결이 끊겼어요"))
    }

    @Test fun uncertainChangeIsNeverPresentedAsAnUnappliedChangeOrRetriedBlindly() {
        val text = WorkFailureReport.describe("tool_exception", emptyList(), unknownEffect = true)
        assertTrue(text.contains("반영됐는지 확인하지 못했어요"))
        assertTrue(text.contains("다시 하기 전에 현재 상태"))
    }

    @Test fun readFailureKeepsVerifiedProgressAndDoesNotEchoRawToolOutput() {
        val outcomes = listOf(
            observation("search_notes", true, VerificationState.VERIFIED),
            observation("search_notes", false, VerificationState.FAILED),
        )
        val text = WorkFailureReport.describe("FINAL_PROVIDER_INCOMPLETE", outcomes)
        assertTrue(text.contains("결과를 가져오지 못했어요"))
        assertTrue(text.contains("작업 기록에 남겨뒀어요"))
        assertFalse(text.contains("RAW_TEST_SECRET"))
    }

    @Test fun unverifiedCompletionExplainsWhichEvidenceIsMissing() {
        val text = WorkFailureReport.describe("FINAL_EVIDENCE_INCOMPLETE", emptyList())
        assertTrue(text.contains("확인할 자료가 부족해요"))
        assertTrue(text.contains("마지막 상태부터 확인"))
    }

    private fun observation(tool: String, success: Boolean, state: VerificationState): ObservedToolOutcome {
        val call = ToolCall("sample", tool, buildJsonObject {})
        return ObservedToolOutcome(call, ToolResult(call.id, success, "RAW_TEST_SECRET", state, "RAW_TEST_SECRET"))
    }
}
