package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudPhoneToolScopeTest {
    private val tools=ExternalAgentContract.NORMAL_PHONE_TOOLS

    @Test fun cloudIsOfferedEveryToolTheLocalModelIsOffered(){
        assertEquals(ToolRegistry.definitions.keys,tools)
        assertTrue(tools.size<=128) // agent_bridge.py / device_tools_mcp.py MAX_TOOLS
        assertFalse(tools.any{it.startsWith("pc_")})
        assertTrue(tools.all{it.matches(Regex("[a-z][a-z0-9_]{0,63}"))})
    }
    @Test fun fullRegistrationFitsTheWireAndIsExportedForPcSchemaValidation(){
        val capture=ExternalAgentCapture("10000000-0000-4000-8000-000000000001","thread","branch","project","binding",3,
            "a".repeat(64),tools,true,pcWorkspaceProfile="phone-only",requirePlan=true)
        val registration=capture.registration()
        val catalogBytes=registration.getValue("phoneTools").toString().toByteArray().size
        assertTrue("catalog $catalogBytes",catalogBytes<200_000) // agent_bridge.py catalog bound
        // pc-worker/tests reads this file to run the broker and MCP schema validators on the real catalog.
        File("build/cloud-phone-catalog.json").writeText(registration.getValue("phoneTools").toString())
    }

    @Test fun publicResearchOffersOnlyPublicReadsAndLocalCalculation() {
        val expected = setOf("search_web", "read_webpage", "search_shopping", "search_youtube_videos",
            "get_current_datetime", "calculate", "date_calculate", "search_publications", "verify_reference")
        val tools = AgentWorkModePolicy.filterTools(AgentWorkMode.RESEARCH, ExternalAgentContract.PUBLIC_RESEARCH_PHONE_TOOLS)
        assertEquals(expected, tools)
        val capture = ExternalAgentCapture("10000000-0000-4000-8000-000000000001", "thread", "branch", null, "binding", 3,
            "a".repeat(64), tools, true, pcWorkspaceProfile = "phone-only", requirePlan = true)
        assertTrue(capture.pcTools.isEmpty())
        assertEquals(expected, capture.registration().getValue("phoneTools").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }.toSet())
    }
}
