package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Tests structured model decisions and host authority, without classifying natural-language keywords. */
class SemanticRequestPlanningTest {
    private fun request(
        action: String = "PUBLIC_RESEARCH",
        goal: String = "직접 연결 가능한 공개 경로를 조사한다",
        target: String = "카카오톡",
        query: String = "카카오톡 직접 연동 API 알림 접근",
        source: String = "none",
        tools: List<String> = listOf("search_web"),
        constraints: List<String> = listOf("내보내기 안내 제외"),
        privateData: Boolean = false,
        question: String = ""
    ) = buildJsonObject {
        put("action", action)
        put("goal", goal)
        put("target", target)
        put("publicQuery", query)
        put("sourceKind", source)
        put("requiredTools", buildJsonArray { tools.forEach { add(it) } })
        put("constraints", buildJsonArray { constraints.forEach { add(it) } })
        put("usesPrivateData", privateData)
        put("question", question)
    }

    private fun parse(value: JsonObject) = SemanticRequestPlanning.parse(value.toString())
    private fun changed(key: String, value: JsonElement): JsonObject = JsonObject(request() + (key to value))
    private fun rejected(raw: String, available: Set<String> = ToolRegistry.definitions.keys) {
        try {
            SemanticRequestPlanning.parse(raw, available)
            fail("Malformed or unauthorized semantic decisions must be rejected")
        } catch (_: IllegalArgumentException) {
            // A failed decision stays a failure; this API does not return a keyword fallback.
        }
    }
    private fun rejected(value: JsonObject) = rejected(value.toString())
    private fun step(tool: String, state: VerificationState = VerificationState.VERIFIED) =
        HarnessStep(tool, buildJsonObject {}, StepEvidence(tool, state, "합성 호스트 검증 결과"))

    @Test fun publicResearchDecisionPreservesGoalTargetQueryAndConstraints() {
        val plan = parse(request())
        assertEquals(SemanticAction.PUBLIC_RESEARCH, plan.action)
        assertEquals("카카오톡", plan.target)
        assertEquals("카카오톡 직접 연동 API 알림 접근", plan.publicQuery)
        assertEquals(listOf("내보내기 안내 제외"), plan.constraints)
        assertEquals(setOf("search_web"), plan.requiredTools)
        assertFalse(plan.usesPrivateData)
        assertEquals("", plan.question)
    }

    @Test fun publicDecisionFeedsTheSameTaskGoalIntentAndOfferedTools() {
        val plan = parse(request())
        val goal = SemanticRequestPlanning.toTaskGoal(plan)
        val intent = SemanticRequestPlanning.toIntentPlan(plan)
        val tools = SemanticRequestPlanning.allowedTools(plan)
        assertEquals(plan.goal, goal.goal)
        assertEquals(setOf("search_web"), goal.requiredTools)
        assertEquals("web", intent.domain)
        assertEquals(plan.goal, intent.ownerGoal)
        assertTrue(intent.recommendedNextTools.contains("search_web"))
        assertTrue(intent.sources.contains(IntentSource.WEB))
        assertTrue(tools.containsAll(plan.requiredTools))
        assertTrue(tools.all { it in setOf("search_web", "search_shopping", "read_webpage", "open_url") })
        assertTrue(intent.deferredTools.isEmpty())
    }

    @Test fun everyRequiredFieldMustBePresentAndAdditionalFieldsAreRejected() {
        val valid = request()
        for (key in valid.keys) rejected(JsonObject(valid.filterKeys { it != key }))
        rejected(JsonObject(valid + ("executeImmediately" to JsonPrimitive(true))))
    }

    @Test fun invalidJsonAndWrongFieldTypesNeverProduceAFallbackPlan() {
        for (raw in listOf("", "{", "[]", "null", "```json\n${request()}\n```")) rejected(raw)
        rejected(changed("usesPrivateData", JsonPrimitive("false")))
        rejected(changed("publicQuery", JsonNull))
        rejected(changed("requiredTools", JsonPrimitive("search_web")))
        rejected(changed("requiredTools", buildJsonArray { add(1) }))
        rejected(changed("constraints", buildJsonObject {}))
        rejected(changed("constraints", buildJsonArray { add(true) }))
        rejected(changed("action", JsonPrimitive("public_research")))
        rejected(changed("sourceKind", JsonPrimitive("KAKAO")))
    }

    @Test fun duplicateKeysAndDuplicateAuthoritiesAreAmbiguousAndRejected() {
        val raw = request().toString()
        rejected(raw.dropLast(1) + ",\"action\":\"ANSWER\"}")
        rejected(request(tools = listOf("search_web", "search_web")))
        rejected(request(constraints = listOf("동일 조건", "동일 조건")))
    }

    @Test fun modelCannotNameAnUnknownOrUnavailableTool() {
        rejected(request(tools = listOf("invented_tool")))
        rejected(request().toString(), available = setOf("read_webpage"))
        val plan = SemanticRequestPlanning.parse(request().toString(), setOf("search_web"))
        assertEquals(setOf("search_web"), plan.requiredTools)
    }

    @Test fun publicResearchCannotReadOrExportPrivateSources() {
        rejected(request(source = "kakao"))
        rejected(request(privateData = true))
        rejected(request(tools = listOf("search_web", "list_messenger_notifications")))
        rejected(request(tools = listOf("save_note")))
        rejected(request(query = ""))
        rejected(request(tools = listOf("read_webpage")))
    }

