package com.user.localagent

import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.integrations.KakaoScreenReader
import com.user.localagent.core.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly gated real read. No private message text is emitted to instrumentation output. */
@RunWith(AndroidJUnit4::class)
class KakaoScreenDeviceValidation {
    @Test fun approvedVisiblePhoneReadUsesLiveAccessibilityAndNoSendAdapter() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue("kakaoVisibleReadApproved=true required",
            InstrumentationRegistry.getArguments().getString("kakaoVisibleReadApproved")=="true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        assertFalse("Owner image work must remain untouched",app.imageStudio.state.value.running)
        val coordinator=runBlocking(kotlinx.coroutines.Dispatchers.Main.immediate){app.runs}
        val threadId=runBlocking {withTimeout(15000){coordinator.selectedThreadId.first{it.isNotBlank()}}}
        assertFalse("Owner agent work must remain untouched",coordinator.hasActiveRuns.value)
        assertTrue("Owner queued work must remain untouched",runBlocking{app.repository.chat.queuedRuns().isEmpty()})
        val reader=KakaoScreenReader(context)
        // The user has separately approved content reads and enabled Android's service manually.
        reader.allow(true)
        val binding=checkNotNull(reader.binding())
        val result=runBlocking {
            reader.readForRequest(ToolCall("qa-kakao-visible","read_kakao_screen",buildJsonObject{}),binding,threadId) {
                check(reader.binding()==binding)
            }
        }
        assertTrue(result.success)
        val snapshot=Json.parseToJsonElement(result.content).jsonObject
        assertEquals("visible_screen_only",snapshot.getValue("coverage").jsonPrimitive.content)
        assertTrue(snapshot.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertFalse(snapshot.getValue("fullHistoryVerified").jsonPrimitive.boolean)
        assertTrue("No readable Kakao text found",snapshot.getValue("textNodes").jsonPrimitive.int>0)
        assertEquals(64,snapshot.getValue("sha256").jsonPrimitive.content.length)
    }
}
