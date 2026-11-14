package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.ToolRegistry
import com.user.localagent.tools.AgentCapabilities
import com.user.localagent.workspace.ProjectWorkspace
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Current prerequisite metadata only. No source records, transport calls, model work or setting changes. */
@RunWith(AndroidJUnit4::class)
class AgentCapabilitiesDeviceValidation {
    @Test fun currentEnvironmentSnapshotRespectsAllowedToolsWithoutMutatingSettings()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentCapabilitiesValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val settings=PcComputeSettings.get(context)
        val revision=settings.authorizationRevision
        val pairedState=settings.state.value
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value)
        val workspace=ProjectWorkspace(context,"capability-qa-"+UUID.randomUUID())
        try {
        val selection=workspace.state.value
        val allowed=setOf("get_current_datetime","search_contacts")
        val report=Json.parseToJsonElement(AgentCapabilities(context).report(allowed,workspace)).jsonObject
        assertEquals("ok",report.getValue("status").jsonPrimitive.content)
        assertEquals(ToolRegistry.definitions.size,report.getValue("registeredToolCount").jsonPrimitive.int)
        assertEquals(allowed,report.getValue("tools").jsonArray.map {it.jsonObject.getValue("name").jsonPrimitive.content}.toSet())
        assertTrue(report.getValue("checksDoNotGrantPermission").jsonPrimitive.boolean)
        assertFalse(report.getValue("executionVerified").jsonPrimitive.boolean)
        assertFalse(report.getValue("networkRequested").jsonPrimitive.boolean)
        assertFalse(report.getValue("settingsChanged").jsonPrimitive.boolean)
        assertFalse(report.getValue("pairedPc").jsonObject.getValue("healthChecked").jsonPrimitive.boolean)
        assertFalse(report.getValue("models").jsonObject.getValue("freshHashVerificationPerformed").jsonPrimitive.boolean)
        assertTrue(report.getValue("permissionChecks").jsonObject.containsKey("android.permission.READ_CONTACTS"))
        assertEquals(revision,settings.authorizationRevision)
        assertEquals(pairedState.paired,settings.state.value.paired)
        assertEquals(pairedState.automaticText,settings.state.value.automaticText)
        assertEquals(pairedState.preferPcImages,settings.state.value.preferPcImages)
        assertEquals(pairedState.claudeText,settings.state.value.claudeText)
        // The observer reads the selected workspace flow; it never selects a new folder.
        assertEquals(selection.external,workspace.state.value.external)
        assertEquals(selection.writable,workspace.state.value.writable)
        assertTrue(Json.parseToJsonElement(AgentCapabilities(context).report(emptySet(),workspace)).jsonObject
            .getValue("tools").jsonArray.isEmpty())
        }finally{workspace.discard()}
    }
}
