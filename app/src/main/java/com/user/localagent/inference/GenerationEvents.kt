package com.user.localagent.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A completed stream is not necessarily a completed answer. */
enum class GenerationStopReason {
    EOG, MAX_OUTPUT, CANCELLED, CONTEXT_REJECTED, RESOURCE_LIMIT, ENGINE_ERROR, SDK_DONE_UNKNOWN,
}

enum class GenerationEvidence { NATIVE, SDK, HOST, INFERRED }

data class GenerationSummary(
    val requestId: String,
    val reason: GenerationStopReason,
    val evidence: GenerationEvidence,
    /** Null means the runtime could not establish whether the configured cap was reached. */
    val capReached: Boolean?,
    /** Zero means an older adapter did not expose its configured cap. */
    val configuredMaxOutputTokens: Int,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val visibleChars: Int = 0,
    val metrics: InferenceMetrics? = null,
    val historyTurnsDropped: Int = 0,
    val errorCode: String? = null,
    val elapsedMillis: Long? = null,
    val thinkingTokenBudget: Int? = null,
    val observedTimeToFirstVisibleTokenSeconds: Double? = null,
) {
    val timeToFirstVisibleTokenSeconds: Double? get() = if (visibleChars <= 0) null else
        observedTimeToFirstVisibleTokenSeconds ?: if (evidence == GenerationEvidence.NATIVE) metrics?.visibleTimeToFirstTokenSeconds else null
    /** SDK decode counts can suggest a cap, but never prove a native EOG. */
    val mayBeTruncated: Boolean get() = reason == GenerationStopReason.MAX_OUTPUT || capReached == true
    /** Uncertain SDK completion permits an explicit text continuation, never automatic repetition. */
    val requiresManualContinuation: Boolean get() = reason == GenerationStopReason.SDK_DONE_UNKNOWN && capReached != true
}

sealed interface GenerationEvent {
    data class Delta(val text: String) : GenerationEvent
    data class Finished(val summary: GenerationSummary) : GenerationEvent
}

internal val unavailableGeneration: StateFlow<GenerationSummary?> = MutableStateFlow(null)

/** Shared by both native adapters: even a finish/cancel race records one terminal only. */
internal class GenerationTerminal(private val publish: (GenerationSummary) -> Unit) {
    private val finished = AtomicBoolean(false)
    fun finish(summary: GenerationSummary): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        publish(summary)
        return true
    }
}

internal fun thinkingBudgetFor(enabled: Boolean, maxOutputTokens: Int): Int {
    require(maxOutputTokens > 0)
    return if (enabled) minOf(256, maxOutputTokens / 2) else 0
}

/** Kept for third-party/older adapters. The concrete local engines report richer native evidence. */
internal fun legacyGenerationEvents(source: Flow<String>): Flow<GenerationEvent> = flow {
    val requestId = UUID.randomUUID().toString()
    var visibleChars = 0
    var sourceFailure: Throwable? = null
    source.catch { error ->
        // A cancelled collector cannot receive another event; local engines also persist lastGeneration.
        if (error is CancellationException) throw error
        sourceFailure = error
    }.collect { text -> visibleChars += text.length; emit(GenerationEvent.Delta(text)) }
    sourceFailure?.let { error ->
        emit(GenerationEvent.Finished(GenerationSummary(
            requestId, GenerationStopReason.ENGINE_ERROR, GenerationEvidence.HOST, null, 0,
            visibleChars = visibleChars, errorCode = "LEGACY_ENGINE_ERROR",
        )))
        throw error
    }
    emit(GenerationEvent.Finished(GenerationSummary(
        requestId, GenerationStopReason.SDK_DONE_UNKNOWN, GenerationEvidence.SDK, null, 0,
        visibleChars = visibleChars,
    )))
}

internal fun Flow<GenerationEvent>.textDeltas(): Flow<String> = mapNotNull { (it as? GenerationEvent.Delta)?.text }

/** Decodes only measured values. A missing visible TTFT does not discard actual token counts. */
internal fun measuredInference(values: DoubleArray): InferenceMetrics? {
    if (values.size != 5 || values.drop(1).any { !it.isFinite() || it < 0.0 } || !values[0].isFinite()) return null
    if (values[1] > Int.MAX_VALUE || values[2] > Int.MAX_VALUE) return null
    if (values[1] != values[1].toInt().toDouble() || values[2] != values[2].toInt().toDouble()) return null
    return InferenceMetrics(values[0].takeIf { it >= 0.0 } ?: -1.0,
        values[1].toInt(), values[2].toInt(), values[3], values[4])
}

internal fun nativeStopReason(code: Int): GenerationStopReason = when (code) {
    1 -> GenerationStopReason.EOG
    2 -> GenerationStopReason.MAX_OUTPUT
    else -> error("Unknown native generation stop code")
}

internal fun sdkGenerationSummary(
    requestId: String, cap: Int, metrics: InferenceMetrics?, visibleChars: Int,
    elapsedMillis: Long, thinkingTokenBudget: Int, visibleTtftSeconds: Double? = null,
): GenerationSummary {
    val capReached = metrics?.decodeTokens?.let { it >= cap }
    return GenerationSummary(requestId, GenerationStopReason.SDK_DONE_UNKNOWN,
        if (capReached == true) GenerationEvidence.INFERRED else GenerationEvidence.SDK,
        capReached, cap, metrics?.prefillTokens, metrics?.decodeTokens, visibleChars, metrics,
        elapsedMillis = elapsedMillis, thinkingTokenBudget = thinkingTokenBudget,
        observedTimeToFirstVisibleTokenSeconds = visibleTtftSeconds)
}

internal fun failureStopReason(error: Throwable, resourcePressure: Boolean = false): GenerationStopReason = when {
    resourcePressure -> GenerationStopReason.RESOURCE_LIMIT
    error is CancellationException -> GenerationStopReason.CANCELLED
    error is IllegalArgumentException && error.message.orEmpty().contains("context", ignoreCase = true) -> GenerationStopReason.CONTEXT_REJECTED
    else -> GenerationStopReason.ENGINE_ERROR
}
