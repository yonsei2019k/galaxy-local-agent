package com.user.localagent.scheduling

import com.user.localagent.core.ScheduledReadToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScheduledReadCancellationReceiptTest {
    private val call = ToolCall("test-cancel", ScheduledReadToolCatalog.CANCEL, buildJsonObject {})
    private val schedule = "test-schedule"

    @Test fun appCancellationRemainsVerifiedWhenSystemCleanupFails() = runBlocking {
        val failure = IOException("synthetic")
        val errors = mutableListOf<Exception?>()
        val result = scheduledReadCancellationReceipt(call, schedule, "cancelled", false, { throw failure }, { errors += it })
        assertReceipt(result, "cancelled", "unconfirmed")
        assertEquals(failure.message, (errors.single() as IOException).message)
        assertTrue(result.verificationSummary.contains("아직 확인하지 못했습니다"))
    }

    @Test fun timeoutDoesNotChangeSavedCancellation() = runBlocking {
        val errors = mutableListOf<Exception?>()
        val result = scheduledReadCancellationReceipt(call, schedule, "cancelled", true, { awaitCancellation() }, { errors += it }, 20)
        assertReceipt(result, "cancelled", "unconfirmed")
        assertEquals(listOf<Exception?>(null), errors)
    }

    @Test fun runningStopIsOnlyARequestEvenAfterSystemCleanup() = runBlocking {
        val result = scheduledReadCancellationReceipt(call, schedule, "running", true, {}, { fail("unexpected failure") })
        assertReceipt(result, "stop_requested", "confirmed")
        assertTrue(result.verificationSummary.contains("중지를 요청했습니다"))
    }

    @Test fun completedWorkKeepsItsStatusAndSkipsCleanup() = runBlocking {
        val result = scheduledReadCancellationReceipt(call, schedule, "completed", false, { fail("should not cancel") }, {})
        assertReceipt(result, "completed", "not_requested")
        assertTrue(result.verificationSummary.contains("이미 끝난 예약"))
    }

    @Test fun cancellationPropagatesWithoutAnUnconfirmedReceipt() = runBlocking {
        val cancel = CancellationException("test")
        try {
            scheduledReadCancellationReceipt(call, schedule, "cancelled", true, { throw cancel }, { fail("must propagate") })
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancel.message, actual.message)
        }
    }

    private fun assertReceipt(result: ToolResult, status: String, cleanup: String) {
        val fields = Json.parseToJsonElement(result.content).jsonObject
        assertTrue(result.success)
        assertEquals(VerificationState.VERIFIED, result.verification)
        assertEquals(call.id, result.id)
        assertEquals(schedule, fields.getValue("scheduleId").jsonPrimitive.content)
        assertEquals(status, fields.getValue("status").jsonPrimitive.content)
        assertEquals(cleanup, fields.getValue("systemCleanup").jsonPrimitive.content)
        assertEquals("false", fields.getValue("runResultVerified").jsonPrimitive.content)
    }
}
