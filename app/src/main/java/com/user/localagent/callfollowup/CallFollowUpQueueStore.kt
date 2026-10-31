package com.user.localagent.callfollowup

import android.content.Context
import com.user.localagent.core.CallFollowUpCandidate
import com.user.localagent.data.DraftStore
import com.user.localagent.models.InstalledModel
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CallFollowUpQueueStatus { QUEUED, TRANSCRIBING, ANALYZING, PUBLISHING, COMPLETE, REVIEW_REQUIRED, PAUSED, DEFERRED, INVALIDATED }

data class CallFollowUpQueueItem(val queueId: String, val status: CallFollowUpQueueStatus, val completedChunks: Int,
    val analyzedChunks: Int, val durationMillis: Long, val reportThreadId: String, val privateProjectId: String,
    val updatedAtMillis: Long, val canRetry: Boolean, val reviewRequired: Boolean, val errorCode: String?, val asrModelId: String? = null,
    val modelPhase: String? = null, val outputChars: Int? = null, val attemptElapsedMs: Long? = null,
    val nativePhase: String? = null, val promptDone: Int? = null, val promptTotal: Int? = null, val decodeTokens: Int? = null)

@Serializable internal data class CallAsrPin(val model: InstalledModel, val projector: InstalledModel? = null,
    val language: String, val threads: Int)

@Serializable internal data class CallPipelineJob(
    val queueId: String,
    val snapshot: StableCallRecording,
    val authorization: CallFollowUpAuthorization,
    val receipt: CallFollowUpEnqueueReceipt,
    val asr: CallAsrPin,
    val textModel: InstalledModel,
    val publicationId: String = UUID.randomUUID().toString(),
    val partialPublicationId: String = UUID.randomUUID().toString(),
    val publicationSha256: String? = null,
    val partialPublicationSha256: String? = null,
    val publicationBody: String? = null,
    val partialPublicationBody: String? = null,
    val epoch: Long = 1,
    val status: String = "QUEUED",
    val chunkCount: Int = 0,
    val analyzedChunks: Int = 0,
    val decodedDurationMillis: Long = 0,
    val transcriptionExhausted: Boolean = false,
    val candidates: List<CallFollowUpCandidate> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val errorCode: String? = null,
    val projectRevision: Long = 0,
    val threadRevision: Long = 0,
    val scopeBound: Boolean = false,
    val analysisContractSha256: String? = null,
) { override fun toString() = "CallPipelineJob(queueId=$queueId,status=$status,privateContentOmitted=true)" }

@Serializable internal data class CallRawChunk(val index: Int, val startMillis: Long, val endMillis: Long,
    val text: String, val textSha256: String, val audioSha256: String, val wavSha256: String,
    val modelId: String, val modelSha256: String, val projectorSha256: String?, val language: String,
    val asrOutputCapReached: Boolean? = null) {
    override fun toString() = "CallRawChunk(index=$index,privateContentOmitted=true)"
}
@Serializable internal data class CallMapCheckpoint(val index: Int, val transcriptSha256: String,
    val modelSha256: String, val raw: String, val rawSha256: String, val metrics: CallMapMetrics? = null,
    val analysisContractSha256: String? = null) {
    override fun toString() = "CallMapCheckpoint(index=$index,privateOutputOmitted=true)"
}
@Serializable internal data class CallMapMetrics(val inputTokenCount: Int?, val inputCountKind: String,
    val prefillTokens: Int?, val decodeTokens: Int?, val stopReason: String, val stopEvidence: String,
    val capReached: Boolean?, val elapsedMillis: Long?)
@Serializable private data class CallQueueIndex(val projectId: String = UUID.randomUUID().toString(), val queueIds: List<String> = emptyList())

