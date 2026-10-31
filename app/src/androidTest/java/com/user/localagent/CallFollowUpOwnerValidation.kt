package com.user.localagent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.callfollowup.*
import com.user.localagent.core.*
import com.user.localagent.data.DraftStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opted-in real ONE recording. No raw audio/transcript/filename/number enters console or reports. */
class CallFollowUpOwnerValidation {
    @Test fun listMetadataOnlyInExplicitOwnerCallFolder() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.listCandidates()
    }

    @Test fun configureOnlyExplicitOwnerCallFolder() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.configureFolder()
    }

    @Test fun selectedRecordingCreatesOneDurableQueueWithNoInventedCallTime() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.intakeOne()
        if(InstrumentationRegistry.getArguments().getString("awaitSelectedCallReport")=="true")probe.awaitReport()
    }

    @Test fun awaitOriginalQueuedRecordingReportWithoutDrivingOrReplayingTheWorker() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.awaitReport()
    }

    @Test fun resumeSavedRecordingAndAwaitReport() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.resumeOnceAndAwait()
    }
    @Test fun inspectSavedRecordingResumeReadinessWithoutChangingJob() = runBlocking(Dispatchers.IO) {
        val probe = OwnerCallProbe(); probe.requireGate(); probe.inspectResumeReadiness()
    }
}

@Serializable private data class OwnerCallReceipt(val sourceKey: String, val queueId: String,
    val snapshotId: String, val audioSha256: String, val projectId: String, val threadId: String,
    val generation: Long, val recordedAtMillis: Long)

private class OwnerCallProbe {
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app by lazy { (context.applicationContext as LocalAgentApplication).container }
    private val receiptFile = DraftStore(context, "owner-call-follow-up-validation-receipt-v1", 12_000)
    private val store = CallFollowUpQueueStore(context)
    private val report = AtomicFile(File(context.filesDir, "validation-reports/call-followup-owner-progress.json"))
    private val sanitizedReport by lazy {
        AtomicFile(File(checkNotNull(context.getExternalFilesDir(null)), "benchmarks/call-followup-owner-validation.json"))
    }
    private var lastSanitized = JSONObject()
    private var stage = "starting"

