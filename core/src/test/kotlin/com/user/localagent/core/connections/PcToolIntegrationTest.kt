package com.user.localagent.core.connections

import com.user.localagent.core.*
import org.junit.Assert.*
import org.junit.Test

class PcToolIntegrationTest {
    @Test fun allFourFixedPcOperationsAreRegisteredWithoutArbitraryCodeOrPaths() {
        assertEquals(4,PcToolCatalog.names.size)
        PcToolCatalog.definitions.forEach {tool->
            assertNotNull(ToolRegistry.definitions[tool.name])
            assertTrue(tool.arguments.keys.intersect(setOf("code","command","script","path","url","uri")).isEmpty())
        }
    }
    @Test fun pcAuthorizationAndVerifiedConnectionAreDistinctGates() {
        val name="analyze_selected_data"
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,ToolCapabilityCatalog.snapshot().first {it.id==name}.availability)
        val authorized=CapabilityEnvironment(grantedPermissions=setOf("connector:pc-tools"))
        assertEquals(CapabilityAvailability.UNVALIDATED,ToolCapabilityCatalog.snapshot(authorized).first {it.id==name}.availability)
        val verified=authorized.copy(validatedConnectors=setOf("pc-tools"))
        assertEquals(CapabilityAvailability.INSTALLED,ToolCapabilityCatalog.snapshot(verified).first {it.id==name}.availability)
    }
    @Test fun remoteToolsAreNeverAdvertisedAsOffline() {
        val snapshot=ToolCapabilityCatalog.snapshot()
        PcToolCatalog.names.forEach {name->assertFalse(snapshot.first {it.id==name}.offline)}
    }
    @Test fun pcFileAndNotebookAccessCannotBeDeclaredPublic() {
        for(name in PcToolCatalog.names){
            val plan=SemanticRequestPlan(SemanticAction.TOOL_TASK,"선택 자료 분석","현재 대화","",SemanticSourceKind.NONE,setOf(name),emptyList(),false,"")
            try{SemanticRequestPlanning.toTaskGoal(plan);fail("private PC tool declared public")}catch(_:IllegalArgumentException){}
            assertTrue(name in SemanticRequestPlanning.toTaskGoal(plan.copy(usesPrivateData=true)).requiredTools)
        }
    }
}
