package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ArtifactHarnessTest {
    private fun step(format:String)=HarnessStep(ToolCall("test-$format","create_document",buildJsonObject {put("format",format);put("title","회의록");put("content","합성 테스트")}),StepEvidence("create_document",VerificationState.VERIFIED,"파일 재조회 확인"))
    @Test fun everyRequestedFormatMustBeVerifiedBeforeCompletion(){
        val goal=TaskHarness.inferGoal("회의록을 DOCX와 PDF 파일로 만들어줘")
        assertEquals(setOf("docx","pdf"),goal.requiredArtifactFormats)
        assertTrue(TaskHarness.decide(goal,listOf(step("docx")),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(step("docx"),step("pdf")),true) is HarnessDecision.Finish)
    }
    @Test fun memoDocumentDoesNotInventAnAdditionalNoteWrite(){
        val goal=TaskHarness.inferGoal("메모 내용을 PDF로 저장해줘")
        assertEquals(setOf("create_document"),goal.requiredTools)
    }
    @Test fun executableExtensionsNeverEnterArtifactTool(){
        var rejected=false
        try {ToolRegistry.validate(ToolCall("test","create_document",buildJsonObject {put("format","exe");put("title","sample");put("content","synthetic")}))}catch(_:IllegalArgumentException){rejected=true}
        assertTrue(rejected)
    }
}
