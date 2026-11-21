package com.user.localagent.integrations

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.AppBrowserToolCatalog
import com.user.localagent.core.PcBrowserToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
    fun pcBackendUsesItsExistingApprovalHandshake() = runBlocking {
        val calls=mutableListOf<JsonObject>()
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){_,body->
            calls+=body
            if(calls.size==1)buildJsonObject{put("status","approval_required");put("approvalId","a".repeat(32))}
            else buildJsonObject{put("error","SNAPSHOT_STALE")}
        }
        tools.execute(click(PcBrowserToolCatalog.ACTION))
        assertEquals(2,calls.size)
        assertEquals("a".repeat(32),calls.last().getValue("approvalId").jsonPrimitive.content)
        assertNull(calls.last()["approvedAppSnapshot"])
    }

    private fun click(tool:String,spoof:Boolean=false)=ToolCall("call",tool,buildJsonObject {
        put("action","click");put("snapshotId","snapshot");put("ref","e1")
        if(spoof)put("approvedAppSnapshot","snapshot")
    })
}
