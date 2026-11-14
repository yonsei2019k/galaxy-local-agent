package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectStorageToolCatalogTest {
    private fun call(path:JsonElement?=null,extras:Map<String,JsonElement> =emptyMap())=ToolCall("qa","inspect_project_storage",JsonObject(extras+(path?.let {mapOf("path" to it)}?:emptyMap())))
    @Test fun rootAndSelectedRelativeDirectoryAreTheOnlyAuthority(){
        assertEquals("",ProjectStorageToolCatalog.path(call()))
        assertEquals("",ProjectStorageToolCatalog.path(call(JsonPrimitive(""))))
        assertEquals("사진/작업",ProjectStorageToolCatalog.path(call(JsonPrimitive("사진/작업"))))
        for(path in listOf("/absolute","../outside","a/../../outside","content://provider/tree","a\\b","a/./b","a\u0000b"))assertTrue(path,runCatching {ProjectStorageToolCatalog.validate(call(JsonPrimitive(path)))}.isFailure)
    }
    @Test fun unknownFieldsWrongTypesAndBudgetEscalationAreRejected(){
        assertTrue(runCatching {ProjectStorageToolCatalog.validate(call(JsonPrimitive(1)))}.isFailure)
        assertTrue(runCatching {ProjectStorageToolCatalog.validate(call(extras=mapOf("maxDepth" to JsonPrimitive(99))))}.isFailure)
        assertTrue(runCatching {ProjectStorageToolCatalog.validate(call().copy(name="delete_project_duplicates"))}.isFailure)
    }
    @Test fun declarationsRemainReadOnlyAndDiscloseActualLimits(){
        assertEquals(setOf("inspect_project_storage","find_project_duplicates"),ProjectStorageToolCatalog.names)
        ProjectStorageToolCatalog.definitions.forEach {definition->
            assertEquals(Risk.LOW,definition.risk);assertEquals(setOf("path"),definition.arguments.keys)
            assertFalse(definition.arguments.getValue("path").required);assertTrue(definition.description.contains("selected project"))
        }
        assertTrue(ProjectStorageToolCatalog.definitions.last().description.contains("never deletes"))
    }
    @Test fun registryIntegrationAcceptsBoundRootsAndKeepsBothToolsReadOnly(){
        for(name in ProjectStorageToolCatalog.names){
            ToolRegistry.validate(call().copy(name=name))
            ToolRegistry.validate(call(JsonPrimitive("")).copy(name=name))
            ToolRegistry.validate(call(JsonPrimitive("선택한 하위 폴더")).copy(name=name))
            assertTrue(ToolReadPolicy.isReadOnly(name))
            assertTrue(runCatching {ToolRegistry.validate(call(JsonPrimitive("../outside")).copy(name=name))}.isFailure)
        }
    }
}
