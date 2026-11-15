package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteToolRegistryIntegrationTest {
    private fun call(tool:String="read_note",extra:JsonObject=buildJsonObject{})=ToolCall("call-demo",tool,buildJsonObject {
        put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");extra.forEach{(k,v)->put(k,v)}
    })
    private fun rejected(block:()->Unit){try{block();fail()}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun registryActuallyParsesNewToolsAndRejectsUnknownOrMalformedArguments(){
        assertEquals(call(),ToolRegistry.validate(call()))
        val patch=call("patch_note",buildJsonObject{put("oldText","  ");put("newText","")});assertEquals(patch,ToolRegistry.validate(patch))
        rejected{ToolRegistry.validate(call(extra=buildJsonObject{put("limit",901)}))}
        rejected{ToolRegistry.validate(call(extra=buildJsonObject{put("offset","1")}))}
        rejected{ToolRegistry.validate(call(extra=buildJsonObject{put("extra","authority")}))}
        rejected{ToolRegistry.validate(call("patch_note",buildJsonObject{put("oldText","");put("newText","new")}))}
        rejected{ToolRegistry.validate(call(extra=buildJsonObject{put("scope",InternalNoteCollections.protected.first())}))}
    }
    @Test fun schemaMatchesRuntimeWhitespaceEmptyReplacementAndPageLimits(){
        val definitions=Json.parseToJsonElement(ToolRegistry.schemaJson(NoteReadPatchToolCatalog.names)).jsonArray
        val read=definitions.single{it.jsonObject["name"]?.jsonPrimitive?.content=="read_note"}.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(900,read["limit"]!!.jsonObject["maximum"]!!.jsonPrimitive.int);assertEquals(2,read["limit"]!!.jsonObject["minimum"]!!.jsonPrimitive.int)
        val patch=definitions.single{it.jsonObject["name"]?.jsonPrimitive?.content=="patch_note"}.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(0,patch["newText"]!!.jsonObject["minLength"]!!.jsonPrimitive.int)
        assertEquals(1,patch["oldText"]!!.jsonObject["minLength"]!!.jsonPrimitive.int)
    }
    @Test fun currentModeAndExactConfirmationContractsRemainEnforced(){
        assertTrue(ToolReadPolicy.isReadOnly("read_note"));assertFalse(ToolReadPolicy.isReadOnly("patch_note"))
        for(mode in listOf(AgentWorkMode.PLAN,AgentWorkMode.REVIEW,AgentWorkMode.GOAL)){
            assertTrue(AgentWorkModePolicy.allows(mode,"read_note"));assertFalse(AgentWorkModePolicy.allows(mode,"patch_note"))
        }
        assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.RESEARCH,"read_note"))
        assertTrue(AgentWorkModePolicy.allows(AgentWorkMode.ACT,"patch_note"))
        assertFalse(CapabilityPolicy.canPreapprove("patch_note"))
        assertTrue(CapabilityPolicy.requiresConfirmation(call("patch_note",buildJsonObject{put("oldText","old");put("newText","new")}),"builtin","hash-demo",
            listOf(CapabilityGrant("builtin","hash-demo","patch_note","team"),CapabilityGrant("builtin","hash-demo","update_note","team"))))
    }
    @Test fun privateReadMetadataAndExistingToolsAreStillAccurate(){
        assertTrue(SemanticRequestPlanning.isPrivateRead("read_note"));assertTrue(SemanticRequestPlanning.isPrivateRead("patch_note"))
        assertEquals("notes",IntentPlanning.domainForTool("patch_note"));assertEquals("notes",IntentPlanning.domainForTool("read_note"))
        val before=setOf("update_note","save_note","search_notes","delete_note")
        assertTrue(ToolRegistry.definitions.keys.containsAll(before))
        rejected{ToolRegistry.validate(ToolCall("old-demo","update_note",buildJsonObject{put("id","note-demo");put("content","")}))}
    }
    @Test fun wholeNoteReadIsNotInferredFromPartialCompletionReport(){
        val c=call();val result=ToolResult(c.id,true,buildJsonObject{put("status","ok");put("partial",true);put("completeBody",false);put("hasMore",true);put("text","fragment")}.toString(),VerificationState.VERIFIED,"host")
        val text=ActionCompletionReport.render(setOf("read_note"),listOf(ObservedToolOutcome(c,result)))
        assertTrue(text.contains("일부 조회"));assertTrue(text.contains("전체 요청의 완료는 확인되지 않았습니다."))
    }
}
