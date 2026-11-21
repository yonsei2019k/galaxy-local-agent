package com.user.localagent.analysis

import android.content.Context
import androidx.work.*
import com.user.localagent.FailureLog
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/** Rebuilds the derived search index from sealed local analysis journals in short transactions. */
class AnalysisMemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LocalAgentApplication
        return try {
            var cursor = inputData.getString(CURSOR).orEmpty()
            repeat(16) {
                cursor = app.container.repository.indexAnalysisMemoryPage(cursor) ?: return Result.success()
                yield()
            }
            enqueue(applicationContext, cursor)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FailureLog.record(applicationContext, "analysis_memory_index", failure)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val CURSOR = "cursor"
        internal fun enqueue(context: Context, cursor: String = "") {
            val request = OneTimeWorkRequestBuilder<AnalysisMemoryWorker>()
                .setInputData(workDataOf(CURSOR to cursor)).build()
            if (cursor.isBlank()) {
                runCatching { submit(context, request) }
                    .onFailure { FailureLog.record(context, "analysis_memory_schedule", it) }
            } else {
                submit(context, request)
            }
        }

        private fun submit(context: Context, request: OneTimeWorkRequest) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "analysis-memory-index", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
