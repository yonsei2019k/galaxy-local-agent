package com.user.localagent.callfollowup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.user.localagent.AppContainer
import com.user.localagent.MainActivity
import com.user.localagent.runtime.R
import com.user.localagent.audio.AudioChunkDecoder
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.*
import com.user.localagent.inference.*
import com.user.localagent.models.AsrCatalog
import com.user.localagent.models.InstalledModel
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal enum class CallPipelineBatchResult { MORE, DEFERRED, STOPPED }
internal class CallPipelineFailure(val code: String) : IllegalStateException(code)
private class EndCallDecodeBatch : CancellationException("Bounded audio batch checkpointed")
private class CallPipelineDeferred : CancellationException("Owner work or device state changed")
private class CallPipelineSourceChanged : CancellationException("Source or private scope changed")
private data class CallOwnedRuntime(val job: Job, val engine: InferenceEngine)

/** Local-only, resumable analysis. This class never invokes ToolExecutor or external write APIs. */
class PersistentCallFollowUpPipeline(context: Context, private val app: AppContainer) : CallFollowUpPipeline {
    private val context = context.applicationContext
    private val store = CallFollowUpQueueStore(this.context)
    private val mapDiagnostics = CallMapAttemptStore(this.context)
    private val owned = ConcurrentHashMap<String, CallOwnedRuntime>()
    private val publications = CallPublicationGuard()
    @Volatile private var lastModelReadinessDetail: String? = null
    private val work get() = WorkManager.getInstance(context)

