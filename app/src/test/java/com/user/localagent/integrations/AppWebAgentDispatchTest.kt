package com.user.localagent.integrations

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.AppBrowserToolCatalog
import com.user.localagent.core.PcBrowserToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWebAgentDispatchTest {
    @Test
    fun onlyTheHostsApprovalReachesTheAppBackend() = runBlocking {
        val calls=mutableListOf<JsonObject>()
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,body->
            calls+=body
            buildJsonObject{put("status","approval_required");put("approvalId","a".repeat(32))}
        }
        val call=click(AppBrowserToolCatalog.ACTION,spoof=true)
        val withoutApproval=tools.execute(call)
        assertEquals(1,calls.size)
        assertNull(calls.single()["approvedAppSnapshot"])
        assertTrue(withoutApproval.effectNotApplied)
        assertFalse(withoutApproval.success)
        tools.execute(call,approvedSnapshot="snapshot")
        assertEquals(2,calls.size)
        assertEquals("snapshot",calls.last().getValue("approvedAppSnapshot").jsonPrimitive.content)
    }

    @Test
    fun preDispatchRefusalAndUnknownEffectRemainDistinct() = runBlocking {
        var code="SNAPSHOT_STALE"
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->
            buildJsonObject{put("error",code)}
        }
        val call=click(AppBrowserToolCatalog.ACTION)
        assertTrue(tools.execute(call).effectNotApplied)
        code="ACTION_OUTCOME_UNKNOWN"
        val unknown=tools.execute(call)
        assertFalse(unknown.effectNotApplied)
        assertFalse(unknown.success)
        assertEquals(VerificationState.UNVERIFIED,unknown.verification)
    }

    @Test
    fun readIsAllowedButRiskyPcActionsNeedTheCurrentReviewedSnapshot() = runBlocking {
        val actions=mutableListOf<JsonObject>()
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,body->
            if(operation=="read")snapshot(risky=true)
            else {
                actions+=body
                if(actions.size==1)challenge() else snapshot(risky=true)
            }
        }
        val read=ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap()))
        assertFalse(tools.review(read).first)
        assertTrue(tools.execute(read).success)
        val action=click(PcBrowserToolCatalog.ACTION).let {call->
            call.copy(arguments=JsonObject(call.arguments+("approvalId" to JsonPrimitive("model-value"))))
        }
        assertTrue(tools.review(action).first)
        for(approved in listOf(null,"stale")){
            assertTrue(tools.execute(action,approved).effectNotApplied)
            assertTrue(actions.isEmpty())
        }
        assertTrue(tools.execute(action,approvedSnapshot="snapshot").success)
        assertEquals(2,actions.size)
        assertNull(actions.first()["approvalId"])
        assertEquals("a".repeat(32),actions.last().getValue("approvalId").jsonPrimitive.content)
    }

    @Test
    fun unexpectedWorkerChallengeDoesNotGrantConsentOrTriggerASecondDispatch() = runBlocking {
        var attempts=0
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,_->
            if(operation=="read")snapshot(risky=false) else {attempts++;challenge()}
        }
        tools.execute(ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap())))
        val action=click(PcBrowserToolCatalog.ACTION)
        assertFalse(tools.review(action).first)
        val denied=tools.execute(action)
        assertEquals(1,attempts)
        assertFalse(denied.success)
        assertTrue(denied.effectNotApplied)
    }

    @Test
    fun pcTypingOutsideSearchNeedsApprovalBeforeBackendDispatch() = runBlocking {
        var attempts=0
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,_->
            if(operation!="read")attempts++
            snapshot(risky=false,tag="input",label="Description")
        }
        tools.execute(ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap())))
        val action=ToolCall("type",PcBrowserToolCatalog.ACTION,buildJsonObject {
            put("action","type");put("snapshotId","snapshot");put("ref","e1");put("text","Synthetic text")
        })
        assertTrue(tools.review(action).first)
        assertTrue(tools.execute(action).effectNotApplied)
        assertEquals(0,attempts)
        assertTrue(tools.execute(action,approvedSnapshot="snapshot").success)
        assertEquals(1,attempts)
    }

    private fun challenge()=buildJsonObject {put("status","approval_required");put("approvalId","a".repeat(32))}

    private fun snapshot(risky:Boolean,tag:String="button",label:String="Details")=buildJsonObject {
        put("snapshotId","snapshot");put("url","https://fixture.example.test")
        putJsonArray("elements"){
            addJsonObject {put("ref","e1");put("tag",tag);put("label",label);put("needsApproval",risky)}
        }
    }

    private fun click(tool:String,spoof:Boolean=false)=ToolCall("call",tool,buildJsonObject {
        put("action","click");put("snapshotId","snapshot");put("ref","e1")
        if(spoof)put("approvedAppSnapshot","snapshot")
    })
}
