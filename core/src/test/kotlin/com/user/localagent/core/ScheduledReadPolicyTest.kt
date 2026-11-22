package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ScheduledReadPolicyTest {
    private val original = ScheduledReadScope("thread-a", "branch-a", "project-a", 1, 2)
    private val plan = ScheduledReadRequest("Check notes", "Find the owner note", 100, "search_notes", original)

    @Test fun duplicateRegistrationKeepsTheSameIdWithoutCrossingOwnersOrRequests() {
        val id = plan.idFor("registration-one")
        assertEquals(id, plan.copy().idFor("registration-one"))
        assertNotEquals(id, plan.idFor("registration-two"))
        assertNotEquals(id, plan.copy(scope=original.copy(threadId="thread-b")).idFor("registration-one"))
        assertNotEquals(id, plan.copy(atEpochMillis=101).idFor("registration-one"))
    }

    @Test fun onlyTheOriginalLiveScopeAndExplicitLocalReadCanRun() {
        assertTrue(plan.allows(original, plan.tool, emptySet()))
        assertFalse(plan.allows(original.copy(threadId="thread-b"), plan.tool, emptySet()))
        assertFalse(plan.allows(original.copy(branchId="branch-b"), plan.tool, emptySet()))
        assertFalse(plan.allows(original.copy(projectRevision=3), plan.tool, emptySet()))
        assertFalse(plan.allows(null, plan.tool, emptySet()))
        assertFalse(plan.allows(original, plan.tool, setOf(plan.tool)))
        assertFalse(plan.allows(original, "read_webpage", emptySet()))
        assertFalse(plan.allows(original, "save_note", emptySet()))
        assertFalse(plan.copy(tool="save_note").allows(original,"save_note",emptySet()))
    }

    @Test fun aDueOccurrenceCanBeAdmittedOnceAndNeverAfterCancellation() {
        assertEquals(ScheduledReadAdmission.WAIT, plan.admission("scheduled", 99, original, emptySet()))
        assertEquals(ScheduledReadAdmission.READY, plan.admission("scheduled", 100, original, emptySet()))
        assertEquals(ScheduledReadAdmission.REVOKED, plan.admission("scheduled", 101, original, setOf(plan.tool)))
        for (state in listOf("queued", "running", "completed", "interrupted", "cancelled")) {
            assertEquals(ScheduledReadAdmission.CLOSED, plan.admission(state, 101, original, emptySet()))
        }
    }

    @Test fun approvedClockReceiptChangesIdentityAndCannotGrantOtherReads() {
        val normalClock = plan.copy(tool = ScheduledReadPolicy.CLOCK_TOOL)
        val approved = normalClock.copy(clockReceiptOnly = true)
        assertNotEquals(normalClock.idFor("registration-one"), approved.idFor("registration-one"))
        assertEquals(approved.idFor("registration-one"), approved.copy().idFor("registration-one"))
        val json = kotlinx.serialization.json.Json
        val legacy = json.decodeFromString<ScheduledReadRequest>(json.encodeToString(normalClock))
        assertFalse(legacy.clockReceiptOnly)
        assertEquals(normalClock.idFor("registration-one"), legacy.idFor("registration-one"))
        assertEquals(ScheduledReadAdmission.REVOKED,
            approved.admission("scheduled", 100, original, setOf(ScheduledReadPolicy.CLOCK_TOOL)))
        assertThrows(IllegalArgumentException::class.java) { plan.copy(clockReceiptOnly = true) }
    }

    @Test fun approvalMarkerMustBeBooleanAndAppliesOnlyToTheDeviceClock() {
        fun schedule(readTool: String, receipt: kotlinx.serialization.json.JsonPrimitive) =
            ToolCall("registration", ScheduledReadToolCatalog.CREATE, buildJsonObject {
                put("title", "Clock")
                put("prompt", "Read the device clock at execution")
                put("datetime", "2027-01-01T09:00:00+09:00")
                put("readTool", readTool)
                put(ScheduledReadToolCatalog.CLOCK_RECEIPT_ONLY, receipt)
            })
        assertTrue(ScheduledReadToolCatalog.clockReceiptOnly(
            ToolRegistry.validate(schedule(ScheduledReadPolicy.CLOCK_TOOL, JsonPrimitive(true)))))
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry.validate(schedule("list_reminders", JsonPrimitive(true)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry.validate(schedule(ScheduledReadPolicy.CLOCK_TOOL, JsonPrimitive("true")))
        }
    }

    @Test fun cancellationDistinguishesWaitingRunningAndFinishedWork() {
        assertEquals(ScheduledReadCancellation.CANCEL_BEFORE_START, ScheduledReadPolicy.cancellation("scheduled"))
        assertEquals(ScheduledReadCancellation.CANCEL_BEFORE_START, ScheduledReadPolicy.cancellation("queued"))
        assertEquals(ScheduledReadCancellation.REQUEST_STOP, ScheduledReadPolicy.cancellation("running"))
        assertEquals(ScheduledReadCancellation.CLOSED, ScheduledReadPolicy.cancellation("cancelled"))
        assertEquals(ScheduledReadCancellation.CLOSED, ScheduledReadPolicy.cancellation("completed"))
    }
}
