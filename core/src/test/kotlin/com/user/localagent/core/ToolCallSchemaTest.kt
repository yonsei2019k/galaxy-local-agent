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
