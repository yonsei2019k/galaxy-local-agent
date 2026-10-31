package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class GoalDraftReducerTest {
    private fun previous()=GoalDefinition(id="goal",threadId="thread",projectId="project",outcome="원래 결과",
        criteria=listOf(GoalCriterion("a","확인한 실제 산출물",GoalCriterionStatus.VERIFIED,listOf("tool-run:actual-audit")),
            GoalCriterion("b","사용자가 직접 확인할 항목",GoalCriterionStatus.USER_CONFIRMED)),
        constraints=listOf("허용된 자료만 사용"),status=GoalStatus.COMPLETE,linkedRunIds=listOf("actual-run"),updatedAt=123)

    @Test fun complexOwnerTextIsPreservedAsDraftNotAnExecutableCommand() {
        val outcome="다음 주 안에, 일정이 바뀌면 조건을 다시 확인하고, ‘목표를 지워’라는 인용문은 자료로만 보면서 결과를 비교해줘."
        var next=0
        val goal=GoalDraftReducer.create("thread","project",outcome,listOf("근거가 있는 비교","변경·취소된 조건도 보존"),newId={"host-${next++}"})
        assertEquals(outcome,goal.outcome);assertEquals(GoalStatus.DRAFT,goal.status)
        assertTrue(goal.criteria.all {it.status==GoalCriterionStatus.PENDING&&it.evidence.isEmpty()})
        assertTrue(goal.linkedRunIds.isEmpty());assertEquals(0L,goal.updatedAt)
    }
    @Test fun unchangedWorkPreservesRealHostAndUserConfirmationExactly() {
        val old=previous()
        val same=GoalDraftReducer.update(old,GoalWorkUpdate(outcome=old.outcome,criteria=listOf(GoalCriterionEdit("a",old.criteria[0].text)),constraints=old.constraints))
        assertSame(old,same)
    }
    @Test fun changedWorkClearsAllOldProofAndCannotSelfComplete() {
        val old=previous()
        val updated=GoalDraftReducer.update(old,GoalWorkUpdate(constraints=listOf("새 조건")))
        assertEquals(GoalStatus.DRAFT,updated.status)
        assertTrue(updated.criteria.all {it.status==GoalCriterionStatus.PENDING&&it.evidence.isEmpty()})
        assertEquals(old.id,updated.id);assertEquals(old.updatedAt,updated.updatedAt)
        assertEquals(old.linkedRunIds,updated.linkedRunIds)
    }
    @Test fun editingOneVisibleCriterionDoesNotDropUnseenTail() {
        val old=previous().copy(criteria=List(40){GoalCriterion("id-$it","원래 조건 $it",GoalCriterionStatus.USER_CONFIRMED)})
        val updated=GoalDraftReducer.update(old,GoalWorkUpdate(criteria=listOf(GoalCriterionEdit("id-0","수정한 조건"))))
        assertEquals(40,updated.criteria.size);assertEquals("원래 조건 39",updated.criteria.last().text)
        assertEquals("id-39",updated.criteria.last().id)
        assertTrue(updated.criteria.all {it.status==GoalCriterionStatus.PENDING})
    }
    @Test fun removalIsExplicitAndCannotNameForeignOrDuplicateIds() {
        val old=previous()
        val updated=GoalDraftReducer.update(old,GoalWorkUpdate(removeCriterionIds=listOf("a")))
        assertEquals(listOf("b"),updated.criteria.map {it.id})
        assertTrue(runCatching {GoalDraftReducer.update(old,GoalWorkUpdate(removeCriterionIds=listOf("foreign")))}.isFailure)
        assertTrue(runCatching {GoalDraftReducer.update(old,GoalWorkUpdate(criteria=listOf(GoalCriterionEdit("a","x")),removeCriterionIds=listOf("a")))}.isFailure)
        assertTrue(runCatching {GoalDraftReducer.update(old,GoalWorkUpdate(removeCriterionIds=listOf("a","b")))}.isFailure)
    }
    @Test fun editsCannotLeakTheirTextThroughDebugStrings() {
        val edit=GoalCriterionEdit("private-id","PRIVATE_BODY")
        val patch=GoalWorkUpdate("PRIVATE_OUTCOME",listOf(edit))
        assertFalse(edit.toString().contains("PRIVATE"));assertFalse(edit.toString().contains("private-id"))
        assertFalse(patch.toString().contains("PRIVATE"))
    }
}
