package com.user.localagent

import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.AppBrowserToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.integrations.AppWebAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureBrowserLoginCardCanary {
    @Test fun boundBrowserReadReturnsWhileTheSameLoginCardIsStillAvailable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("secureLoginCardApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val web=AppWebAgent.get(context)
        val binding=AppWebAgent.WorkBinding("bound-login-canary","canary-thread","canary-branch")
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                try {
                    val tools=BrowserAgentTools.app(context,{binding}){true}
                    val request=ToolCall("bound-login",AppBrowserToolCatalog.OPEN,buildJsonObject {
                        put("url","https://hallymedu.co.kr/new_home/My/login/")
                        put("showOnPhone",true)
                    })
                    val result=async {tools.execute(request)}
                    val card=withTimeout(30_000){web.ownerLoginCard.filterNotNull().first()}
                    assertEquals(BrowserAgentTools.PHONE_AUTH_REQUIRED,withTimeout(5_000){result.await()}.verificationSummary)
                    withContext(Dispatchers.Main){
                        val originalAuth=web.waitingAuthBinding(binding.runId)
                        assertNotNull(originalAuth)
                        repeat(3){
                            web.foldOwnerLogin(card.id)
                            assertTrue(web.ownerLoginFolded.value)
                            assertEquals(card,web.ownerLoginCard.value)
                            assertEquals(originalAuth,web.waitingAuthBinding(binding.runId))
                            web.unfoldOwnerLogin(card.id)
                            assertFalse(web.ownerLoginFolded.value)
                            assertEquals(card,web.ownerLoginCard.value)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main){
                        web.ownerLoginCard.value?.let { web.cancelLoginCard(it.id) }
                        web.closeBrowser()
                    }
                }
            }
        }
    }

    @Test fun actualSitePromptsInTheSecureNativeCardWithoutSubmittingCredentials() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("secureLoginCardApproved")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val web=AppWebAgent.get(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val request=ToolCall("secure-native-card-canary",AppBrowserToolCatalog.OPEN,buildJsonObject {
                    put("url","https://hallymedu.co.kr/new_home/My/login/")
                    put("showOnPhone",true)
                })
                val result=async {BrowserAgentTools.app(context).execute(request)}
                try {
                    val card=withTimeout(30_000){web.ownerLoginCard.filterNotNull().first()}
                    assertEquals("hallymedu.co.kr",card.host)
                    val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    withTimeout(7_000){
                        while(!showsLoginFields(automation.rootInActiveWindow))delay(300)
                    }
                    withContext(Dispatchers.Main){web.cancelLoginCard(card.id)}
                    assertFalse(result.await().success)
                } finally {
                    withContext(Dispatchers.Main){web.closeBrowser()}
                    result.cancel()
                }
            }
        }
    }

    private fun showsLoginFields(root:AccessibilityNodeInfo?):Boolean {
        if(root==null||root.packageName?.toString()!="com.user.localagent")return false
        val labels=setOf("사이트 로그인","아이디","비밀번호","로그인하고 계속")
        val found=mutableSetOf<String>()
        val scan=ArrayDeque<AccessibilityNodeInfo>()
        scan.add(root)
        repeat(120){
            val node=scan.removeFirstOrNull()?:return found==labels
            val text=node.text?.toString()
            if(text in labels&&node.isVisibleToUser)found+=checkNotNull(text)
            if(found==labels)return true
            for(i in 0 until node.childCount)node.getChild(i)?.let(scan::add)
        }
        return false
    }
}
