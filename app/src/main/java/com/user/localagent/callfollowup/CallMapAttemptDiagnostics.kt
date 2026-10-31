package com.user.localagent.callfollowup

import android.content.Context
import android.os.SystemClock
import com.user.localagent.data.DraftStore
import com.user.localagent.inference.GenerationSummary
import com.user.localagent.inference.NativeGenerationPhase
import com.user.localagent.inference.NativeGenerationProgress
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal enum class CallMapPhase { LOAD, COUNT, GENERATE, VALIDATE, SUCCEEDED, FAILED, CANCELLED }
@Serializable internal enum class CallMapValidationCategory { NOT_RUN, PASSED, SHAPE_OR_EXACT_SOURCE_REJECTED, INPUT_BUDGET,
    OUTPUT_INCOMPLETE, OUTPUT_LIMIT, ENGINE_FAILURE, TIME_LIMIT_OR_CANCELLED, SOURCE_OR_SCOPE_CHANGED }

internal object CallMapGenerationProof {
    fun current(generationEntered: Boolean, previousRequestId: String?, observed: GenerationSummary?, engineLast: GenerationSummary?): GenerationSummary? =
        if (!generationEntered) null else observed ?: engineLast?.takeIf { it.requestId != previousRequestId }
}

/** A generation attempt binds once to a new host request; a prior repair's snapshot is never evidence. */
internal class CallMapNativeProgressGate(private val excludedRequestIds: Set<String>) {
    private var requestId: String? = null
    @Synchronized fun accept(progress: NativeGenerationProgress?): NativeGenerationProgress? {
        if (progress == null || progress.requestId in excludedRequestIds) return null
        if (requestId == null) requestId = progress.requestId
        return progress.takeIf { it.requestId == requestId }
    }
}

/** Metadata only; there is deliberately no prompt, response text, path, URI, quote or exception message. */
@Serializable internal data class CallMapAttemptDiagnostics(
    val attemptId: String, val queueId: String, val mapIndex: Int, val queueEpoch: Long, val sequence: Int,
    val phase: CallMapPhase, val startedAtMillis: Long, val phaseStartedAtMillis: Long, val updatedAtMillis: Long,
    val lastProgressAtMillis: Long, val elapsedMillis: Long, val outputCharacters: Int = 0,
    val inputTokenCount: Int? = null, val inputCountKind: String = "UNAVAILABLE",
    val prefillTokens: Int? = null, val decodeTokens: Int? = null,
    val stopReason: String? = null, val stopEvidence: String? = null, val capReached: Boolean? = null,
    val validation: CallMapValidationCategory = CallMapValidationCategory.NOT_RUN,
    val modelSha256: String, val schemaSha256: String, val systemSha256: String,
    val nativeRequestId: String? = null, val nativePhase: String? = null,
    val nativePromptProcessed: Int? = null, val nativePromptTotal: Int? = null, val nativeSampledTokens: Int? = null,
    val nativeRawUtf8Bytes: Long? = null, val nativeVisibleUtf8Bytes: Long? = null, val nativeElapsedMillis: Long? = null,
) {
    val terminal get() = phase in setOf(CallMapPhase.SUCCEEDED, CallMapPhase.FAILED, CallMapPhase.CANCELLED)
}

