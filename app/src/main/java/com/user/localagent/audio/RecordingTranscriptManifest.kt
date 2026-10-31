package com.user.localagent.audio

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Decoder window provenance only. These are not aligned utterance/word/speaker timestamps. */
data class AudioTranscriptChunk(
    val index: Int,
    val noteId: String,
    val startMillis: Long,
    val endMillis: Long,
    val noteSha256: String,
)

data class TranscriptManifestPage(val noteId: String, val sha256: String)
data class IncompleteAudioNote(val noteId: String, val bodySha256: String)
data class AudioCleanupResult(val deleted: Int = 0, val missing: Int = 0,
    val retainedChanged: Int = 0, val retainedUnverified: Int = 0) {
    val verified: Boolean get() = retainedChanged == 0 && retainedUnverified == 0
    operator fun plus(other: AudioCleanupResult) = AudioCleanupResult(deleted + other.deleted, missing + other.missing,
        retainedChanged + other.retainedChanged, retainedUnverified + other.retainedUnverified)
}

object RecordingTranscriptManifest {
    const val COLLECTION = "recording-transcription-manifests"
    const val KIND = "recording_transcript"
    const val SCHEMA_VERSION = 1
    const val PAGE_SIZE = 20
    const val MAX_CHUNKS = 480
    const val MAX_DURATION_MILLIS = 7_200_000L

    fun validateChunks(chunks: List<AudioTranscriptChunk>, durationMillis: Long) {
        require(durationMillis in 1..MAX_DURATION_MILLIS && chunks.size in 1..MAX_CHUNKS)
        require(chunks.map { it.noteId }.distinct().size == chunks.size)
        var previousEnd = 0L
        chunks.forEachIndexed { index, chunk ->
            require(chunk.index == index && chunk.noteId.matches(UUID_PATTERN) && chunk.noteSha256.matches(SHA_PATTERN))
            require(chunk.startMillis == previousEnd && chunk.endMillis > chunk.startMillis &&
                chunk.endMillis - chunk.startMillis <= 15_000 && chunk.endMillis <= durationMillis)
            previousEnd = chunk.endMillis
        }
        require(previousEnd == durationMillis)
    }

