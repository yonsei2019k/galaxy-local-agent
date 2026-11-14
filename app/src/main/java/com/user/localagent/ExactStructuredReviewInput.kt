package com.user.localagent

import com.user.localagent.models.InferenceModelTarget
import com.user.localagent.inference.loadTarget

import com.user.localagent.inference.GenerationEvent
import com.user.localagent.inference.GenerationStopReason
import com.user.localagent.inference.InferenceEngine
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Fixed public codes; input text and backend errors never become public messages. */
internal class ExactReviewInputPause(val code: Code, cause: Throwable? = null) :
    Exception(code.name, cause) {
    enum class Code {
        EXACT_REVIEW_NOT_APPLICABLE, EXACT_REVIEW_INPUT_INVALID, EXACT_REVIEW_INPUT_REUSED,
        EXACT_REVIEW_COUNT_UNAVAILABLE, EXACT_REVIEW_COUNT_INVALID, EXACT_REVIEW_COUNT_FAILED,
        EXACT_REVIEW_CONTEXT_LIMIT, EXACT_REVIEW_FINISH_UNCONFIRMED,
    }
}

/**
 * Immutable final transport input, not a permission or receipt. Capture AFTER all writer changes.
 * The existing request-owned engine/compute lease and caller authorization remain mandatory.
 */
internal class ExactStructuredReviewInput private constructor(
    val prompt: String,
    val system: String,
    history: List<InferenceTurn>,
    val schema: String,
    options: InferenceOptions,
) {
    val history: List<InferenceTurn> = Collections.unmodifiableList(history.map { it.copy() })
    val options: InferenceOptions = options.copy()
    private val consumed = AtomicBoolean(false)

    /** One collection, one exact count, at most one generation. No retry, clipping or cap reduction. */
    fun events(engine: InferenceEngine, modelPath: String): Flow<GenerationEvent> = eventsLoading(engine) { engine.load(modelPath, options) }

    fun events(engine: InferenceEngine, target: InferenceModelTarget): Flow<GenerationEvent> = eventsLoading(engine) { engine.loadTarget(target, options) }

    private fun eventsLoading(engine: InferenceEngine, load: suspend () -> Unit): Flow<GenerationEvent> = flow {
        if (!consumed.compareAndSet(false, true)) pause(ExactReviewInputPause.Code.EXACT_REVIEW_INPUT_REUSED)
        currentCoroutineContext().ensureActive()
        if (!engine.supportsStructuredOutput) pause(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_UNAVAILABLE)
        load()
        currentCoroutineContext().ensureActive()
        val count = try {
            engine.countStructuredChatTokens(prompt, system, schema, history)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw ExactReviewInputPause(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_FAILED, error)
        }
        currentCoroutineContext().ensureActive()
        if (count == null) pause(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_UNAVAILABLE)
        if (count < 0) pause(ExactReviewInputPause.Code.EXACT_REVIEW_COUNT_INVALID)
        if (count.toLong() + options.maxOutputTokens + RESERVED_TOKENS > options.contextSize) {
            pause(ExactReviewInputPause.Code.EXACT_REVIEW_CONTEXT_LIMIT)
        }
        currentCoroutineContext().ensureActive()
        var finished = false
        engine.generateStructuredEvents(prompt, system, schema, history).collect { event ->
            currentCoroutineContext().ensureActive()
            if (finished) pause(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED)
            if (event is GenerationEvent.Finished) {
                val summary = event.summary
                if (summary.reason != GenerationStopReason.EOG || summary.capReached != false ||
                    summary.configuredMaxOutputTokens != options.maxOutputTokens) {
                    pause(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED)
                }
                finished = true
            }
            emit(event)
        }
        if (!finished) pause(ExactReviewInputPause.Code.EXACT_REVIEW_FINISH_UNCONFIRMED)
    }

    companion object {
        const val RESERVED_TOKENS = 64

        fun capture(
            prompt: String, system: String, history: List<InferenceTurn>, schema: String,
            options: InferenceOptions,
        ): ExactStructuredReviewInput {
            if (prompt.isBlank() || schema.isBlank() || options.contextSize !in 128..131072 ||
                options.maxOutputTokens !in 1..4096 || history.any { it.role !in setOf("user", "assistant") }) {
                pause(ExactReviewInputPause.Code.EXACT_REVIEW_INPUT_INVALID)
            }
            return ExactStructuredReviewInput(prompt, system, history, schema, options)
        }

        fun requireApplicable(
            enabled: Boolean, allowCloudText: Boolean, structuredSchema: String?, display: Boolean,
            continuing: Boolean,
        ) {
            if (enabled && (allowCloudText || structuredSchema == null || display || continuing)) {
                pause(ExactReviewInputPause.Code.EXACT_REVIEW_NOT_APPLICABLE)
            }
        }

        private fun pause(code: ExactReviewInputPause.Code): Nothing = throw ExactReviewInputPause(code)
    }
}
