package com.user.localagent.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.user.localagent.AppContainer
import com.user.localagent.LocalAgentApplication
import com.user.localagent.core.ScheduledReadPolicy
import com.user.localagent.core.ScheduledReadToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ScheduledReadWork(private val context: Context, private val app: AppContainer) {
    suspend fun execute(call: ToolCall, registrationRunId: String): ToolResult = withContext(Dispatchers.IO) {
        fun text(key: String) = call.arguments.getValue(key).jsonPrimitive.content
        when (call.name) {
            ScheduledReadToolCatalog.CREATE -> {
                val record = app.repository.chat.scheduleRead(registrationRunId, text("title").trim(), text("prompt"),
                    OffsetDateTime.parse(text("datetime")).toInstant().toEpochMilli(), text("readTool"))
                if (record.status == ScheduledReadPolicy.SCHEDULED)
                    arm(record.id, OffsetDateTime.parse(text("datetime")).toInstant().toEpochMilli())
                receipt(call, record.id, record.status, "한 번의 기기 내 조회 등록을 확인했습니다. 실행 결과는 원래 대화에서 확인할 수 있습니다.")
            }
            ScheduledReadToolCatalog.CANCEL -> {
                val id = text("scheduleId")
                val record = app.runs.cancelScheduledRead(registrationRunId, id)
                WorkManager.getInstance(context).cancelUniqueWork(id)
                val stopping = record.status == "running" && record.errorCode == ScheduledReadPolicy.STOP_REQUESTED
                receipt(call, id, if (stopping) "stop_requested" else record.status, if (stopping)
                    "해당 실행에 중단을 요청했습니다. 확인된 결과는 원래 대화에서 확인할 수 있습니다." else
                    "해당 예약의 현재 상태를 확인했습니다.")
            }
            else -> error("지원하지 않는 읽기 예약 작업입니다.")
        }
    }

    suspend fun restore() {
        for ((id, plan) in app.repository.chat.pendingScheduledReads()) {
            arm(id, plan.atEpochMillis)
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
            if (app.runs.deliverScheduledRead(id)) Result.retry() else {
                app.repository.chat.awaitScheduledRead(id)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {runCatching {app.runs.interruptScheduledRead(id)}}
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
