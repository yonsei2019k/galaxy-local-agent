package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficeCapabilityIntegrationTest {
    private fun envelope(name:String,args:JsonObject)=buildJsonObject {put("type","tool_call");put("tool",name);put("arguments",args)}.toString()
    private fun reject(raw:String) {
        try {ToolRegistry.parseAndValidate(raw);fail("invalid tool call executed")}
        catch(_:IllegalArgumentException) { /* validation required */ }
        catch(_:IllegalStateException) { /* strict action validator */ }
    }
    @Test fun intervalCallsPassFullRegistryAndNativeSchemaDescribesObjectsRatherThanAlarmDays() {
        val args=buildJsonObject {
            put("start","2026-10-31T22:00:00+09:00");put("end","2026-11-01T08:00:00+09:00")
            put("timeZone","Asia/Seoul");put("durationMinutes",90)
            put("busy",buildJsonArray {add(buildJsonObject {put("start","2026-11-01T01:00:00+09:00");put("end","2026-11-01T03:00:00+09:00")})})
        }
        val call=ToolRegistry.parseAndValidate(envelope("find_free_time",args))
        val result=Json.parseToJsonElement(OfficeToolEngine.execute(call)).jsonObject
        assertEquals(2,result.getValue("matchingWindowCount").jsonPrimitive.int)
        val schema=Json.parseToJsonElement(ToolRegistry.schemaJson(setOf("find_free_time"))).jsonArray.single().jsonObject
        val busy=schema.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("busy").jsonObject
        assertEquals("object",busy.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(false,busy.getValue("items").jsonObject.getValue("additionalProperties").jsonPrimitive.boolean)
        reject(envelope("find_free_time",JsonObject(args+("busy" to buildJsonArray {add(1)}))))
        reject(envelope("create_alarm",buildJsonObject {put("hour",8);put("minute",0);put("days",args.getValue("busy"))}))
    }

    @Test fun publicLookupsAndOfflineComputationsHaveCorrectModesAndNetworkCapabilities() {
        for(tool in OfficeToolCatalog.names) {
            for(mode in AgentWorkMode.entries)assertTrue("$mode $tool",AgentWorkModePolicy.allows(mode,tool))
            assertTrue(ToolCapabilityCatalog.snapshot().first {it.id==tool}.offline)
            assertTrue(ToolReadPolicy.isReadOnly(tool))
        }
        for(tool in EvidenceToolCatalog.names) {
            assertTrue(AgentWorkModePolicy.allows(AgentWorkMode.RESEARCH,tool))
            assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.PLAN,tool))
            assertFalse(ToolCapabilityCatalog.snapshot().first {it.id==tool}.offline)
            assertTrue(ToolReadPolicy.isReadOnly(tool))
        }
        assertFalse(ToolReadPolicy.isReadOnly("create_document"))
    }

    @Test fun computationalModelPlanDoesNotAcquirePrivateDataOrDeferSafeComputationAsAWrite() {
        val plan=ModelDecisionContract.parse("""{"action":"TOOL_TASK","goal":"일정을 만들지 말고 날짜 차이와 가능한 시간만 계산한다","targetType":"GENERAL","requiredTools":["date_calculate","find_free_time"]}""")
        assertFalse(plan.usesPrivateData)
        val intent=SemanticRequestPlanning.toIntentPlan(plan)
        assertTrue(intent.deferredTools.isEmpty())
        assertEquals(setOf("date_calculate","find_free_time"),intent.recommendedNextTools.toSet())
    }

    @Test fun scholarlyPublicPlanUsesAvailableSpecialistToolAndCannotMixPrivateReads() {
        val raw="""{"action":"PUBLIC_RESEARCH","goal":"근거와 서지정보를 확인한다","targetType":"GENERAL","publicQuery":"shift work sleep","requiredTools":["search_publications","verify_reference"]}"""
        val plan=ModelDecisionContract.parse(raw)
        assertEquals(setOf("search_publications","verify_reference"),plan.requiredTools)
        assertFalse(plan.usesPrivateData)
        try {ModelDecisionContract.parse(raw,setOf("search_web"));fail("disabled specialized tool accepted")}
        catch(_:ModelDecisionException) { /* no capability escalation */ }
        val mixed=raw.replace("\"verify_reference\"","\"search_notes\"")
        try {ModelDecisionContract.parse(mixed);fail("private/public mixing accepted")}
        catch(_:ModelDecisionException) { /* explicit separate private phase required */ }
    }

    @Test fun specialistLimitsAndExclusiveCitationInputsAreValidatedBeforeExecution() {
        reject(envelope("search_publications",buildJsonObject {put("query","sleep");put("limit",6)}))
        reject(envelope("verify_reference",buildJsonObject {put("doi","10.1000/x");put("citation","another title")}))
        reject(envelope("verify_reference",buildJsonObject {put("limit",3)}))
        ToolRegistry.parseAndValidate(envelope("search_publications",buildJsonObject {put("query","sleep");put("limit",5)}))
        val schema=ToolCallSchema.forTools(setOf("calculate","date_calculate","find_free_time","search_publications","verify_reference"))
        assertTrue(schema.length<=65536)
        val definitions=Json.parseToJsonElement(ToolRegistry.schemaJson(EvidenceToolCatalog.names)).jsonArray.associate {element->
            val entry=element.jsonObject
            entry.getValue("name").jsonPrimitive.content to entry.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.int
        }
        assertEquals(mapOf("search_publications" to 5,"verify_reference" to 3),definitions)
    }
}
