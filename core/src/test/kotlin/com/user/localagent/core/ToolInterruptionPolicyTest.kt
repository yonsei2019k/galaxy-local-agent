package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolInterruptionPolicyTest {
    private fun call(name:String)=ToolCall("timeout-test",name,buildJsonObject{})

    @Test fun failedRegisteredReadsReleaseThePendingEffectWithoutPretendingToReturnData() {
        for(name in listOf("get_current_datetime","calculate","search_web","search_shopping","read_webpage","search_notes","read_note",
            "search_gmail_messages","read_gmail_message","list_google_calendar_events","search_drive_files","read_drive_text_file","read_google_document")) {
            val actual=ToolInterruptionPolicy.timedOut(call(name))
            assertEquals(name,"timeout-test",actual.id)
            assertFalse(name,actual.success)
            assertEquals(name,VerificationState.FAILED,actual.verification)
            assertTrue(name,actual.effectNotApplied)
        }
    }

    @Test fun writesAndScreenOrBrowserActionsKeepUncertainEffectsAndCannotBeReplayed() {
        for(name in listOf("save_note","patch_note","send_gmail_message","reply_gmail_message","screen_action","app_browser_action","open_app_browser_page","prepare_calendar_event")) {
            val actual=ToolInterruptionPolicy.timedOut(call(name))
            assertFalse(name,actual.success)
            assertEquals(name,VerificationState.UNVERIFIED,actual.verification)
            assertFalse(name,actual.effectNotApplied)
        }
    }

    @Test fun unregisteredReadLikeNamesCannotClaimNoEffect() {
        val actual=ToolInterruptionPolicy.timedOut(call("read_unknown_future_tool"))
        assertEquals(VerificationState.UNVERIFIED,actual.verification)
        assertFalse(actual.effectNotApplied)
    }

    @Test fun realReadFailuresRetainTheirDiagnosticAndUncertainMutationsStayUncertain() {
        val failure=ToolResult("timeout-test",false,"unavailable",VerificationState.UNVERIFIED,"provider offline")
        val read=ToolInterruptionPolicy.observed(call("search_web"),failure)
        assertEquals(failure.copy(verification=VerificationState.FAILED,effectNotApplied=true),read)
        assertSame(failure,ToolInterruptionPolicy.observed(call("patch_note"),failure))
        val partial=failure.copy(success=true)
        assertSame(partial,ToolInterruptionPolicy.observed(call("search_web"),partial))
    }
}
