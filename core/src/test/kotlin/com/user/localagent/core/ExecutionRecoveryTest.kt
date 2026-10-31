package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionRecoveryTest {
    private fun step(tool:String,state:VerificationState,query:String)=HarnessStep(tool,buildJsonObject {put("query",query)},StepEvidence(tool,state,"host evidence"))
    @Test fun verifiedReadRecoveryCanCompleteWithoutErasingTheFailedAttempt(){
        val goal=TaskGoal("메모 검색",listOf("실제 메모 검색"),requiredTools=setOf("search_notes"))
        val steps=listOf(step("search_notes",VerificationState.FAILED,"first"),step("search_notes",VerificationState.VERIFIED,"corrected"))
        assertTrue(TaskHarness.decide(goal,steps,true) is HarnessDecision.Finish)
        assertEquals(VerificationState.FAILED,steps.first().evidence.state)
    }
    @Test fun unrelatedReadCannotSubstituteForMissingSourceEvidence(){
        val goal=TaskGoal("문자 분석",listOf("문자 제공자 조회"),requiredTools=setOf("get_sms_summary"))
        assertTrue(TaskHarness.decide(goal,listOf(step("get_sms_summary",VerificationState.FAILED,"sms"),step("search_notes",VerificationState.VERIFIED,"notes")),true) is HarnessDecision.Stop)
    }
    @Test fun uncertainWriteBlocksAnotherWriteButAllowsInspection(){
        val goal=TaskGoal("프로젝트 파일 수정",listOf("파일 재조회 검증"),requiredTools=setOf("write_project_file"))
        val failed=step("write_project_file",VerificationState.FAILED,"write")
        val read=ToolCall("inspect","read_project_file",buildJsonObject {put("path","README.md")})
        val write=ToolCall("retry","write_project_file",buildJsonObject {put("path","different.md");put("content","changed")})
        assertTrue(TaskHarness.beforeStep(goal,listOf(failed),read) is HarnessDecision.Advance)
        assertTrue(TaskHarness.beforeStep(goal,listOf(failed),write) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(failed,step("read_project_file",VerificationState.VERIFIED,"inspect")),true) is HarnessDecision.Stop)
    }
    @Test fun projectEditCanReadTheActualFileBeforeChangingIt(){
        val goal=TaskHarness.inferGoal("현재 프로젝트 README 파일을 수정해줘")
        val read=ToolCall("inspect","read_project_file",buildJsonObject {put("path","README.md")})
        assertTrue("Reading project content must not be mistaken for an unrequested write",TaskHarness.beforeStep(goal,emptyList(),read) is HarnessDecision.Advance)
    }
    @Test fun projectTextWriteDoesNotRequireAnUnrelatedExportArtifact(){
        val goal=TaskHarness.inferGoal("현재 프로젝트에 notes.txt 파일을 만들어줘")
        assertTrue(goal.requiredArtifactFormats.isEmpty())
        assertTrue(TaskHarness.decide(goal,listOf(step("write_project_file",VerificationState.VERIFIED,"notes.txt")),true) is HarnessDecision.Finish)
    }
}
