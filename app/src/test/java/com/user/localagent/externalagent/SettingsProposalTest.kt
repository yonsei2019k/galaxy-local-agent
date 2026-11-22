package com.user.localagent.externalagent

import com.user.localagent.ui.settingsCloudModel
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SettingsProposalTest {
    private val id = UUID.randomUUID().toString()
    private val digest = "a".repeat(64)

    private fun capture(binding: String = "settings:$id", tools: Set<String> = setOf(SettingsProposal.TOOL)) =
        ExternalAgentCapture(id, id, id, null, binding, 1, digest, tools, true,
            pcWorkspaceProfile = "phone-only", requirePlan = true)

    @Test fun proposalToolIsRestrictedToTheSettingsSession() {
        assertFalse(SettingsProposal.TOOL in ExternalAgentContract.NORMAL_PHONE_TOOLS)
        val granted = capture()
        assertTrue(granted.pcTools.isEmpty())
        val catalog = granted.registration().getValue("phoneTools").jsonArray
        assertEquals(SettingsProposal.TOOL, catalog.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { capture(binding = "normal") }
        assertThrows(IllegalArgumentException::class.java) { capture(tools = setOf(SettingsProposal.TOOL, "save_note")) }
    }

    @Test fun settingsUseTheSelectedProviderAndPreserveLocalAuthority() {
        val codex = ExternalAgentSettings.MODEL_ID
        val claude = ExternalAgentSettings.CLAUDE_MODEL_ID
        assertEquals(codex, settingsCloudModel(codex, true))
        assertEquals(claude, settingsCloudModel(claude, false))
        assertEquals(claude, settingsCloudModel("auto", true))
        assertEquals(codex, settingsCloudModel("auto", false))
        assertNull(settingsCloudModel("private-local", true))
    }

    @Test fun changeRequestsRequireAnExactAllowedValueAndBinding() {
        val granted = capture()
        fun call(arguments: String, binding: String = granted.projectBinding) = ExternalAgentProposal(
            UUID.randomUUID().toString(), 1, SettingsProposal.TOOL, Json.parseToJsonElement(arguments).jsonObject,
            digest, binding, 1,
        ).checkedCall(granted)
        assertEquals("en", SettingsProposal.parse(call("""{"setting":"language","value":"en"}""").arguments).value)
        assertEquals(SettingsProposal("check","updates"), SettingsProposal.parse(call("""{"setting":"check","value":"updates"}""").arguments))
        assertEquals(SettingsProposal("handoff","chat"), SettingsProposal.parse(call("""{"setting":"handoff","value":"chat"}""").arguments))
        listOf(
            """{"setting":"check","value":"install"}""",
            """{"setting":"handoff","value":"https://example.com"}""",
            """{"setting":"language","value":"pc"}""",
            """{"setting":"image","value":true}""",
            """{"setting":"account","value":"open"}""",
            """{"setting":"image","value":"pc","confirmed":true}""",
        ).forEach { assertThrows(IllegalArgumentException::class.java) { call(it) } }
        assertThrows(IllegalArgumentException::class.java) {
            call("""{"setting":"language","value":"en"}""", "another-session")
        }
    }
}
