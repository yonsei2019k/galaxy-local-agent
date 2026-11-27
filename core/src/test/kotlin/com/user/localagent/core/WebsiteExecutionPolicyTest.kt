package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebsiteExecutionPolicyTest {
    private fun call(tool:String,vararg fields:Pair<String,JsonPrimitive>) =
        ToolCall("fixture",tool,JsonObject(fields.toMap()))

    @Test fun everyPlannerModeAndTheExecutorRefuseTheOldPhoneWebsiteTools() {
        val catalog=ToolRegistry.definitions.keys
        for(mode in AgentWorkMode.entries) {
            val offered=AgentWorkModePolicy.filterTools(mode,catalog)
            for(tool in AppBrowserToolCatalog.names+setOf("open_url","open_government_service")) {
                assertFalse("$mode offered $tool",tool in offered)
                assertFalse(tool,WebsiteExecutionPolicy.allows(call(tool)))
            }
        }
        val actionTools=AgentWorkModePolicy.filterTools(AgentWorkMode.ACT,catalog)
        assertTrue(actionTools.containsAll(PcBrowserToolCatalog.names))
        assertTrue(actionTools.containsAll(ScreenControlToolCatalog.names))
    }

    @Test fun filteringPreservesTheOriginalAuthorityAndCannotReplaceAPhoneGrant() {
        val localTools=setOf("calculate","open_url")+AppBrowserToolCatalog.names
        assertEquals(setOf("calculate"),WebsiteExecutionPolicy.offered(localTools,true))
        val captured=localTools+WebsiteExecutionPolicy.pcWebTools
        assertEquals(setOf("calculate"),WebsiteExecutionPolicy.offered(captured,false))
        assertEquals(setOf("calculate")+WebsiteExecutionPolicy.pcWebTools,WebsiteExecutionPolicy.offered(captured,true))
    }

    @Test fun nativeDeviceAppsStayAvailableAndBrowserPlaybackAndChromeControlsStayOnPc() {
        assertTrue(WebsiteExecutionPolicy.permitsNativeScreen(false,false))
        assertFalse(WebsiteExecutionPolicy.permitsNativeScreen(true,false))
        assertFalse(WebsiteExecutionPolicy.permitsNativeScreen(false,true))
        assertTrue(WebsiteExecutionPolicy.allows(call("screen_action","targetArea" to JsonPrimitive("content"))))
        assertTrue(WebsiteExecutionPolicy.allows(call("read_screen")))
        assertFalse(WebsiteExecutionPolicy.allows(call("screen_action","targetArea" to JsonPrimitive("browser_ui"))))
        assertFalse(WebsiteExecutionPolicy.allows(call("read_screen","requirePlaybackEnd" to JsonPrimitive(true))))
        assertFalse(WebsiteExecutionPolicy.allows(call("save_browser_pdf")))
        assertFalse(WebsiteExecutionPolicy.allows(call("save_browser_pdf","browser" to JsonPrimitive("app"))))
        assertTrue(WebsiteExecutionPolicy.allows(call("save_browser_pdf","browser" to JsonPrimitive("pc"))))
    }

    @Test fun onlyThePairedPcPreDispatchReceiptCanCheckpointARequestForReconnection() {
        val preflight=ToolResult("fixture",false,"",VerificationState.FAILED,WebsiteExecutionPolicy.RECONNECT_CODE,true)
        for(tool in WebsiteExecutionPolicy.pcWebTools) {
            assertTrue(WebsiteExecutionPolicy.waiting(tool,preflight))
            assertFalse(WebsiteExecutionPolicy.waiting(tool,preflight.copy(effectNotApplied=false)))
            assertFalse(WebsiteExecutionPolicy.waiting(tool,preflight.copy(verification=VerificationState.UNVERIFIED,effectNotApplied=false)))
            assertFalse(WebsiteExecutionPolicy.waiting(tool,preflight.copy(verificationSummary="timeout")))
            assertFalse(WebsiteExecutionPolicy.waiting(tool,preflight.copy(success=true,verification=VerificationState.VERIFIED,effectNotApplied=false)))
        }
        assertFalse(WebsiteExecutionPolicy.waiting("read_screen",preflight))
    }
}
