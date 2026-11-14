package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompactPlanningSchemaTest {
    @Test fun compactGrammarPreservesScopedToolsAndHostSemanticRejection() {
        val offered=setOf("calculate","search_web")
        val compact=ModelDecisionContract.planningSchemaJson(offered)
        assertTrue(compact.length<ModelDecisionContract.schemaJson(offered).length/2)
        val properties=Json.parseToJsonElement(compact).jsonObject.getValue("properties").jsonObject
        val tools=properties.getValue("requiredTools").jsonObject.getValue("items").jsonObject.getValue("enum").jsonArray.map{it.jsonPrimitive.content}.toSet()
        assertEquals(offered,tools)
        assertTrue(runCatching{ModelDecisionContract.parse("""{"action":"PUBLIC_RESEARCH","goal":"조사","targetType":"GENERAL","requiredTools":["search_web"]}""",offered)}.isFailure)
        assertTrue(runCatching{ModelDecisionContract.parse("""{"action":"TOOL_TASK","goal":"계산","targetType":"GENERAL","requiredTools":["save_note"]}""",offered)}.isFailure)
        assertEquals(SemanticAction.TOOL_TASK,ModelDecisionContract.parse("""{"action":"TOOL_TASK","goal":"계산","targetType":"GENERAL","requiredTools":["calculate"]}""",offered).action)
    }
}
