package com.user.localagent.externalagent

import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PrivateResearchReadEvidenceTest {
    private val page = ToolResult("fixture", true, "x".repeat(3650), VerificationState.VERIFIED)
    private val failure = ToolResult("fixture-failure", false, "unavailable", VerificationState.FAILED, effectNotApplied = true)

    @Test fun keepsBoundedVerifiedEvidenceAndSkipsConfirmedFailures() {
        val evidence = checkNotNull(PrivateResearchReadEvidence.capture(listOf("search_web" to failure, "read_webpage" to page)))
        assertEquals(1, evidence.size)
        val read = evidence.single().jsonObject
        assertEquals("read_webpage", read.getValue("tool").jsonPrimitive.content)
        assertEquals(3600, read.getValue("content").jsonPrimitive.contentOrNull?.length)
        assertTrue(read.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test fun doesNotHandOffUnknownEffectsOrChanges() {
        assertNull(PrivateResearchReadEvidence.capture(listOf("read_webpage" to page.copy(verification = VerificationState.UNVERIFIED))))
        assertNull(PrivateResearchReadEvidence.capture(listOf("save_note" to page, "read_webpage" to page)))
        assertNull(PrivateResearchReadEvidence.capture(listOf("read_webpage" to failure)))
    }
}
