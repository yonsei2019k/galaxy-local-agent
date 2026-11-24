package com.user.localagent.scheduling

import android.content.Context
import androidx.work.*
import com.user.localagent.LocalAgentApplication
import com.user.localagent.connections.ConnectionStore
import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.WaitCondition
import java.util.concurrent.TimeUnit

internal class GoalWaitWork(private val context: Context) {
    private val encrypted = ConnectionStore(context)
    val store = GoalWaitStore({ encrypted.read("goal.wait.v1") }, { encrypted.write("goal.wait.v1", it) })

    suspend fun arm(record: GoalWaitRecord) {
        val condition = record.condition as? WaitCondition.AtTime ?: return
        val request = OneTimeWorkRequestBuilder<GoalWaitWorker>()
            .setInputData(workDataOf("goalId" to record.goalId, "waitId" to record.id))
            .setInitialDelay((condition.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        if (condition.needsPlayback) request.setConstraints(Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
        WorkManager.getInstance(context).enqueueUniqueWork("goal-wait-"+record.id, ExistingWorkPolicy.KEEP, request.build()).await()
    }

    suspend fun cancel(record: GoalWaitRecord) {
        store.replace(record.copy(resumedOnceFor=record.id),record)
        WorkManager.getInstance(context).cancelUniqueWork("goal-wait-"+record.id).await()
    }

    suspend fun restore() {
        for (record in store.all()) if (record.condition is WaitCondition.AtTime) arm(record)
    }
}

class GoalWaitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = (applicationContext as LocalAgentApplication).container
        val goalId = inputData.getString("goalId") ?: return Result.failure()
        val record = app.goalWaits.store.get(goalId) ?: return Result.success()
        if (record.id != inputData.getString("waitId")) return Result.success()
        return if (app.runs.wakeTimedGoal(record)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
