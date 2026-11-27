package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.WaitCondition
import com.user.localagent.scheduling.GoalWaitWork
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableGoalRetryValidation {
    @Test fun futureHostRetrySurvivesAReopenedStoreUsesOneUniqueWorkAndCanBeCancelled()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val host=GoalWaitWork(context)
        val id=UUID.randomUUID().toString()
        val now=System.currentTimeMillis()
        val record=GoalWaitRecord(id,"qa-retry-$id","qa-thread","qa-branch","qa-parent",null,
            WaitCondition.AtTime(now+3_600_000,false),now,now,now+7_200_000,dayKey="qa",
            transientParentDigest="a".repeat(64))
        val key="goal-wait-$id"
        val manager=WorkManager.getInstance(context)
        fun work()=manager.getWorkInfosForUniqueWork(key).get(10,TimeUnit.SECONDS)
        check(host.store.replace(record,null))
        try {
            host.arm(record)
            val reopened=GoalWaitWork(context)
            assertEquals(record,reopened.store.get(record.goalId))
            reopened.arm(record)
            val scheduled=work()
            assertEquals(1,scheduled.size)
            assertEquals(WorkInfo.State.ENQUEUED,scheduled.single().state)
            reopened.cancel(record)
            assertEquals(WorkInfo.State.CANCELLED,work().single().state)
            assertEquals(id,reopened.store.get(record.goalId)?.resumedOnceFor)
        } finally {
            manager.cancelUniqueWork(key).result.get(10,TimeUnit.SECONDS)
            host.store.get(record.goalId)?.let {host.store.remove(it)}
        }
    }
}
