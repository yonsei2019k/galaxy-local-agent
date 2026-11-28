package com.user.localagent.scheduling

import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.GoalWaitPolicy
import com.user.localagent.core.GoalWaitStopReason
import com.user.localagent.core.WakeEvent
import com.user.localagent.core.WaitDecision
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

    @Test fun corruptArchivedDataCannotBeOverwrittenByANewWait() {
        val store = GoalWaitStore({ "broken" },{ fail("existing waits were overwritten") })
        assertThrows(IllegalArgumentException::class.java) { store.replace(first,null) }
    }

    @Test fun consumedWakeSurvivesProcessRecreation() {
        var data: String? = null
        val store = GoalWaitStore({ data },{ data=it })
        assertTrue(store.replace(first,null))
        val consumed = first.copy(resumedOnceFor=first.id,wakesTotal=1)
        assertTrue(store.replace(consumed,first))
        assertEquals(consumed,GoalWaitStore({ data },{ fail() }).get(first.goalId))
    }

    @Test fun capacityReclaimsOnlyTheExactObsoleteRecord() {
        var data: String? = null
        val store = GoalWaitStore({ data },{ data=it })
        val records=(1..256).map {first.copy(id="wait-$it",goalId="goal-$it")}
        records.forEach {assertTrue(store.replace(it,null))}
        val stale=records.first()
        val current=stale.copy(id="new-wait")
        assertTrue(store.replace(current,stale))
        assertFalse(store.replace(first,null,setOf(stale)))
        assertTrue(store.replace(first,null,setOf(records.last())))
        assertEquals(current,store.get(current.goalId))
        assertEquals(first,store.get(first.goalId))
        assertNull(store.get(records.last().goalId))
    }

    @Test fun aRecordedStopSurvivesRestartAndCannotTurnIntoAResume() {
        var data:String?=null
        val store=GoalWaitStore({data},{data=it})
        assertTrue(store.replace(first,null))
        val stopping=first.copy(stopReason=GoalWaitStopReason.AUTH_EXPIRED,retryAt=50_000)
        assertTrue(store.replace(stopping,first))
        val reopened=GoalWaitStore({data},{data=it})
        val saved=checkNotNull(reopened.get(first.goalId))
        assertEquals(stopping,saved)
        assertEquals(0L,GoalWaitPolicy.dueAt(saved))
        assertEquals(WaitDecision.Stop("wait_auth_expired"),
            GoalWaitPolicy.onWake(saved,WakeEvent.OWNER_AUTH_DONE,1000,java.time.ZoneOffset.UTC,true))
        assertTrue(reopened.replace(saved.copy(resumedOnceFor=saved.id),saved))
        assertNull(GoalWaitPolicy.dueAt(checkNotNull(reopened.get(first.goalId))))
    }
}
