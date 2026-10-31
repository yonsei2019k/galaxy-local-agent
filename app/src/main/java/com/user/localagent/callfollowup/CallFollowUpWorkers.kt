package com.user.localagent.callfollowup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CallRecordingReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val controller = (applicationContext as LocalAgentApplication).container.callFollowUp
        if (controller.reconcile() && runAttemptCount < 5) Result.retry() else Result.success()
    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); if (runAttemptCount < 5) Result.retry() else Result.failure()
    } catch (cancel: CancellationException) { throw cancel
    } catch (_: SecurityException) { Result.success()
    } catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
}

class CallRecordingPrepareWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("sourceKey") ?: return Result.failure()
        val generation = inputData.getLong("generation", -1)
        if (!key.matches(Regex("[a-f0-9]{64}")) || generation < 0) return Result.failure()
        val controller = (applicationContext as LocalAgentApplication).container.callFollowUp
        return try {
            when (controller.prepare(key, generation)) {
                PreparationResult.DONE -> Result.success()
                PreparationResult.NEEDS_REVIEW -> Result.failure()
                PreparationResult.RETRY, PreparationResult.DEFERRED -> if (runAttemptCount < 5) Result.retry() else Result.success()
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            controller.preparationFailed(key, generation)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            controller.preparationFailed(key, generation)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