    fun pageJson(transcriptId: String, pageIndex: Int, chunks: List<AudioTranscriptChunk>): String {
        require(transcriptId.matches(UUID_PATTERN) && pageIndex in 0 until (MAX_CHUNKS / PAGE_SIZE) && chunks.size in 1..PAGE_SIZE)
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION); put("kind", "${KIND}_page"); put("transcriptId", transcriptId); put("pageIndex", pageIndex)
            put("chunks", buildJsonArray { chunks.forEach { add(chunkJson(it)) } })
        }.toString().also { require(it.length <= 12_000) }
    }

    fun rootJson(transcriptId: String, selection: RecordingSelection, durationMillis: Long, engineId: String,
        sourceTextSha256: String, chunkCount: Int, pages: List<TranscriptManifestPage>): String {
        require(transcriptId.matches(UUID_PATTERN) && sourceTextSha256.matches(SHA_PATTERN))
        require(durationMillis in 1..MAX_DURATION_MILLIS && chunkCount in 1..MAX_CHUNKS && engineId.length in 1..100)
        require(pages.size == (chunkCount + PAGE_SIZE - 1) / PAGE_SIZE && pages.map { it.noteId }.distinct().size == pages.size)
        pages.forEach { require(it.noteId.matches(UUID_PATTERN) && it.sha256.matches(SHA_PATTERN)) }
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION); put("kind", KIND); put("transcriptId", transcriptId)
            put("sourceIdentity", "recording-transcript:$transcriptId"); put("requestRunId", selection.requestRunId); put("threadId", selection.threadId)
            put("sourceDisplayName", selection.displayName); put("recordingId", selection.recordingId)
            put("sourceIdentityScope", "listed_mediastore_entry_and_verified_saved_transcript")
            put("sourceAudioSha256Verified", false)
            put("durationMillis", durationMillis); put("engineId", engineId); put("chunkCount", chunkCount)
            put("fullTextPersisted", true); put("sourceTextSha256", sourceTextSha256)
            put("sourceTextHashFormat", "ordered-note-bodies-utf8-lf-v1")
            put("pages", buildJsonArray { pages.forEach { page -> add(buildJsonObject { put("noteId", page.noteId); put("sha256", page.sha256) }) } })
            put("timestampPrecision", "decoded_15_second_window"); put("wordTimestamps", false); put("speakerDiarization", false); put("speakerIdentity", false)
            put("completionScope", "all_decoder_windows_processed_and_saved_notes_read_back")
            put("recognitionAccuracyVerified", false); put("originalSpeechCompletenessVerified", false)
            put("untrustedSourceData", true)
        }.toString().also { require(it.length <= 12_000) }
    }

    fun chunkJson(chunk: AudioTranscriptChunk) = buildJsonObject {
        put("index", chunk.index); put("noteId", chunk.noteId); put("startMillis", chunk.startMillis); put("endMillis", chunk.endMillis); put("noteSha256", chunk.noteSha256)
    }

    /** A direct host UI selection, not a MediaStore-list capability and not a synthetic chat run. */
    fun rootForUserSelection(transcriptId: String, threadId: String, pickerSelectionId: String, sourceDisplayName: String,
        durationMillis: Long, engineId: String, sourceTextSha256: String, chunkCount: Int,
        pages: List<TranscriptManifestPage>): String {
        require(transcriptId.matches(UUID_PATTERN) && pickerSelectionId.matches(UUID_PATTERN) && sourceTextSha256.matches(SHA_PATTERN))
        require(threadId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && sourceDisplayName.length in 1..200 && '\u0000' !in sourceDisplayName)
        require(durationMillis in 1..MAX_DURATION_MILLIS && chunkCount in 1..MAX_CHUNKS && engineId.length in 1..100)
        require(pages.size == (chunkCount + PAGE_SIZE - 1) / PAGE_SIZE && pages.map { it.noteId }.distinct().size == pages.size)
        pages.forEach { require(it.noteId.matches(UUID_PATTERN) && it.sha256.matches(SHA_PATTERN)) }
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION); put("kind", KIND); put("transcriptId", transcriptId)
            put("sourceIdentity", "recording-transcript:$transcriptId"); put("threadId", threadId)
            put("origin", "user_selected_document"); put("pickerSelectionId", pickerSelectionId); put("requestRunId", JsonNull)
            put("sourceDisplayName", sourceDisplayName)
            put("sourceIdentityScope", "user_selected_document_and_verified_saved_transcript")
            put("sourceAudioSha256Verified", false)
            put("durationMillis", durationMillis); put("engineId", engineId); put("chunkCount", chunkCount)
            put("fullTextPersisted", true); put("sourceTextSha256", sourceTextSha256)
            put("sourceTextHashFormat", "ordered-note-bodies-utf8-lf-v1")
            put("pages", buildJsonArray { pages.forEach { page -> add(buildJsonObject { put("noteId", page.noteId); put("sha256", page.sha256) }) } })
            put("timestampPrecision", "decoded_15_second_window"); put("wordTimestamps", false); put("speakerDiarization", false); put("speakerIdentity", false)
            put("completionScope", "all_decoder_windows_processed_and_saved_notes_read_back")
            put("recognitionAccuracyVerified", false); put("originalSpeechCompletenessVerified", false)
            put("untrustedSourceData", true)
        }.toString().also { require(it.length <= 12_000) }
    }

    fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex()

    /** Recompute from every ordered saved note body, including its provenance header, followed by one LF each. */
    fun sourceTextSha256(noteBodies: List<String>): String {
        require(noteBodies.size in 1..MAX_CHUNKS)
        val digest = MessageDigest.getInstance("SHA-256")
        noteBodies.forEach { require(it.length in 1..12_000); digest.update(it.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte()) }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val SHA_PATTERN = Regex("[a-f0-9]{64}")
}
