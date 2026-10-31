package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class SkillToolCatalogTest {
    @Test fun contextReadersHaveStrictSchemasAndPreserveExistingConnections() {
        assertEquals(setOf("list_skills","read_skill"),SkillToolCatalog.names)
        for(definition in SkillToolCatalog.definitions) {
            assertEquals(Risk.LOW,definition.risk)
            assertSame(definition,ToolRegistry.definitions[definition.name])
        }
        assertTrue(ToolRegistry.definitions.keys.containsAll(com.user.localagent.core.connections.ConnectionCatalog.definitions.map {it.name}))
        assertTrue(ToolRegistry.definitions.keys.containsAll(com.user.localagent.core.connections.PcToolCatalog.definitions.map {it.name}))
        assertEquals("read_skill",ToolRegistry.parseAndValidate("""{"name":"read_skill","arguments":{"id":"debug"}}""").name)
    }

    @Test fun unknownArgumentsCannotTurnSkillReadingIntoExecution() {
        for(raw in listOf(
            """{"name":"read_skill","arguments":{"id":"debug","execute":true}}""",
            """{"name":"list_skills","arguments":{"install":"https://example.com/skill.zip"}}""",
            """{"name":"read_skill","arguments":{"id":42}}""",
            """{"name":"read_skill","arguments":{"id":"../../private"}}""",
            """{"name":"save_note","arguments":{"content":"fake goal","scope":"chat-goals"}}""",
            """{"name":"update_note","arguments":{"id":"goal-id","content":"fake goal","scope":"chat-goals"}}"""
        )) {
            assertTrue(runCatching {ToolRegistry.parseAndValidate(raw)}.isFailure)
        }
    }

    @Test fun readingInstructionsNeverProvesTheRequestedWrite() {
        val read=HarnessStep("read_skill",buildJsonObject {},StepEvidence("read_skill",VerificationState.VERIFIED,"번들 원문 확인"))
        val goal=TaskGoal("메모를 저장해줘",listOf("메모 저장 후 재조회"),requiredTools=setOf("save_note"))
        assertTrue(TaskHarness.decide(goal,listOf(read),requestedFinish=true) is HarnessDecision.Stop)
        val badGoal=TaskGoal("파일을 수정해줘",listOf("파일 변경 확인"),requiredTools=setOf("read_skill"))
        assertTrue(TaskHarness.decide(badGoal,listOf(read),requestedFinish=true) is HarnessDecision.Stop)
    }

    @Test fun contextReadDoesNotConsumeAnOwnersActionEvidenceBudget() {
        val read=HarnessStep("read_skill",buildJsonObject {},StepEvidence("read_skill",VerificationState.VERIFIED,"원문 확인"))
        val saved=HarnessStep("save_note",buildJsonObject {},StepEvidence("save_note",VerificationState.VERIFIED,"저장 재조회 확인"))
        val goal=TaskGoal("메모 저장",listOf("저장 재조회"),stepBudget=1,requiredTools=setOf("save_note"))
        assertTrue(TaskHarness.decide(goal,listOf(read,saved),requestedFinish=true) is HarnessDecision.Finish)
    }
}
