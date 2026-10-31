package com.user.localagent.callfollowup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.CancellationException

class CallFollowUpProcessWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("queueId") ?: return Result.failure()
        if (!id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) return Result.failure()
        val epoch = inputData.getLong("epoch", -1)
        if (epoch <= 0) return Result.failure()
        val pipeline = (applicationContext as LocalAgentApplication).container.callFollowUpPipeline
        return try {
            when (pipeline.processBatch(id, epoch)) {
                CallPipelineBatchResult.MORE -> pipeline.enqueueNext(id, epoch, false)
                CallPipelineBatchResult.DEFERRED -> pipeline.enqueueNext(id, epoch, true)
                CallPipelineBatchResult.STOPPED -> Unit
            }
            Result.success()
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
