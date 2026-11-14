package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolUsePolicyTest {
    @Test fun noteFilterAndAbsoluteTimeGuidanceReachBothModelCatalogs(){
        val compact=Json.parseToJsonElement(ToolUsePolicy.compactCatalog(setOf("search_notes","create_reminder"))).jsonArray
        val note=compact.single{it.jsonObject["tool"]?.jsonPrimitive?.content=="search_notes"}.jsonObject
        assertTrue(note.getValue("argumentDescriptions").jsonObject.getValue("scope").jsonPrimitive.content.contains("not an access scope"))
        assertTrue(note.getValue("argumentDescriptions").jsonObject.getValue("query").jsonPrimitive.content.contains("Literal substring"))
        val full=Json.parseToJsonElement(ToolRegistry.schemaJson(setOf("search_notes","create_reminder"))).jsonArray
        val reminder=full.single{it.jsonObject["name"]?.jsonPrimitive?.content=="create_reminder"}.jsonObject
        assertTrue(reminder.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("datetime").jsonObject.getValue("description").jsonPrimitive.content.contains("supplied absolute"))
    }

    @Test fun verificationToolsAreRegisteredReadOnlyTools(){
        ToolUsePolicy.verificationTools.forEach {
            assertTrue(it in ToolRegistry.definitions)
            assertTrue(ToolReadPolicy.isReadOnly(it))
        }
    }
    @Test fun answersGetVerificationToolsButClarifyAndCancelDoNot(){
        val offered=setOf("calculate","date_calculate","get_current_datetime","save_note")
        assertEquals(setOf("calculate","date_calculate","get_current_datetime"),ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,offered))
        assertEquals(emptySet<String>(),ToolUsePolicy.withVerification(emptySet(),SemanticAction.CLARIFY,offered))
        assertEquals(setOf("save_note"),ToolUsePolicy.withVerification(setOf("save_note"),SemanticAction.CANCEL,offered))
        assertFalse("save_note" in ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,offered))
        assertEquals(setOf("calculate"),ToolUsePolicy.withVerification(emptySet(),SemanticAction.ANSWER,setOf("calculate")))
    }
    @Test fun policyIsToolFirstAndNamesOnlyOfferedTools(){
        val prompt=AssistantPolicy.build(setOf("calculate","read_skill"),"2026-11-14T08:30:00+09:00")
        assertTrue(prompt.contains("도구를 먼저 사용"))
        assertTrue(prompt.contains("calculate"));assertFalse(prompt.contains("date_calculate"))
        assertTrue(prompt.contains("read_skill로 읽고"))
        assertFalse(prompt.contains("꼭 필요할 때만"))
        assertTrue(SemanticPlannerInstructions.build(setOf("calculate")).contains("Prefer tools"))
    }
    @Test fun gateOnlyConsidersDraftsWithNumbers(){
        assertTrue(ToolUsePolicy.worthVerifying("1,200 + 50 = 1,250원"))
        assertTrue(ToolUsePolicy.worthVerifying("12 × 3은 36"))
        assertTrue(ToolUsePolicy.worthVerifying("할인율 15% 적용"))
        assertTrue(ToolUsePolicy.worthVerifying("100 - 30 = 70"))
        assertFalse(ToolUsePolicy.worthVerifying("안녕하세요"))
        assertFalse(ToolUsePolicy.worthVerifying("1. 첫째\n2. 둘째"))
        assertFalse(ToolUsePolicy.worthVerifying("회의는 2026-11-14 15:30입니다"))
        assertTrue(ToolUsePolicy.gateSystem(setOf("calculate")).contains("review_next"))
    }
}
