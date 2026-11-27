package com.user.localagent

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.StepEvidence
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BrowserAuthContinuationTest {
    @Test fun authenticatedResumeUsesOriginalBrowserAndRequiresFreshInspectableSnapshot() {
        val prior=step("open_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        assertEquals("read_pc_browser_page",BrowserAuthContinuation.readAfterAuth(prior))
        assertNull(BrowserAuthContinuation.readAfterAuth(step(prior.tool,VerificationState.FAILED,false,BrowserAgentTools.PHONE_AUTH_REQUIRED)))
        fun page(content:String)=com.user.localagent.core.ToolResult("read",true,content,VerificationState.VERIFIED)
        assertTrue(BrowserAuthContinuation.verifiedPage(page("""{"snapshotId":"new","loginRequired":false}""")))
        for(body in listOf("""{"snapshotId":null,"loginRequired":false}""","""{"snapshotId":"new","loginRequired":true}""",
            """{"snapshotId":"new","loginRequired":false,"blocked":true}""","""{"snapshotId":"new","loginRequired":false,"externalHandoffBlocked":true}""")){
            assertFalse(BrowserAuthContinuation.verifiedPage(page(body)))
        }
    }
    private val auth=step("open_app_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
    private fun step(tool:String,state:VerificationState,untouched:Boolean=false,note:String="fixture") =
        HarnessStep(tool,JsonObject(emptyMap()),StepEvidence(tool,state,note,effectNotApplied=untouched))

    @Test fun completedReadsAndKnownNoEffectLoginCanResumeOnce() {
        assertTrue(BrowserAuthContinuation.safeSteps(listOf(step("read_webpage",VerificationState.VERIFIED),auth)))
        assertEquals(BrowserAuthContinuation.childId("parent-a"),BrowserAuthContinuation.childId("parent-a"))
        assertNotEquals(BrowserAuthContinuation.childId("parent-a"),BrowserAuthContinuation.childId("parent-b"))
    }

    @Test fun statusTimeoutPreservesOnlyTheMatchingSettledLoginRead() {
        val stage="PC_BRIDGE_TIMEOUT_GET_SESSION_STATUS"
        assertTrue(BrowserAuthContinuation.canPauseAfterStatusTimeout(stage,"a","a",listOf(auth)))
        assertFalse(BrowserAuthContinuation.canPauseAfterStatusTimeout(stage,"b","a",listOf(auth)))
        assertFalse(BrowserAuthContinuation.canPauseAfterStatusTimeout(stage,null,null,listOf(auth)))
        assertFalse(BrowserAuthContinuation.canPauseAfterStatusTimeout("PC_BRIDGE_TIMEOUT_POST_RESULT","a","a",listOf(auth)))
        assertFalse(BrowserAuthContinuation.canPauseAfterStatusTimeout(stage,"a","a",
            listOf(step("app_browser_action",VerificationState.UNVERIFIED),auth)))
    }

    @Test fun ownerCanReopenTheLastAuthenticatedOpenFromSavedEvidence() {
        fun open(url:String)=HarnessStep(auth.tool,JsonObject(mapOf("url" to kotlinx.serialization.json.JsonPrimitive(url))),auth.evidence)
        val login=open("https://hallymedu.co.kr/new_home/My/login/?state=old")
        assertEquals("https://hallymedu.co.kr/new_home/My/login/",BrowserAuthContinuation.ownerLoginPage(listOf(login)))
        assertEquals("https://example.org/",BrowserAuthContinuation.ownerLoginPage(listOf(open("https://example.org/private?q=token"))))
        assertNull(BrowserAuthContinuation.ownerLoginPage(listOf(open("https://user@example.org/private"))))
        assertNull(BrowserAuthContinuation.ownerLoginPage(listOf(open("http://example.org/"))))
        assertNull(BrowserAuthContinuation.ownerLoginPage(listOf(login,step("read_app_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED))))
    }

    @Test fun reopenedNaverAuthUsesTheBoundServicePathWithFreshSignIn() {
        val page="https://booking.naver.com/my/reservations"
        val task="$page?reservationId=fixture&date=2026-11-26"
        val open=HarnessStep(auth.tool,JsonObject(mapOf("url" to JsonPrimitive("$task&session=old#fragment"))),auth.evidence)
        val request=QueuedChatRequest("run","thread","branch",null,"fixture","model",false,emptyList(),"user",
            previousSteps=listOf(open))
        val saved=Json.parseToJsonElement(request.toJson()).jsonObject
        fun context(observed:String)=JsonObject(saved+(BrowserAuthContinuation.NAVER_RETURN_PAGE_KEY to JsonPrimitive(observed))).toString()
        val expected=BrowserAuthContinuation.ReopenPage(page,true,task)
        assertEquals(expected,BrowserAuthContinuation.reopenPage(request.toJson()))
        assertEquals(expected,BrowserAuthContinuation.reopenPage(context("$page?state=discarded")))
        assertEquals(expected,BrowserAuthContinuation.reopenPage(context("https://mail.naver.com/")))

        val read=step("read_app_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        val readOnly=Json.parseToJsonElement(request.copy(previousSteps=listOf(read)).toJson()).jsonObject
        fun readContext(observed:String)=JsonObject(readOnly+(BrowserAuthContinuation.NAVER_RETURN_PAGE_KEY to JsonPrimitive(observed))).toString()
        assertEquals(expected,BrowserAuthContinuation.reopenPage(readContext("$task&access%5Ftoken=discarded")))
        assertEquals(BrowserAuthContinuation.ReopenPage(page,true),BrowserAuthContinuation.reopenPage(readContext(page)))
        assertNull(BrowserAuthContinuation.reopenPage(readContext("https://booking.naver.com.example.org/my/reservations")))
    }

    @Test fun mutationAndUnknownReadCannotBeRetriedThroughLogin() {
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("app_browser_action",VerificationState.VERIFIED),auth)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("read_webpage",VerificationState.UNVERIFIED),auth)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("open_app_browser_page",VerificationState.FAILED),auth).dropLast(1)))
    }

    @Test fun pcLoginRestoresTheObservedPublicHttpsOriginWithoutGuessingAnotherSite() {
        val read=step("read_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        val request=QueuedChatRequest("run","thread","branch",null,"fixture","model",false,emptyList(),"user",previousSteps=listOf(read))
        val context=Json.parseToJsonElement(request.toJson()).jsonObject
        fun bound(origin:String)=JsonObject(context+(BrowserAuthContinuation.PC_LOGIN_ORIGIN_KEY to JsonPrimitive(origin))).toString()
        assertNull(BrowserAuthContinuation.pcLoginOrigin(request.toJson()))
        for(origin in listOf("https://hallymedu.co.kr","https://courses.example.org")){
            assertTrue(BrowserAuthContinuation.canReopenLogin(bound(origin)))
            assertEquals(origin,BrowserAuthContinuation.pcLoginOrigin(bound(origin)))
        }
        for(origin in listOf("http://courses.example.org","https://localhost","https://user@courses.example.org","https://courses.example.org/?token=x")){
            assertNull(BrowserAuthContinuation.pcLoginOrigin(bound(origin)))
        }
    }

    @Test fun reopenUsesTheOriginalOfficialPcSiteAndOnlySettledReads() {
        fun open(url:String)=HarnessStep("open_pc_browser_page",buildJsonObject {put("url",url)},
            StepEvidence("open_pc_browser_page",VerificationState.VERIFIED,"Observed"))
        val read=step("read_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        val steps=listOf(open("https://www.kairb.org/cme/cme_complete.php"),read)
        assertTrue(BrowserAuthContinuation.canReopenPcLogin(steps))
        val saved=QueuedChatRequest("run","thread","branch",null,"owner query","auto",false,emptyList(),"user",previousSteps=steps)
        assertTrue(BrowserAuthContinuation.canReopenLogin(saved.toJson()))
        assertFalse(BrowserAuthContinuation.canReopenLogin(saved.copy(previousSteps=listOf(read)).toJson()))
        assertTrue(BrowserAuthContinuation.canReopenPcLogin(listOf(open("https://kairb.org/"),read)))
        assertFalse(BrowserAuthContinuation.canReopenPcLogin(listOf(read)))
        assertFalse(BrowserAuthContinuation.canReopenPcLogin(listOf(open("https://www.kairb.org.example.org/"),read)))
        assertFalse(BrowserAuthContinuation.canReopenPcLogin(listOf(open("https://www.kairb.org:8443/"),read)))
        assertFalse(BrowserAuthContinuation.canReopenPcLogin(listOf(open("https://www.kairb.org/"),
            step("act_on_pc_browser_page",VerificationState.VERIFIED),read)))
    }

    @Test fun onlyAnActualOwnerCardCanContinueAPairedOpen() {
        val card=step("open_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PHONE_AUTH_REQUIRED)
        assertTrue(BrowserAuthContinuation.safeSteps(listOf(card)))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("open_pc_browser_page",VerificationState.FAILED,true,BrowserAgentTools.PC_AUTH_REQUIRED))))
        assertFalse(BrowserAuthContinuation.safeSteps(listOf(step("act_on_pc_browser_page",VerificationState.VERIFIED),card)))
    }
}
