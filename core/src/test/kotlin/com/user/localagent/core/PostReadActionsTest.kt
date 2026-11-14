package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PostReadActionsTest {
    private val offered=setOf("search_contacts","prepare_sms")
    private val plan=ModelDecisionContract.parse("""{"action":"TOOL_TASK","goal":"조회 후 문자 초안 준비","targetType":"PERSONAL_RECORD","requiredTools":["search_contacts"],"constraints":["실제 발송 금지"]}""",offered)
    private fun steps(state:VerificationState)=listOf(HarnessStep("search_contacts",buildJsonObject{put("query","QA")},StepEvidence("search_contacts",state,"조회 결과")))
    @Test fun onlyAnActuallyVerifiedReadExposesItsRelatedOfferedAction(){
        assertEquals(setOf("prepare_sms"),PostReadActions.offered(plan,steps(VerificationState.VERIFIED),offered))
        assertTrue(PostReadActions.offered(plan,steps(VerificationState.FAILED),offered).isEmpty())
        assertTrue(PostReadActions.offered(plan,emptyList(),offered).isEmpty())
    }
    @Test fun oldReadOnlyOrDisabledScopesCannotGainTheAction(){
        assertTrue(PostReadActions.offered(plan,steps(VerificationState.VERIFIED),setOf("search_contacts")).isEmpty())
        assertTrue(runCatching{PostReadActions.bind(plan,steps(VerificationState.VERIFIED),"prepare_sms",setOf("search_contacts"))}.isFailure)
    }
    @Test fun modelSelectedFollowupPreservesTheOwnerGoalConstraintsAndPrivacy(){
        val bound=PostReadActions.bind(plan,steps(VerificationState.VERIFIED),"prepare_sms",offered)
        assertEquals(plan.goal,bound.goal);assertEquals(plan.constraints,bound.constraints);assertEquals(plan.sourceKind,bound.sourceKind)
        assertEquals(plan.usesPrivateData,bound.usesPrivateData);assertEquals(offered,bound.requiredTools)
    }
    @Test fun unrelatedToolsAndUntrustedNewPermissionsAreRejected(){
        assertTrue(runCatching{PostReadActions.bind(plan,steps(VerificationState.VERIFIED),"send_gmail_message",offered+"send_gmail_message")}.isFailure)
        assertTrue(PostReadActions.offered(plan.copy(action=SemanticAction.ANSWER),steps(VerificationState.VERIFIED),offered).isEmpty())
    }
    @Test fun reviewCanAskAnOwnerQuestionWithoutExecutingAnything(){
        val schema=NextActionQuestion.extend(AutonomousReviewPolicy.schema(setOf("search_contacts")))
        assertEquals(3,Json.parseToJsonElement(schema).jsonObject.getValue("oneOf").jsonArray.size)
        assertEquals("어느 연락처인가요?",NextActionQuestion.parse("""{"type":"clarification","question":"어느 연락처인가요?"}"""))
        assertFalse(schema.contains("prepare_sms"))
    }
}