    @Test fun aPrivateSourceAnalysisRequiresActualSourceContentReads() {
        val plan = parse(request(action = "ANALYZE_RECORDS", query = "", source = "kakao",
            tools = listOf("search_conversation_imports"), privateData = true))
        assertEquals(SemanticAction.ANALYZE_RECORDS, plan.action)
        assertEquals(setOf("search_conversation_imports"), SemanticRequestPlanning.toTaskGoal(plan).requiredTools)
        val intent = SemanticRequestPlanning.toIntentPlan(plan)
        assertEquals("messages", intent.domain)
        assertTrue(intent.sources.contains(IntentSource.KAKAO_IMPORT))
        assertFalse(SemanticRequestPlanning.allowedTools(plan).contains("search_web"))

        rejected(request(action = "ANALYZE_RECORDS", query = "", source = "none",
            tools = listOf("search_conversation_imports"), privateData = true))
        rejected(request(action = "ANALYZE_RECORDS", query = "", source = "kakao",
            tools = listOf("search_conversation_imports"), privateData = false))
        rejected(request(action = "ANALYZE_RECORDS", query = "", source = "kakao",
            tools = listOf("list_imported_conversations"), privateData = true))
    }

    @Test fun privateAnalysisCannotSwitchSourceWriteRepliesOrSearchTheWeb() {
        for (tool in listOf("get_sms_summary", "reply_messenger_notification", "search_web")) {
            rejected(request(action = "ANALYZE_RECORDS", query = "", source = "kakao",
                tools = listOf("search_conversation_imports", tool), privateData = true))
        }
        rejected(request(action = "ANALYZE_RECORDS", source = "sms", tools = listOf("get_sms_summary"), privateData = true))
        rejected(request(action = "ANALYZE_RECORDS", query = "", source = "sms",
            tools = listOf("list_messenger_notifications"), privateData = true))
    }

    @Test fun answerCancelAndClarificationNeverGrantExecutionTools() {
        for (action in listOf("ANSWER", "CANCEL", "CLARIFY")) {
            val question = if (action == "CLARIFY") "어떤 앱에 연결할까요?" else ""
            val valid = request(action = action, query = "", tools = emptyList(), question = question)
            val plan = parse(valid)
            assertTrue(SemanticRequestPlanning.allowedTools(plan).isEmpty())
            assertTrue(SemanticRequestPlanning.toTaskGoal(plan).requiredTools.isEmpty())
            rejected(JsonObject(valid + ("requiredTools" to buildJsonArray { add("search_web") })))
        }
        rejected(request(action = "CLARIFY", query = "", tools = emptyList(), question = ""))
        rejected(request(action = "ANSWER", query = "", tools = emptyList(), question = "실행할까요?"))
    }

    @Test fun connectionStatusAndConnectRequestsDoNotInventAConnector() {
        for (action in listOf("CONNECTION_STATUS", "CONNECT_APP")) {
            val plan = parse(request(action = action, query = "", source = "kakao", tools = emptyList()))
            assertTrue(SemanticRequestPlanning.allowedTools(plan).isEmpty())
            val goal = SemanticRequestPlanning.toTaskGoal(plan)
            assertTrue(goal.requiredTools.isEmpty())
            assertTrue(TaskHarness.decide(goal, listOf(step("list_messenger_notifications")), requestedFinish = true) is HarnessDecision.Stop)
            rejected(request(action = action, query = "", source = "kakao", tools = listOf("list_messenger_notifications")))
        }
    }

    @Test fun explicitToolTaskRetainsItsWriteGoalAndNeedsVerifiedCompletion() {
        val plan = parse(request(action = "TOOL_TASK", goal = "요청한 본문으로 메모를 저장한다",
            query = "", tools = listOf("save_note")))
        val goal = SemanticRequestPlanning.toTaskGoal(plan)
        assertEquals(setOf("save_note"), goal.requiredTools)
        assertTrue(SemanticRequestPlanning.allowedTools(plan).contains("save_note"))
        assertTrue(TaskHarness.decide(goal, emptyList(), requestedFinish = true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal, listOf(step("save_note", VerificationState.PREPARED)), requestedFinish = true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal, listOf(step("save_note", VerificationState.FAILED)), requestedFinish = true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal, listOf(step("save_note")), requestedFinish = true) is HarnessDecision.Finish)
    }

    @Test fun aToolTaskCannotAutomaticallyCombinePrivateRecordsAndPublicSearch() {
        rejected(request(action = "TOOL_TASK", source = "kakao", privateData = true,
            tools = listOf("search_conversation_imports", "search_web")))
        rejected(request(action = "TOOL_TASK", query = "", tools = emptyList()))
    }

    @Test fun sourceCountsCannotSatisfyPublicResearchCompletionEvidence() {
        val goal = SemanticRequestPlanning.toTaskGoal(parse(request()))
        assertTrue(TaskHarness.decide(goal, listOf(step("list_messenger_notifications")), requestedFinish = true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal, listOf(step("search_web", VerificationState.UNVERIFIED)), requestedFinish = true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal, listOf(step("search_web")), requestedFinish = true) is HarnessDecision.Finish)
    }

    @Test fun oversizedOrBlankStructuredDecisionsAreRejected() {
        rejected(request(goal = ""))
        rejected(request(goal = "가".repeat(1001)))
        rejected(request(target = "가".repeat(201)))
        rejected(request(query = "가".repeat(501)))
        rejected(request(constraints = List(7) { "조건 $it" }))
        rejected(request(constraints = listOf("")))
        rejected(request(constraints = listOf("가".repeat(241))))
        rejected(request().toString() + " ".repeat(8193))
    }
}
