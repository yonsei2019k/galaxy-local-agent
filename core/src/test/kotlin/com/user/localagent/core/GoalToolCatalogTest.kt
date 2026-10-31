package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalToolCatalogTest {
    private fun call(name:String,arguments:JsonObject)=ToolCall("synthetic",name,arguments)
    private fun version()=buildJsonObject {put("goalId","actual-goal");put("expectedUpdatedAt","1900000000000")}
    @Test fun realLongRevisionIsCopiedAsStringWithoutWeakeningGenericIntegerValidation() {
        GoalToolCatalog.validate(call(GoalToolCatalog.PAUSE,version()))
        assertEquals(1900000000000L,GoalToolCatalog.revision(JsonPrimitive("1900000000000")))
        for(value in listOf<JsonElement>(JsonPrimitive(1900000000000L),JsonPrimitive("01"),JsonPrimitive("0"),JsonPrimitive("+1"),JsonPrimitive("9223372036854775808"))) {
            assertTrue(runCatching {GoalToolCatalog.revision(value)}.isFailure)
        }
    }
    @Test fun modelCannotSupplyStatusEvidenceRunLinksScopeOrConfirmation() {
        val create=buildJsonObject {put("outcome","복잡한 요청의 결과");put("criteria",buildJsonArray {add("실제 결과를 확인한다")})}
        GoalToolCatalog.validate(call(GoalToolCatalog.CREATE,create))
        for(key in listOf("status","evidence","linkedRunIds","threadId","projectId","scope","userConfirmed")) {
            assertTrue(runCatching {GoalToolCatalog.validate(call(GoalToolCatalog.CREATE,JsonObject(create+mapOf(key to JsonPrimitive("forged")))))}.isFailure)
        }
        val forged=JsonObject(version()+mapOf("criteria" to buildJsonArray {add(buildJsonObject {put("id","a");put("text","기준");put("status","verified")})}))
        assertTrue(runCatching {GoalToolCatalog.validate(call(GoalToolCatalog.UPDATE,forged))}.isFailure)
    }
    @Test fun partialPageEditsHaveExplicitIdsAndSeparateRemoval() {
        val patch=JsonObject(version()+mapOf("criteria" to buildJsonArray {
            add(buildJsonObject {put("id","existing");put("text","원문 그대로 새 기준")})
            add(buildJsonObject {put("text","새롭게 추가한 기준")})
        },"removeCriterionIds" to buildJsonArray {add("another")}))
        GoalToolCatalog.validate(call(GoalToolCatalog.UPDATE,patch))
        assertTrue(GoalToolCatalog.isArrayArgument(GoalToolCatalog.UPDATE,"criteria"))
        val schema=GoalToolCatalog.argumentSchema(GoalToolCatalog.UPDATE,"criteria")!!
        assertFalse(schema.getValue("items").jsonObject.getValue("additionalProperties").jsonPrimitive.boolean)
    }
    @Test fun criteriaPageAndCreationBoundsAreEnforcedWithoutPhraseMatching() {
        GoalToolCatalog.validate(call(GoalToolCatalog.GET,JsonObject(version()+mapOf("criteriaOffset" to JsonPrimitive(64),"criteriaLimit" to JsonPrimitive(16)))))
        assertTrue(runCatching {GoalToolCatalog.validate(call(GoalToolCatalog.GET,buildJsonObject {put("criteriaOffset",64)}))}.isFailure)
        assertTrue(runCatching {GoalToolCatalog.validate(call(GoalToolCatalog.GET,buildJsonObject {put("criteriaLimit",17)}))}.isFailure)
        val empty=buildJsonObject {put("outcome","목표");put("criteria",buildJsonArray {})}
        assertTrue(runCatching {GoalToolCatalog.validate(call(GoalToolCatalog.CREATE,empty))}.isFailure)
        assertEquals(7,GoalToolCatalog.names.size)
        assertEquals(setOf(GoalToolCatalog.GET),GoalToolCatalog.contextReadNames)
        assertEquals(Risk.HIGH,GoalToolCatalog.definitions.single {it.name==GoalToolCatalog.CLEAR}.risk)
        assertTrue(GoalToolCatalog.definitions.filter {it.name in setOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)}.all {it.risk==Risk.LOW})
    }
}
