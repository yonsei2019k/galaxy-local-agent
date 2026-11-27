package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudPhoneToolScopeTest {
    private val tools=ExternalAgentContract.NORMAL_PHONE_TOOLS

    @Test fun cloudPreservesToolCoverageWithinOlderWorkerLimits(){
        // The on-device sub-agent reuses the local engine and is never offered to cloud runs.
        assertEquals(ToolRegistry.definitions.keys-LocalSubagentToolCatalog.names-ExternalAgentContract.CLOUD_HOME_ALIASES-WebsiteExecutionPolicy.phoneWebTools,tools)
        assertFalse(LocalSubagentToolCatalog.RUN_SUBAGENT in tools)
        val outlook = setOf("list_outlook_inbox", "list_outlook_calendar")
        assertTrue(tools.containsAll(setOf(PcBrowserToolCatalog.OPEN, "prepare_kakao_share", "list_naver_mail_inbox", "list_x_bookmarks")))
        val ownerOnly = setOf(GoalToolCatalog.CREATE, GoalToolCatalog.START, GoalToolCatalog.RESUME)
        val progress = GoalProgressToolCatalog.names + GoalWaitToolCatalog.WAIT
        assertFalse(GoalToolCatalog.CLEAR in tools - GoalToolCatalog.unavailableForRun(true))
        assertTrue(GoalToolCatalog.CLEAR in tools - GoalToolCatalog.unavailableForRun(false))
        for (segment in listOf(false, true)) {
            val eligible = tools - GoalToolCatalog.unavailableForRun(segment)
            assertTrue(eligible.containsAll(outlook))
            assertEquals(if (segment) emptySet() else ownerOnly, eligible.intersect(ownerOnly))
            assertEquals(if (segment) progress else emptySet(), eligible.intersect(progress))
            for (mode in AgentWorkMode.entries) {
                val offered = AgentWorkModePolicy.filterTools(mode, eligible)
                // Older workers count two optional PC reads against the same limit.
                assertTrue("$mode segment=$segment size=${offered.size}", offered.size + 2 <= ExternalAgentContract.MAX_PHONE_TOOLS)
            }
        }
        assertFalse(tools.any{it.startsWith("pc_")})
        assertTrue(tools.all{it.matches(Regex("[a-z][a-z0-9_]{0,63}"))})
    }
    @Test fun fullRegistrationFitsTheWireAndIsExportedForPcSchemaValidation(){
        val goalTools = AgentWorkModePolicy.filterTools(AgentWorkMode.ACT, tools - GoalToolCatalog.unavailableForRun(true))
        val capture=ExternalAgentCapture("10000000-0000-4000-8000-000000000001","thread","branch","project","binding",3,
            "a".repeat(64),goalTools,true,pcWorkspaceProfile="phone-only",requirePlan=true)
        val registration=capture.registration()
        val catalogBytes=registration.getValue("phoneTools").toString().toByteArray().size
        assertTrue("catalog $catalogBytes",catalogBytes<200_000) // agent_bridge.py catalog bound
        // pc-worker/tests reads this file to run the broker and MCP schema validators on the real catalog.
        val catalog=File(File("build").apply {mkdirs()},"cloud-phone-catalog.json")
        catalog.writeText(registration.getValue("phoneTools").toString())
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
