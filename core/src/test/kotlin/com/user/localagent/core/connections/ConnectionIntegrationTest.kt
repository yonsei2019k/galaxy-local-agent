package com.user.localagent.core.connections

import com.user.localagent.core.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionIntegrationTest {
    @Test fun allConnectionDefinitionsAreRegisteredInTheHostSchema() {
        assertTrue(ToolRegistry.definitions.keys.containsAll(ConnectionCatalog.tools.keys))
        assertTrue(ToolRegistry.schemaJson(listOf("search_gmail_messages")).contains("search_gmail_messages"))
    }
    @Test fun privateConnectionReadMustBeDeclaredByTheMeaningPlan() {
        val plan=SemanticRequestPlan(SemanticAction.TOOL_TASK,"메일 조회","Gmail","",SemanticSourceKind.NONE,setOf("search_gmail_messages"),emptyList(),false,"")
        try{SemanticRequestPlanning.toTaskGoal(plan);fail("private read was accepted as public work")}catch(_:IllegalArgumentException){}
        assertEquals(setOf("search_gmail_messages"),SemanticRequestPlanning.toTaskGoal(plan.copy(usesPrivateData=true)).requiredTools)
    }
    @Test fun authenticationAndEvidenceAreDistinctAvailabilityGates() {
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,ToolCapabilityCatalog.snapshot().first {it.id=="search_gmail_messages"}.availability)
        val authenticated=CapabilityEnvironment(grantedPermissions=setOf("connector:gmail"))
        assertEquals(CapabilityAvailability.UNVALIDATED,ToolCapabilityCatalog.snapshot(authenticated).first {it.id=="search_gmail_messages"}.availability)
        assertEquals(CapabilityAvailability.INSTALLED,ToolCapabilityCatalog.snapshot(authenticated.copy(validatedConnectors=setOf("gmail"))).first {it.id=="search_gmail_messages"}.availability)
    }
    @Test fun readAuthorizationDoesNotAdvertiseWriteAccessOrOfflineCloudTools() {
        val env=CapabilityEnvironment(grantedPermissions=setOf("connector:gmail"),validatedConnectors=setOf("gmail"))
        val catalog=ToolCapabilityCatalog.snapshot(env)
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,catalog.first {it.id=="create_gmail_draft"}.availability)
        assertFalse(catalog.first {it.id=="search_gmail_messages"}.offline)
    }
}
