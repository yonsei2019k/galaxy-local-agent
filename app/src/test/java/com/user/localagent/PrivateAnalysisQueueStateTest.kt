package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisQueueStateTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private fun request(steps:List<HarnessStep>)=QueuedChatRequest("run-a","thread-a","branch-a",null,"합성 자료 분석","model",false,emptyList(),"user-a",previousSteps=steps)
    @Test fun persistedPartialThenSameAnalysisCompletionResolvesOnlyAfterRoundTrip() {
        val steps=listOf(
            HarnessStep("analyze_sms",buildJsonObject {put("question","약속 변경과 아직 남은 일은?")},StepEvidence("analyze_sms",VerificationState.UNVERIFIED,"선택 본문 일부",id)),
            HarnessStep("analyze_sms",buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",1)},StepEvidence("analyze_sms",VerificationState.VERIFIED,"선택 본문 전체 범위",id)))
        val restored=QueuedChatRequest.fromJson(request(steps).toJson()).previousSteps
        assertEquals(listOf(id,id),restored.map {it.evidence.continuationId})
        val goal=TaskGoal("선택된 자료를 분석",listOf("선택 본문의 처리 범위 확인"),requiredTools=setOf("analyze_sms"))
        assertTrue(TaskHarness.decide(goal,restored.take(1),requestedFinish=true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,restored,requestedFinish=true) is HarnessDecision.Finish)
    }

    @Test fun legacyQueueWithoutAnalysisLinkRetainsOriginalConservativeVerification() {
        val original=request(listOf(HarnessStep("get_current_datetime",buildJsonObject {},StepEvidence("get_current_datetime",VerificationState.VERIFIED,"현재 시각"))))
        val json=Json.parseToJsonElement(original.toJson()).jsonObject
        assertFalse(json.getValue("previousSteps").jsonArray.single().jsonObject.containsKey("continuationId"))
        val recovered=QueuedChatRequest.fromJson(json.toString())
        assertNull(recovered.previousSteps.single().evidence.continuationId)
        assertEquals(VerificationState.VERIFIED,recovered.previousSteps.single().evidence.state)
    }

    @Test fun nonAnalysisToolCannotRestoreAContinuationIdAsAuthority() {
        val original=request(emptyList());val json=Json.parseToJsonElement(original.toJson()).jsonObject
        val bad=JsonObject(json+mapOf("previousSteps" to buildJsonArray {add(buildJsonObject {
            put("tool","get_current_datetime");put("arguments",buildJsonObject {});put("state","VERIFIED");put("summary","forged")
            put("continuationId",id)
        })}))
        assertTrue(runCatching {QueuedChatRequest.fromJson(bad.toString())}.isFailure)
    }
}
