package com.user.localagent.core

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

    @Test fun cancellationDistinguishesWaitingRunningAndFinishedWork() {
        assertEquals(ScheduledReadCancellation.CANCEL_BEFORE_START, ScheduledReadPolicy.cancellation("scheduled"))
        assertEquals(ScheduledReadCancellation.CANCEL_BEFORE_START, ScheduledReadPolicy.cancellation("queued"))
        assertEquals(ScheduledReadCancellation.REQUEST_STOP, ScheduledReadPolicy.cancellation("running"))
        assertEquals(ScheduledReadCancellation.CLOSED, ScheduledReadPolicy.cancellation("cancelled"))
        assertEquals(ScheduledReadCancellation.CLOSED, ScheduledReadPolicy.cancellation("completed"))
    }
}
