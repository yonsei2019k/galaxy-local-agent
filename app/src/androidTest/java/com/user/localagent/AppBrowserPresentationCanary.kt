package com.user.localagent

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.AppBrowserToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.integrations.AppWebAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBrowserPresentationCanary {
    @Test fun explicitPhoneOpenShowsTheLoadedPageFromTheTool() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("browserPresentationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val web=AppWebAgent.get(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val browser=BrowserAgentTools.app(context)
                fun request(show:Boolean)=ToolCall("browser-probe",AppBrowserToolCatalog.OPEN,buildJsonObject {
                    put("url","https://example.com/")
                    if(show)put("showOnPhone",true)
                })
                try {
                    assertTrue(browser.execute(request(false)).success)
                    assertNull(web.presentedAddress.value)
                    val shown=browser.execute(request(true))
                    assertTrue("The public probe page must actually load",shown.success)
                    val result=Json.parseToJsonElement(shown.content).jsonObject
                    assertEquals(JsonPrimitive("Example Domain"),result["title"])
                    assertEquals(JsonPrimitive(true),result["phoneBrowserVisible"])
                } finally {
                    withContext(Dispatchers.Main){web.closeBrowser()}
                }
            }
        }
    }
}
