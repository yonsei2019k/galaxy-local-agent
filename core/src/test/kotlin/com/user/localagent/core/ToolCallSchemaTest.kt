package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallSchemaTest {
    private fun branches(vararg tools:String)=Json.parseToJsonElement(ToolCallSchema.forTools(tools.toSet())).jsonObject.getValue("oneOf").jsonArray.map {it.jsonObject}
    private fun property(branch:JsonObject,key:String)=branch.getValue("properties").jsonObject.getValue(key).jsonObject
    @Test fun onlyAllowedToolNamesHaveStrictIndependentEnvelopeBranches(){
        val branches=branches("save_note","create_document")
        assertEquals(2,branches.size)
        assertEquals(listOf("create_document","save_note"),branches.map {property(it,"tool").getValue("enum").jsonArray.single().jsonPrimitive.content})
        branches.forEach {branch->
            assertFalse(branch.getValue("additionalProperties").jsonPrimitive.boolean)
            assertEquals(setOf("type","tool","arguments"),branch.getValue("properties").jsonObject.keys)
            assertEquals(setOf("type","tool","arguments"),branch.getValue("required").jsonArray.map {it.jsonPrimitive.content}.toSet())
            assertEquals("tool_call",property(branch,"type").getValue("enum").jsonArray.single().jsonPrimitive.content)
        }
    }
    @Test fun requiredArgumentsAndBoundsComeFromActualRegistry(){
        val arguments=property(branches("create_document").single(),"arguments")
        assertEquals(setOf("format","title","content"),arguments.getValue("required").jsonArray.map {it.jsonPrimitive.content}.toSet())
        assertFalse(arguments.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(200,property(arguments,"title").getValue("maxLength").jsonPrimitive.int)
        assertEquals(12000,property(arguments,"content").getValue("maxLength").jsonPrimitive.int)
        assertEquals(setOf("docx","xlsx","pdf","txt","csv"),property(arguments,"format").getValue("enum").jsonArray.map {it.jsonPrimitive.content}.toSet())
    }
    @Test fun zeroArgumentToolsRequireAnEmptyObjectInsteadOfInventedInputs(){
        val arguments=property(branches("get_current_datetime").single(),"arguments")
        assertTrue(arguments.getValue("properties").jsonObject.isEmpty());assertTrue(arguments.getValue("required").jsonArray.isEmpty())
        assertFalse(arguments.getValue("additionalProperties").jsonPrimitive.boolean)
    }
    @Test fun optionalSearchScopeAcceptsOmittedExactEmptyAndKnownCollection(){
        val arguments=property(branches("search_notes").single(),"arguments")
        assertFalse("scope" in arguments.getValue("required").jsonArray.map{it.jsonPrimitive.content})
        assertEquals(0,property(arguments,"scope").getValue("minLength").jsonPrimitive.int)
        val unknownScope="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"synthetic note"}}"""
        assertFalse(ToolRegistry.parseAndValidate(unknownScope).arguments.containsKey("scope"))
        val emptyScope="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"synthetic note","scope":""}}"""
        assertEquals("",ToolRegistry.parseAndValidate(emptyScope).arguments.getValue("scope").jsonPrimitive.content)
        val knownScope="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"synthetic note","scope":"known collection"}}"""
        assertEquals("known collection",ToolRegistry.parseAndValidate(knownScope).arguments.getValue("scope").jsonPrimitive.content)
        // Nonempty values remain exact filters: no fabricated value is silently removed.
        val literalScope="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"synthetic note","scope":">"}}"""
        assertEquals(">",ToolRegistry.parseAndValidate(literalScope).arguments.getValue("scope").jsonPrimitive.content)
    }
    @Test fun emptySearchScopeDoesNotAcceptWhitespaceOrNonStringValues(){
        val invalid=listOf<JsonElement>(JsonPrimitive(" "),JsonPrimitive("\t"),JsonPrimitive("\n"),JsonNull,
            JsonPrimitive(0),JsonPrimitive(false),buildJsonArray{},buildJsonObject{})
        invalid.forEach {scope->
            val raw=buildJsonObject{put("type","tool_call");put("tool","search_notes");put("arguments",buildJsonObject{
                put("query","synthetic note");put("scope",scope)
            })}.toString()
            assertTrue("Rejected scope: $scope",runCatching{ToolRegistry.parseAndValidate(raw)}.isFailure)
        }
    }
    @Test fun emptySearchFilterDoesNotRelaxRequiredOrOtherOptionalScopeContracts(){
        for(tool in listOf("read_note","patch_note","save_note","create_reminder","update_note")){
            val arguments=property(branches(tool).single(),"arguments")
            assertEquals(1,property(arguments,"scope").getValue("minLength").jsonPrimitive.int)
            for(scope in listOf(""," ")){
                val args=buildJsonObject{
                    when(tool){
                        "read_note","patch_note"->{put("id","note-demo");put("expectedUpdatedAt","1")}
                        "save_note"->{put("content","body")}
                        "create_reminder"->{put("title","reminder");put("datetime","2026-11-15T12:00:00+09:00")}
                        "update_note"->{put("id","note-demo");put("content","body")}
                    }
                    if(tool=="patch_note"){put("oldText","old");put("newText","new")}
                    put("scope",scope)
                }
                assertTrue("Rejected $tool scope",runCatching{ToolRegistry.validate(ToolCall("scope-boundary",tool,args))}.isFailure)
            }
        }
        val emptyQuery="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"","scope":""}}"""
        assertTrue(runCatching{ToolRegistry.parseAndValidate(emptyQuery)}.isFailure)
    }
    @Test fun emptySearchFilterCannotGrantRequestCapabilityOrMutationPermission(){
        val raw="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"synthetic note","scope":""}}"""
        val call=ToolRegistry.parseAndValidate(raw)
        assertTrue(ToolReadPolicy.isReadOnly(call.name))
        assertEquals(call,ToolRegistry.validateForRequest(call,setOf("search_notes")))
        assertTrue(runCatching{ToolRegistry.validateForRequest(call,emptySet())}.isFailure)
        assertTrue(runCatching{ToolRegistry.validateForRequest(call,setOf("read_note"))}.isFailure)
        assertFalse(ToolReadPolicy.isReadOnly("patch_note"))
        assertFalse(CapabilityPolicy.canPreapprove("patch_note"))
        for(mode in listOf(AgentWorkMode.PLAN,AgentWorkMode.REVIEW,AgentWorkMode.GOAL)){
            assertFalse(AgentWorkModePolicy.allows(mode,"patch_note"))
        }
    }
    @Test fun outputSchemaDoesNotGrantApprovalOrAnArbitraryUriField(){
        val branch=branches("generate_image").single()
        assertFalse(branch.toString().contains("requiresConfirmation"))
        assertFalse(property(branch,"arguments").getValue("properties").jsonObject.containsKey("referenceUri"))
        assertTrue(runCatching {ToolRegistry.parseAndValidate("""{"type":"tool_call","tool":"generate_image","arguments":{"prompt":"fox","referenceUri":"content://forged/image"}}""")}.isFailure)
    }
    @Test fun alarmAndImageNumbersCannotDecodeOutsideHostBounds(){
        val alarm=property(branches("create_alarm").single(),"arguments")
        assertEquals(23,property(alarm,"hour").getValue("maximum").jsonPrimitive.int)
        assertEquals(59,property(alarm,"minute").getValue("maximum").jsonPrimitive.int)
        assertEquals(7,property(alarm,"days").getValue("maxItems").jsonPrimitive.int)
        val image=property(branches("generate_image").single(),"arguments")
        assertEquals(50,property(image,"steps").getValue("maximum").jsonPrimitive.int)
    }
    @Test fun unknownAndEmptyToolScopesCannotEnableStructuredActions(){
        assertTrue(runCatching {ToolCallSchema.forTools(emptySet())}.isFailure)
        assertTrue(runCatching {ToolCallSchema.forTools(setOf("send_email_now"))}.isFailure)
    }
    @Test fun schemaOrderIsDeterministicAcrossCallerSetOrder(){
        assertEquals(ToolCallSchema.forTools(linkedSetOf("save_note","get_current_datetime")),ToolCallSchema.forTools(linkedSetOf("get_current_datetime","save_note")))
    }
    @Test fun validDecodingShapeStillNeedsOwnerGoalAndHostResultVerification(){
        val raw="""{"type":"tool_call","tool":"get_current_datetime","arguments":{}}"""
        val call=ToolRegistry.parseAndValidate(raw)
        val unrelated=HarnessStep(call,StepEvidence(call.name,VerificationState.VERIFIED,"현재 시간 확인"))
        assertTrue(TaskHarness.decide(TaskHarness.inferGoal("메모 저장해줘"),listOf(unrelated),true) is HarnessDecision.Stop)
    }
}
