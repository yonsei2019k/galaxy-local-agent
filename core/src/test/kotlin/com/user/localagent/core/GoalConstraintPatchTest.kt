package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class GoalConstraintPatchTest {
    private fun previous()=GoalDefinition(id="goal",threadId="thread",outcome="기존 결과",projectId="project",
        criteria=listOf(GoalCriterion("criterion","확인 기준",GoalCriterionStatus.VERIFIED,listOf("actual-audit"))),
        constraints=List(17){"원래 조건 $it"},status=GoalStatus.COMPLETE,linkedRunIds=listOf("actual-run"),updatedAt=12).validate()

    @Test fun seventeenthLegacyConstraintIsEditedWithoutDroppingAnyOtherCondition(){
        val old=previous()
        val result=GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(16 to "바꾼 마지막 조건")))
        assertEquals(17,result.constraints.size)
        assertEquals(old.constraints.take(16),result.constraints.take(16))
        assertEquals("바꾼 마지막 조건",result.constraints[16])
        assertEquals(GoalStatus.DRAFT,result.status)
        assertTrue(result.criteria.all{it.status==GoalCriterionStatus.PENDING&&it.evidence.isEmpty()})
        assertEquals(old.linkedRunIds,result.linkedRunIds);assertEquals(old.updatedAt,result.updatedAt)
        assertEquals("원래 조건 16",old.constraints[16])
    }

    @Test fun unchangedLegacyConstraintPatchPreservesHostProofAndOriginalInstance(){
        val old=previous()
        assertSame(old,GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(16 to old.constraints[16]))))
    }

    @Test fun actualIndicesAndEightFieldBudgetAreEnforced(){
        val old=previous()
        for(index in listOf(-1,17,12000))assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(index to "다른 조건")))}.isFailure)
        assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=(0..8).associateWith{"다른 조건 $it"}))}.isFailure)
        val result=GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=(0..7).associateWith{"다른 조건 $it"}))
        assertEquals(old.constraints.drop(8),result.constraints.drop(8))
    }

    @Test fun typedPartialEditsCannotMixWithWholeConstraintReplacement(){
        val old=previous()
        for(full in listOf(emptyList(),listOf("전체 새 조건")))
            assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraints=full,constraintEdits=mapOf(16 to "부분 새 조건")))}.isFailure)
        assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraints=List(17){"새 조건 $it"}))}.isFailure)
        assertTrue(runCatching{GoalDraftReducer.create("thread",null,"새 결과",listOf("기준"),List(17){"새 조건 $it"})}.isFailure)
    }

    @Test fun partialFieldResultPreservesLongUnreadPrefixInsteadOfUsingTheFragmentAsAWholeField(){
        val conditions=previous().constraints.toMutableList().also{it[16]="가".repeat(2001)+"마지막 문장"}
        val old=previous().copy(constraints=conditions)
        val fullEdited=old.constraints[16].replace("마지막 문장","수정한 문장")
        val result=GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(16 to fullEdited)))
        assertEquals("가".repeat(2001)+"수정한 문장",result.constraints[16])
        assertEquals(17,result.constraints.size)
    }

    @Test fun invalidConditionAndPrivateDebugStringsRemainGuarded(){
        val old=previous()
        assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(16 to "")))}.isFailure)
        assertTrue(runCatching{GoalDraftReducer.update(old,GoalWorkUpdate(constraintEdits=mapOf(16 to "a\u0000b")))}.isFailure)
        assertFalse(GoalWorkUpdate(constraintEdits=mapOf(16 to "PRIVATE_BODY")).toString().contains("PRIVATE_BODY"))
    }
}
