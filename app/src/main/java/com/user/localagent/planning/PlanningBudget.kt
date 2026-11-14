package com.user.localagent.planning

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlanningBudget(
    val totalMs: Long = 900_000,
    val loadMs: Long = 90_000,
    val tokenizeMs: Long = 90_000,
    val firstGenerationMs: Long = 420_000,
    val retryGenerationMs: Long = 300_000,
    val heartbeatMs: Long = 1_000,
)

internal class PlanningStageTimeout(val stage: String) : IllegalStateException()

/** Only this stage's own deadline becomes a recoverable failure. Parent cancellation propagates. */
internal suspend fun <T : Any> planningStage(
    stage: String, timeoutMs: Long, heartbeatMs: Long, progress: () -> Unit, block: suspend () -> T,
): T = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val heartbeat = launch { while (true) { delay(heartbeatMs); progress() } }
        try { block() } finally { heartbeat.cancel() }
    }
} ?: throw PlanningStageTimeout(stage)
