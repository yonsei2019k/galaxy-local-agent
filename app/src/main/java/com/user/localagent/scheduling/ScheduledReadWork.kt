package com.user.localagent.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.user.localagent.AppContainer
import com.user.localagent.FailureLog
import com.user.localagent.LocalAgentApplication
import com.user.localagent.core.ScheduledReadPolicy
import com.user.localagent.core.ScheduledReadRequest
import com.user.localagent.core.ScheduledReadToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ScheduledReadWork(private val context: Context, private val app: AppContainer) {
    private val recovery = ScheduledReadRecovery(
        isScheduled = { id -> app.repository.chat.run(id)?.status == ScheduledReadPolicy.SCHEDULED },
        arm = ::arm,
    )

    suspend fun execute(call: ToolCall, registrationRunId: String): ToolResult = withContext(Dispatchers.IO) {
        fun text(key: String) = call.arguments.getValue(key).jsonPrimitive.content
        when (call.name) {
            ScheduledReadToolCatalog.CREATE -> {
                val record = app.repository.chat.scheduleRead(registrationRunId, text("title").trim(), text("prompt"),
                    OffsetDateTime.parse(text("datetime")).toInstant().toEpochMilli(), text("readTool"),
                    ScheduledReadToolCatalog.clockReceiptOnly(call))
                if (record.status == ScheduledReadPolicy.SCHEDULED) {
                    arm(record.id, OffsetDateTime.parse(text("datetime")).toInstant().toEpochMilli())
                    receipt(call, record.id, record.status, "한 번의 기기 내 조회 등록을 확인했습니다. 실행 결과는 원래 대화에서 확인할 수 있습니다.")
                } else {
                    receipt(call, record.id, record.status, "기존 예약의 현재 상태를 확인했습니다.")
                }
            }
            ScheduledReadToolCatalog.CANCEL -> {
                val id = text("scheduleId")
                val record = recovery.cancel { app.scopedRuns.cancelScheduledRead(registrationRunId, id) }
                scheduledReadCancellationReceipt(
                    call, id, record.status, record.errorCode == ScheduledReadPolicy.STOP_REQUESTED,
                    cancelSystemWork = { WorkManager.getInstance(context).cancelUniqueWork(id).await() },
                    onUnconfirmed = { error ->
                        FailureLog.record(context, "scheduled_read_cancel", error, "system_cleanup_unconfirmed")
                    },
                )
            }
            else -> error("지원하지 않는 읽기 예약 작업입니다.")
        }
    }

    suspend fun restore() {
        restorePendingScheduledReads(app.repository.chat.pendingScheduledReads(), recovery::rearm) { error ->
            FailureLog.record(context, "scheduled_read_restore", error, "schedule_rearm_failed")
        }
    }

    private suspend fun arm(id: String, time: Long) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<ScheduledReadWorker>()
            .setInputData(workDataOf(INPUT_ID to id))
            .setInitialDelay((time - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request).result
            .get(5, TimeUnit.SECONDS)
        Unit
    }

    private fun receipt(call: ToolCall, id: String, status: String, summary: String) = ToolResult(
        call.id, true, buildJsonObject {
            put("scheduleId", id); put("status", status); put("runResultVerified", false)
        }.toString(), VerificationState.VERIFIED, summary,
    )

    companion object { const val INPUT_ID = "scheduledReadId" }
}

class ScheduledReadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ScheduledReadWork.INPUT_ID)?.takeIf(ScheduledReadPolicy::isId) ?: return Result.failure()
        val app = (applicationContext as? LocalAgentApplication)?.container ?: return Result.failure()
        return try {
            if (app.scopedRuns.deliverScheduledRead(id)) Result.retry() else {
                app.repository.chat.awaitScheduledRead(id)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {runCatching {app.scopedRuns.interruptScheduledRead(id)}}
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

internal suspend fun restorePendingScheduledReads(
    reservations: Iterable<Pair<String, ScheduledReadRequest>>,
    arm: suspend (String, Long) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    for ((id, plan) in reservations) {
        try {
            arm(id, plan.atEpochMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}

internal class ScheduledReadRecovery(
    private val isScheduled: suspend (String) -> Boolean,
    private val arm: suspend (String, Long) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun rearm(id: String, time: Long) = mutex.withLock {
        // Cancellation can commit after startup takes its pending snapshot.
        if (isScheduled(id)) arm(id, time)
    }

    suspend fun <T> cancel(action: suspend () -> T): T = mutex.withLock { action() }
}


internal suspend fun scheduledReadCancellationReceipt(
    call: ToolCall,
    id: String,
    status: String,
    stopRequested: Boolean,
    cancelSystemWork: suspend () -> Unit,
    onUnconfirmed: (Exception?) -> Unit,
    timeoutMillis: Long = 5_000L,
): ToolResult {
    val stopping = stopRequested && status in setOf("running", "waiting_approval")
    val cleanup = if (status == "cancelled" || stopping) {
        val confirmed = try {
            val finished = withTimeoutOrNull(timeoutMillis) {
                cancelSystemWork()
                true
            } == true
            if (!finished) onUnconfirmed(null)
            finished
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onUnconfirmed(failure)
            false
        }
        if (confirmed) "confirmed" else "unconfirmed"
    } else "not_requested"
    val summary = when {
        stopping -> "진행 중인 실행에 중지를 요청했습니다. 최종 상태는 원래 대화에서 확인할 수 있습니다."
        status == "cancelled" -> "해당 예약이 취소된 상태를 확인했습니다."
        else -> "이미 끝난 예약입니다. 기존 결과와 상태를 유지했습니다."
    } + if (cleanup == "unconfirmed") " Android 예약 작업 정리는 아직 확인하지 못했습니다." else ""
    return ToolResult(
        call.id, true, buildJsonObject {
            put("scheduleId", id)
            put("status", if (stopping) "stop_requested" else status)
            put("runResultVerified", false)
            put("systemCleanup", cleanup)
        }.toString(), VerificationState.VERIFIED, summary,
    )
}
