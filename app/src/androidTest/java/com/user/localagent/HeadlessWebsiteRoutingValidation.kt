package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import com.user.localagent.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlessWebsiteRoutingValidation {
    @Test fun savedMobileWebsiteCallsCannotOpenAnIntentWebViewOrPhoneScreen()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val executor=ToolExecutor(context,app.repository,app.scheduler,app.conversationImports,app.phoneRecords)
        val calls=listOf(
            ToolCall("url","open_url",buildJsonObject {put("url","https://example.invalid/")}),
            ToolCall("portal","open_government_service",buildJsonObject {put("service","gov24")}),
            ToolCall("page","open_app_browser_page",buildJsonObject {put("url","https://example.invalid/");put("showOnPhone",true)}),
            ToolCall("read","read_app_browser_page",buildJsonObject {}),
            ToolCall("pdf","save_browser_pdf",buildJsonObject {put("snapshotId","fixture");put("ref","r1");put("browser","phone")}),
            ToolCall("lesson","read_screen",buildJsonObject {put("requirePlaybackEnd",true)})
        )
        for(call in calls) {
            assertTrue(call.name,runCatching {executor.prepareIntent(call)}.isFailure)
            val result=executor.execute(call)
            assertEquals(call.id,result.id)
            assertFalse(call.name,result.success)
            assertEquals(VerificationState.FAILED,result.verification)
            assertEquals("phone_web_route_unavailable",result.verificationSummary)
            assertTrue(call.name,result.effectNotApplied)
        }
    }
}
