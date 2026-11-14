package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InternalBetaToolBoundaryTest {
    private val reads=ProjectStorageToolCatalog.names+setOf("list_reminders","get_agent_capabilities")
    @Test fun addedReadToolsRemainInCapturedReviewScopeButNeverPublicResearch(){
        reads.forEach{name->
            assertTrue(ToolRegistry.definitions.getValue(name).description.isNotBlank())
            assertTrue(ToolReadPolicy.isReadOnly(name))
            assertTrue(AgentWorkModePolicy.allows(AgentWorkMode.REVIEW,name))
            assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.RESEARCH,name))
        }
    }
    @Test fun diagnosticsCannotAcquireAuthorityAndReminderFiltersAreTypedAndBounded(){
        ToolRegistry.parseAndValidate("""{"name":"get_agent_capabilities","arguments":{}}""")
        ToolRegistry.parseAndValidate("""{"name":"list_reminders","arguments":{"limit":50,"includeCompleted":true}}""")
        for(raw in listOf(
            """{"name":"get_agent_capabilities","arguments":{"grant":true}}""",
            """{"name":"list_reminders","arguments":{"limit":51}}""",
            """{"name":"list_reminders","arguments":{"includeCompleted":"true"}}""",
            """{"name":"inspect_project_storage","arguments":{"path":"../outside"}}"""
        ))assertTrue(runCatching{ToolRegistry.parseAndValidate(raw)}.isFailure)
    }
}
