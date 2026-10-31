package com.user.localagent.callfollowup

/** Owner URI plus actual selected-folder metadata only, never filename matching or source text. */
internal object CallManualIntakePolicy {
    enum class Recovery { NONE, PREPARE_SELECTED_SOURCE, FIND_EXISTING_PIPELINE }

    fun recovery(job: RecordingJob, current: CallRecordingObservation?, generation: Long): Recovery {
        if (job.generation != generation || job.ownerSelectedAtMillis?.let { it > 0 } != true || current != job.observation) return Recovery.NONE
        return when (job.state) {
            RecordingJobState.ENQUEUED -> if (job.snapshot != null) Recovery.FIND_EXISTING_PIPELINE else Recovery.NONE
            RecordingJobState.OBSERVED, RecordingJobState.CHECKING -> Recovery.PREPARE_SELECTED_SOURCE
            else -> Recovery.NONE
        }
    }

    data class DocumentIdentity(val grantId: String, val listedUri: String, val authority: String, val documentId: String)

    fun selectDocument(grantId: String, selectedAuthority: String?, selectedDocumentId: String, hasSelectedReadGrant: Boolean,
        children: List<DocumentIdentity>): String {
        require(hasSelectedReadGrant && !selectedAuthority.isNullOrBlank() && selectedDocumentId.isNotBlank())
        return children.singleOrNull { it.grantId == grantId && it.authority == selectedAuthority && it.documentId == selectedDocumentId }
            ?.listedUri ?: error("선택한 파일이 설정한 폴더의 직접 자식이 아닙니다.")
    }

    fun verifyMediaIdentity(expectedVolume: String, expectedFolder: String, actualVolume: String, actualFolder: String, mediaId: Long, mime: String) {
        require(actualVolume == expectedVolume && actualFolder == expectedFolder && mediaId > 0 && mime.startsWith("audio/")) {
            "선택한 파일의 실제 저장소와 통화 녹음 폴더를 확인하지 못했습니다."
        }
    }

    fun select(grantId: String, uri: String, folderMetadata: List<CallRecordingObservation>): CallRecordingObservation {
        require(grantId.matches(Regex("[a-f0-9]{64}")) && uri.startsWith("content://"))
        val selected = folderMetadata.singleOrNull { it.uri == uri && it.grantId == grantId }
            ?: error("설정한 통화 녹음 폴더에서 직접 선택한 파일을 확인하지 못했습니다.")
        require(selected.sourceKey == callDigest("$grantId\n$uri") && selected.byteCount in 1..MAX_RECORDING_BYTES && selected.modifiedAtMillis > 0)
        return selected
    }
}