    override suspend fun readiness(): PipelineReadiness = withContext(Dispatchers.IO) {
        if (RuntimeGate.inference.get() || RuntimeGate.benchmark.get()) return@withContext PipelineReadiness.BUSY
        try { modelPins(); lastModelReadinessDetail = null; PipelineReadiness.READY }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: CallPipelineFailure) { lastModelReadinessDetail = failure.code; PipelineReadiness.MODEL_UNAVAILABLE }
        catch (_: Exception) { lastModelReadinessDetail = "LOCAL_MODEL_READINESS_UNAVAILABLE"; PipelineReadiness.MODEL_UNAVAILABLE }
    }

    fun modelReadinessDetail(): String? = lastModelReadinessDetail

    override suspend fun find(snapshotId: String): CallFollowUpEnqueueReceipt? = withContext(Dispatchers.IO) {
        val job = store.recoverSnapshot(snapshotId) ?: return@withContext null
        if (job.status == "INVALIDATED" || store.sourceInvalidated(job.snapshot.sourceKey, job.authorization.generation)) return@withContext null
        requireAuthorized(job, allowPaused = true)
        ensureRoute(job)
        if (job.status !in STOPPED) schedule(job, false)
        job.receipt
    }

    override suspend fun enqueue(snapshot: StableCallRecording, authorization: CallFollowUpAuthorization): CallFollowUpEnqueueReceipt = withContext(Dispatchers.IO) {
        snapshot.validate()
        check(app.callFollowUp.isAuthorized(snapshot, authorization)) { "원래 통화 녹음의 처리 승인이 변경되었습니다." }
        check(!store.sourceInvalidated(snapshot.sourceKey, authorization.generation))
        find(snapshot.snapshotId)?.let { return@withContext it }
        val pins = modelPins()
        val queueId = UUID.randomUUID().toString()
        val receipt = CallFollowUpEnqueueReceipt(snapshot.snapshotId, queueId, store.projectId(), UUID.randomUUID().toString())
        val job = store.insert(CallPipelineJob(queueId, snapshot, authorization, receipt, pins.first, pins.second,
            analysisContractSha256 = analysisContractSha256()))
        // IDs are durably reserved before Room creation, so process death reuses this route.
        ensureRoute(job)
        requireAuthorized(job)
        schedule(job, false)
        job.receipt
    }

    override suspend fun invalidate(sourceKey: String, generation: Long, reason: String) = withContext(Dispatchers.IO) {
        require(reason.matches(Regex("[a-z_]{1,100}")))
        store.invalidateSource(sourceKey, generation) // Fence precedes cancellation; late enqueue cannot revive it.
        for (id in store.ids()) {
            val job = store.job(id) ?: continue
            if (job.snapshot.sourceKey != sourceKey || job.authorization.generation != generation) continue
            if (reason != "owner_forgot_source" && (job.status == "COMPLETE" || job.status == "REVIEW_REQUIRED" && job.errorCode == null)) continue
            store.markInvalidated(id)
            owned[id]?.let { it.engine.cancel(); it.job.cancel() }
            work.cancelUniqueWork(workName(id))
            if (reason == "owner_forgot_source") {
                // Fence/cancellation already happened. Wait only for this job's in-flight commit,
                // then reread its saved hashes; it may have pinned a body after our first read.
                withContext(NonCancellable) { publications.cleanup(id) {
                    val actual = store.job(id) ?: return@cleanup
                    store.erasePayloads(id)
                    mapDiagnostics.forget(id)
                    eraseUnchangedReport(actual.publicationId, actual.publicationSha256, actual.receipt.reportThreadId)
                    eraseUnchangedReport(actual.partialPublicationId, actual.partialPublicationSha256, actual.receipt.reportThreadId)
                } }
            }
        }
    }

    suspend fun listJobs(offset:Int=0,limit:Int=100): List<CallFollowUpQueueItem> = withContext(Dispatchers.IO) {
        require(offset>=0&&limit in 1..100)
        store.ids().asReversed().drop(offset).take(limit).mapNotNull(store::job).map { job ->
            val diagnostic = mapDiagnostics.latest(job.queueId)?.takeIf { it.queueEpoch == job.epoch }
            CallFollowUpQueueItem(job.queueId, CallFollowUpQueueStatus.valueOf(job.status), job.chunkCount, job.analyzedChunks,
                job.decodedDurationMillis.takeIf { it > 0 } ?: job.snapshot.durationMillis,
                job.receipt.reportThreadId, job.receipt.privateProjectId, job.updatedAtMillis,
                CallAnalysisContractPolicy.permitsRetry(job.errorCode) &&
                    (job.status in setOf("PAUSED", "DEFERRED") || job.status == "REVIEW_REQUIRED" && job.errorCode != null),
                true, job.errorCode, job.asr.model.id, diagnostic?.phase?.name, diagnostic?.outputCharacters, diagnostic?.elapsedMillis,
                diagnostic?.nativePhase, diagnostic?.nativePromptProcessed, diagnostic?.nativePromptTotal, diagnostic?.nativeSampledTokens)
        }
    }

    internal fun latestMapDiagnostics(queueId: String): CallMapAttemptDiagnostics? =
        store.job(queueId)?.let { job -> mapDiagnostics.latest(queueId)?.takeIf { it.queueEpoch == job.epoch } }

    suspend fun sourceForReview(queueId: String): Uri = withContext(Dispatchers.IO) {
        val job = checkNotNull(store.job(queueId))
        checkSource(job, allowPaused = true)
        Uri.parse(job.snapshot.uri)
    }

    internal suspend fun transcriptForReview(queueId: String, startChunk: Int = 0, limit: Int = 10): List<CallRawChunk> = withContext(Dispatchers.IO) {
        require(startChunk >= 0 && limit in 1..10)
        val job = checkNotNull(store.job(queueId))
        checkSource(job, allowPaused = true)
        (startChunk until minOf(job.chunkCount, startChunk + limit)).map { checkNotNull(store.raw(queueId, it)) }
    }

    suspend fun retry(queueId: String) = withContext(Dispatchers.IO) {
        val current = checkNotNull(store.job(queueId))
        check(CallAnalysisContractPolicy.permitsRetry(current.errorCode)) { "분석 지침 버전이 달라 기존 결과와 섞지 않습니다. 명시적인 새 분석이 필요합니다." }
        check(current.status in setOf("PAUSED", "DEFERRED") || current.status == "REVIEW_REQUIRED" && current.errorCode != null) { "재시도할 중단 작업이 아닙니다." }
        requireAuthorized(current, allowPaused = true)
        checkSource(current, allowPaused = true)
        val next = checkNotNull(store.changeControl(queueId, "QUEUED"))
        work.cancelUniqueWork(workName(queueId))
        schedule(next, false, replace = true)
    }

    /** Pause just this queue item. Explicit retry keeps immutable chunks; source forget is separate. */
    suspend fun cancel(queueId: String) = withContext(Dispatchers.IO) {
        val current = store.job(queueId) ?: return@withContext
        if (current.status in setOf("COMPLETE", "INVALIDATED") || current.status == "REVIEW_REQUIRED" && current.errorCode == null) return@withContext
        store.changeControl(queueId, "PAUSED")
        owned[queueId]?.let { it.engine.cancel(); it.job.cancel() }
        work.cancelUniqueWork(workName(queueId))
    }

    suspend fun forget(queueId: String) = withContext(Dispatchers.IO) {
        val job = checkNotNull(store.job(queueId))
        app.callFollowUp.forget(job.snapshot.sourceKey)
        // Controller's current source revision may belong to a newer opt-in generation.
        // The explicitly selected historical queue must be erased as well.
        invalidate(job.snapshot.sourceKey, job.authorization.generation, "owner_forgot_source")
    }

    /** WorkManager callback: cooperative deadline is 5m30s, below its ordinary 10-minute window. */
    internal suspend fun processBatch(queueId: String, epoch: Long): CallPipelineBatchResult = withContext(Dispatchers.IO) {
        var initial = store.job(queueId) ?: return@withContext CallPipelineBatchResult.STOPPED
        if (initial.epoch != epoch || initial.status in STOPPED) return@withContext CallPipelineBatchResult.STOPPED
        try {
            requireAuthorized(initial)
            ensureRoute(initial)
            initial = checkNotNull(store.job(queueId)) // The first route creation captures its exact revisions durably.
            if (!deviceSafe() || !app.runs.selectionReady.value || app.runs.busy.value || app.runs.queued.value.isNotEmpty() ||
                RuntimeGate.benchmark.get() || !RuntimeGate.inference.compareAndSet(false, true)) {
                store.update(queueId, epoch) { it.copy(status = "DEFERRED", errorCode = "DEVICE_OR_OWNER_BUSY") }
                return@withContext CallPipelineBatchResult.DEFERRED
            }
            try {
                app.cancelIdleUnload(); app.updates.setBusy(true)
                withTimeout(STAGE_MILLIS) {
                    checkSource(initial)
                    verifyPins(initial)
                    app.engine.unload() // Own the gate; release any idle chat model before this private ASR/model.
                    when {
                        !initial.transcriptionExhausted -> transcribeBatch(initial)
                        initial.analyzedChunks < initial.chunkCount -> analyzeOne(initial)
                        else -> publish(initial, partial = false)
                    }
                }
            } finally {
                // Never release the native gate before the exact owned engine has stopped and unloaded.
                withContext(NonCancellable) {
                    owned.remove(queueId)?.let { runtime -> runtime.engine.cancel(); runtime.engine.unload() }
                    app.updates.setBusy(false)
                    RuntimeGate.inference.set(false)
                    app.scheduleIdleUnload()
                }
            }
            val result = store.job(queueId)
            if (result == null || result.epoch != epoch || result.status in STOPPED) CallPipelineBatchResult.STOPPED else CallPipelineBatchResult.MORE
        } catch (deferred: CallPipelineDeferred) {
            currentCoroutineContext().ensureActive()
            store.update(queueId, epoch) { it.copy(status = "DEFERRED", errorCode = "DEVICE_OR_OWNER_BUSY") }
            CallPipelineBatchResult.DEFERRED
        } catch (changed: CallPipelineSourceChanged) {
            currentCoroutineContext().ensureActive()
            store.update(queueId, epoch) { it.copy(status = "REVIEW_REQUIRED", errorCode = "SOURCE_OR_SCOPE_CHANGED") }
            CallPipelineBatchResult.STOPPED
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failed(initial, "STAGE_TIME_LIMIT")
            CallPipelineBatchResult.STOPPED
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: CallPipelineFailure) {
            failed(initial, failure.code)
            CallPipelineBatchResult.STOPPED
        } catch (_: Exception) {
            failed(initial, "LOCAL_STAGE_FAILED")
            CallPipelineBatchResult.STOPPED
        }
    }

    internal fun enqueueNext(queueId: String, epoch: Long, deferred: Boolean) {
        store.job(queueId)?.takeIf { it.epoch == epoch && it.status !in STOPPED }?.let { schedule(it, true, deferred) }
    }

    private suspend fun transcribeBatch(initial: CallPipelineJob) {
        store.update(initial.queueId, initial.epoch) { it.copy(status = "TRANSCRIBING", errorCode = null) } ?: return
        if (initial.asr.model.id !in AsrCatalog.modelIds || initial.asr.projector == null)
            throw CallPipelineFailure("BACKGROUND_ASR_MODEL_REQUIRED")
        val engine: InferenceEngine = com.user.localagent.inference.PcInferenceEngine(context)
        withOwnedEngine(initial, engine) {
            var loaded = false; var count = 0
            val started = SystemClock.elapsedRealtime()
            var exhausted = false
            try {
                AudioChunkDecoder(context).decode(Uri.parse(initial.snapshot.uri), chunkSeconds = 15,
                    expectedSourceSha256 = initial.snapshot.sha256, expectedSourceBytes = initial.snapshot.byteCount) { chunk ->
                    checkSource(initial)
                    val job = checkNotNull(store.job(initial.queueId))
                    if (chunk.index < job.chunkCount) return@decode
                    check(chunk.index == job.chunkCount)
                    // A completed raw write may precede the cursor commit during process death.
                    val recovered = store.raw(job.queueId, chunk.index)
                    val raw = if (recovered != null) {
                        check(recovered.startMillis == chunk.startMillis && recovered.endMillis == chunk.startMillis + chunk.durationMillis &&
                            recovered.wavSha256 == fileSha(chunk.file)) { "전사 원본 버전이 변경되었습니다." }
                        recovered
                    } else {
                        if (!loaded) {
                            val pin = initial.asr
                            engine.load(pin.model.path, InferenceOptions(contextSize = 2048, backend = "CPU", audioEnabled = true,
                                thinking = false, maxOutputTokens = 512, projectorPath = pin.projector?.path, cpuThreads = pin.threads, deviceOnly = true))
                            loaded = true
                        }
                        val text = StringBuilder()
                        withTimeout(240_000) { engine.transcribeAudio(chunk.file).collect { value ->
                            currentCoroutineContext().ensureActive()
                            if (text.length + value.length > 10_000) throw CallPipelineFailure("ASR_OUTPUT_LIMIT")
                            text.append(value)
                        } }
                        checkSource(initial)
                        val exact = text.toString()
                        CallRawChunk(chunk.index, chunk.startMillis, chunk.startMillis + chunk.durationMillis, exact, callDigest(exact),
                            initial.snapshot.sha256, fileSha(chunk.file), initial.asr.model.id, initial.asr.model.sha256,
                            initial.asr.projector?.sha256, initial.asr.language,
                            engine.lastMetrics.value?.decodeTokens?.let { it >= 512 })
                    }
                    withContext(NonCancellable) { store.appendRaw(initial.queueId, initial.epoch, raw) }
                    currentCoroutineContext().ensureActive()
                    if (++count >= 4 || SystemClock.elapsedRealtime() - started >= 240_000) throw EndCallDecodeBatch()
                }
                exhausted = true
            } catch (_: EndCallDecodeBatch) { currentCoroutineContext().ensureActive() }
            if (exhausted) {
                checkSource(initial)
                val job = checkNotNull(store.job(initial.queueId))
                if (job.chunkCount == 0) throw CallPipelineFailure("NO_DECODED_AUDIO")
                store.update(initial.queueId, initial.epoch) { it.copy(transcriptionExhausted = true, status = "ANALYZING") }
            }
        }
    }

    private suspend fun analyzeOne(initial: CallPipelineJob) {
        val contract = analysisContractSha256()
        when (CallAnalysisContractPolicy.decide(initial.analysisContractSha256, contract, initial.analyzedChunks,
            store.map(initial.queueId, initial.analyzedChunks) != null)) {
            CallAnalysisContractPolicy.Decision.REQUIRES_EXPLICIT_REANALYSIS -> throw CallPipelineFailure("ANALYSIS_CONTRACT_CHANGED")
            CallAnalysisContractPolicy.Decision.ADOPT_UNPROCESSED_LEGACY -> store.update(initial.queueId, initial.epoch) {
                it.copy(analysisContractSha256 = contract)
            } ?: return
            CallAnalysisContractPolicy.Decision.SAME_CONTRACT -> Unit
        }
        store.update(initial.queueId, initial.epoch) { it.copy(status = "ANALYZING", errorCode = null) } ?: return
        val source = source(initial)
        val index = initial.analyzedChunks
        val chunkIds = listOfNotNull(source.chunks.getOrNull(index - 1)?.id, source.chunks[index].id)
        val input = CallFollowUpModelContract.modelDraftInput(source, chunkIds) + "\n" + buildJsonObject {
            put("type", "prior_candidate_ledger_summary"); put("untrustedSourceData", true)
            put("semanticAccuracyVerified", false); put("originalRecordsRetained", true)
            put("priorSourceBodiesDeliveredByThisLedger", false)
            put("candidateCount", initial.candidates.size)
            put("previousCandidates", buildJsonArray { initial.candidates.forEach { candidate -> add(buildJsonObject {
                put("id", candidate.id); put("kind", candidate.kind.name); put("speechAct", candidate.speechAct.name)
                put("summary", candidate.summary); put("domain", candidate.domain.name)
                put("criticalFields", buildJsonArray { candidate.criticalFields.forEach { field -> add(buildJsonObject {
                    put("kind", field.kind.name); put("rawValue", field.rawValue); put("uncertainty", field.uncertainty.name)
                    put("citationIds", JsonArray(field.citationIds.map(::JsonPrimitive)))
                }) } })
                put("temporal", candidate.temporal?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
                put("replacesCandidateIds", JsonArray(candidate.replacesCandidateIds.map(::JsonPrimitive)))
                put("citationRefs", buildJsonArray { candidate.citations.forEach { citation -> add(buildJsonObject {
                    put("id", citation.id); put("chunkId", citation.chunkId)
                }) } })
                put("uncertainties", JsonArray(candidate.uncertainties.map(::JsonPrimitive)))
            }) } })
            put("currentChunkId", source.chunks[index].id)
        }
        val coverage = CallProcessingCoverage(source.chunks.take(index + 1).map { it.id }.toSet())
        val saved = store.map(initial.queueId, index)
        var mapMetrics = saved?.metrics
        val raw = if (saved != null) {
            check(saved.transcriptSha256 == source.transcriptSha256 && saved.modelSha256 == initial.textModel.sha256 && saved.analysisContractSha256 == contract)
            saved.raw
        } else {
            val engine: InferenceEngine = com.user.localagent.inference.PcInferenceEngine(context)
            withOwnedEngine(initial, engine) { coroutineScope {
                val system = mapSystem(index)
                val schema = CallFollowUpModelContract.modelDraftSchema.toString()
                fun recorder(phase: CallMapPhase = CallMapPhase.LOAD) = CallMapAttemptRecorder(mapDiagnostics, initial.queueId, index,
                    initial.epoch, initial.textModel.sha256, callDigest(schema), callDigest(system), phase)
                var diagnostic = recorder()
                val activeDiagnostic = java.util.concurrent.atomic.AtomicReference(diagnostic)
                val nativeAttempt = java.util.concurrent.atomic.AtomicReference<Pair<CallMapAttemptRecorder, CallMapNativeProgressGate>?>(null)
                fun observeNativeProgress() {
                    nativeAttempt.get()?.let { (recorder, gate) ->
                        gate.accept((engine as? LlamaCppEngine)?.nativeProgress?.value)?.let(recorder::nativeProgress)
                    }
                }
                val heartbeat = launch(Dispatchers.IO) { while (isActive) {
                    delay(1000); observeNativeProgress(); activeDiagnostic.get().heartbeat()
                } }
                var failure: Throwable? = null
                var generationEntered = false
                var previousGenerationId: String? = null
                var observedTerminal: GenerationSummary? = null
                try {
                    engine.load(initial.textModel.path, InferenceOptions(contextSize = 4096, thinking = false, backend = "CPU",
                    maxOutputTokens = 1536, cpuThreads = 4, deviceOnly = true))
                    var answer: String? = null
                    var repair = ""
                    repeat(2) { attempt ->
                        if (answer != null) return@repeat
                        nativeAttempt.set(null) // COUNT belongs to no native generation, including a repair attempt.
                        if (attempt > 0) { diagnostic = recorder(CallMapPhase.COUNT); activeDiagnostic.set(diagnostic) }
                        generationEntered = false
                        previousGenerationId = engine.lastGeneration.value?.requestId
                        observedTerminal = null
                        diagnostic.phase(CallMapPhase.COUNT)
                        val query = input + repair
                        val structuredTokens = engine.countStructuredChatTokens(query, system, schema)
                        // A plain count is a lower-bound preflight only. The runtime's schema-expanded
                        // context check remains authoritative when this engine has no exact counter.
                        val tokens = structuredTokens ?: engine.countChatTokens(query, system)
                        val countKind = when { structuredTokens != null -> "STRUCTURED_RENDERED"; tokens != null -> "PLAIN_PREFLIGHT_ONLY"; else -> "UNAVAILABLE" }
                        diagnostic.counted(tokens, countKind)
                        if (tokens != null && tokens + 1536 + 16 > 4096) throw CallPipelineFailure("MODEL_INPUT_BUDGET")
                        val text = StringBuilder(); var terminal: GenerationSummary? = null
                        diagnostic.phase(CallMapPhase.GENERATE)
                        generationEntered = true
                        nativeAttempt.set(diagnostic to CallMapNativeProgressGate(listOfNotNull(previousGenerationId,
                            (engine as? LlamaCppEngine)?.nativeProgress?.value?.requestId).toSet()))
                        engine.generateStructuredEvents(query, system, schema).collect { event ->
                            when (event) {
                                is GenerationEvent.Delta -> {
                                    if (text.length + event.text.length > 262_144) throw CallPipelineFailure("MODEL_OUTPUT_LIMIT")
                                    text.append(event.text)
                                    diagnostic.progress(text.length)
                                }
                                is GenerationEvent.Finished -> {
                                    observeNativeProgress()
                                    terminal = event.summary; observedTerminal = event.summary; diagnostic.observed(event.summary)
                                }
                            }
                        }
                        checkSource(initial)
                        val finish = terminal ?: engine.lastGeneration.value ?: throw CallPipelineFailure("MODEL_TERMINATION_UNKNOWN")
                        if (finish.capReached == true || finish.reason !in setOf(GenerationStopReason.EOG, GenerationStopReason.SDK_DONE_UNKNOWN))
                            throw CallPipelineFailure("MODEL_OUTPUT_INCOMPLETE")
                        val candidateRaw = text.toString()
                        diagnostic.phase(CallMapPhase.VALIDATE)
                        try {
                            val validated = CallFollowUpValidator.validateModelDraftDelta(source, coverage, initial.candidates, candidateRaw, chunkIds.toSet())
                            validateNewCandidates(initial, validated, source.chunks[index].id, index)
                            answer = candidateRaw
                            mapMetrics = CallMapMetrics(tokens, countKind, finish.prefillTokens, finish.decodeTokens,
                                finish.reason.name, finish.evidence.name, finish.capReached, finish.elapsedMillis)
                            diagnostic.finish(CallMapPhase.SUCCEEDED, finish, CallMapValidationCategory.PASSED)
                        } catch (error: IllegalArgumentException) {
                            diagnostic.finish(CallMapPhase.FAILED, finish, CallMapValidationCategory.SHAPE_OR_EXACT_SOURCE_REJECTED)
                            if (attempt == 1) throw CallPipelineFailure("MODEL_EVIDENCE_INVALID")
                            repair = "\nHOST_VALIDATION: The previous response did not pass exact source/schema/citation validation. " +
                                "Return a fresh bounded JSON delta from these same source bytes; do not invent missing data."
                        }
                    }
                    checkNotNull(answer)
                } catch (error: Throwable) { failure = error; throw error }
                finally {
                    withContext(NonCancellable) {
                        heartbeat.cancelAndJoin()
                        observeNativeProgress()
                        // Capture only this attempt, before withOwnedEngine unloads/reset lastGeneration.
                        val terminal = CallMapGenerationProof.current(generationEntered, previousGenerationId, observedTerminal, engine.lastGeneration.value)
                        diagnostic.finish(if (failure is CancellationException) CallMapPhase.CANCELLED else CallMapPhase.FAILED,
                            terminal, failure?.let(::diagnosticCategory))
                    }
                }
            } }
        }
        val report = CallFollowUpValidator.validateModelDraftDelta(source, coverage, initial.candidates, raw, chunkIds.toSet())
        validateNewCandidates(initial, report, source.chunks[index].id, index)
        checkSource(initial)
        withContext(NonCancellable) {
            store.appendMap(initial.queueId, initial.epoch, CallMapCheckpoint(index, source.transcriptSha256,
                initial.textModel.sha256, raw, callDigest(raw), mapMetrics, contract), report.candidates.map { it.proposal })
        }
    }

    private fun mapSystem(index: Int): String = CallFollowUpModelContract.modelDraftInstructions + "\n" +
        "Return NEW candidates only with unique local labels; the HOST assigns canonical IDs for chunk $index. Prior candidates are immutable host records. " +
        "Include the full previous raw chunk only as context; every new candidate must cite the current chunk. " +
        "Keep intentional repeated speech. Corrections may refer to actual prior candidate IDs. " +
        "No tool call or action execution. Return only JSON."

    private fun analysisContractSha256(): String = callDigest("call-analysis-contract-v1\n" + mapSystem(0) + "\n" +
        CallFollowUpModelContract.modelDraftSchema.toString() + "\nmodelDraftInput-v3-host-label-resolution-full-adjacent-two-plus-ledger;CPU4;context4096;output1536;thinkingOff")

    private fun diagnosticCategory(error: Throwable): CallMapValidationCategory = when {
        error is CancellationException -> CallMapValidationCategory.TIME_LIMIT_OR_CANCELLED
        error is CallPipelineFailure -> when (error.code) {
            "MODEL_INPUT_BUDGET" -> CallMapValidationCategory.INPUT_BUDGET
            "MODEL_OUTPUT_LIMIT" -> CallMapValidationCategory.OUTPUT_LIMIT
            "MODEL_OUTPUT_INCOMPLETE", "MODEL_TERMINATION_UNKNOWN" -> CallMapValidationCategory.OUTPUT_INCOMPLETE
            "MODEL_EVIDENCE_INVALID" -> CallMapValidationCategory.SHAPE_OR_EXACT_SOURCE_REJECTED
            "SOURCE_OR_SCOPE_CHANGED" -> CallMapValidationCategory.SOURCE_OR_SCOPE_CHANGED
            else -> CallMapValidationCategory.ENGINE_FAILURE
        }
        else -> CallMapValidationCategory.ENGINE_FAILURE
    }

    private fun validateNewCandidates(job: CallPipelineJob, report: ValidatedCallFollowUpReport, currentChunkId: String, index: Int) {
        val previous = job.candidates.map { it.id }.toSet()
        report.candidates.filter { it.proposal.id !in previous }.forEach { candidate ->
            require(candidate.proposal.id.startsWith("c${index}_") && candidate.proposal.citations.all { it.id.startsWith("c${index}_") })
            require(candidate.proposal.citations.any { it.chunkId == currentChunkId })
        }
    }

    private suspend fun source(job: CallPipelineJob): CallFollowUpSource {
        val chunks = (0 until job.chunkCount).map { index ->
            val raw = checkNotNull(store.raw(job.queueId, index))
            check(raw.audioSha256 == job.snapshot.sha256 && raw.modelId == job.asr.model.id && raw.modelSha256 == job.asr.model.sha256 &&
                raw.projectorSha256 == job.asr.projector?.sha256 && raw.language == job.asr.language)
            CallTranscriptChunk("chunk_$index", raw.startMillis, raw.endMillis, raw.text, raw.textSha256)
        }
        val ended = job.authorization.callEndedSignalAtMillis
        return CallFollowUpSource.create(job.receipt.reportThreadId, "call-recordings/${job.snapshot.snapshotId}", job.snapshot.sha256,
            maxOf(job.snapshot.durationMillis, job.decodedDurationMillis), ended?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString() },
            chunks, job.transcriptionExhausted, knownAsrOmission = chunks.any { it.text.isBlank() } ||
                (0 until job.chunkCount).any { store.raw(job.queueId, it)?.asrOutputCapReached == true } ||
                job.transcriptionExhausted && kotlin.math.abs(job.decodedDurationMillis - job.snapshot.durationMillis) > 250,
            callTimeAnchorKind = if (ended == null) CallTimeAnchorKind.UNKNOWN else CallTimeAnchorKind.CALL_END_EVENT)
    }

    private suspend fun publish(initial: CallPipelineJob, partial: Boolean) {
        val job = checkNotNull(store.job(initial.queueId))
        if (job.epoch != initial.epoch) return
        checkSource(job)
        val report = if (job.chunkCount > 0) {
            val source = source(job)
            CallFollowUpValidator.validate(source, CallProcessingCoverage(source.chunks.take(job.analyzedChunks).map { it.id }.toSet(), interrupted = partial),
                CallFollowUpProposal(scopeId = source.scopeId, audioRelativePath = source.audioRelativePath, audioSha256 = source.audioSha256,
                    transcriptSha256 = source.transcriptSha256, claimsComplete = false, candidates = job.candidates))
        } else null
        val body = (if (partial) job.partialPublicationBody else job.publicationBody) ?: CallFollowUpReportText.render(job, report, partial)
        val publication = if (partial) job.partialPublicationId else job.publicationId
        val sha = callDigest(body)
        val oldSha = if (partial) job.partialPublicationSha256 else job.publicationSha256
        if (oldSha != null) {
            if (oldSha != sha) return // An earlier immutable partial stays historical; do not overwrite it.
        } else store.update(job.queueId, job.epoch) {
            if (partial) it.copy(partialPublicationSha256 = sha, partialPublicationBody = body)
            else it.copy(publicationSha256 = sha, publicationBody = body, status = "PUBLISHING")
        } ?: return
        publications.commit(job.queueId,
            verifyBefore = { checkSource(job) },
            alreadyExists = { app.repository.getMessage(publication) != null },
            write = {
                val message = app.repository.chat.publishPrivateCallFollowUpReport(job.receipt.reportThreadId, publication, body)
                check(message.id == publication && app.repository.chat.fullMessageText(message.id) == body)
            },
            verifyAfter = {
                checkSource(job)
                if (!partial && store.update(job.queueId, job.epoch) { it.copy(status =
                    if (report?.coverage?.completeWithinDeclaredTranscript == true) "COMPLETE" else "REVIEW_REQUIRED", errorCode = null) } == null)
                    throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED")
            },
            eraseInserted = { app.repository.rollbackPrivateCallFollowUpReportIfUnchanged(job.receipt.reportThreadId, publication, sha) })
        if (!store.sourceInvalidated(job.snapshot.sourceKey, job.authorization.generation) && app.callFollowUp.isAuthorized(job.snapshot, job.authorization)) notifyReport(job)
    }

    private suspend fun failed(initial: CallPipelineJob, code: String) {
        if (!code.matches(Regex("[A-Z_]{1,64}"))) return
        val current = store.update(initial.queueId, initial.epoch) { it.copy(status = "REVIEW_REQUIRED", errorCode = code) } ?: return
        try { publish(current, partial = true) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The durable error and exact chunks remain available for explicit retry. */ }
    }

    private suspend fun <T> withOwnedEngine(job: CallPipelineJob, engine: InferenceEngine, block: suspend () -> T): T = coroutineScope {
        val operation = checkNotNull(currentCoroutineContext()[Job])
        check(owned.putIfAbsent(job.queueId, CallOwnedRuntime(operation, engine)) == null)
        val watcher = launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (!deviceSafe() || app.runs.busy.value || app.runs.queued.value.isNotEmpty()) {
                    engine.cancel(); operation.cancel(CallPipelineDeferred()); return@launch
                }
                try { checkSource(job) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { engine.cancel(); operation.cancel(CallPipelineSourceChanged()); return@launch }
            }
        }
        try { block() } finally {
            withContext(NonCancellable) {
                watcher.cancelAndJoin()
                engine.cancel(); engine.unload()
                owned.remove(job.queueId)
            }
        }
    }

    private suspend fun modelPins(): Pair<CallAsrPin, InstalledModel> {
        app.models.refresh()
        val selection = app.models.asrSelection.value
        val readyIds = AsrCatalog.modelIds.filter { app.models.asrReady(it) }.toSet()
        val choice = CallBackgroundAsrPolicy.select(selection.modelId, readyIds)
            ?: throw CallPipelineFailure("BACKGROUND_QWEN_ASR_UNAVAILABLE")
        val model = app.models.models.value.single { it.id == choice.modelId && it.verified }
        val projector = app.models.models.value.single { it.id == model.id + "-mmproj" && it.verified }
        val localText = app.models.models.value.singleOrNull {
            it.id == "gemma-e2b" && it.verified && it.runtime in setOf("gguf", "litertlm") &&
                java.io.File(it.path).isFile
        } ?: throw CallPipelineFailure("TEXT_MODEL_UNAVAILABLE")
        return CallAsrPin(model, projector, choice.language, if (model.id == "asr-qwen-17b") 2 else 4) to localText
    }

    private suspend fun verifyPins(job: CallPipelineJob) {
        if (job.asr.model.id !in AsrCatalog.modelIds || job.asr.projector?.id != job.asr.model.id + "-mmproj")
            throw CallPipelineFailure("BACKGROUND_ASR_MODEL_REQUIRED")
        app.models.refresh()
        val files = listOfNotNull(job.textModel, job.asr.model, job.asr.projector)
        files.forEach { pin -> if (app.models.models.value.none { it.id == pin.id && it.path == pin.path && it.sha256 == pin.sha256 && it.verified })
            throw CallPipelineFailure("MODEL_FILE_UNAVAILABLE") }
    }
    private suspend fun ensureRoute(job: CallPipelineJob) {
        try {
            val thread = app.repository.chat.ensurePrivateCallFollowUpRoute(job.receipt.privateProjectId, job.receipt.reportThreadId)
            check(thread.projectId == job.receipt.privateProjectId && !thread.archived)
            if (!job.scopeBound) {
                val project = checkNotNull(app.repository.chat.project(job.receipt.privateProjectId))
                store.update(job.queueId, job.epoch) { current ->
                    if (current.scopeBound) current else current.copy(scopeBound = true, projectRevision = project.revision, threadRevision = thread.revision)
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED") }
    }
    private fun requireAuthorized(job: CallPipelineJob, allowPaused: Boolean = false) {
        if (store.sourceInvalidated(job.snapshot.sourceKey, job.authorization.generation) || !app.callFollowUp.isAuthorized(job.snapshot, job.authorization))
            throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED")
        val current = store.job(job.queueId) ?: throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED")
        if (current.epoch != job.epoch || current.status == "INVALIDATED" || !allowPaused && current.status == "PAUSED")
            throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED")
    }
    private suspend fun checkSource(job: CallPipelineJob, allowPaused: Boolean = false) {
        currentCoroutineContext().ensureActive()
        try {
            requireAuthorized(job, allowPaused)
            check(app.callFollowUp.sourceStillPresent(job.snapshot, job.authorization))
            val thread = checkNotNull(app.repository.chat.thread(job.receipt.reportThreadId))
            val project = checkNotNull(app.repository.chat.project(job.receipt.privateProjectId))
            check(job.scopeBound && thread.revision == job.threadRevision && project.revision == job.projectRevision &&
                thread.projectId == project.id && !thread.archived && !project.archived && project.settings.memoryMode == "project" &&
                !project.settings.useOwnerMemory && !project.settings.learnOwnerFacts)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw CallPipelineFailure("SOURCE_OR_SCOPE_CHANGED") }
    }
    private fun deviceSafe(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        @Suppress("DEPRECATION") val phoneIdle = try {
            context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) { false }
        if (!phoneIdle) return false
        val power = context.getSystemService(PowerManager::class.java)
        return context.getSystemService(UserManager::class.java).isUserUnlocked &&
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 20..100 &&
            !power.isPowerSaveMode && power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
    }
    private suspend fun eraseUnchangedReport(id: String, expected: String?, threadId: String) {
        if (expected == null) return
        app.repository.deletePrivateCallFollowUpReportIfUnchanged(threadId, id, expected)
    }
    private fun notifyReport(job: CallPipelineJob) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "통화 후속 보고서", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, job.queueId.hashCode(), Intent(context, MainActivity::class.java)
            .putExtra("open_thread_id", job.receipt.reportThreadId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(job.queueId.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("통화 녹음 후속 보고서").setContentText("전사와 후속 제안을 확인해주세요. 중요 항목은 확인이 필요합니다.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
    private fun schedule(job: CallPipelineJob, append: Boolean, deferred: Boolean = false, replace: Boolean = false) {
        work.enqueueUniqueWork(workName(job.queueId), when { replace -> ExistingWorkPolicy.REPLACE; append -> ExistingWorkPolicy.APPEND_OR_REPLACE; else -> ExistingWorkPolicy.KEEP },
            OneTimeWorkRequestBuilder<CallFollowUpProcessWorker>().setInputData(workDataOf("queueId" to job.queueId, "epoch" to job.epoch))
                .setInitialDelay(if (deferred) 15 * 60 else 3, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).addTag("call-follow-up-pipeline-v1").build())
    }
    companion object {
        private const val STAGE_MILLIS = 330_000L
        private const val CHANNEL = "call_follow_up_reports_v1"
        private val STOPPED = setOf("PAUSED", "COMPLETE", "REVIEW_REQUIRED", "INVALIDATED")
        private fun workName(id: String) = "call-follow-up-pipeline-v1.$id"
        private fun fileSha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
