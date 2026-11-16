package com.user.localagent.externalagent

import com.user.localagent.core.*
import org.junit.Assert.*
import org.junit.Test

class CloudPhoneToolScopeTest {
    private val tools=ExternalAgentContract.NORMAL_PHONE_TOOLS

    @Test fun everyCloudPhoneToolIsRegisteredAndFitsTheBrokerBounds(){
        assertTrue(tools.size<=32) // agent_bridge.py TOOL_CATALOG_BOUND
        tools.forEach{assertTrue(it,it in ToolRegistry.definitions)}
        assertFalse(tools.any{it.startsWith("pc_")})
        // agent_bridge.py bounds the registered catalog JSON at 65,536 bytes.
        assertTrue(ToolRegistry.schemaJson(tools.sorted()).toByteArray().size<60_000)
    }
    @Test fun coversTheOwnerRequestedCategoriesWithoutDirectSendOrDelete(){
        listOf("search_contacts","list_calendar_events","get_sms_summary","prepare_sms","search_web","read_screen","screen_action").forEach{assertTrue(it,it in tools)}
        listOf("send_gmail_message","reply_gmail_message","reply_messenger_notification","delete_note","delete_reminder","delete_memory","patch_note").forEach{assertFalse(it,it in tools)}
    }
}
