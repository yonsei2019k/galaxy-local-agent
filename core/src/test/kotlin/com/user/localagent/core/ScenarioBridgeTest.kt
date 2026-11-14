package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScenarioBridgeTest {
    private val raw="""{"action":"TOOL_TASK","goal":"메모 저장","targetType":"PERSONAL_RECORD","requiredTools":["save_note"]}"""
    @Test fun exportsActualPolicyAndCapabilityClosure() {
        val answer=ScenarioBridge.respond(buildJsonObject {put("op","plan");put("raw",raw);put("now","2026-11-14T10:00:00+09:00")})
        assertTrue(answer.getValue("allowed").jsonArray.any{it.jsonPrimitive.content=="save_note"})
        assertTrue(answer.getValue("system").jsonPrimitive.content.contains("save_note"))
    }
    @Test fun bridgeRejectsCallOutsideActualPlan() {
        assertTrue(runCatching {ScenarioBridge.respond(buildJsonObject {
            put("op","call");put("planRaw",raw);put("allowed",buildJsonArray{add("save_note")})
            put("raw","""{"type":"tool_call","tool":"prepare_sms","arguments":{"phoneNumber":"01000000000","message":"x"}}""")
        })}.isFailure)
    }
    @Test fun preparedNeverBecomesCompleted() {
        val plan="""{"action":"TOOL_TASK","goal":"문자 초안","targetType":"DEVICE","requiredTools":["prepare_sms"]}"""
        val answer=ScenarioBridge.respond(buildJsonObject {
            put("op","finish");put("planRaw",plan)
            put("steps",buildJsonArray {add(buildJsonObject {
                put("tool","prepare_sms");put("arguments",buildJsonObject{put("phoneNumber","01000000000");put("message","합성")});put("verification","PREPARED")
            })})
        })
        assertEquals("stop",answer.getValue("decision").jsonPrimitive.content)
        assertTrue(answer.getValue("prepared").jsonPrimitive.boolean)
    }
    @Test fun registeredToolsAllHaveDescriptions() {
        assertTrue(ToolRegistry.definitions.values.filter{it.description.isBlank()}.map{it.name}.toString(),
            ToolRegistry.definitions.values.all{it.description.isNotBlank()})
    }
    @Test fun plannerUsesOfferedDescriptionsRatherThanOnlyNames() {
        val prompt=SemanticPlannerInstructions.build(setOf("prepare_sms","get_current_datetime"))
        assertTrue(prompt.contains(ToolRegistry.definitions.getValue("prepare_sms").description.substringBefore(". ")))
        assertFalse(prompt.contains("reply_gmail_message:"))
        assertTrue(prompt.contains("let the host check actual authorization"))
        assertTrue(prompt.contains("Optional tool filters are not missing owner facts"))
        assertTrue(prompt.contains("More owner preferences cannot create a missing capability"))
        assertTrue(SemanticPlannerInstructions.build(setOf("open_tmap")).contains("Does not start driving"))
    }
    @Test fun connectorResearchRejectionGivesTypedRepairWithoutExpandingTools() {
        val wrong="""{"action":"PUBLIC_RESEARCH","goal":"영상 검색","targetType":"CONTENT","publicQuery":"스트레칭","requiredTools":["search_youtube_videos"]}"""
        val error=runCatching {ModelDecisionContract.parse(wrong)}.exceptionOrNull() as ModelDecisionException
        assertEquals("invalid_public_tools",error.code)
        assertTrue(error.hint.contains("TOOL_TASK"))
        assertTrue(error.hint.contains("search_web"))
        assertTrue(runCatching {ModelDecisionContract.parse(wrong)}.isFailure)
    }
    @Test fun calendarSelectionRetainsExclusiveDayBoundaryWithoutGlobalCalendarRules() {
        val now="2026-11-14T10:00:00+09:00"
        assertTrue(AssistantPolicy.buildToolSelection(setOf("list_calendar_events"),now).contains("following day's 00:00 exclusive"))
        assertFalse(AssistantPolicy.buildToolSelection(setOf("save_note"),now).contains("23:59:59"))
    }
}
