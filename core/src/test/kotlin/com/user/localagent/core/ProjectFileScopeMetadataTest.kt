package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Relative-path metadata must not add root authority. */
class ProjectFileScopeMetadataTest {
    private val names=setOf("list_project_files","read_project_file","write_project_file")

    @Test fun relativePathGuidanceReachesCompactAndNativeWithoutAddingARootArgument(){
        val compact=Json.parseToJsonElement(ToolUsePolicy.compactCatalog(names)).jsonArray.associate{it.jsonObject.getValue("tool").jsonPrimitive.content to it.jsonObject}
        val native=Json.parseToJsonElement(ToolRegistry.schemaJson(names)).jsonArray.associate{it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject}
        for(name in names){
            val definition=ToolRegistry.definitions.getValue(name)
            val expected=definition.arguments.getValue("path").description
            assertTrue(expected.isNotBlank());assertTrue(expected.contains("bound phone project"))
            assertEquals(expected,compact.getValue(name).getValue("argumentDescriptions").jsonObject.getValue("path").jsonPrimitive.content)
            val parameters=native.getValue(name).getValue("parameters").jsonObject
            assertEquals(definition.arguments.keys,parameters.getValue("properties").jsonObject.keys)
            assertEquals(expected,parameters.getValue("properties").jsonObject.getValue("path").jsonObject.getValue("description").jsonPrimitive.content)
            assertFalse("root" in definition.arguments);assertFalse("uri" in definition.arguments)
            assertTrue(definition.description.length<=160)
            assertTrue(SemanticPlannerInstructions.build(names).contains(definition.description))
        }
    }

    @Test fun singleFilenameAndOmittedListingRootStayValidWithoutAbsolutePaths(){
        val call=ToolCall("synthetic","read_project_file",buildJsonObject{put("path","관찰.md")})
        assertEquals(listOf("관찰.md"),ProjectPaths.segments("관찰.md"))
        assertEquals(call,ToolRegistry.validate(call))
        ToolRegistry.validate(ToolCall("synthetic","list_project_files",buildJsonObject{}))
        ToolRegistry.validate(ToolCall("synthetic","list_project_files",buildJsonObject{put("path","")}))
        for(path in listOf("/관찰.md","../관찰.md","content://provider/file","C:\\notes.md")){
            assertTrue(path,runCatching{ToolRegistry.validate(call.copy(arguments=buildJsonObject{put("path",path)}))}.isFailure)
        }
        assertTrue(runCatching{ToolRegistry.validate(call.copy(arguments=buildJsonObject{put("path","관찰.md");put("root","/new-root")}))}.isFailure)
    }

    @Test fun requiredFieldsLengthsAndMutationRiskKeepTheExistingContract(){
        val list=ToolRegistry.definitions.getValue("list_project_files")
        val read=ToolRegistry.definitions.getValue("read_project_file")
        val write=ToolRegistry.definitions.getValue("write_project_file")
        assertFalse(list.arguments.getValue("path").required)
        assertTrue(read.arguments.getValue("path").required)
        assertTrue(write.arguments.getValue("path").required)
        assertTrue(write.arguments.getValue("content").required)
        assertFalse(write.arguments.getValue("expectedSha256").required)
        assertEquals(500,read.arguments.getValue("path").maxLength)
        assertEquals(12000,write.arguments.getValue("content").maxLength)
        assertEquals(Risk.LOW,read.risk);assertEquals(Risk.LOW,list.risk);assertEquals(Risk.MEDIUM,write.risk)
        // A path hint cannot bypass the host's same-run full read/SHA/approval or SAF checks.
        assertTrue(write.description.contains("approval"));assertTrue(write.description.contains("SHA-256"))
    }
}