/** Exact raw chunks/maps are immutable encrypted records; only small host cursors are mutable. */
internal class CallFollowUpQueueStore private constructor(private val records: (String, Int) -> Record) {
    constructor(context: Context) : this({ key, max ->
        val file = DraftStore(context, "call-follow-up-pipeline-v1.$key", max)
        object : Record {
            override fun read() = file.readStrict()
            override fun write(value: String) { file.write(value); check(file.readStrict() == value) }
        }
    })
    internal interface Record { fun read(): String; fun write(value: String) }
    fun projectId(): String = synchronized(LOCK) { index().also { write("index", JSON.encodeToString(it), 4_000_000) }.projectId }
    fun ids(): List<String> = synchronized(LOCK) { index().queueIds }
    fun job(id: String): CallPipelineJob? = synchronized(LOCK) { readJob(id) }
    fun findSnapshot(id: String): CallPipelineJob? = synchronized(LOCK) {
        require(SHA.matches(id))
        read("snapshot.$id", 200).takeIf { it.isNotEmpty() }?.let(::readJob)
    }
    fun sourceInvalidated(key: String, generation: Long): Boolean = synchronized(LOCK) {
        require(SHA.matches(key) && generation >= 0)
        read("fence.$key.$generation", 100).isNotEmpty()
    }
    fun invalidateSource(key: String, generation: Long) = synchronized(LOCK) {
        require(SHA.matches(key) && generation >= 0)
        write("fence.$key.$generation", "invalidated", 100)
    }
    fun insert(job: CallPipelineJob): CallPipelineJob = synchronized(LOCK) {
        check(!sourceInvalidated(job.snapshot.sourceKey, job.authorization.generation)) { "Source authorization was cancelled" }
        recoverSnapshot(job.snapshot.snapshotId)?.let { existing ->
            check(existing.status != "INVALIDATED")
            return@synchronized existing
        }
        check(readJob(job.queueId) == null)
        val index = index(); check(index.queueIds.size < 50_000)
        write("index", JSON.encodeToString(index.copy(queueIds = index.queueIds + job.queueId)), 4_000_000)
        writeJob(job)
        // Job precedes reverse index; lookup can recover this narrow crash gap using indexed job IDs.
        write("snapshot.${job.snapshot.snapshotId}", job.queueId, 200)
        job
    }
    fun recoverSnapshot(id: String): CallPipelineJob? = synchronized(LOCK) {
        findSnapshot(id) ?: index().queueIds.asSequence().mapNotNull(::readJob).firstOrNull { it.snapshot.snapshotId == id }?.also {
            write("snapshot.$id", it.queueId, 200)
        }
    }
    fun update(id: String, epoch: Long, transform: (CallPipelineJob) -> CallPipelineJob): CallPipelineJob? = synchronized(LOCK) {
        val old = readJob(id) ?: return@synchronized null
        if (old.epoch != epoch || old.status == "INVALIDATED" || sourceInvalidated(old.snapshot.sourceKey, old.authorization.generation)) return@synchronized null
        val next = transform(old)
        require(next.queueId == id && next.snapshot == old.snapshot && next.authorization == old.authorization && next.receipt == old.receipt)
        require(next.asr == old.asr && next.textModel == old.textModel) { "기존 작업의 음성·분석 모델을 바꾸지 않습니다." }
        if (old.analysisContractSha256 != null) require(next.analysisContractSha256 == old.analysisContractSha256)
        else if (next.analysisContractSha256 != null) require(old.analyzedChunks == 0 && map(id, 0) == null)
        writeJob(next.copy(updatedAtMillis = System.currentTimeMillis()))
        readJob(id)
    }
    fun changeControl(id: String, status: String): CallPipelineJob? = synchronized(LOCK) {
        require(status in setOf("QUEUED", "PAUSED", "INVALIDATED"))
        val old = readJob(id) ?: return@synchronized null
        if (old.status == "INVALIDATED" || sourceInvalidated(old.snapshot.sourceKey, old.authorization.generation)) return@synchronized null
        old.copy(epoch = old.epoch + 1, status = status, errorCode = null, updatedAtMillis = System.currentTimeMillis()).also(::writeJob)
    }
    fun markInvalidated(id: String): CallPipelineJob? = synchronized(LOCK) {
        val old = readJob(id) ?: return@synchronized null
        old.copy(epoch = old.epoch + 1, status = "INVALIDATED", errorCode = "SOURCE_INVALIDATED", updatedAtMillis = System.currentTimeMillis()).also(::writeJob)
    }
    fun raw(id: String, index: Int): CallRawChunk? = synchronized(LOCK) {
        requireId(id); require(index in 0..480)
        read("raw.$id.$index", 48_000).takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<CallRawChunk>(it) }?.also {
            require(it.index == index && callDigest(it.text) == it.textSha256)
        }
    }
    fun appendRaw(id: String, epoch: Long, raw: CallRawChunk): CallPipelineJob? = synchronized(LOCK) {
        val job = writable(id, epoch) ?: return@synchronized null
        require(raw.index == job.chunkCount && raw.index in 0..479 && raw.startMillis == job.decodedDurationMillis && raw.endMillis > raw.startMillis)
        require(raw.text.length <= 10_000 && callDigest(raw.text) == raw.textSha256 && raw.audioSha256 == job.snapshot.sha256 &&
            raw.modelId == job.asr.model.id && raw.modelSha256 == job.asr.model.sha256 && raw.projectorSha256 == job.asr.projector?.sha256 && raw.language == job.asr.language)
        val previous = raw(id, raw.index)
        require(previous == null || previous == raw) { "Immutable transcript changed" }
        if (previous == null) write("raw.$id.${raw.index}", JSON.encodeToString(raw), 48_000)
        job.copy(chunkCount = job.chunkCount + 1, decodedDurationMillis = raw.endMillis, updatedAtMillis = System.currentTimeMillis()).also(::writeJob)
    }
    fun map(id: String, index: Int): CallMapCheckpoint? = synchronized(LOCK) {
        requireId(id); require(index in 0..480)
        read("map.$id.$index", 300_000).takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<CallMapCheckpoint>(it) }?.also {
            require(it.index == index && callDigest(it.raw) == it.rawSha256)
        }
    }
    fun appendMap(id: String, epoch: Long, map: CallMapCheckpoint, candidates: List<CallFollowUpCandidate>): CallPipelineJob? = synchronized(LOCK) {
        val job = writable(id, epoch) ?: return@synchronized null
        require(map.index == job.analyzedChunks && map.index < job.chunkCount && job.transcriptionExhausted && candidates.size <= 64)
        require(callDigest(map.raw) == map.rawSha256 && map.modelSha256 == job.textModel.sha256)
        require(map.analysisContractSha256 == job.analysisContractSha256) { "분석 지침 버전이 저장된 작업과 다릅니다." }
        val previous = map(id, map.index)
        require(previous == null || previous == map) { "Immutable analysis checkpoint changed" }
        if (previous == null) write("map.$id.${map.index}", JSON.encodeToString(map), 300_000)
        job.copy(analyzedChunks = job.analyzedChunks + 1, candidates = candidates, updatedAtMillis = System.currentTimeMillis()).also(::writeJob)
    }
    fun erasePayloads(id: String) = synchronized(LOCK) {
        val job = readJob(id) ?: return@synchronized
        check(job.status == "INVALIDATED")
        for (index in 0..job.chunkCount.coerceAtMost(480)) {
            write("raw.$id.$index", "", 48_000); write("map.$id.$index", "", 300_000)
        }
        writeJob(job.copy(candidates = emptyList(), publicationBody = null, partialPublicationBody = null))
    }
    private fun writable(id: String, epoch: Long): CallPipelineJob? = readJob(id)?.takeIf {
        it.epoch == epoch && it.status !in setOf("INVALIDATED", "PAUSED", "COMPLETE") && !sourceInvalidated(it.snapshot.sourceKey, it.authorization.generation)
    }
    private fun readJob(id: String): CallPipelineJob? {
        requireId(id)
        return read("job.$id", 1_000_000).takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<CallPipelineJob>(it) }?.also {
            require(it.queueId == id && it.chunkCount in 0..480 && it.analyzedChunks in 0..it.chunkCount && it.epoch > 0)
            it.snapshot.validate(); it.receipt.validate(it.snapshot)
        }
    }
    private fun writeJob(job: CallPipelineJob) {
        requireId(job.queueId); requireId(job.receipt.reportThreadId); requireId(job.receipt.privateProjectId)
        require(job.receipt.queueId == job.queueId && job.epoch > 0 && job.chunkCount in 0..480 && job.analyzedChunks in 0..job.chunkCount)
        CallFollowUpQueueStatus.valueOf(job.status)
        require(job.analysisContractSha256 == null || job.analysisContractSha256.matches(Regex("[a-f0-9]{64}")))
        job.snapshot.validate(); job.receipt.validate(job.snapshot)
        job.publicationBody?.let { require(callDigest(it) == job.publicationSha256) }
        job.partialPublicationBody?.let { require(callDigest(it) == job.partialPublicationSha256) }
        write("job.${job.queueId}", JSON.encodeToString(job), 1_000_000)
    }
    private fun index(): CallQueueIndex = read("index", 4_000_000).let {
        if (it.isEmpty()) CallQueueIndex() else JSON.decodeFromString<CallQueueIndex>(it).also { index ->
            requireId(index.projectId); require(index.queueIds.size <= 50_000 && index.queueIds.distinct().size == index.queueIds.size); index.queueIds.forEach(::requireId)
        }
    }
    private fun read(key: String, max: Int) = records(key, max).read()
    private fun write(key: String, value: String, max: Int) = records(key, max).write(value)
    companion object {
        private val LOCK = Any()
        private val SHA = Regex("[a-f0-9]{64}")
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private fun requireId(id: String) { require(UUID_PATTERN.matches(id)) }
        fun forTest(records: (String, Int) -> Record) = CallFollowUpQueueStore(records)
    }
}
