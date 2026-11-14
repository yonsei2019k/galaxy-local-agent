package com.user.localagent.tools

import com.user.localagent.core.*
import com.user.localagent.workspace.ProjectSelection
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentCapabilitiesTest {
    private fun report(allowed:Set<String>,environment:CapabilityEnvironment=CapabilityEnvironment(),
        checks:Map<String,CapabilityCheck> = emptyMap(),workspace:ProjectSelection?=null,
        paired:Boolean?=null)=Json.parseToJsonElement(AgentCapabilityReport.render(
            allowed,environment,checks,workspace,paired,true,true,3)).jsonObject
    private fun tools(root:JsonObject)=root.getValue("tools").jsonArray.map {it.jsonObject}

    @Test fun requestFilterNeverEchoesUnknownNamesOrGrantsOtherRegisteredTools(){
        val root=report(setOf("get_current_datetime","search_contacts","https://private.example/secret"))
        assertEquals(2,root.getValue("allowedToolCount").jsonPrimitive.int)
        assertEquals(setOf("get_current_datetime","search_contacts"),tools(root).map {it.getValue("name").jsonPrimitive.content}.toSet())
        assertEquals(ToolRegistry.definitions.size,root.getValue("registeredToolCount").jsonPrimitive.int)
        assertFalse(root.toString().contains("private.example"))
        assertTrue(root.getValue("checksDoNotGrantPermission").jsonPrimitive.boolean)
        assertFalse(root.getValue("executionVerified").jsonPrimitive.boolean)
        assertEquals("REQUIRES_PERMISSION",tools(root).first {it["name"]?.jsonPrimitive?.content=="search_contacts"}["availability"]?.jsonPrimitive?.content)
    }

    @Test fun actualGrantChangesPrerequisiteWhileUnknownPermissionCannotBecomeInstalled(){
        val permission="android.permission.READ_CONTACTS"
        val grant=CapabilityEnvironment(grantedPermissions=setOf(permission))
        val known=tools(report(setOf("search_contacts"),grant,mapOf(permission to CapabilityCheck.GRANTED))).single()
        assertEquals("INSTALLED",known.getValue("availability").jsonPrimitive.content)
        assertFalse(known.getValue("executionVerified").jsonPrimitive.boolean)
        val unknown=tools(report(setOf("search_contacts"),grant,mapOf(permission to CapabilityCheck.UNCONFIRMED))).single()
        assertEquals("UNVALIDATED",unknown.getValue("availability").jsonPrimitive.content)
    }

    @Test fun pairedConfigurationAndCachedModelHistoryNeverBecomeConnectivityOrRuntimeProof(){
        val environment=CapabilityEnvironment(grantedPermissions=setOf("connector:gmail:write","connector:gmail","connector:pc-tools"),
            installedModels=setOf("image","text"),validatedConnectors=setOf("gmail","pc-tools"))
        val root=report(setOf("create_gmail_draft","analyze_selected_data","generate_image"),environment,paired=true)
        assertTrue(tools(root).all {it.getValue("availability").jsonPrimitive.content=="UNVALIDATED"})
        val pc=root.getValue("pairedPc").jsonObject
        assertTrue(pc.getValue("configurationRegistered").jsonPrimitive.boolean)
        assertFalse(pc.getValue("reachabilityVerified").jsonPrimitive.boolean)
        assertFalse(pc.getValue("healthChecked").jsonPrimitive.boolean)
        val models=root.getValue("models").jsonObject
        assertEquals(3,models.getValue("cachedVerifiedRecordCount").jsonPrimitive.int)
        assertFalse(models.getValue("runtimeReadyVerified").jsonPrimitive.boolean)
        assertEquals("UNCONFIRMED",models.getValue("verification").jsonPrimitive.content)
    }

    @Test fun untrustedWorkspaceNameAndNonPublicCheckKeysCannotLeakAddressesOrPaths(){
        val forbidden="https://private.example/credential"
        val root=report(emptySet(),checks=mapOf(forbidden to CapabilityCheck.GRANTED),
            workspace=ProjectSelection(forbidden,true,false))
        assertFalse(root.toString().contains(forbidden))
        assertTrue(root.getValue("permissionChecks").jsonObject.isEmpty())
        assertEquals("선택한 프로젝트",root.getValue("workspace").jsonObject.getValue("name").jsonPrimitive.content)
        val normal=report(emptySet(),workspace=ProjectSelection("팀 자료",true,false))
        assertEquals("팀 자료",normal.getValue("workspace").jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(normal.getValue("workspace").jsonObject.getValue("writable").jsonPrimitive.boolean)
    }

    @Test fun completeCatalogIsBoundedAndOmissionsAreExplicit(){
        val root=report(ToolRegistry.definitions.keys)
        val count=root.getValue("reportedToolCount").jsonPrimitive.int
        assertTrue(count<=AgentCapabilityReport.MAX_TOOLS)
        assertTrue(root.toString().length<=AgentCapabilityReport.MAX_CHARS)
        assertEquals(ToolRegistry.definitions.size-count,root.getValue("omittedToolCount").jsonPrimitive.int)
        assertEquals(count<ToolRegistry.definitions.size,root.getValue("partial").jsonPrimitive.boolean)
        assertTrue(tools(root).all {it.getValue("allowedInCurrentRequest").jsonPrimitive.boolean})
        val empty=report(emptySet())
        assertTrue(tools(empty).isEmpty())
        assertEquals(0,empty.getValue("allowedToolCount").jsonPrimitive.int)
    }
}
