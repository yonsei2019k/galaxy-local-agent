package com.user.localagent.audio

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.user.localagent.AppContainer
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.data.LocalRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SelectedAudioTranscriptionResult(
    val transcriptId: String,
    val manifestNoteId: String,
    val manifestSha256: String,
    val sourceTextSha256: String,
    val chunkCount: Int,
    val durationMillis: Long,
    val engineId: String,
    val pageCount: Int,
)

/** System-picker UI bridge only. The caller owns RuntimeGate and the captured original scope. */
class SelectedAudioTranscription(
    private val context: Context,
    private val app: AppContainer,
    private val scopedRepo: LocalRepository,
    private val originThreadId: String,
    private val originSelectionId: String,
    private val checkStillAuthorized: () -> Unit,
    private val discardCallback: suspend (String, List<IncompleteAudioNote>) -> AudioCleanupResult,
) {
    private val started = AtomicBoolean(false)
    @Volatile var lastCleanupResult: AudioCleanupResult? = null
        private set
    @Volatile var completedResult: SelectedAudioTranscriptionResult? = null
        private set

    init {
        require(originThreadId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(originSelectionId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
    }

    suspend fun transcribe(uri: Uri, onProgress: (Int, Long) -> Unit = { _, _ -> }): SelectedAudioTranscriptionResult =
        withContext(Dispatchers.IO) {
            check(started.compareAndSet(false, true)) { "이미 처리한 자료 선택 요청입니다. 파일을 다시 선택해주세요." }
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "시스템 파일 선택으로 고른 오디오가 필요합니다." }
            check(RuntimeGate.inference.get()) { "현재 음성 작업의 모델 실행 범위가 필요합니다." }
            val ownedManifestNotes = mutableListOf<IncompleteAudioNote>()
            var worker: LocalAudioTranscriber? = null
            var transcription: AudioTranscriptionResult? = null
            var committed = false
            try {
                withTimeout(12L * 60 * 60 * 1000) {
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    val name = displayName(uri)
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    worker = LocalAudioTranscriber(app, AudioChunkDecoder(context), scopedRepo, checkStillAuthorized, discardCallback)
                    transcription = checkNotNull(worker).transcribe(uri) { index, start ->
                        checkStillAuthorized(); onProgress(index, start)
                    }
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    val result = checkNotNull(transcription)
                    val transcriptId = checkNotNull(result.transcriptRunId)
                    val engineId = checkNotNull(result.engineId)
                    RecordingTranscriptManifest.validateChunks(result.chunks, result.durationMillis)
                    check(result.chunkCount == result.chunks.size && result.noteIds == result.chunks.map { it.noteId })
                    val bodies = result.chunks.map { chunk ->
                        currentCoroutineContext().ensureActive(); checkStillAuthorized()
                        val note = checkNotNull(scopedRepo.getNote(chunk.noteId)) { "저장한 전사 조각을 다시 읽지 못했습니다." }
                        check(note.collection == "audio-transcripts" && RecordingTranscriptManifest.sha256(note.body) == chunk.noteSha256)
                        note.body
                    }
                    val textSha = RecordingTranscriptManifest.sourceTextSha256(bodies)
                    val pages = result.chunks.chunked(RecordingTranscriptManifest.PAGE_SIZE).mapIndexed { index, chunks ->
                        currentCoroutineContext().ensureActive(); checkStillAuthorized()
                        val id = UUID.randomUUID().toString()
                        val body = RecordingTranscriptManifest.pageJson(transcriptId, index, chunks)
                        val sha = RecordingTranscriptManifest.sha256(body)
                        ownedManifestNotes += IncompleteAudioNote(id, sha) // Before SQLite may commit and suspend/cancel.
                        val note = scopedRepo.saveNote("녹음 전사 근거 목록 · ${index + 1}", body, RecordingTranscriptManifest.COLLECTION, id)
                        check(scopedRepo.getNote(id) == note) { "전사 근거 목록 저장을 확인하지 못했습니다." }
                        TranscriptManifestPage(id, sha)
                    }
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    val rootId = UUID.randomUUID().toString()
                    val rootBody = RecordingTranscriptManifest.rootForUserSelection(transcriptId, originThreadId, originSelectionId,
                        name, result.durationMillis, engineId, textSha, result.chunkCount, pages)
                    val rootSha = RecordingTranscriptManifest.sha256(rootBody)
                    ownedManifestNotes += IncompleteAudioNote(rootId, rootSha)
                    val root = scopedRepo.saveNote("녹음 전사 자료 · $name", rootBody, RecordingTranscriptManifest.COLLECTION, rootId)
                    check(scopedRepo.getNote(rootId) == root) { "전사 자료 저장을 확인하지 못했습니다." }
                    // Verify the complete chain after every publication, rather than trusting only
                    // the earlier per-write observations while another UI operation could edit it.
                    pages.forEach { page ->
                        currentCoroutineContext().ensureActive(); checkStillAuthorized()
                        val saved = checkNotNull(scopedRepo.getNote(page.noteId))
                        check(saved.collection == RecordingTranscriptManifest.COLLECTION && RecordingTranscriptManifest.sha256(saved.body) == page.sha256)
                    }
                    result.chunks.forEach { chunk ->
                        currentCoroutineContext().ensureActive(); checkStillAuthorized()
                        val saved = checkNotNull(scopedRepo.getNote(chunk.noteId))
                        check(saved.collection == "audio-transcripts" && RecordingTranscriptManifest.sha256(saved.body) == chunk.noteSha256)
                    }
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    val finalRoot = checkNotNull(scopedRepo.getNote(rootId))
                    check(finalRoot.collection == RecordingTranscriptManifest.COLLECTION && RecordingTranscriptManifest.sha256(finalRoot.body) == rootSha)
                    currentCoroutineContext().ensureActive(); checkStillAuthorized()
                    SelectedAudioTranscriptionResult(transcriptId, rootId, rootSha, textSha, result.chunkCount,
                        result.durationMillis, engineId, pages.size).also {
                        completedResult = it
                        committed = true
                    }
                }
            } finally {
                if (!committed) withContext(NonCancellable + Dispatchers.IO) {
                    val transcriptId = transcription?.transcriptRunId
                    val owned = (ownedManifestNotes + transcription?.chunks.orEmpty().map { IncompleteAudioNote(it.noteId, it.noteSha256) })
                        .distinctBy { it.noteId }
                    val own = if (transcriptId == null || owned.isEmpty()) AudioCleanupResult() else
                        runCatching { discardCallback(transcriptId, owned) }.getOrElse { AudioCleanupResult(retainedUnverified = owned.size) }
                    lastCleanupResult = own + (worker?.lastCleanupResult ?: AudioCleanupResult())
                }
            }
        }

    private suspend fun displayName(uri: Uri): String = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { runCatching { signal.cancel() } }
        val observed = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val clean = observed.orEmpty().replace(Regex("[\\p{Cntrl}]"), " ").trim()
        var end = minOf(200, clean.length)
        if (end > 0 && end < clean.length && clean[end - 1].isHighSurrogate() && clean[end].isLowSurrogate()) end--
        continuation.resumeWith(Result.success(clean.substring(0, end).ifBlank { "선택한 녹음" }))
    }
}
