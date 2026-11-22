package com.user.localagent

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.briefing.BriefingNotifications
import com.user.localagent.runtime.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SingleChatUpgradeDeviceValidation {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun approved() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("singleChatUpgradeApproved") == "true")
    }

    @Test fun rendererRecoveryRestoresTheSameDraftAndHasNoOldScreen() = runBlocking {
        val activity = compose.activity
        val app = (activity.application as LocalAgentApplication).container
        await("initial conversation selection") { app.runs.selectionReady.value }
        val priorThread = app.runs.selectedThreadId.value
        val fixture = app.repository.chat.createRemoteTestThread()
        val draft = "Synthetic renderer recovery ${UUID.randomUUID()}"
        val token = JsonPrimitive(draft).toString()
        try {
            instrumentation.runOnMainSync { app.runs.selectThread(fixture.id) }
            await("synthetic room selection") { app.runs.selectedThreadId.value == fixture.id && app.runs.selectionReady.value }
            await("initial WebView and composer readiness") { js("Boolean(document.querySelector('textarea[aria-label=\"메시지\"]:not(:disabled)'))") == "true" }
            instrumentation.runOnMainSync {
                val view = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertTrue(view.isAppearanceLightStatusBars)
                assertTrue(view.isAppearanceLightNavigationBars)
            }
            val draftCommand = buildJsonObject {
                put("version",1);put("threadId",fixture.id);put("branchId",fixture.activeBranchId);put("action","draft")
                put("payload",buildJsonObject{put("text",draft)})
            }
            js("window.ziphapNative.postMessage(${JsonPrimitive(draftCommand.toString())});true")
            await("native draft acceptance") { app.runs.draft.value == draft }
            instrumentation.runOnMainSync {
                assertTrue("A live renderer must be terminated to test the actual recovery path",
                    findWebView(activity.window.decorView)?.webViewRenderProcess?.terminate() == true)
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("다시 열기").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("다시 열기").assertIsDisplayed().performClick()
            await("reopened WebView shows bound draft") { js("document.querySelector('textarea[aria-label=\"메시지\"]')?.value === $token && !document.body.innerText.includes('기존 화면')") == "true" }
            js("document.querySelector('button[aria-label=\"대화 및 앱 메뉴 열기\"]')?.click();true")
            await("fixed team rooms and no test or project menu") {
                js("""(() => {
                    const menu = document.querySelector('dialog[open]');
                    const staff = menu?.querySelector('[aria-label="직원 채팅방"]');
                    return !!staff && Array.from(staff.querySelectorAll('button')).map(b => b.textContent.trim()).join('|')
                        === '회장 이수빈|과장 김호영|대리 김규민|대리 김서율'
                        && !/PC 원격 (시험|정리)/.test(menu.textContent)
                        && !Array.from(menu.querySelectorAll('button')).some(b => b.textContent.trim() === '프로젝트');
                })()""") == "true"
            }
            assertEquals(fixture.id, app.runs.selectedThreadId.value)
        } finally {
            instrumentation.runOnMainSync {
                if (app.runs.selectedThreadId.value == fixture.id) app.runs.selectThread(priorThread)
            }
            app.repository.chat.archiveThread(fixture.id)
        }
    }

    @Test fun productionBriefingNotificationPostsTheRobotSmallIcon() = runBlocking {
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(notifications.areNotificationsEnabled())
        assumeTrue("Do not replace a live owner notification", notifications.activeNotifications.none { it.id == 2301 })
        try {
            BriefingNotifications.show(context, 0, 0)
            await("Android notification posted") { notifications.activeNotifications.any { it.id == 2301 } }
            val icon = notifications.activeNotifications.single { it.id == 2301 }.notification.smallIcon
            assertEquals(R.drawable.ic_agent, icon.resId)
            assertTrue(icon.loadDrawable(context) != null)
        } finally {
            notifications.cancel(2301)
        }
    }

    private suspend fun js(code: String): String? {
        val result = CompletableDeferred<String?>()
        instrumentation.runOnMainSync {
            val view = findWebView(compose.activity.window.decorView)
            if (view == null) result.complete(null) else view.evaluateJavascript(code) { result.complete(it) }
        }
        return withTimeout(5_000) { result.await() }
    }

    private suspend fun await(stage:String,condition: suspend () -> Boolean) {
        try { withTimeout(40_000) {
            while (true) {
                compose.mainClock.advanceTimeByFrame()
                if (condition()) break
                delay(150)
            }
        } }
        catch (error:TimeoutCancellationException) { throw AssertionError("Timed out: $stage",error) }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
