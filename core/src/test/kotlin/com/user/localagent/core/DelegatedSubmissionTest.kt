package com.user.localagent.core

import com.user.localagent.core.DelegatedAction.*
import org.junit.Assert.*
import org.junit.Test

class DelegatedSubmissionTest {
    private val scope=DelegationScope(setOf("https://kairb.org"),setOf(QUIZ_SUBMIT,SURVEY_SUBMIT))

    @Test fun permitsAllowedLabelsOnDelegatedOrigin(){
        assertTrue(DelegatedSubmission.permits(scope,"https://kairb.org/quiz/1","제출"))
        assertTrue(DelegatedSubmission.permits(scope,"https://kairb.org/survey","설문 제출하기"))
        assertTrue(DelegatedSubmission.permits(scope,"https://KAIRB.org:443/q","확인"))
    }
    @Test fun forbiddenLabelWins(){
        assertFalse(DelegatedSubmission.permits(scope,"https://kairb.org/pay","결제하고 제출"))
        for(l in listOf("전송","탈퇴","삭제"))assertFalse(l,DelegatedSubmission.permits(scope,"https://kairb.org/q",l))
    }
    @Test fun rejectsOtherOriginsAndSchemesAndNull(){
        assertFalse(DelegatedSubmission.permits(scope,"https://evil.example/quiz","제출"))
        assertFalse(DelegatedSubmission.permits(scope,"http://kairb.org/q","제출"))
        assertFalse(DelegatedSubmission.permits(scope,"https://kairb.org:8443/q","제출"))
        assertFalse(DelegatedSubmission.permits(null,"https://kairb.org/q","제출"))
        assertFalse(DelegatedSubmission.permits(scope,"https://kairb.org/q","취소"))
    }
    @Test fun constraintRoundTrip(){
        assertEquals(scope,DelegatedSubmission.fromConstraints(listOf("x",DelegatedSubmission.toConstraint(scope))))
    }
    @Test fun invalidConstraintsGiveNull(){
        val line=DelegatedSubmission.toConstraint(scope)
        assertNull(DelegatedSubmission.fromConstraints(listOf(line,line)))
        assertNull(DelegatedSubmission.fromConstraints(emptyList()))
        assertNull(DelegatedSubmission.fromConstraints(listOf(DelegatedSubmission.CONSTRAINT_PREFIX+"{bad")))
        assertNull(DelegatedSubmission.fromConstraints(listOf(DelegatedSubmission.CONSTRAINT_PREFIX+
            """{"origins":["http://kairb.org"],"actions":["quiz_submit"]}""")))
    }
    @Test fun actionsAreEnforced(){
        val lesson=DelegationScope(setOf("https://kairb.org"),setOf(LESSON_COMPLETE))
        assertTrue(DelegatedSubmission.permits(lesson,"https://kairb.org/q","다음"))
        assertFalse(DelegatedSubmission.permits(lesson,"https://kairb.org/q","제출"))
        assertFalse(DelegatedSubmission.permits(lesson,"https://kairb.org/q","완료 제출"))
        val quiz=DelegationScope(setOf("https://kairb.org"),setOf(QUIZ_SUBMIT))
        assertTrue(DelegatedSubmission.permits(quiz,"https://kairb.org/q","제출"))
        assertTrue(DelegatedSubmission.permits(quiz,"https://kairb.org/q","다음"))
    }
    @Test fun labelIsNormalizedAndFailsClosed(){
        val u="https://kairb.org/q"
        assertFalse(DelegatedSubmission.permits(scope,u,"결​제 제출"))
        assertFalse(DelegatedSubmission.permits(scope,u,"a".repeat(130)+"제출"))
        assertTrue(DelegatedSubmission.permits(scope,u,"ｓｕｂｍｉｔ"))
    }
    @Test fun lookalikeOriginsRejected(){
        for(u in listOf("https://kairb.org.evil.example/q","https://kairb.org@evil.example/q","https://kairb.org./q"))
            assertFalse(u,DelegatedSubmission.permits(scope,u,"제출"))
    }
    @Test fun scopeValidation(){
        assertThrows(IllegalArgumentException::class.java){DelegationScope(emptySet(),setOf(QUIZ_SUBMIT))}
        assertThrows(IllegalArgumentException::class.java){DelegationScope(setOf("https://kairb.org"),emptySet())}
        assertThrows(IllegalArgumentException::class.java){DelegationScope(setOf("https://kairb.org/x"),setOf(QUIZ_SUBMIT))}
        assertThrows(IllegalArgumentException::class.java){
            DelegationScope((1..5).map{"https://a$it.org"}.toSet(),setOf(QUIZ_SUBMIT))}
    }
    @org.junit.Test fun requiresTheActualAssessmentCategoryAndSameOriginAction() {
        val scope=DelegationScope(setOf("https://example.org"),setOf(DelegatedAction.QUIZ_SUBMIT))
        org.junit.Assert.assertTrue(DelegatedSubmission.permitsAssessmentForm(scope,
            "https://example.org/quiz/view","https://example.org/quiz/submit","Submit"))
        org.junit.Assert.assertFalse(DelegatedSubmission.permitsAssessmentForm(scope,
            "https://example.org/quiz/view","https://other.example/quiz/submit","Submit"))
        org.junit.Assert.assertFalse(DelegatedSubmission.permitsAssessmentForm(scope,
            "https://example.org/quiz/view","https://example.org/checkout","Submit"))
        org.junit.Assert.assertFalse(DelegatedSubmission.permitsAssessmentForm(scope,
            "https://example.org/survey/view","https://example.org/survey/submit","Submit"))
    }
}
