package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ConversationSubmissionPolicyTest {
    @Test fun ordinaryConversationKeepsWholeTextAndUsesActionLoopWithoutVocabularyRouting() {
        val requests = listOf(
            "계획만 세우고 아직 파일은 바꾸지 마. 뒤에 정정하면 그 조건도 반영해줘.",
            "내 목표를 정하고 필요한 스킬을 읽어서 조사한 다음 검토해줘.",
            "오류를 디버그하고, 조사 결과를 보고 수정 여부를 판단하자.",
            "  줄 앞 공백\n원래 요청과 조건을 그대로 보존해줘.  ",
        )
        for (request in requests) {
            val parsed = ConversationSubmissionPolicy.parse(request)
            assertEquals(AgentWorkMode.ACT, parsed.mode)
            assertEquals(request, parsed.rawText)
            assertEquals(request, parsed.requestText)
            assertNull(parsed.explicitModeCommand)
            assertFalse(parsed.commandOnly)
        }
    }

    @Test fun eachRegisteredLegacyCommandAppliesOnlyToItsOwnSubmission() {
        val commands = mapOf(
            "/act" to AgentWorkMode.ACT, "/plan" to AgentWorkMode.PLAN,
            "/goal" to AgentWorkMode.ACT, "/debug" to AgentWorkMode.DEBUG,
            "/review" to AgentWorkMode.REVIEW, "/research" to AgentWorkMode.RESEARCH,
        )
        for ((command, mode) in commands) {
            val raw = "$command 원문을 확인해줘.  "
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertEquals(mode, parsed.mode)
            assertEquals(command, parsed.explicitModeCommand)
            assertEquals(raw, parsed.rawText)
            assertEquals("원문을 확인해줘.  ", parsed.requestText)
            assertFalse(parsed.commandOnly)
            assertEquals(AgentWorkMode.ACT, ConversationSubmissionPolicy.newRequestMode())
            assertEquals(AgentWorkMode.ACT, ConversationSubmissionPolicy.parse("다음 요청").mode)
        }
    }

    @Test fun bareCommandsCarryNoPayloadAndCannotBecomeStickyPreferences() {
        for (raw in listOf("/plan", "  /goal\r\n  ", "\t/research\n")) {
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertTrue(parsed.commandOnly)
            assertEquals("", parsed.requestText)
            assertEquals(raw, parsed.rawText)
            assertNotNull(parsed.explicitModeCommand)
            assertEquals(AgentWorkMode.ACT, ConversationSubmissionPolicy.parse("새 요청").mode)
        }
    }

    @Test fun newGoalDirectiveUsesConversationLoopWithoutUpgradingPersistedGoalMode() {
        val parsed = ConversationSubmissionPolicy.parse("/goal 이번 주 조사 목표를 정해줘")
        assertEquals(AgentWorkMode.ACT, parsed.mode)
        assertEquals("/goal", parsed.explicitModeCommand)
        assertEquals("이번 주 조사 목표를 정해줘", parsed.requestText)
        assertEquals(AgentWorkMode.GOAL, AgentWorkMode.parse("goal"))
        assertNotEquals(AgentWorkMode.parse("goal"), parsed.mode)
        val bare = ConversationSubmissionPolicy.parse("/goal")
        assertTrue(bare.commandOnly)
        assertEquals(AgentWorkMode.ACT, bare.mode)
        assertEquals("", bare.requestText)
    }

    @Test fun exactTokenAndCaseAreRequiredAndUnknownCommandsRemainRaw() {
        val rawInputs = listOf(
            "/plan2 바꾸지 마", "/plan_more 확인", "/planning 확인", "/plan: 확인",
            "/plan/act 확인", "/PLAN 확인", "/future-command 계획", "/계획 검토",
            "/plan\u0000 확인", "/plan🙂 확인", "/ plan 확인",
        )
        for (raw in rawInputs) {
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertEquals(raw, parsed.requestText)
            assertEquals(raw, parsed.rawText)
            assertEquals(AgentWorkMode.ACT, parsed.mode)
            assertNull(parsed.explicitModeCommand)
            assertFalse(parsed.commandOnly)
        }
    }

    @Test fun commandMustBeFirstTokenAndIsNeverSearchedInsideTaskOrQuotedData() {
        for (raw in listOf("1 /plan 검토", "설명\n/plan 검토", "`/plan` 검토", "\"/review\"", "12\n/research 공개 조사")) {
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertEquals(raw, parsed.requestText)
            assertEquals(AgentWorkMode.ACT, parsed.mode)
            assertNull(parsed.explicitModeCommand)
        }
    }

    @Test fun whitespaceDelimitsTheOneExplicitCommandWithoutRecursivelyParsingPayload() {
        for (separator in listOf(" ", "\t", "\n", "\r\n", "\u2003")) {
            val raw = " \n\t/plan${separator}/act 원본 조건\n둘째 줄  "
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertEquals(AgentWorkMode.PLAN, parsed.mode)
            assertEquals("/plan", parsed.explicitModeCommand)
            assertEquals("/act 원본 조건\n둘째 줄  ", parsed.requestText)
            assertEquals(raw, parsed.rawText)
        }
    }

    @Test fun emptyWhitespaceAndSlashOnlyInputsRemainOrdinaryWithoutInventedPayload() {
        for (raw in listOf("", " \r\n\t", "/", "///")) {
            val parsed = ConversationSubmissionPolicy.parse(raw)
            assertEquals(raw, parsed.requestText)
            assertEquals(AgentWorkMode.ACT, parsed.mode)
            assertNull(parsed.explicitModeCommand)
            assertFalse(parsed.commandOnly)
        }
    }

    @Test fun debugRepresentationCannotExposeEitherPrivateOriginalOrPayload() {
        val raw = "/plan confidential-source-12345"
        val rendered = ConversationSubmissionPolicy.parse(raw).toString()
        assertFalse(rendered.contains("confidential-source-12345"))
        assertFalse(rendered.contains(raw))
        assertTrue(rendered.contains("mode=plan"))
        assertTrue(rendered.contains("rawTextLength="))
    }
}
