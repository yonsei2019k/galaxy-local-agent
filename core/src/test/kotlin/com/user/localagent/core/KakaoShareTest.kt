package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KakaoShareTest {
    private val call = ToolCall("test", KakaoShare.TOOL, buildJsonObject { put("message","합성 공유 문구") })

    @Test fun noRecipientOrSendCanBeBoundToDraft() {
        assertEquals(call,ToolRegistry.validate(call))
        for (key in listOf("recipient", "uuid", "send", "uri","packageName")) {
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry.validate(call.copy(arguments=JsonObject(call.arguments + (key to JsonPrimitive("fake")))))
            }
        }
        assertTrue(CapabilityPolicy.requiresConfirmation(call, "builtin", "hash", emptyList()))
        assertFalse(CapabilityPolicy.canPreapprove(KakaoShare.TOOL))
    }
    @Test fun draftHandoffPausesWithoutDeliveryOrLeakingDraftContents() {
        val result = ToolResult(call.id,true,"share",VerificationState.PREPARED)
        assertTrue(PreparedHandoff.pendingEffect(call.name,result.verification))
        val report = ActionCompletionReport.render(setOf(call.name),listOf(ObservedToolOutcome(call,result)))
        assertTrue(report.contains(KakaoShare.HANDOFF))
        assertFalse(report.contains("합성 공유 문구"))
    }
    @Test fun capabilityReflectsInstalledShareHandler() {
        fun state(handlers:Set<String>) = ToolCapabilityCatalog.snapshot(CapabilityEnvironment(installedHandlers=handlers))
            .single { it.id==KakaoShare.TOOL }.availability
        assertEquals(CapabilityAvailability.UNVALIDATED,state(emptySet()))
        assertEquals(CapabilityAvailability.INSTALLED,state(setOf(KakaoShare.HANDLER)))
    }
}
