package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisCapabilityTest {
    private val first="12345678-1234-1234-1234-123456789abc"
    private val second="12345678-1234-1234-1234-123456789abd"
    private fun call(name:String,args:JsonObject)=ToolCall("synthetic",name,args)
    private fun step(tool:String,id:String?,state:VerificationState)=HarnessStep(tool,buildJsonObject {
        if(tool in PrivateAnalysisToolCatalog.analysisNames){if(state==VerificationState.UNVERIFIED)put("question","synthetic")else{put("analysisId",id);put("expectedProcessedChunks",16)}}
    },StepEvidence(tool,state,"synthetic host evidence",id))
    private fun reject(block:()->Unit){assertThrows(Exception::class.java){block()}}

    @Test fun sourceAndQuestionAreRequiredForNewAnalysisWhileContinuationMustUseActualProgressVersion() {
        PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("question","지난달 약속 변경을 원문과 대조한다")}))
        PrivateAnalysisToolCatalog.validate(call("analyze_audio_transcript",buildJsonObject {put("analysisId",first);put("expectedProcessedChunks",16)}))
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("analysisId",first)}))}
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_audio_transcript",buildJsonObject {put("question","요약")}))}
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("analysisId",first);put("expectedProcessedChunks",16);put("question","다른 질문")}))}
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("question","질문");put("expectedProcessedChunks",0)}))}
    }
    @Test fun limitsDatesHashesAndEvidencePagingRejectAmbiguousOrUnboundedArguments() {
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("question","질문");put("maxChunks",33)}))}
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_sms",buildJsonObject {put("question","질문");put("start","2026-11-01T00:00:00+09:00");put("end","2026-11-01T00:00:00+09:00")}))}
        reject {PrivateAnalysisToolCatalog.validate(call("analyze_conversation_import",buildJsonObject {put("question","질문");put("contentSha256","invented")}))}
        reject {PrivateAnalysisToolCatalog.validate(call("read_analysis_evidence",buildJsonObject {put("analysisId",first);put("evidenceId","a".repeat(64));put("offset",1)}))}
        reject {PrivateAnalysisToolCatalog.validate(call("list_analysis_sources",buildJsonObject {put("kind","all_app_private_databases")}))}
    }
    @Test fun partialAnalysisCannotCompleteTaskButLaterVerifiedSameContinuationCanResolveItsPendingScope() {
        val goal=TaskGoal("선택한 SMS 본문 전체를 분석한다",listOf("선택 범위의 처리를 확인한다"),6,setOf("analyze_sms"))
        val partial=step("analyze_sms",first,VerificationState.UNVERIFIED)
        assertTrue(TaskHarness.decide(goal,listOf(partial),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(partial,step("analyze_sms",first,VerificationState.VERIFIED)),true) is HarnessDecision.Finish)
        assertTrue(TaskHarness.decide(goal,listOf(partial,step("analyze_sms",second,VerificationState.VERIFIED)),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(partial,step("analyze_audio_transcript",first,VerificationState.VERIFIED)),true) is HarnessDecision.Stop)
    }
    @Test fun unrelatedUnverifiedStepsAreNotSilentlyDismissedByAnAnalysisCompletion() {
        val goal=TaskGoal("선택한자료를분석한다",listOf("실제범위확인"),6,setOf("analyze_sms"))
        val unresolved=step("get_sms_summary",null,VerificationState.UNVERIFIED)
        assertTrue(TaskHarness.decide(goal,listOf(unresolved,step("analyze_sms",first,VerificationState.VERIFIED)),true) is HarnessDecision.Stop)
        reject {StepEvidence("get_sms_summary",VerificationState.VERIFIED,"cannot invent analysis continuation",first)}
    }
    @Test fun hostResultScopeMustAgreeWithVerificationAndBindTheActualAnalysisId() {
        val tool=call("analyze_sms",buildJsonObject {put("question","질문")})
        fun result(complete:Boolean,state:VerificationState)=ToolResult(tool.id,true,buildJsonObject {put("analysisId",first);put("analysisCompleteWithinSelectedText",complete)}.toString(),state,"host observed")
        val partial=AnalysisCompletionEvidence.fromResult(tool,result(false,VerificationState.UNVERIFIED))
        assertEquals(first,partial.continuationId)
        reject {AnalysisCompletionEvidence.fromResult(tool,result(false,VerificationState.VERIFIED))}
        reject {AnalysisCompletionEvidence.fromResult(tool,result(true,VerificationState.UNVERIFIED))}
    }
}
