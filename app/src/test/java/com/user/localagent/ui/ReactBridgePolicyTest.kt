package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class ReactBridgePolicyTest {
    private fun wire(action:String,payload:String)="""{"version":1,"threadId":"thread","branchId":"branch","action":"$action","payload":$payload}"""
    @Test fun bridgeAcceptsOnlyTypedUiInputAndNeverNativeToolCalls(){
        assertNotNull(ReactBridgePolicy.parse(wire("send","""{"text":"안녕","submitToken":"one"}""")))
        assertNull(ReactBridgePolicy.parse(wire("execute_tool","""{"tool":"save_note"}""")))
        assertNull(ReactBridgePolicy.parse(wire("send","""{"text":"x","submitToken":"one","approved":true}""")))
        assertNull(ReactBridgePolicy.parse(wire("stop","{}")))
        assertNotNull(ReactBridgePolicy.parse(wire("stop","""{"runId":"run","actionEpoch":2}""")))
    }
    @Test fun malformedOversizeAndUnboundCommandsAreRejected(){
        assertNull(ReactBridgePolicy.parse("{}"))
        assertNull(ReactBridgePolicy.parse(wire("draft","""{"text":true}""")))
        assertNull(ReactBridgePolicy.parse(wire("draft","""{"text":"${"x".repeat(12001)}"}""")))
        assertNull(ReactBridgePolicy.parse(wire("stop","""{"runId":"r","actionEpoch":-1}""")))
    }
    @Test fun oldChatRouteIsClosedAndScopedMenuActionsRemainAvailable(){
        assertNull(ReactBridgePolicy.parse(wire("nativeDetails","{}")))
        assertNotNull(ReactBridgePolicy.parse(wire("chatMenu","{}")))
        assertNotNull(ReactBridgePolicy.parse(wire("messageMenu","""{"messageId":"m"}""")))
        assertNotNull(ReactBridgePolicy.parse(wire("removeAttachment","""{"id":"one-file"}""")))
        assertNull(ReactBridgePolicy.parse(wire("removeAttachment","""{"id":"one-file","approved":true}""")))
    }
    @Test fun steerAndCollaborationStayBoundedDataOnly(){
        assertNull(ReactBridgePolicy.parse(wire("steer","""{"runId":"r","actionEpoch":1,"text":" "}""")))
        assertNull(ReactBridgePolicy.parse(wire("steer","""{"runId":"r","actionEpoch":1,"text":"${"x".repeat(4001)}"}""")))
        assertNotNull(ReactBridgePolicy.parse(wire("copy","""{"messageId":"m"}""")))
        assertNull(ReactBridgePolicy.parse(wire("copy","""{"text":"arbitrary"}""")))
        val post="""{"clientMessageId":"00000000-0000-4000-8000-000000000001","text":"협업 요청","recipient":"codex","bindingEpoch":1}"""
        assertNotNull(ReactBridgePolicy.parse(wire("collabSend",post)))
        assertNull(ReactBridgePolicy.parse(wire("collabSend",post.replace("codex","executor"))))
        assertNull(ReactBridgePolicy.parse(wire("collabSend",post.dropLast(1)+",\"approved\":true}")))
    }
}
