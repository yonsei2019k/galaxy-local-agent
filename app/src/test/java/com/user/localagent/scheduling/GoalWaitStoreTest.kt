package com.user.localagent.scheduling

import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.WaitCondition
import org.junit.Assert.*
import org.junit.Test

class GoalWaitStoreTest {
    private val first = GoalWaitRecord("wait-1","goal","thread","branch","parent",7,
        WaitCondition.AtTime(50_000,false),1,1,60_000,dayKey="2026-11-24")

    @Test fun sameGoalReplacementUsesCompareAndSetAcrossStoreInstances() {
        var data: String? = null
        fun store() = GoalWaitStore({ data },{ data=it })
        assertTrue(store().replace(first,null))
        assertEquals(first,store().get(first.goalId))
        val next = first.copy(id="wait-2")
        assertTrue(store().replace(next,first))
        assertFalse(store().replace(first,first))
        assertEquals(listOf(next),store().all())
    }

    @Test fun consumedWakeSurvivesProcessRecreation() {
        var data: String? = null
        val store = GoalWaitStore({ data },{ data=it })
        assertTrue(store.replace(first,null))
        val consumed = first.copy(resumedOnceFor=first.id,wakesTotal=1)
        assertTrue(store.replace(consumed,first))
        assertEquals(consumed,GoalWaitStore({ data },{ fail() }).get(first.goalId))
    }
}
