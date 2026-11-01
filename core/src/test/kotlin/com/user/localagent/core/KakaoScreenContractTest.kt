package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KakaoScreenContractTest {
    @Test fun adapterOffersOnlyBoundedReadWithoutHiddenActionArguments() {
        assertEquals(setOf("read_kakao_screen"),KakaoScreenToolCatalog.names)
        val definition=ToolRegistry.definitions.getValue("read_kakao_screen")
        assertEquals(Risk.LOW,definition.risk)
        assertTrue(ToolReadPolicy.isReadOnly(definition.name))
        assertTrue(definition.arguments.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry.validate(ToolCall("synthetic",definition.name,buildJsonObject{put("send",true)}))
        }
    }
    @Test fun consentAloneIsNotDeviceValidation() {
        fun state(grants:Set<String>)=ToolCapabilityCatalog.snapshot(CapabilityEnvironment(grantedPermissions=grants))
            .single{it.id=="read_kakao_screen"}.availability
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state(emptySet()))
        assertEquals(CapabilityAvailability.UNVALIDATED,state(setOf("kakao_screen_access")))
    }
}
