package com.user.localagent.tools

import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCalendarReadEvidenceTest {
    private val call = ToolCall("calendar-read", PhoneCalendarReadEvidence.TOOL, JsonObject(emptyMap()))

    @Test
    fun exactUnappliedReadPermissionFailureCanPauseAnActiveRequest() {
        val denied = PhoneCalendarReadEvidence.permissionDenied(call)
        assertEquals(call.id, denied.id)
        assertEquals(VerificationState.FAILED, denied.verification)
        assertTrue(denied.effectNotApplied)
        assertTrue(PhoneCalendarReadEvidence.requiresPermission(call.name, denied))
        assertFalse(PhoneCalendarReadEvidence.requiresPermission("prepare_calendar_event", denied))
        assertFalse(PhoneCalendarReadEvidence.requiresPermission(call.name, denied.copy(effectNotApplied = false)))
    }

    @Test
    fun missingProviderResponseIsAnUnverifiedReadAndDoesNotRequestAccess() {
        val unavailable = PhoneCalendarReadEvidence.providerUnavailable(call)
        assertFalse(unavailable.success)
        assertEquals(VerificationState.FAILED, unavailable.verification)
        assertFalse(PhoneCalendarReadEvidence.requiresPermission(call.name, unavailable))
    }
}
