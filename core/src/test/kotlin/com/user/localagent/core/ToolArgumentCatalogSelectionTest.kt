package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentCatalogSelectionTest {
    private val notes=setOf("search_notes","read_note","patch_note")
    @Test fun selectingAnActualMutationKeepsTheNextReadInsideTheDirectToolProtocol(){
        val patch=ToolCall("patch","patch_note",buildJsonObject{
            put("id","note-a");put("scope","team");put("expectedUpdatedAt","1");put("oldText","old");put("newText","new")
        })
        val goal=DirectSelectedActionGoal.select(DirectSelectedActionGoal.initial("수정 후 같은 메모 재조회",18,emptyList()),patch,notes)
        assertEquals(setOf("patch_note","read_note"),goal.requiredTools)
        val details=ToolArgumentCatalogSelection.forProtocol(true,goal.requiredTools,notes)
        assertTrue(DirectNextActionProtocol.eligible(true,true,notes,details))
        val after=DirectNextActionProtocol.parse("""{"type":"tool_call","tool":"read_note","arguments":{"id":"note-a","scope":"team","expectedUpdatedAt":"2"}}""",details)
        assertTrue(after is DirectNextActionProtocol.Decision.Tool)
        assertEquals("2",(after as DirectNextActionProtocol.Decision.Tool).call.arguments.getValue("expectedUpdatedAt").jsonPrimitive.content)
    }
    @Test fun protocolCoveragePreservesSemanticAndOverBudgetSelections(){
        val chosen=setOf("read_note","patch_note")
        assertEquals(chosen,ToolArgumentCatalogSelection.forProtocol(false,chosen,notes))
        assertEquals(chosen,ToolArgumentCatalogSelection.forProtocol(true,chosen,notes,preferFull=false))
        assertEquals(chosen,ToolArgumentCatalogSelection.forProtocol(true,chosen,notes,0))
        assertEquals(chosen,ToolArgumentCatalogSelection.forProtocol(true,chosen,ToolRegistry.definitions.keys,Int.MAX_VALUE))
        assertTrue(runCatching{ToolArgumentCatalogSelection.forProtocol(true,notes,setOf("read_note"))}.isFailure)
    }
    @Test fun directSmallOfferedSetIncludesActualArgumentAlternatives() {
        val selected=ToolArgumentCatalogSelection.select(true,emptySet(),notes)
        val catalog=Json.parseToJsonElement(ToolUsePolicy.compactCatalog(selected)).jsonArray
            .associate {it.jsonObject.getValue("tool").jsonPrimitive.content to it.jsonObject.getValue("fields").jsonObject}
        assertEquals(notes,catalog.keys)
        assertEquals("string required",catalog.getValue("read_note").getValue("id").jsonPrimitive.content)
        assertEquals("string optional",catalog.getValue("patch_note").getValue("oldText").jsonPrimitive.content)
        assertEquals("string optional",catalog.getValue("patch_note").getValue("newText").jsonPrimitive.content)
        assertEquals("string optional",catalog.getValue("patch_note").getValue("oldTextQuoteId").jsonPrimitive.content)
        assertEquals("string optional",catalog.getValue("patch_note").getValue("newTextQuoteId").jsonPrimitive.content)
        assertEquals("string required",catalog.getValue("patch_note").getValue("scope").jsonPrimitive.content)
    }
    @Test fun originalSemanticPathKeepsEmptySelection() {
        assertTrue(ToolArgumentCatalogSelection.select(false,emptySet(),notes).isEmpty())
    }
    @Test fun existingGuidanceSelectionIsPreservedEvenWithoutFallbackBudget() {
        assertEquals(setOf("read_note"),ToolArgumentCatalogSelection.select(true,setOf("read_note"),notes,0))
    }
    @Test fun noAllowedToolsCannotProduceDefinitions() {
        assertTrue(ToolArgumentCatalogSelection.select(true,emptySet(),emptySet()).isEmpty())
    }
    @Test fun realSerializedCatalogUsesExactUtf16BudgetBoundary() {
        val allowed=setOf("read_note")
        val size=ToolUsePolicy.compactCatalog(allowed).length
        assertTrue(size in 1 until ToolArgumentCatalogSelection.MAX_CATALOG_UTF16)
        assertEquals(allowed,ToolArgumentCatalogSelection.select(true,emptySet(),allowed,size+1))
        assertTrue(ToolArgumentCatalogSelection.select(true,emptySet(),allowed,size).isEmpty())
        assertTrue(ToolArgumentCatalogSelection.select(true,emptySet(),allowed,size-1).isEmpty())
    }
    @Test fun largeRegistryCannotBeDumpedByPassingUnlimitedBudget() {
        assertTrue(ToolRegistry.definitions.size>ToolArgumentCatalogSelection.MAX_FALLBACK_TOOLS)
        assertTrue(ToolArgumentCatalogSelection.select(true,emptySet(),ToolRegistry.definitions.keys,Int.MAX_VALUE).isEmpty())
    }
    @Test fun noUnknownToolOrOutOfScopeSelectionCanBeAdded() {
        assertTrue(ToolArgumentCatalogSelection.select(true,emptySet(),setOf("not_a_registered_tool")).isEmpty())
        try {
            ToolArgumentCatalogSelection.select(true,setOf("patch_note"),setOf("read_note"))
            fail("Out-of-scope selection returned")
        }catch(_:IllegalArgumentException){}
    }
    @Test fun resultSnapshotCannotFollowLaterCallerExpansion() {
        val allowed=mutableSetOf("read_note")
        val result=ToolArgumentCatalogSelection.select(true,emptySet(),allowed)
        allowed+="patch_note"
        assertEquals(setOf("read_note"),result)
        try {(result as MutableSet<String>).add("patch_note");fail("Mutable result")}
        catch(_:UnsupportedOperationException){}
    }
}
