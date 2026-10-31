package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SemanticDependencyClosureTest {
    private fun importPlan() = SemanticRequestPlan(SemanticAction.ANALYZE_RECORDS, "지정한 대화 자료를 분석한다", "카카오톡", "",
        SemanticSourceKind.KAKAO, setOf("search_conversation_imports"), emptyList(), true, "", SemanticTargetType.PERSONAL_RECORD)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("A disabled prerequisite must reject the plan") }
        catch (_: IllegalArgumentException) { /* no execution or widened capability */ }
    }

    @Test fun importSearchOffersCatalogBeforeTheContentRead() {
        val plan = importPlan()
        assertEquals(setOf("list_imported_conversations", "search_conversation_imports"), SemanticRequestPlanning.allowedTools(plan))
        val intent = SemanticRequestPlanning.toIntentPlan(plan)
        assertEquals(listOf("list_imported_conversations", "search_conversation_imports"), intent.recommendedNextTools)
        assertEquals(listOf(IntentSource.KAKAO_IMPORT), intent.sources)
    }

    @Test fun metadataPrerequisiteNeverReplacesTheActualContentEvidence() {
        val goal = SemanticRequestPlanning.toTaskGoal(importPlan())
        assertEquals(setOf("search_conversation_imports"), goal.requiredTools)
        val catalog = HarnessStep("list_imported_conversations", buildJsonObject {},
            StepEvidence("list_imported_conversations", VerificationState.VERIFIED, "자료 목록 확인"))
        assertTrue(TaskHarness.decide(goal, listOf(catalog), requestedFinish = true) is HarnessDecision.Stop)
        val content = HarnessStep("search_conversation_imports", buildJsonObject {},
            StepEvidence("search_conversation_imports", VerificationState.VERIFIED, "지정한 출처 내용 확인"))
        assertTrue(TaskHarness.decide(goal, listOf(catalog, content), requestedFinish = true) is HarnessDecision.Finish)
    }

    @Test fun canonicalParsingRejectsADisabledCatalogPrerequisite() {
        val encoded = SemanticRequestPlanning.contextJson(importPlan())
        rejected { SemanticRequestPlanning.parse(encoded, setOf("search_conversation_imports")) }
        val valid = SemanticRequestPlanning.parse(encoded, setOf("list_imported_conversations", "search_conversation_imports"))
        assertEquals(importPlan(), valid)
    }

    @Test fun helperConversionsCannotRestoreDisabledPrerequisites() {
        val plan = importPlan()
        val onlySearch = setOf("search_conversation_imports")
        rejected { SemanticRequestPlanning.allowedTools(plan, onlySearch) }
        rejected { SemanticRequestPlanning.toIntentPlan(plan, IntentCapabilities(availableTools = onlySearch)) }
    }

    @Test fun sparseDecisionsAlsoRespectTheHostsDisabledDependencies() {
        val raw = buildJsonObject {
            put("action", "ANALYZE_RECORDS"); put("goal", "지정한 대화 자료를 분석한다")
            put("targetType", "PERSONAL_RECORD")
            put("sourceKind", "kakao"); put("requiredTools", buildJsonArray { add("search_conversation_imports") })
        }.toString()
        rejected { ModelDecisionContract.parse(raw, setOf("search_conversation_imports")) }
        val plan = ModelDecisionContract.parse(raw, setOf("list_imported_conversations", "search_conversation_imports"))
        assertEquals(setOf("search_conversation_imports"), plan.requiredTools)
    }

    @Test fun closureDoesNotAddUnrelatedToolsOrRewriteTheMeaningPlan() {
        val encoded = Json.parseToJsonElement(SemanticRequestPlanning.contextJson(importPlan())).jsonObject
        assertEquals(listOf("search_conversation_imports"), encoded.getValue("requiredTools").jsonArray.map { it.jsonPrimitive.content })
        val public = SemanticRequestPlan(SemanticAction.PUBLIC_RESEARCH, "공개 주제를 조사한다", "주제", "공개 주제",
            SemanticSourceKind.NONE, setOf("search_web"), emptyList(), false, "")
        assertEquals(setOf("search_web"), SemanticRequestPlanning.allowedTools(public))
        assertFalse(SemanticRequestPlanning.allowedTools(importPlan()).contains("get_sms_summary"))
        assertFalse(SemanticRequestPlanning.allowedTools(importPlan()).contains("search_web"))
    }
}
