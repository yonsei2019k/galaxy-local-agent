package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic structured choices only; no natural-language request classifier is involved. */
class ModelDecisionContractTest {
    @Test fun storageAndConnectorPrivacyRoundTripThroughBothContracts() {
        val tools=setOf("inspect_project_storage","find_project_duplicates")+
            com.user.localagent.core.connections.PcToolCatalog.names
        for(tool in tools){
            val plan=parse(decision("TOOL_TASK","requiredTools" to array(tool)))
            assertTrue("Private capability lost: $tool",plan.usesPrivateData)
            assertEquals(plan,SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(plan)))
        }
        val storage=parse(decision("TOOL_TASK","requiredTools" to array("inspect_project_storage","find_project_duplicates","save_note")))
        assertTrue(storage.usesPrivateData)
        assertEquals("mixed_private_public",failure(decision("TOOL_TASK","publicQuery" to JsonPrimitive("public"),
            "requiredTools" to array("inspect_project_storage","search_web")).toString()).code)
    }

    private fun decision(action: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf("action" to JsonPrimitive(action), "goal" to JsonPrimitive("현재 사용자의 목표"), "targetType" to JsonPrimitive("GENERAL")) + fields)
    private fun array(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
    private fun parse(value: JsonObject) = ModelDecisionContract.parse(value.toString())
    private fun failure(raw: String, available: Set<String> = ToolRegistry.definitions.keys): ModelDecisionException {
        try { ModelDecisionContract.parse(raw, available); fail("Invalid decision was accepted") }
        catch (error: ModelDecisionException) { return error }
        throw AssertionError("Expected a safe decision-contract failure")
    }
    private fun publicDecision(vararg fields: Pair<String, JsonElement>) = decision("PUBLIC_RESEARCH",
        "publicQuery" to JsonPrimitive("공개 API 연결 경로"), "requiredTools" to array("search_web"), *fields)

    @Test fun sparseAnswersAndCancellationKeepAnExplicitTargetType() {
        for (action in listOf("ANSWER", "CANCEL")) {
            val plan = parse(decision(action))
            assertEquals(SemanticAction.valueOf(action), plan.action)
            assertEquals("현재 사용자의 목표", plan.goal)
            assertEquals(SemanticSourceKind.NONE, plan.sourceKind)
            assertFalse(plan.usesPrivateData)
            assertTrue(plan.requiredTools.isEmpty())
            assertEquals(SemanticTargetType.GENERAL, plan.targetType)
            assertEquals("", plan.publicQuery)
            assertEquals("", plan.question)
        }
    }

    @Test fun generalActionsCanOmitTargetClassification() {
        val candidates = listOf(decision("ANSWER"), decision("CANCEL"), publicDecision(),
            decision("CLARIFY", "question" to JsonPrimitive("필수 조건은 무엇인가요?")),
            decision("CONNECTION_STATUS", "target" to JsonPrimitive("연결 대상")))
        for (candidate in candidates) {
            val plan = parse(JsonObject(candidate.filterKeys { it != "targetType" }))
            assertEquals(SemanticTargetType.GENERAL, plan.targetType)
        }
    }

    @Test fun unusedPrivateAnnotationsCannotCreateReadsInNonExecutingActions() {
        for (action in listOf("ANSWER", "CANCEL", "CONNECTION_STATUS", "CONNECT_APP", "CLARIFY")) {
            val plan = parse(decision(action, "target" to JsonPrimitive("실제 연결 대상"),
                "sourceKind" to JsonPrimitive("unused_metadata"), "usesPrivateData" to JsonPrimitive(true),
                "publicQuery" to JsonPrimitive("SYNTHETIC_UNUSED_QUERY"),
                "question" to JsonPrimitive(if (action == "CLARIFY") "필수 조건은 무엇인가요?" else "")))
            assertEquals(SemanticSourceKind.NONE, plan.sourceKind)
            assertFalse(plan.usesPrivateData)
            assertTrue(plan.requiredTools.isEmpty())
            assertEquals("", plan.publicQuery)
            assertFalse(SemanticRequestPlanning.contextJson(plan).contains("SYNTHETIC_UNUSED_QUERY"))
        }
    }

    @Test fun nonExecutingActionsStillRejectActualToolAuthority() {
        val error = failure(decision("ANSWER", "requiredTools" to array("search_web")).toString())
        assertEquals("unexpected_tools", error.code)
        assertEquals("missing_connection_target", failure(decision("CONNECT_APP").toString()).code)
        assertEquals("missing_question", failure(decision("CLARIFY").toString()).code)
    }

    @Test fun publicResearchDerivesCanonicalFieldsWithoutRequiringUnusedAnnotations() {
        val plan = parse(publicDecision("constraints" to array("원래 목표 보존", "출처 비교")))
        assertEquals(SemanticAction.PUBLIC_RESEARCH, plan.action)
        assertEquals(setOf("search_web"), plan.requiredTools)
        assertEquals(listOf("원래 목표 보존", "출처 비교"), plan.constraints)
        assertFalse(plan.usesPrivateData)
        assertEquals(SemanticSourceKind.NONE, plan.sourceKind)
        assertEquals(plan, SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(plan)))
    }

    @Test fun publicResearchCannotIgnoreAnExplicitPrivateSourceOrPrivacyClaim() {
        assertEquals("public_private_conflict", failure(publicDecision("sourceKind" to JsonPrimitive("kakao")).toString()).code)
        assertEquals("public_private_conflict", failure(publicDecision("usesPrivateData" to JsonPrimitive(true)).toString()).code)
        assertEquals("missing_public_query", failure(decision("PUBLIC_RESEARCH", "requiredTools" to array("search_web")).toString()).code)
        assertEquals("invalid_public_tools", failure(publicDecision("requiredTools" to array("search_web", "search_notes")).toString()).code)
    }

    @Test fun targetCapabilityStillSeparatesRetailProductsAndServices() {
        val product = parse(publicDecision("targetType" to JsonPrimitive("PHYSICAL_PRODUCT"), "requiredTools" to array("search_shopping")))
        assertEquals(SemanticTargetType.PHYSICAL_PRODUCT, product.targetType)
        assertEquals("shopping_target_mismatch", failure(publicDecision("targetType" to JsonPrimitive("SERVICE"), "requiredTools" to array("search_shopping")).toString()).code)
        val service = parse(publicDecision("targetType" to JsonPrimitive("SERVICE")))
        assertEquals(setOf("search_web"), service.requiredTools)
    }

    @Test fun analysisDerivesPrivacyFromTheRequiredSourceAndContentRead() {
        val plan = parse(decision("ANALYZE_RECORDS", "sourceKind" to JsonPrimitive("kakao"),
            "requiredTools" to array("search_conversation_imports"), "usesPrivateData" to JsonPrimitive(false)))
        assertTrue(plan.usesPrivateData)
        assertEquals(SemanticSourceKind.KAKAO, plan.sourceKind)
        assertEquals("missing_content_read", failure(decision("ANALYZE_RECORDS", "sourceKind" to JsonPrimitive("kakao"), "requiredTools" to array("list_imported_conversations")).toString()).code)
        assertEquals("invalid_record_tools", failure(decision("ANALYZE_RECORDS", "sourceKind" to JsonPrimitive("kakao"), "requiredTools" to array("search_conversation_imports", "get_sms_summary")).toString()).code)
    }

    @Test fun compoundToolTasksPreserveAllToolsAndConstraintsButNotPrivateExport() {
        val plan = parse(decision("TOOL_TASK", "requiredTools" to array("search_notes", "save_note"),
            "constraints" to array("기존 내용 보존", "저장 후 확인")))
        assertEquals(setOf("search_notes", "save_note"), plan.requiredTools)
        assertTrue(plan.usesPrivateData)
        assertEquals(listOf("기존 내용 보존", "저장 후 확인"), plan.constraints)
        assertEquals("mixed_private_public", failure(decision("TOOL_TASK", "requiredTools" to array("search_notes", "search_web"), "publicQuery" to JsonPrimitive("공개 주제")).toString()).code)
    }

    @Test fun repeatedToolsAndConstraintsAreDeduplicatedWithoutAddingAuthority() {
        val plan = parse(publicDecision("requiredTools" to array("search_web", "search_web"),
            "constraints" to array("출처 확인", "출처 확인")))
        assertEquals(setOf("search_web"), plan.requiredTools)
        assertEquals(listOf("출처 확인"), plan.constraints)
    }

    @Test fun unknownFieldsWrongTypesAndUnavailableToolsRemainFailures() {
        assertEquals("unknown_field", failure(publicDecision("executeImmediately" to JsonPrimitive(true)).toString()).code)
        assertEquals("invalid_privacy_type", failure(publicDecision("usesPrivateData" to JsonPrimitive("false")).toString()).code)
        assertEquals("invalid_array_type", failure(publicDecision("requiredTools" to JsonPrimitive("search_web")).toString()).code)
        assertEquals("unknown_tool", failure(publicDecision("requiredTools" to array("invented_tool")).toString()).code)
        assertEquals("unavailable_tool", failure(publicDecision().toString(), setOf("read_webpage")).code)
    }

    @Test fun errorsUseFixedRepairHintsWithoutGeneratedFieldValues() {
        val marker = "SYNTHETIC_REJECTED_PRIVATE_VALUE"
        val error = failure(publicDecision("requiredTools" to array(marker)).toString())
        assertEquals("unknown_tool", error.code)
        assertFalse(error.hint.contains(marker))
        assertFalse(error.message.orEmpty().contains(marker))
        assertTrue(error.hint.all { it.code < 128 })
        val raw = publicDecision().toString()
        assertEquals("duplicate_key", failure(raw.dropLast(1) + ",\"action\":\"ANSWER\"}").code)
    }

    @Test fun actionSchemasRequireOnlyFieldsThatActionUses() {
        val branches = Json.parseToJsonElement(ModelDecisionContract.schemaJson()).jsonObject.getValue("oneOf").jsonArray
        fun branch(action: String) = branches.map { it.jsonObject }.single {
            it.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content == action
        }
        assertEquals(setOf("action", "goal"), branch("ANSWER").getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("action", "goal", "publicQuery", "requiredTools"), branch("PUBLIC_RESEARCH").getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("shopping_target_mismatch", failure(JsonObject(publicDecision("requiredTools" to array("search_shopping")).filterKeys { it != "targetType" }).toString()).code)
        assertFalse(branch("PUBLIC_RESEARCH").getValue("properties").jsonObject.containsKey("usesPrivateData"))
        assertFalse(branch("ANSWER").getValue("properties").jsonObject.containsKey("requiredTools"))
        assertTrue(branch("CONNECT_APP").getValue("required").jsonArray.any { it.jsonPrimitive.content == "target" })
    }
}