    fun requireGate() {
        assumeTrue("ownerCallFolderApproved=true required", args.getString("ownerCallFolderApproved") == "true")
        requireGranted(Manifest.permission.READ_PHONE_STATE)
        requireGranted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    suspend fun listCandidates() = guarded("list_exact_folder_metadata") {
        val grant = requestedGrant()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0"
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_PENDING)
        val rows = JSONArray(); var more = false
        context.contentResolver.query(MediaStore.Audio.Media.getContentUri(grant.volume), columns, selection,
            arrayOf(grant.location), "${MediaStore.MediaColumns.DATE_ADDED} DESC,${MediaStore.Audio.Media._ID} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                if (rows.length() == 20) { more = true; break }
                rows.put(JSONObject().put("recording_id", cursor.getLong(0)).put("duration_ms", cursor.getLong(1))
                    .put("bytes", cursor.getLong(2)).put("added_at_ms", cursor.getLong(3) * 1000)
                    .put("modified_at_ms", cursor.getLong(4) * 1000).put("pending", cursor.getLong(5) != 0L))
            }
        } ?: error("The exact approved folder metadata could not be read")
        checkpoint("folder_metadata_listed", JSONObject().put("folder_grant_sha256", grant.id)
            .put("recordings", rows).put("has_more", more).put("automatic_analysis_enabled_by_this_method", false))
    }

    suspend fun configureFolder() = guarded("configure_folder") {
        val grant = requestedGrant()
        require(!RuntimeGate.inference.get() && !RuntimeGate.benchmark.get()) { "An existing native task has priority" }
        // Only exact-folder metadata; this method never opens an audio stream or picks another folder.
        val count = exactFolderAudioCount(grant)
        require(count > 0) { "The explicitly requested folder has no visible audio metadata" }
        val priorSource = app.callFollowUp.configuredSource()
        val priorState = app.callFollowUp.state()
        require(!priorState.enabled || priorSource?.id == grant.id) { "An enabled different source must be changed through owner settings" }
        val before = System.currentTimeMillis()
        val enabled = app.callFollowUp.setEnabled(true, grant)
        val saved = CallFollowUpJournal(context).config()
        check(enabled.enabled && saved.enabled && saved.source == grant)
        if (!priorState.enabled) check(saved.enabledAtMillis >= before)
        checkpoint("folder_configured", JSONObject().put("folder_metadata_count", count)
            .put("readiness", enabled.readiness.name).put("generation", enabled.generation))
        // Leave requested automatic detection ON. No old file is ingested by this configuration test.
    }

    suspend fun intakeOne() = guarded("select_one") {
        val grant = requestedGrant()
        check(app.callFollowUp.configuredSource() == grant && app.callFollowUp.state().enabled)
        awaitIdleCoordinator()
        check(!RuntimeGate.inference.get() && !RuntimeGate.benchmark.get())
        check(app.callFollowUpPipeline.readiness() == PipelineReadiness.READY) { "Installed call-only ASR/text models are required" }
        val id = args.getString("callRecordingId")?.toLongOrNull()?.takeIf { it > 0 }
            ?: error("One owner-selected positive MediaStore ID is required")
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(grant.volume), id)
        val duration = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
        require(duration in 1..120_000) { "Select one recording of at most two minutes for this bounded owner validation" }
        // Prove this is the owner-selected exact folder, not a guessed filename or caller identity.
        val observation = CallRecordingDiscovery(context).list(grant, 0).singleOrNull { it.uri == uri.toString() }
            ?: error("The selected ID is outside the approved folder")
        require(observation.byteCount in 1..MAX_RECORDING_BYTES && !observation.pending)
        val previous = CallFollowUpJournal(context).job(observation.sourceKey)
        require(previous == null) { "Choose an unprocessed recording; this proof must not adopt an earlier run" }
        val config = CallFollowUpJournal(context).config()
        val selectedAt = System.currentTimeMillis()
        stage = "waiting_for_stability_and_queue"
        val sourceKey = app.callFollowUp.requestSelectedRecording(uri)
        check(sourceKey == observation.sourceKey)
        val queueDeadline = SystemClock.elapsedRealtime() + 240_000
        var receipt: CallFollowUpEnqueueReceipt? = null
        while (receipt == null && SystemClock.elapsedRealtime() < queueDeadline) {
            currentCoroutineContext().ensureActive()
            val job = CallFollowUpJournal(context).job(sourceKey)
            check(job == null || job.state !in setOf(RecordingJobState.FORGOTTEN, RecordingJobState.CANCELLED, RecordingJobState.SOURCE_DELETED, RecordingJobState.NEEDS_REVIEW))
            receipt = job?.receipt
            checkpoint(stage, JSONObject().put("detector_state", job?.state?.name ?: "absent")
                .put("snapshot_persisted", job?.snapshot != null).put("receipt_persisted", receipt != null))
            if (receipt == null) delay(2000)
        }
        val queued = checkNotNull(receipt) { "Normal workers did not produce a durable queue within four minutes" }
        val detector = checkNotNull(CallFollowUpJournal(context).job(sourceKey))
        val snapshot = checkNotNull(detector.snapshot)
        require(snapshot.durationMillis <= 120_000) { "Owner validation is limited to one recording of at most two minutes" }
        check(detector.ownerSelectedAtMillis?.let { it >= selectedAt } == true)
        check(CallFollowUpJournal(context).config().enabledAtMillis == config.enabledAtMillis)
        val job = checkNotNull(store.job(queued.queueId))
        check(job.snapshot == snapshot && job.authorization.callEndedSignalAtMillis == null)
        check(job.asr.model.id in setOf("asr-qwen-17b", "asr-qwen-06b") && job.asr.language == "ko")
        check(app.callFollowUpPipeline.find(snapshot.snapshotId) == queued)
        check(app.callFollowUp.requestSelectedRecording(uri) == sourceKey)
        check(CallFollowUpJournal(context).job(sourceKey)?.receipt == queued)
        val saved = OwnerCallReceipt(sourceKey, queued.queueId, snapshot.snapshotId, snapshot.sha256,
            queued.privateProjectId, queued.reportThreadId, job.authorization.generation, System.currentTimeMillis())
        val encoded = Json.encodeToString(saved)
        receiptFile.write(encoded); check(receiptFile.readStrict() == encoded)
        checkpoint("queue_verified", JSONObject().put("queue_id", saved.queueId).put("report_thread_id", saved.threadId)
            .put("private_project_id", saved.projectId).put("snapshot_id", saved.snapshotId).put("audio_sha256", saved.audioSha256)
            .put("duration_ms", snapshot.durationMillis).put("asr_model_id", job.asr.model.id)
            .put("call_time_anchor_known", false).put("source_untouched", true))
    }

    suspend fun awaitReport() = guarded("await_normal_worker") {
        val saved = Json.decodeFromString<OwnerCallReceipt>(receiptFile.readStrict())
        val timeoutSeconds = args.getString("ownerCallReportTimeoutSeconds")?.toLongOrNull() ?: 1800
        require(timeoutSeconds in 60..3600)
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        var done: CallPipelineJob? = null
        while (done == null && SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val job = checkNotNull(store.job(saved.queueId))
            val diagnostic = app.callFollowUpPipeline.latestMapDiagnostics(saved.queueId)
            check(job.snapshot.snapshotId == saved.snapshotId && job.snapshot.sha256 == saved.audioSha256 &&
                job.receipt.reportThreadId == saved.threadId && job.receipt.privateProjectId == saved.projectId)
            checkpoint(stage, JSONObject().put("queue_id", saved.queueId).put("status", job.status)
                .put("decoded_chunks", job.chunkCount).put("analyzed_chunks", job.analyzedChunks)
                .put("duration_ms", job.snapshot.durationMillis).put("asr_model_id", job.asr.model.id).put("text_model_id", job.textModel.id)
                .put("asr_model_sha256", job.asr.model.sha256).put("asr_encoder_sha256", job.asr.projector?.sha256 ?: JSONObject.NULL)
                .put("text_model_sha256", job.textModel.sha256)
                .put("map_attempt_phase", diagnostic?.phase?.name ?: JSONObject.NULL)
                .put("map_output_characters", diagnostic?.outputCharacters ?: JSONObject.NULL)
                .put("map_attempt_elapsed_ms", diagnostic?.elapsedMillis ?: JSONObject.NULL)
                .put("map_last_progress_at_ms", diagnostic?.lastProgressAtMillis ?: JSONObject.NULL)
                .put("map_updated_at_ms", diagnostic?.updatedAtMillis ?: JSONObject.NULL)
                .put("map_input_token_count", diagnostic?.inputTokenCount ?: JSONObject.NULL)
                .put("map_input_count_kind", diagnostic?.inputCountKind ?: "UNAVAILABLE")
                .put("map_stop_reason", diagnostic?.stopReason ?: JSONObject.NULL)
                .put("map_stop_evidence", diagnostic?.stopEvidence ?: JSONObject.NULL)
                .put("map_cap_reached", diagnostic?.capReached ?: JSONObject.NULL)
                .put("map_validation_category", diagnostic?.validation?.name ?: JSONObject.NULL)
                .put("map_native_phase", diagnostic?.nativePhase ?: JSONObject.NULL)
                .put("map_native_prompt_done", diagnostic?.nativePromptProcessed ?: JSONObject.NULL)
                .put("map_native_prompt_total", diagnostic?.nativePromptTotal ?: JSONObject.NULL)
                .put("map_native_sampled_tokens", diagnostic?.nativeSampledTokens ?: JSONObject.NULL)
                .put("map_native_raw_utf8_bytes", diagnostic?.nativeRawUtf8Bytes ?: JSONObject.NULL)
                .put("map_native_visible_utf8_bytes", diagnostic?.nativeVisibleUtf8Bytes ?: JSONObject.NULL)
                .put("map_native_elapsed_ms", diagnostic?.nativeElapsedMillis ?: JSONObject.NULL)
                .put("transcription_exhausted", job.transcriptionExhausted).put("error_code", job.errorCode ?: JSONObject.NULL))
            check(job.status !in setOf("PAUSED", "INVALIDATED") && !(job.status == "REVIEW_REQUIRED" && job.errorCode != null)) {
                "The normal pipeline stopped; inspect private enum-only checkpoint"
            }
            if (job.status == "COMPLETE" || job.status == "REVIEW_REQUIRED" && job.errorCode == null) done = job else delay(5000)
        }
        val job = checkNotNull(done) { "Normal worker report deadline exceeded; no completion is claimed" }
        verifyCompleteSavedReport(job, saved)
    }

    suspend fun inspectResumeReadiness() = guarded("inspect_saved_recording") {
        val saved = Json.decodeFromString<OwnerCallReceipt>(receiptFile.readStrict())
        val job = checkNotNull(store.job(saved.queueId))
        val journal = CallFollowUpJournal(context)
        val config = journal.config()
        val detector = journal.job(saved.sourceKey)
        val thread = app.repository.chat.thread(saved.threadId)
        val project = app.repository.chat.project(saved.projectId)
        val fields = JSONObject().put("status",job.status).put("error_code",job.errorCode ?: JSONObject.NULL)
            .put("detector_state",detector?.state?.name ?: "absent")
            .put("decoded_chunks",job.chunkCount).put("analyzed_chunks",job.analyzedChunks)
            .put("transcription_exhausted",job.transcriptionExhausted)
            .put("configured_enabled",config.enabled)
            .put("generation_matches",config.generation==job.authorization.generation)
            .put("grant_matches",config.source?.id==job.authorization.sourceGrantId)
            .put("detector_generation_matches",detector?.generation==job.authorization.generation)
            .put("detector_snapshot_matches",detector?.snapshot==job.snapshot)
            .put("detector_modified_matches",detector?.observation?.modifiedAtMillis==job.snapshot.modifiedAtMillis)
            .put("source_fenced",store.sourceInvalidated(job.snapshot.sourceKey,job.authorization.generation))
            .put("controller_authorized",app.callFollowUp.isAuthorized(job.snapshot,job.authorization))
            .put("source_still_present",app.callFollowUp.sourceStillPresent(job.snapshot,job.authorization))
            .put("scope_bound",job.scopeBound).put("thread_exists",thread!=null).put("project_exists",project!=null)
            .put("thread_revision_matches",thread?.revision==job.threadRevision)
            .put("project_revision_matches",project?.revision==job.projectRevision)
            .put("thread_project_matches",thread?.projectId==project?.id)
            .put("thread_archived",thread?.archived ?: false).put("project_archived",project?.archived ?: false)
            .put("project_memory_private",project?.settings?.memoryMode=="project" && !project.settings.useOwnerMemory && !project.settings.learnOwnerFacts)
        checkpoint("resume_readiness",fields)
    }

    suspend fun resumeOnceAndAwait() {
        guarded("resume_saved_recording") {
            val saved = Json.decodeFromString<OwnerCallReceipt>(receiptFile.readStrict())
            val original = checkNotNull(store.job(saved.queueId))
            check(original.snapshot.snapshotId == saved.snapshotId && original.snapshot.sha256 == saved.audioSha256 &&
                original.receipt.privateProjectId == saved.projectId && original.receipt.reportThreadId == saved.threadId &&
                original.authorization.generation == saved.generation)
            check(original.transcriptionExhausted && original.chunkCount > 0) { "This resume gate reuses an already complete ASR source only" }
            check(original.status in setOf("PAUSED", "DEFERRED") || original.status == "REVIEW_REQUIRED" && original.errorCode != null)
            val rawVersion = (0 until original.chunkCount).map { checkNotNull(store.raw(saved.queueId, it)) }
            awaitIdleCoordinator()
            app.callFollowUpPipeline.retry(saved.queueId) // Exactly one explicit retry, no new intake or ASR reset.
            val resumed = checkNotNull(store.job(saved.queueId))
            check(resumed.snapshot == original.snapshot && resumed.asr == original.asr && resumed.textModel == original.textModel &&
                resumed.chunkCount == original.chunkCount && resumed.transcriptionExhausted && resumed.epoch > original.epoch)
            check((0 until resumed.chunkCount).map { checkNotNull(store.raw(saved.queueId, it)) } == rawVersion)
            checkpoint("resume_requested", JSONObject().put("decoded_chunks", resumed.chunkCount).put("analyzed_chunks", resumed.analyzedChunks)
                .put("transcription_exhausted", true).put("status", resumed.status).put("asr_model_id", resumed.asr.model.id)
                .put("text_model_id", resumed.textModel.id).put("asr_model_sha256", resumed.asr.model.sha256)
                .put("text_model_sha256", resumed.textModel.sha256).put("raw_source_reused_unchanged", true))
        }
        awaitReport()
    }

    private suspend fun verifyCompleteSavedReport(job: CallPipelineJob, receipt: OwnerCallReceipt) {
        stage = "verify_report_and_source_chain"
        check(job.transcriptionExhausted && job.chunkCount > 0 && job.analyzedChunks == job.chunkCount)
        check(job.authorization.callEndedSignalAtMillis == null)
        val mapStats = JSONArray()
        val chunks = (0 until job.chunkCount).map { index ->
            val raw = checkNotNull(store.raw(job.queueId, index))
            check(raw.audioSha256 == receipt.audioSha256 && raw.modelSha256 == job.asr.model.sha256 && raw.projectorSha256 == job.asr.projector?.sha256)
            check(callDigest(raw.text) == raw.textSha256)
            val map = checkNotNull(store.map(job.queueId, index))
            check(map.modelSha256 == job.textModel.sha256 && callDigest(map.raw) == map.rawSha256)
            mapStats.put(JSONObject().put("index", index).put("model_sha256", map.modelSha256).put("output_sha256", map.rawSha256)
                .put("input_token_count", map.metrics?.inputTokenCount ?: JSONObject.NULL)
                .put("input_count_kind", map.metrics?.inputCountKind ?: "UNAVAILABLE")
                .put("prefill_tokens", map.metrics?.prefillTokens ?: JSONObject.NULL)
                .put("decode_tokens", map.metrics?.decodeTokens ?: JSONObject.NULL)
                .put("stop_reason", map.metrics?.stopReason ?: "UNAVAILABLE")
                .put("stop_evidence", map.metrics?.stopEvidence ?: "UNAVAILABLE")
                .put("cap_reached", map.metrics?.capReached ?: JSONObject.NULL)
                .put("elapsed_ms", map.metrics?.elapsedMillis ?: JSONObject.NULL))
            CallTranscriptChunk("chunk_$index", raw.startMillis, raw.endMillis, raw.text, raw.textSha256)
        }
        val source = CallFollowUpSource.create(receipt.threadId, "call-recordings/${receipt.snapshotId}", receipt.audioSha256,
            maxOf(job.snapshot.durationMillis, job.decodedDurationMillis), null, chunks, true,
            knownAsrOmission = chunks.any { it.text.isBlank() } || (0 until job.chunkCount).any { store.raw(job.queueId, it)?.asrOutputCapReached == true } ||
                kotlin.math.abs(job.decodedDurationMillis - job.snapshot.durationMillis) > 250)
        val validated = CallFollowUpValidator.validate(source, CallProcessingCoverage(chunks.map { it.id }.toSet()),
            CallFollowUpProposal(scopeId = source.scopeId, audioRelativePath = source.audioRelativePath, audioSha256 = source.audioSha256,
                transcriptSha256 = source.transcriptSha256, claimsComplete = false, candidates = job.candidates))
        val message = checkNotNull(app.repository.getMessage(job.publicationId))
        check(message.threadId == receipt.threadId && message.role == "assistant" && message.text.isNotBlank())
        check(callDigest(message.text) == job.publicationSha256 && message.text == job.publicationBody)
        val project = checkNotNull(app.repository.chat.project(receipt.projectId))
        check(!project.settings.useOwnerMemory && !project.settings.learnOwnerFacts && project.settings.memoryMode == "project")
        check(app.repository.chat.toolRunsForThread(receipt.threadId).first().isEmpty()) { "Background reports must not execute external tools" }
        check(validated.canExecuteAutomatically == false && validated.candidates.all { it.requiresApproval })
        checkpoint("report_verified", JSONObject().put("queue_id", receipt.queueId).put("report_thread_id", receipt.threadId)
            .put("status", job.status).put("decoded_chunks", job.chunkCount).put("analyzed_chunks", job.analyzedChunks)
            .put("candidate_count", validated.candidates.size).put("coverage_complete", validated.coverage.completeWithinDeclaredTranscript)
            .put("audio_sha256", receipt.audioSha256).put("transcript_sha256", source.transcriptSha256)
            .put("asr_model_sha256", job.asr.model.sha256).put("asr_encoder_sha256", job.asr.projector?.sha256 ?: JSONObject.NULL)
            .put("text_model_sha256", job.textModel.sha256).put("map_metrics", mapStats)
            .put("report_body_sha256", job.publicationSha256).put("report_verified", true)
            .put("asr_accuracy_verified", false).put("whole_call_meaning_verified", false).put("semantic_accuracy_verified", false)
            .put("automatic_external_actions", 0).put("owner_report_retained", true))
    }

    private fun requestedGrant(): RecordingSourceGrant {
        val path = args.getString("callFolderRelativePath") ?: error("Explicit owner-selected relative folder path required")
        val volume = args.getString("callFolderVolume") ?: "external_primary"
        return RecordingSourceGrant(RecordingSourceKind.MEDIA_FOLDER, path, volume, true).validate()
    }

    private fun exactFolderAudioCount(grant: RecordingSourceGrant): Int {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0"
        return context.contentResolver.query(MediaStore.Audio.Media.getContentUri(grant.volume),
            arrayOf(MediaStore.Audio.Media._ID), selection, arrayOf(grant.location), null)?.use { cursor ->
            var count = 0; while (cursor.moveToNext()) { check(++count <= 5000) }; count
        } ?: error("The approved folder metadata is unavailable")
    }

    private fun requireGranted(permission: String) {
        check(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) { "Existing Android permission is required; this test does not grant it" }
    }

    private suspend fun awaitIdleCoordinator() {
        withTimeout(30_000) { while (!app.runs.selectionReady.value) delay(100) }
        check(!app.runs.busy.value && app.runs.activeRunId == null && app.runs.queued.value.isEmpty()) { "Owner work has priority" }
    }

    private suspend fun guarded(initialStage: String, action: suspend () -> Unit) {
        stage = initialStage
        writeSanitizedCheckpoint(initialStage, JSONObject()) // Never expose an earlier method's stale PASS.
        try { action() }
        catch (error: Throwable) {
            checkpoint("failed", JSONObject().put("failed_stage", stage).put("exception_class", error.javaClass.simpleName)
                .put("error_code",(error as? CallPipelineFailure)?.code ?: JSONObject.NULL))
            // Avoid JUnit dumping a provider URI, raw ASR output or native exception text into console.
            throw AssertionError("Owner call validation failed at $stage; inspect its private metadata checkpoint")
        }
    }

    private fun checkpoint(value: String, fields: JSONObject) {
        check(report.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        fields.put("timestamp_ms", System.currentTimeMillis()).put("stage", value)
            .put("raw_content_exported", false).put("test_drives_native_worker", false)
        val output = report.startWrite()
        try { output.write(fields.toString().toByteArray(Charsets.UTF_8)); report.finishWrite(output) }
        catch (error: Throwable) { report.failWrite(output); throw error }
        writeSanitizedCheckpoint(value, fields)
    }

    /** Strict allowlist. No source/queue/thread/project IDs, URIs, raw text or output hashes. */
    private fun writeSanitizedCheckpoint(value: String, fields: JSONObject) {
        val clean = JSONObject(lastSanitized.toString()) // Preserve last observed counts if a later phase fails.
        val stages = setOf("starting", "list_exact_folder_metadata", "folder_metadata_listed", "configure_folder", "folder_configured",
            "select_one", "waiting_for_stability_and_queue", "queue_verified", "await_normal_worker", "verify_report_and_source_chain", "report_verified",
            "resume_saved_recording", "resume_requested", "inspect_saved_recording", "resume_readiness", "failed")
        clean.put("stage", value.takeIf { it in stages } ?: "unknown_stage")
        clean.put("timestamp_ms", System.currentTimeMillis()).put("raw_content_exported", false).put("sanitized_metadata_only", true)
        clean.put("test_drives_native_worker", false)
        fun selectedText(key: String, allowed: Set<String>) {
            if (!fields.has(key)) return
            val text = fields.opt(key) as? String
            clean.put(key, text?.takeIf { it in allowed } ?: JSONObject.NULL)
        }
        selectedText("failed_stage", stages)
        selectedText("status", CallFollowUpQueueStatus.entries.map { it.name }.toSet())
        selectedText("detector_state", RecordingJobState.entries.map { it.name }.toSet() + "absent")
        selectedText("readiness", CallFollowUpReadiness.entries.map { it.name }.toSet())
        selectedText("exception_class", setOf("AssertionError", "IllegalArgumentException", "IllegalStateException", "SecurityException",
            "CallPipelineFailure", "TimeoutCancellationException", "CancellationException", "IOException"))
        selectedText("error_code", setOf("DEVICE_OR_OWNER_BUSY", "SOURCE_OR_SCOPE_CHANGED", "SOURCE_INVALIDATED", "STAGE_TIME_LIMIT",
            "LOCAL_STAGE_FAILED", "MODEL_FILE_UNAVAILABLE", "BACKGROUND_ASR_MODEL_REQUIRED", "BACKGROUND_QWEN_ASR_UNAVAILABLE",
            "TEXT_MODEL_UNAVAILABLE", "TEXT_MODEL_UNSUPPORTED", "MODEL_INPUT_BUDGET", "MODEL_OUTPUT_LIMIT", "MODEL_OUTPUT_INCOMPLETE",
            "MODEL_EVIDENCE_INVALID", "MODEL_TERMINATION_UNKNOWN", "NO_DECODED_AUDIO", "ASR_OUTPUT_LIMIT", "ANALYSIS_CONTRACT_CHANGED"))
        selectedText("map_attempt_phase", CallMapPhase.entries.map { it.name }.toSet())
        selectedText("map_native_phase", setOf("PREPARE", "SAMPLER_INIT", "PREFILL", "DECODE", "FINISHED"))
        selectedText("map_validation_category", CallMapValidationCategory.entries.map { it.name }.toSet())
        selectedText("map_input_count_kind", setOf("STRUCTURED_RENDERED", "PLAIN_PREFLIGHT_ONLY", "UNAVAILABLE"))
        selectedText("map_stop_reason", setOf("EOG", "MAX_OUTPUT", "CANCELLED", "CONTEXT_REJECTED", "RESOURCE_LIMIT", "ENGINE_ERROR", "SDK_DONE_UNKNOWN"))
        selectedText("map_stop_evidence", setOf("NATIVE", "SDK", "HOST", "INFERRED"))
        val modelIds = setOf("asr-qwen-17b", "asr-qwen-06b", "gemma-e2b", "gemma-e4b", "gemma-e2b-media", "qwen-06b", "qwen-2b", "qwen-4b")
        selectedText("asr_model_id", modelIds); selectedText("text_model_id", modelIds)
        for (key in setOf("decoded_chunks", "analyzed_chunks", "candidate_count", "duration_ms", "folder_metadata_count", "automatic_external_actions",
            "map_output_characters", "map_attempt_elapsed_ms", "map_input_token_count", "map_native_prompt_done", "map_native_prompt_total",
            "map_native_sampled_tokens", "map_native_raw_utf8_bytes", "map_native_visible_utf8_bytes", "map_native_elapsed_ms")) {
            val number = fields.opt(key) as? Number ?: continue
            val integer = number.toLong()
            if (number.toDouble().isFinite() && number.toDouble() == integer.toDouble() && integer in 0..1_000_000_000_000L) clean.put(key, integer)
        }
        for (key in setOf("map_last_progress_at_ms", "map_updated_at_ms")) {
            val number = fields.opt(key) as? Number ?: continue
            val timestamp = number.toLong()
            if (number.toDouble().isFinite() && number.toDouble() == timestamp.toDouble() && timestamp in 0..4_102_444_800_000L)
                clean.put(key, timestamp)
        }
        for (key in setOf("transcription_exhausted", "snapshot_persisted", "receipt_persisted", "call_time_anchor_known", "source_untouched",
            "coverage_complete", "report_verified", "asr_accuracy_verified", "whole_call_meaning_verified", "semantic_accuracy_verified",
            "owner_report_retained", "automatic_analysis_enabled_by_this_method", "has_more", "map_cap_reached", "raw_source_reused_unchanged",
            "configured_enabled", "generation_matches", "grant_matches", "detector_generation_matches", "detector_snapshot_matches",
            "detector_modified_matches", "source_fenced", "controller_authorized", "source_still_present", "scope_bound", "thread_exists",
            "project_exists", "thread_revision_matches", "project_revision_matches", "thread_project_matches", "thread_archived",
            "project_archived", "project_memory_private")) {
            (fields.opt(key) as? Boolean)?.let { clean.put(key, it) }
        }
        for (key in setOf("asr_model_sha256", "asr_encoder_sha256", "text_model_sha256")) {
            if (fields.has(key)) clean.put(key, (fields.opt(key) as? String)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: JSONObject.NULL)
        }
        (fields.opt("map_metrics") as? JSONArray)?.let { rows ->
            val metrics = JSONArray()
            for (index in 0 until minOf(rows.length(), 480)) {
                val row = rows.optJSONObject(index) ?: continue
                val entry = JSONObject()
                for (key in setOf("index", "input_token_count", "prefill_tokens", "decode_tokens", "elapsed_ms")) {
                    val number = row.opt(key) as? Number
                    val integer = number?.toLong()
                    entry.put(key, if (number != null && integer != null && number.toDouble().isFinite() &&
                        number.toDouble() == integer.toDouble() && integer in 0..1_000_000_000L) integer else JSONObject.NULL)
                }
                entry.put("input_count_kind", (row.opt("input_count_kind") as? String)?.takeIf {
                    it in setOf("STRUCTURED_RENDERED", "PLAIN_PREFLIGHT_ONLY", "UNAVAILABLE") } ?: "UNAVAILABLE")
                entry.put("stop_reason", (row.opt("stop_reason") as? String)?.takeIf {
                    it in setOf("EOG", "MAX_OUTPUT", "CANCELLED", "CONTEXT_REJECTED", "RESOURCE_LIMIT", "ENGINE_ERROR", "SDK_DONE_UNKNOWN", "UNAVAILABLE") } ?: "UNAVAILABLE")
                entry.put("stop_evidence", (row.opt("stop_evidence") as? String)?.takeIf {
                    it in setOf("NATIVE", "SDK", "HOST", "INFERRED", "UNAVAILABLE") } ?: "UNAVAILABLE")
                entry.put("cap_reached", row.opt("cap_reached") as? Boolean ?: JSONObject.NULL)
                metrics.put(entry)
            }
            clean.put("map_metrics", metrics)
        }
        check(sanitizedReport.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = sanitizedReport.startWrite()
        try { output.write(clean.toString().toByteArray(Charsets.UTF_8)); sanitizedReport.finishWrite(output) }
        catch (error: Throwable) { sanitizedReport.failWrite(output); throw error }
        lastSanitized = clean
    }
}
