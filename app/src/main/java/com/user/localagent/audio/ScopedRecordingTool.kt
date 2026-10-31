package com.user.localagent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.user.localagent.AppContainer
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.RecordingToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/** Caller owns the request RuntimeGate. It reloads its chosen text model on the next agent round. */
class ScopedRecordingTool(
    private val context: Context,
    private val app: AppContainer,
    private val repository: LocalRepository,
    private val store: ListedRecordingStore,
    private val discardIncompleteNotes: suspend (String, List<IncompleteAudioNote>) -> AudioCleanupResult,
) {
    private data class Completed(val rootNoteId: String, val rootSha256: String, val content: JsonObject,
        val pages: List<TranscriptManifestPage>, val chunks: List<AudioTranscriptChunk>)
    private val completed = linkedMapOf<String, Completed>()
    var lastCleanupResult: AudioCleanupResult? = null
        private set

    suspend fun execute(call: ToolCall): ToolResult {
        val createdManifestNotes = mutableListOf<IncompleteAudioNote>()
        var transcription: AudioTranscriptionResult? = null
        var worker: LocalAudioTranscriber? = null
        var committed = false
        var cleanupAttempted = false
        var failureStage = "invalid_input"
        suspend fun cleanup(): AudioCleanupResult = withContext(NonCancellable + Dispatchers.IO) {
            cleanupAttempted = true
            val owned = (createdManifestNotes + transcription?.chunks.orEmpty().map { IncompleteAudioNote(it.noteId, it.noteSha256) })
                .distinctBy { it.noteId }
            val transcriptId = transcription?.transcriptRunId
            val own = if (transcriptId == null || owned.isEmpty()) AudioCleanupResult() else
                runCatching { discardIncompleteNotes(transcriptId, owned) }.getOrElse { AudioCleanupResult(retainedUnverified = owned.size) }
            (own + (worker?.lastCleanupResult ?: AudioCleanupResult())).also { lastCleanupResult = it }
        }
        try {
            RecordingToolCatalog.validate(call)
            failureStage = "runtime_lease_required"
            check(RuntimeGate.inference.get()) { "현재 요청의 모델 실행 범위가 필요합니다." }
            failureStage = "source_expired"
            val selection = store.resolve(call.arguments.getValue("recordingId").jsonPrimitive.content)
            fun verifyAccess() {
                try { store.requireCurrent(selection) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { throw RecordingToolFailure("source_expired") }
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    throw RecordingToolFailure("connection_required", permission)
                }
            }
            verifyAccess()
            completed[selection.recordingId]?.let { cached ->
                failureStage = "stored_source_unavailable"
                val root = repository.getNote(cached.rootNoteId)
                if (root == null || root.collection != RecordingTranscriptManifest.COLLECTION ||
                    RecordingTranscriptManifest.sha256(root.body) != cached.rootSha256) throw RecordingToolFailure("stored_source_changed")
                cached.pages.forEach { page ->
                    currentCoroutineContext().ensureActive(); verifyAccess()
                    val note = checkNotNull(repository.getNote(page.noteId))
                    if (note.collection != RecordingTranscriptManifest.COLLECTION || RecordingTranscriptManifest.sha256(note.body) != page.sha256)
                        throw RecordingToolFailure("stored_source_changed")
                }
                cached.chunks.forEach { chunk ->
                    currentCoroutineContext().ensureActive(); verifyAccess()
                    val note = checkNotNull(repository.getNote(chunk.noteId))
                    if (note.collection != "audio-transcripts" || RecordingTranscriptManifest.sha256(note.body) != chunk.noteSha256)
                        throw RecordingToolFailure("stored_source_changed")
                }
                verifyAccess()
                return ToolResult(call.id, true, JsonObject(cached.content + ("reusedVerifiedResult" to JsonPrimitive(true))).toString(),
                    VerificationState.VERIFIED, "같은 실행의 저장·재조회된 전사 결과 재사용 · 추가 음성 처리 없음")
            }
            selection.durationMillis?.let { if (it > AudioChunkDecoder.MAX_DURATION_MS) throw RecordingToolFailure("input_limit_exceeded") }
            failureStage = "model_required"
            app.models.asrModel() // Availability only; never resolves/downloads a new model.
            failureStage = "asr_unavailable"
            worker = LocalAudioTranscriber(app, AudioChunkDecoder(context), repository, ::verifyAccess, discardIncompleteNotes)
            transcription = worker.transcribe(Uri.parse(selection.contentUri))
            failureStage = "transcript_verification_failed"
            val result = checkNotNull(transcription)
            val transcriptId = requireNotNull(result.transcriptRunId)
            RecordingTranscriptManifest.validateChunks(result.chunks, result.durationMillis)
            check(result.chunkCount == result.chunks.size && result.noteIds == result.chunks.map { it.noteId })
            val bodies = result.chunks.map { chunk ->
                currentCoroutineContext().ensureActive(); verifyAccess()
                val note = checkNotNull(repository.getNote(chunk.noteId)) { "전사 조각을 다시 읽지 못했습니다." }
                check(note.collection == "audio-transcripts" && RecordingTranscriptManifest.sha256(note.body) == chunk.noteSha256)
                note.body
            }
            val sourceTextSha256 = RecordingTranscriptManifest.sourceTextSha256(bodies)
            val pages = result.chunks.chunked(RecordingTranscriptManifest.PAGE_SIZE).mapIndexed { index, chunks ->
                currentCoroutineContext().ensureActive(); verifyAccess()
                val id = UUID.randomUUID().toString()
                val body = RecordingTranscriptManifest.pageJson(transcriptId, index, chunks)
                createdManifestNotes += IncompleteAudioNote(id, RecordingTranscriptManifest.sha256(body))
                val note = repository.saveNote("녹음 전사 근거 목록 · ${index + 1}", body, RecordingTranscriptManifest.COLLECTION, id)
                check(repository.getNote(id) == note) { "전사 근거 목록 저장을 확인하지 못했습니다." }
                TranscriptManifestPage(id, RecordingTranscriptManifest.sha256(body))
            }
            currentCoroutineContext().ensureActive(); verifyAccess()
            val rootId = UUID.randomUUID().toString()
            val rootBody = RecordingTranscriptManifest.rootJson(transcriptId, selection, result.durationMillis,
                checkNotNull(result.engineId), sourceTextSha256, result.chunkCount, pages)
            createdManifestNotes += IncompleteAudioNote(rootId, RecordingTranscriptManifest.sha256(rootBody))
            val root = repository.saveNote("녹음 전사 자료 · ${selection.displayName}", rootBody, RecordingTranscriptManifest.COLLECTION, rootId)
            check(repository.getNote(rootId) == root) { "전사 자료 저장을 확인하지 못했습니다." }
            currentCoroutineContext().ensureActive(); verifyAccess()
            val rootSha256 = RecordingTranscriptManifest.sha256(rootBody)
            val content = buildJsonObject {
                put("status", "ok"); put("success", true); put("sourceKind", RecordingTranscriptManifest.KIND)
                put("transcriptId", transcriptId); put("sourceIdentity", "recording-transcript:$transcriptId")
                put("manifestNoteId", rootId); put("manifestSha256", rootSha256); put("sourceTextSha256", sourceTextSha256)
                put("sourceTextHashFormat", "ordered-note-bodies-utf8-lf-v1")
                put("chunkCount", result.chunkCount); put("durationMillis", result.durationMillis); put("engineId", result.engineId)
                put("pageCount", pages.size); put("fullTextPersisted", true)
                put("sourceAudioSha256Verified", false); put("timestampPrecision", "decoded_15_second_window")
                put("wordTimestamps", false); put("speakerDiarization", false); put("speakerIdentity", false)
                put("recognitionAccuracyVerified", false); put("originalSpeechCompletenessVerified", false)
                put("completionScope", "all_decoder_windows_processed_and_saved_notes_read_back")
                put("previewChunks", buildJsonArray { result.chunks.take(3).forEach { add(RecordingTranscriptManifest.chunkJson(it)) } })
                put("nextTool", "analyze_audio_transcript"); put("untrustedSourceData", true)
                put("audioSentToServer", false); put("reusedVerifiedResult", false)
            }
            completed[selection.recordingId] = Completed(rootId, rootSha256, content, pages, result.chunks)
            committed = true
            return ToolResult(call.id, true, content.toString(), VerificationState.VERIFIED,
                "선택한 녹음의 전사 조각·근거 목록 저장과 재조회 확인 · 분석 결론·음성 정확도·화자 구분 검증 아님")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val cleanup = cleanup()
            val failure = error as? RecordingToolFailure
            val code = failure?.code ?: failureStage
            return ToolResult(call.id, false, buildJsonObject {
                put("status", code); put("success", false); put("sourceKind", RecordingTranscriptManifest.KIND)
                put("connectionRequired", code == "connection_required")
                failure?.permission?.let { put("requiredPermission", it) }
                put("automaticRetry", false); put("modelDownloadPerformed", false)
                put("meaning", "No verified transcription manifest was completed. Check current listing, Android audio access and the selected installed ASR model; do not treat metadata as speech content.")
                put("cleanupVerified", cleanup.verified); put("retainedChangedOutputs", cleanup.retainedChanged)
                put("retainedUnverifiedOutputs", cleanup.retainedUnverified)
            }.toString(), VerificationState.FAILED, "녹음 전사·저장 검증을 완료하지 못함")
        } finally {
            if (!committed && !cleanupAttempted) cleanup()
        }
    }
}

private class RecordingToolFailure(val code: String, val permission: String? = null) : IllegalStateException(code)
