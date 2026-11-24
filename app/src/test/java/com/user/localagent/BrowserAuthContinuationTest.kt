package com.user.localagent

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.StepEvidence
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class BrowserAuthContinuationTest {
    private val auth=step("open_app_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
    private fun step(tool:String,state:VerificationState,untouched:Boolean=false,note:String="fixture") =
        HarnessStep(tool,JsonObject(emptyMap()),StepEvidence(tool,state,note,effectNotApplied=untouched))

    @Test fun completedReadsAndKnownNoEffectLoginCanResumeOnce() {
        assertTrue(BrowserAuthContinuation.safeSteps(listOf(step("read_webpage",VerificationState.VERIFIED),auth)))
        assertEquals(BrowserAuthContinuation.childId("parent-a"),BrowserAuthContinuation.childId("parent-a"))
        assertNotEquals(BrowserAuthContinuation.childId("parent-a"),BrowserAuthContinuation.childId("parent-b"))
    }

    @Test fun mutationAndUnknownReadCannotBeRetriedThroughLogin() {
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("app_browser_action",VerificationState.VERIFIED),auth)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("read_webpage",VerificationState.UNVERIFIED),auth)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("open_app_browser_page",VerificationState.FAILED),auth).dropLast(1)))
    }

    @Test fun onlyAnActualOwnerCardCanContinueAPairedOpen() {
        val card=step("open_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        assertTrue(BrowserAuthContinuation.safeSteps(listOf(card)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("open_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PC_AUTH_REQUIRED))))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("act_on_pc_browser_page",VerificationState.VERIFIED),card)))
    }
}
