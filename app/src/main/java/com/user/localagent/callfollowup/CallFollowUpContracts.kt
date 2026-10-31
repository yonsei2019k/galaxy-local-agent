package com.user.localagent.callfollowup

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable enum class RecordingSourceKind { MEDIA_FOLDER, SAF_TREE }

/** Created only by the owner's folder selection, never by a recording, model or filename. */
@Serializable data class RecordingSourceGrant(
    val kind: RecordingSourceKind,
    val location: String,
    val volume: String = "external_primary",
    val ownerConfirmedCallRecordingFolder: Boolean = false,
) {
    val id: String get() = callDigest("${kind.name}\n$volume\n$location")
    fun validate(): RecordingSourceGrant = apply {
        require(ownerConfirmedCallRecordingFolder) { "통화 녹음 폴더를 직접 선택해주세요." }
        require(location.length in 1..2048 && location.none { it.code < 32 })
        require(volume == "external_primary" || volume.matches(Regex("[a-fA-F0-9-]{4,80}")))
        if (kind == RecordingSourceKind.MEDIA_FOLDER) {
            require(!location.startsWith('/') && location.endsWith('/') && location.length > 1)
            require(location.split('/').dropLast(1).all { it.isNotBlank() && it != "." && it != ".." })
        } else require(location.startsWith("content://com.android.externalstorage.documents/tree/"))
    }
}

@Serializable data class CallRecordingObservation(
    val sourceKey: String,
    val grantId: String,
    val uri: String,
    val byteCount: Long,
    val modifiedAtMillis: Long,
    val addedAtMillis: Long,
    val pending: Boolean = false,
) {
    val revision: String get() = callDigest("$sourceKey\n$byteCount\n$modifiedAtMillis")
}

/** Container EOF was checked; this is not an ASR-accuracy or speaker-identity claim. */
@Serializable data class StableCallRecording(
    val snapshotId: String,
    val sourceKey: String,
    val sourceGrantId: String,
    val uri: String,
    val sha256: String,
    val byteCount: Long,
    val modifiedAtMillis: Long,
    val durationMillis: Long,
    val capturedAtMillis: Long,
    val containerEofChecked: Boolean = true,
) {
    fun validate(): StableCallRecording = apply {
        require(listOf(snapshotId, sourceKey, sourceGrantId, sha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(snapshotId == callDigest("$sourceKey\n$sha256"))
        require(uri.startsWith("content://") && byteCount in 1..MAX_RECORDING_BYTES)
        require(durationMillis in 1..MAX_RECORDING_DURATION_MILLIS && containerEofChecked)
    }
}

@Serializable data class CallFollowUpAuthorization(
    val generation: Long,
    val sourceGrantId: String,
    val enabledAtMillis: Long,
    val callEndedSignalAtMillis: Long? = null,
)

enum class PipelineReadiness { READY, MODEL_UNAVAILABLE, BUSY, DISABLED }

@Serializable data class CallFollowUpEnqueueReceipt(
    val snapshotId: String,
    val queueId: String,
    val privateProjectId: String,
    val reportThreadId: String,
) {
    fun validate(snapshot: StableCallRecording) = apply {
        require(snapshotId == snapshot.snapshotId)
        require(listOf(queueId, privateProjectId, reportThreadId).all { it.length in 1..160 && it.none { c -> c.code < 32 } })
    }
}

/**
 * Root-owned persistent queue, not long inference inside a WorkManager detection worker.
 * find/enqueue MUST atomically deduplicate snapshotId across process death. Only a durable queue
 * commit may return a receipt. The queue keeps the original private project/report route, checks
 * authorization before each stage/publication, and never directly sends email or commits calendar
 * changes. It must verify the actual decoder's immutable input copy SHA matches snapshot.sha256;
 * checking only a pathname or model-provided hash is insufficient. Source deletion/revocation or
 * invalidate cancels pending work without deleting the owner's recording or another owner's job.
 */
interface CallFollowUpPipeline {
    suspend fun readiness(): PipelineReadiness
    suspend fun find(snapshotId: String): CallFollowUpEnqueueReceipt?
    suspend fun enqueue(snapshot: StableCallRecording, authorization: CallFollowUpAuthorization): CallFollowUpEnqueueReceipt
    suspend fun invalidate(sourceKey: String, generation: Long, reason: String)
}

enum class CallFollowUpReadiness { DISABLED, SOURCE_NOT_SELECTED, REQUIRES_SOURCE_ACCESS, REQUIRES_PHONE_PERMISSION,
    MODEL_UNAVAILABLE, DEFERRED_DEVICE_STATE, READY, SOURCE_REVIEW_REQUIRED }

data class CallFollowUpState(
    val enabled: Boolean,
    val generation: Long,
    val readiness: CallFollowUpReadiness,
    val status: String,
    val lastCallEndedAtMillis: Long,
)

internal const val MAX_RECORDING_BYTES = 100L * 1024 * 1024
internal const val MAX_RECORDING_DURATION_MILLIS = 2L * 60 * 60 * 1000
internal fun callDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