/** Immutable per-attempt event rows; a small index makes latest progress readable during generation. */
internal class CallMapAttemptStore private constructor(private val records: (String, Int) -> Record) {
    constructor(context: Context) : this({ key, max ->
        val file = DraftStore(context, "call-map-diagnostics-v1.$key", max)
        object : Record {
            override fun read() = file.readStrict()
            override fun write(value: String) { file.write(value); check(file.readStrict() == value) }
        }
    })
    internal interface Record { fun read(): String; fun write(value: String) }
    fun append(value: CallMapAttemptDiagnostics): Boolean = synchronized(LOCK) {
        validate(value)
        if (read("forgotten.${value.queueId}").isNotEmpty()) return@synchronized false
        val ids = attemptIds(value.queueId)
        if (value.attemptId !in ids) {
            require(value.sequence == 0 && ids.size < 10_000)
            write("index.${value.queueId}", JSON.encodeToString(ids + value.attemptId))
        }
        val cursorKey = "latest.${value.attemptId}"
        val priorSequence = read(cursorKey).takeIf { it.isNotEmpty() }?.toInt()
        val prior = priorSequence?.let { event(value.attemptId, it) }
        require(priorSequence == null && value.sequence == 0 || priorSequence != null && value.sequence == priorSequence + 1)
        if (prior != null) {
            require(!prior.terminal && prior.queueId == value.queueId && prior.mapIndex == value.mapIndex && prior.queueEpoch == value.queueEpoch)
            require(prior.modelSha256 == value.modelSha256 && prior.schemaSha256 == value.schemaSha256 && prior.systemSha256 == value.systemSha256)
            require(value.updatedAtMillis >= prior.updatedAtMillis && value.elapsedMillis >= prior.elapsedMillis && value.outputCharacters >= prior.outputCharacters)
        }
        val key = "event.${value.attemptId}.${value.sequence}"
        val serialized = JSON.encodeToString(value)
        val existing = read(key)
        require(existing.isEmpty() || existing == serialized)
        if (existing.isEmpty()) write(key, serialized)
        write(cursorKey, value.sequence.toString())
        true
    }
    fun latest(queueId: String): CallMapAttemptDiagnostics? = synchronized(LOCK) {
        requireId(queueId)
        if (read("forgotten.$queueId").isNotEmpty()) return@synchronized null
        attemptIds(queueId).asReversed().firstNotNullOfOrNull { id ->
            read("latest.$id").takeIf { it.isNotEmpty() }?.toInt()?.let { event(id, it) }
        }
    }
    fun forget(queueId: String) = synchronized(LOCK) {
        requireId(queueId)
        write("forgotten.$queueId", "forgotten") // Fence first, so a late finally cannot recreate diagnostics.
        for (id in attemptIds(queueId)) {
            val last = read("latest.$id").takeIf { it.isNotEmpty() }?.toInt() ?: -1
            for (sequence in 0..(last + 1).coerceAtMost(600)) write("event.$id.$sequence", "")
            write("latest.$id", "")
        }
        write("index.$queueId", "")
    }
    fun event(attemptId: String, sequence: Int): CallMapAttemptDiagnostics? {
        requireId(attemptId); require(sequence in 0..600)
        return read("event.$attemptId.$sequence").takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<CallMapAttemptDiagnostics>(it) }?.also {
            validate(it); require(it.attemptId == attemptId && it.sequence == sequence)
        }
    }
    private fun attemptIds(id: String): List<String> {
        requireId(id)
        return read("index.$id").takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<List<String>>(it) }.orEmpty().also { list ->
            require(list.size <= 10_000 && list.distinct().size == list.size); list.forEach(::requireId)
        }
    }
    private fun read(key: String) = records(key, 1_000_000).read()
    private fun write(key: String, value: String) = records(key, 1_000_000).write(value)
    private fun validate(value: CallMapAttemptDiagnostics) {
        requireId(value.queueId); requireId(value.attemptId)
        require(value.mapIndex in 0..479 && value.queueEpoch > 0 && value.sequence in 0..600 && value.outputCharacters in 0..262_144)
        require(value.inputCountKind in setOf("UNAVAILABLE", "PLAIN_PREFLIGHT_ONLY", "STRUCTURED_RENDERED"))
        require(listOf(value.modelSha256, value.schemaSha256, value.systemSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(value.stopReason == null || value.stopReason in setOf("EOG", "MAX_OUTPUT", "CANCELLED", "CONTEXT_REJECTED", "RESOURCE_LIMIT", "ENGINE_ERROR", "SDK_DONE_UNKNOWN"))
        require(value.stopEvidence == null || value.stopEvidence in setOf("NATIVE", "SDK", "HOST", "INFERRED"))
        require(listOfNotNull(value.inputTokenCount, value.prefillTokens, value.decodeTokens).all { it >= 0 })
        val nativeValues = listOf(value.nativeRequestId, value.nativePhase, value.nativePromptProcessed, value.nativePromptTotal,
            value.nativeSampledTokens, value.nativeRawUtf8Bytes, value.nativeVisibleUtf8Bytes, value.nativeElapsedMillis)
        require(nativeValues.all { it == null } || nativeValues.all { it != null })
        if (value.nativeRequestId != null) {
            requireId(value.nativeRequestId)
            require(value.nativePhase in NativeGenerationPhase.entries.map { it.name })
            val total = checkNotNull(value.nativePromptTotal)
            require(total in 0..4096 && checkNotNull(value.nativePromptProcessed) in 0..total)
            require(checkNotNull(value.nativeSampledTokens) in 0..2056)
            require(checkNotNull(value.nativeRawUtf8Bytes) in 0..1_048_576L && checkNotNull(value.nativeVisibleUtf8Bytes) in 0..1_048_576L)
            require(checkNotNull(value.nativeElapsedMillis) >= 0)
        }
    }
    companion object {
        private val LOCK = Any()
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private fun requireId(id: String) { require(runCatching { UUID.fromString(id) }.isSuccess) }
        fun forTest(records: (String, Int) -> Record) = CallMapAttemptStore(records)
    }
}

/** One second progress throttle; phase/terminal changes are immediate and never contain output text. */
internal class CallMapAttemptRecorder(private val store: CallMapAttemptStore, queueId: String, mapIndex: Int, epoch: Long,
    modelSha: String, schemaSha: String, systemSha: String, initialPhase: CallMapPhase = CallMapPhase.LOAD,
    private val now: () -> Long = System::currentTimeMillis, private val monotonic: () -> Long = SystemClock::elapsedRealtime) {
    private val started = now()
    private val startedMonotonic = monotonic()
    private var lastWrite = Long.MIN_VALUE
    private var state = CallMapAttemptDiagnostics(UUID.randomUUID().toString(), queueId, mapIndex, epoch, -1, initialPhase,
        started, started, started, started, 0, modelSha256 = modelSha, schemaSha256 = schemaSha, systemSha256 = systemSha)
    init { persist(true) }
    @Synchronized fun phase(value: CallMapPhase) {
        if (state.terminal) return
        val time = now()
        state = state.copy(phase = value, phaseStartedAtMillis = time, lastProgressAtMillis = time)
        persist(true)
    }
    @Synchronized fun counted(tokens: Int?, kind: String) { if (!state.terminal) { state = state.copy(inputTokenCount = tokens, inputCountKind = kind); persist(true) } }
    @Synchronized fun progress(characters: Int) {
        if (state.terminal) return
        state = state.copy(outputCharacters = characters, lastProgressAtMillis = if (characters > state.outputCharacters) now() else state.lastProgressAtMillis)
        persist(false)
    }
    @Synchronized fun heartbeat() { if (!state.terminal) persist(false) }
    @Synchronized fun nativeProgress(progress: NativeGenerationProgress) {
        if (state.terminal || state.nativeRequestId != null && state.nativeRequestId != progress.requestId) return
        if (state.nativePhase != null && (progress.phase.code < NativeGenerationPhase.valueOf(state.nativePhase!!).code ||
            progress.processedPromptTokens < checkNotNull(state.nativePromptProcessed) ||
            progress.sampledTokens < checkNotNull(state.nativeSampledTokens) ||
            progress.rawUtf8Bytes < checkNotNull(state.nativeRawUtf8Bytes) ||
            progress.visibleUtf8Bytes < checkNotNull(state.nativeVisibleUtf8Bytes) ||
            progress.elapsedMillis < checkNotNull(state.nativeElapsedMillis))) return
        val advanced = state.nativePhase != progress.phase.name || state.nativePromptProcessed != progress.processedPromptTokens ||
            state.nativeSampledTokens != progress.sampledTokens || state.nativeRawUtf8Bytes != progress.rawUtf8Bytes ||
            state.nativeVisibleUtf8Bytes != progress.visibleUtf8Bytes
        state = state.copy(nativeRequestId = progress.requestId, nativePhase = progress.phase.name,
            nativePromptProcessed = progress.processedPromptTokens, nativePromptTotal = progress.totalPromptTokens,
            nativeSampledTokens = progress.sampledTokens, nativeRawUtf8Bytes = progress.rawUtf8Bytes,
            nativeVisibleUtf8Bytes = progress.visibleUtf8Bytes, nativeElapsedMillis = progress.elapsedMillis,
            lastProgressAtMillis = if (advanced) now() else state.lastProgressAtMillis)
        persist(false)
    }
    @Synchronized fun validated(value: CallMapValidationCategory) { if (!state.terminal) { state = state.copy(validation = value); persist(true) } }
    @Synchronized fun observed(summary: GenerationSummary) {
        if (state.terminal) return
        state = state.copy(stopReason = summary.reason.name, stopEvidence = summary.evidence.name, capReached = summary.capReached,
            prefillTokens = summary.prefillTokens, decodeTokens = summary.decodeTokens)
        persist(true)
    }
    @Synchronized fun finish(phase: CallMapPhase, summary: GenerationSummary?, validation: CallMapValidationCategory? = null) {
        if (state.terminal) return
        require(phase in setOf(CallMapPhase.SUCCEEDED, CallMapPhase.FAILED, CallMapPhase.CANCELLED))
        val time = now()
        state = state.copy(phase = phase, phaseStartedAtMillis = time, stopReason = summary?.reason?.name ?: state.stopReason,
            stopEvidence = summary?.evidence?.name ?: state.stopEvidence, capReached = summary?.capReached ?: state.capReached,
            prefillTokens = summary?.prefillTokens ?: state.prefillTokens, decodeTokens = summary?.decodeTokens ?: state.decodeTokens,
            validation = validation ?: state.validation)
        persist(true)
    }
    private fun persist(force: Boolean) {
        val time = now()
        val tick = monotonic()
        if (!force && lastWrite != Long.MIN_VALUE && tick - lastWrite < 1000) return
        state = state.copy(sequence = state.sequence + 1, updatedAtMillis = maxOf(time, state.updatedAtMillis), elapsedMillis = maxOf(state.elapsedMillis, tick - startedMonotonic))
        store.append(state); lastWrite = tick
    }
}
