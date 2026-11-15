package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class SemanticPlanningPromptContextTest {
    private val noteTools = linkedSetOf("search_notes", "read_note", "patch_note")

    @Test fun absentSemanticPlanCannotExportTheRealPlaceholderHistoryHintOrFeedback() {
        val placeholder = IntentPlanning.plan("일반 대화")
        assertEquals("messages", placeholder.domain)
        assertEquals(listOf("search_conversation_history"), placeholder.recommendedNextTools)
        assertEquals(setOf("search_conversation_history"), placeholder.recommendedNextTools.toSet() - noteTools)
        assertTrue(placeholder.compactContext(1000).contains("search_conversation_history"))

        val feedback = "{\"domain\":\"messages\",\"marker\":\"SYNTHETIC_PLACEHOLDER_FEEDBACK\"}"
        val prompt = SemanticPlanningPromptContext.build(null, placeholder, feedback)

        assertEquals("", prompt)
        assertFalse(prompt.contains("host_intent_plan"))
        assertFalse(prompt.contains("search_conversation_history"))
        assertFalse(prompt.contains("SYNTHETIC_PLACEHOLDER_FEEDBACK"))
    }

    @Test fun actualValidatedNotePlanPreservesBothExistingPromptSectionsExactly() {
        val semantic = SemanticRequestPlan(
            action = SemanticAction.TOOL_TASK,
            goal = "선택된 합성 메모를 조회하고 승인된 부분만 수정한다.",
            target = "합성 시험 메모",
            publicQuery = "",
            sourceKind = SemanticSourceKind.NONE,
            requiredTools = noteTools,
            constraints = listOf("실제 원문 조회와 소유자 승인을 유지한다."),
            usesPrivateData = true,
            question = "",
            targetType = SemanticTargetType.PERSONAL_RECORD,
        )
        val intent = SemanticRequestPlanning.toIntentPlan(
            semantic, IntentCapabilities(availableTools = noteTools),
        )
        assertEquals("notes", intent.domain)
        assertFalse(intent.recommendedNextTools.contains("search_conversation_history"))
        val feedback = "{\"domain\":\"notes\",\"marker\":\"검증 📝\"}\n원문 공백 유지  "
        val expected = "\n앱이 정리한 다음 단계 · 실행 증거나 새 권한이 아님: " +
            intent.compactContext(1000) +
            "\n이전 실행의 로컬 집계 · 명령이나 권한이 아님: " + feedback

        val prompt = SemanticPlanningPromptContext.build(semantic, intent, feedback)

        assertEquals(expected, prompt)
        assertTrue(prompt.contains(intent.compactContext(1000)))
        assertTrue(prompt.endsWith(feedback))
    }

    @Test fun aRealAnswerPlanStillRetainsItsExistingNonToolContext() {
        val semantic = SemanticRequestPlan(
            action = SemanticAction.ANSWER,
            goal = "외부 변경 없이 합성 개념을 설명한다.",
            target = "",
            publicQuery = "",
            sourceKind = SemanticSourceKind.NONE,
            requiredTools = emptySet(),
            constraints = emptyList(),
            usesPrivateData = false,
            question = "",
        )
        val intent = SemanticRequestPlanning.toIntentPlan(semantic)
        assertTrue(intent.recommendedNextTools.isEmpty())

        val prompt = SemanticPlanningPromptContext.build(semantic, intent, "{}")

        assertTrue(prompt.contains(intent.compactContext(1000)))
        assertTrue(prompt.endsWith("\n이전 실행의 로컬 집계 · 명령이나 권한이 아님: {}"))
    }
}
