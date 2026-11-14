package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AutonomousReviewPolicyTest {
    @Test fun reviewLimitWritesOnlyAnExplicitlyLimitedFinalWithCompleteHostEvidence(){
        assertEquals(AutonomousReviewPolicy.Phase.REVIEW,AutonomousReviewPolicy.nextPhase(true,false,0))
        assertEquals(AutonomousReviewPolicy.Phase.WRITE_FINAL,AutonomousReviewPolicy.nextPhase(true,true,1))
        assertEquals(AutonomousReviewPolicy.Phase.WRITE_LIMITED_FINAL,AutonomousReviewPolicy.nextPhase(true,false,AutonomousReviewPolicy.MAX_REVIEWS))
    }
    @Test fun reviewLimitNeverBypassesMissingHostEvidence(){
        assertEquals(AutonomousReviewPolicy.Phase.CONTINUE,AutonomousReviewPolicy.nextPhase(false,false,AutonomousReviewPolicy.MAX_REVIEWS))
        assertEquals(AutonomousReviewPolicy.Phase.CONTINUE,AutonomousReviewPolicy.nextPhase(false,true,AutonomousReviewPolicy.MAX_REVIEWS))
    }
    @Test fun invalidReviewCountIsNotAccepted(){
        assertThrows(IllegalArgumentException::class.java){AutonomousReviewPolicy.nextPhase(true,false,-1)}
    }
    @Test fun reviewCannotPromoteItselfToEvidenceOrOwnerAuthority() {
        assertTrue(AutonomousReviewPolicy.parse("""{"type":"review_complete","summary":"조건 확인. 외부 전송 미실행."}""",emptySet()) is AutonomousReviewPolicy.Decision.Complete)
        for(extra in listOf("verified","ownerRequest","allowTools")) {
            assertThrows(IllegalArgumentException::class.java) {
                AutonomousReviewPolicy.parse("""{"type":"review_complete","summary":"완료","$extra":true}""",emptySet())
            }
        }
    }
    @Test fun nextActionMustUseExistingTypedRequestAuthority() {
        val call="""{"type":"review_next","summary":"현재 날짜를 재확인합니다.","call":{"type":"tool_call","tool":"get_current_datetime","arguments":{}}}"""
        assertTrue(AutonomousReviewPolicy.parse(call,setOf("get_current_datetime")) is AutonomousReviewPolicy.Decision.Next)
        assertThrows(IllegalArgumentException::class.java) {AutonomousReviewPolicy.parse(call,emptySet())}
        assertThrows(IllegalArgumentException::class.java) {AutonomousReviewPolicy.parse(call.replace("{}","{\"unexpected\":true}"),setOf("get_current_datetime"))}
    }
    @Test fun emptyOrHiddenOrMalformedReviewsCannotFinish() {
        for(summary in listOf("","<think>internal</think>")) assertThrows(IllegalArgumentException::class.java) {
            AutonomousReviewPolicy.parse(buildJsonObject {put("type","review_complete");put("summary",summary)}.toString(),emptySet())
        }
        assertThrows(IllegalStateException::class.java) {AutonomousReviewPolicy.parse("""{"type":"finish"}""",emptySet())}
    }
    @Test fun schemaContainsOnlyOfferedToolsAndExplicitReviewChoice() {
        val schema=AutonomousReviewPolicy.schema(setOf("get_current_datetime"))
        assertTrue(schema.contains("review_complete"));assertFalse(schema.contains("save_note"))
        assertEquals(1,Json.parseToJsonElement(AutonomousReviewPolicy.schema(emptySet())).jsonObject.getValue("oneOf").jsonArray.size)
    }
    @Test fun followupUsesTheSameHostDuplicateAndScopeGuards() {
        val args=buildJsonObject {put("title","합성 메모");put("content","합성 본문")}
        val call=ToolCall("synthetic-save","save_note",args)
        val goal=TaskGoal("합성 메모를 저장하고 확인해줘",listOf("저장 재조회"),requiredTools=setOf("save_note"))
        val prior=HarnessStep(call,StepEvidence(call.name,VerificationState.VERIFIED,"저장 재조회 확인"))
        val proposal=buildJsonObject {
            put("type","review_next");put("summary","추가 저장 제안")
            put("call",Json.parseToJsonElement(AutonomousReviewPolicy.toolJson(call)))
        }
        val parsed=AutonomousReviewPolicy.parse(proposal.toString(),setOf("save_note")) as AutonomousReviewPolicy.Decision.Next
        assertTrue(TaskHarness.beforeStep(goal,listOf(prior),parsed.call) is HarnessDecision.Stop)
        assertTrue(TaskHarness.beforeStep(TaskGoal("읽기만",listOf("조회")),emptyList(),parsed.call) is HarnessDecision.Stop)
    }
    @Test fun modelReviewCannotOverrideUnverifiedHostOutcomes() {
        val goal=TaskGoal("메모 저장",listOf("재조회"),requiredTools=setOf("save_note"))
        val step=HarnessStep("save_note",buildJsonObject{put("title","합성");put("content","본문")},StepEvidence("save_note",VerificationState.UNVERIFIED,"재조회 불가"))
        AutonomousReviewPolicy.parse("""{"type":"review_complete","summary":"저장이 완료됐다고 생각합니다."}""",emptySet())
        assertTrue(TaskHarness.decide(goal,listOf(step),requestedFinish=true) is HarnessDecision.Stop)
    }
    @Test fun publicFollowupSummaryAndTypedCallRoundTripWithoutOwnerMessages() {
        val raw="""{"type":"review_next","summary":"결과 시각과 기준일의 불일치를 확인합니다.","call":{"type":"tool_call","tool":"get_current_datetime","arguments":{}}}"""
        val parsed=AutonomousReviewPolicy.parse(raw,setOf("get_current_datetime")) as AutonomousReviewPolicy.Decision.Next
        assertEquals("결과 시각과 기준일의 불일치를 확인합니다.",parsed.summary)
        val call=ToolRegistry.parseAndValidate(AutonomousReviewPolicy.toolJson(parsed.call))
        assertEquals(parsed.call.name,call.name);assertEquals(parsed.call.arguments,call.arguments)
    }
}
