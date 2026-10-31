package com.user.localagent.callfollowup

import android.content.Context
import com.user.localagent.data.DraftStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal data class CallFollowUpConfig(
    val enabled: Boolean = false,
    val generation: Long = 0,
    val enabledAtMillis: Long = 0,
    val source: RecordingSourceGrant? = null,
    val phoneOffHook: Boolean = false,
    val callStartedAtMillis: Long = 0,
    val lastCallStartedAtMillis: Long = 0,
    val lastCallEndedAtMillis: Long = 0,
    val status: String = "disabled",
    val sourceKeys: List<String> = emptyList(),
)

@Serializable internal enum class RecordingJobState { OBSERVED, CHECKING, ENQUEUED, CANCELLED, SOURCE_DELETED, FORGOTTEN, NEEDS_REVIEW }

@Serializable internal data class RecordingJob(
    val sourceKey: String,
    val generation: Long,
    val observation: CallRecordingObservation,
    val state: RecordingJobState = RecordingJobState.OBSERVED,
    val stableSinceMillis: Long,
    val attempts: Int = 0,
    val snapshot: StableCallRecording? = null,
    val receipt: CallFollowUpEnqueueReceipt? = null,
    val status: String = "waiting_for_file_close",
    val ownerSelectedAtMillis: Long? = null,
)

/** AES-GCM AtomicFile records. Tombstones and completed source revisions are never evicted. */
internal class CallFollowUpJournal private constructor(private val record: (String, Int) -> JournalRecord) {
    constructor(context: Context) : this({ key, max ->
        val store = DraftStore(context, "call-follow-up-v1.$key", max)
        object : JournalRecord {
            override fun read() = store.readStrict()
            override fun write(value: String) { store.write(value); check(store.readStrict() == value) }
        }
    })

    internal interface JournalRecord { fun read(): String; fun write(value: String) }

    fun config(): CallFollowUpConfig = synchronized(LOCK) { readConfig() }
    fun updateConfig(transform: (CallFollowUpConfig) -> CallFollowUpConfig): CallFollowUpConfig = synchronized(LOCK) {
        val value = transform(readConfig())
        require(value.generation >= 0 && value.sourceKeys.size <= 50_000 && value.sourceKeys.distinct().size == value.sourceKeys.size)
        require(value.sourceKeys.all(::validKey))
        value.source?.validate()
        write("config", JSON.encodeToString(value), 4_000_000)
        value
    }
    fun job(key: String): RecordingJob? = synchronized(LOCK) { readJob(key) }

    /** Recheck generation and permanent tombstone inside the same publication lock. */
    fun updateJob(key: String, generation: Long, transform: (RecordingJob?) -> RecordingJob): RecordingJob? = synchronized(LOCK) {
        require(validKey(key))
        val config = readConfig()
        if (!config.enabled || config.generation != generation) return@synchronized null
        val old = readJob(key)
        if (old?.state in setOf(RecordingJobState.FORGOTTEN, RecordingJobState.CANCELLED, RecordingJobState.SOURCE_DELETED)) return@synchronized null
        val value = transform(old)
        require(value.sourceKey == key && value.generation == generation && value.observation.sourceKey == key)
        require(value.observation.grantId == config.source?.id)
        require(value.attempts in 0..MAX_ATTEMPTS)
        value.snapshot?.validate()
        value.receipt?.let { it.validate(checkNotNull(value.snapshot)) }
        // Index is written first. A crash can leave a missing record, never an undiscoverable pending record.
        if (key !in config.sourceKeys) {
            check(config.sourceKeys.size < 50_000) { "후속 작업 기록 보관 한도입니다. 저장 상태를 점검해주세요." }
            write("config", JSON.encodeToString(config.copy(sourceKeys = config.sourceKeys + key)), 4_000_000)
        }
        write("source.$key", JSON.encodeToString(value), 32_768)
        value
    }

    fun tombstone(key: String, state: RecordingJobState, reason: String): RecordingJob? = synchronized(LOCK) {
        require(validKey(key) && state in setOf(RecordingJobState.FORGOTTEN, RecordingJobState.CANCELLED, RecordingJobState.SOURCE_DELETED))
        val old = readJob(key) ?: return@synchronized null
        val value = old.copy(state = state, status = reason.take(100))
        write("source.$key", JSON.encodeToString(value), 32_768)
        value
    }

    private fun readConfig(): CallFollowUpConfig = read("config", 4_000_000).let { raw ->
        if (raw.isEmpty()) CallFollowUpConfig() else JSON.decodeFromString<CallFollowUpConfig>(raw).also {
            require(it.generation >= 0 && it.sourceKeys.size <= 50_000 && it.sourceKeys.all(::validKey))
            it.source?.validate()
        }
    }
    private fun readJob(key: String): RecordingJob? {
        require(validKey(key))
        return read("source.$key", 32_768).takeIf { it.isNotEmpty() }?.let { JSON.decodeFromString<RecordingJob>(it) }
            ?.also {
                require(it.sourceKey == key && it.observation.sourceKey == key && it.generation >= 0 && it.attempts in 0..MAX_ATTEMPTS)
                require(it.observation.sourceKey == callDigest("${it.observation.grantId}\n${it.observation.uri}"))
                it.snapshot?.validate()
                it.receipt?.let { receipt -> receipt.validate(checkNotNull(it.snapshot)) }
            }
    }
    private fun read(key: String, max: Int) = record(key, max).read()
    private fun write(key: String, value: String, max: Int) = record(key, max).write(value)

    companion object {
        private val LOCK = Any()
        private val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        const val MAX_ATTEMPTS = 12
        private fun validKey(key: String) = key.matches(Regex("[a-f0-9]{64}"))
        internal fun forTest(records: (String, Int) -> JournalRecord) = CallFollowUpJournal(records)
    }
}
