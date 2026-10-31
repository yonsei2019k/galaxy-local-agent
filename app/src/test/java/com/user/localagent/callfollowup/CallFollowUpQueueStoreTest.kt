package com.user.localagent.callfollowup

import com.user.localagent.models.InstalledModel
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CallFollowUpQueueStoreTest {
    private class Disk {
        val data = mutableMapOf<String, String>()
        fun reopen() = CallFollowUpQueueStore.forTest { key, max -> object : CallFollowUpQueueStore.Record {
            override fun read() = data[key].orEmpty()
            override fun write(value: String) { require(value.length <= max); data[key] = value }
        } }
    }
    private fun job(store: CallFollowUpQueueStore): CallPipelineJob {
        val id = UUID.randomUUID().toString(); val sourceKey = "a".repeat(64); val sha = "b".repeat(64); val grant = "c".repeat(64)
        val snapshot = StableCallRecording(callDigest("$sourceKey\n$sha"), sourceKey, grant,
            "content://media/external_primary/audio/media/123456", sha, 6000, 120_000, 30_000, 160_000)
        val receipt = CallFollowUpEnqueueReceipt(snapshot.snapshotId, id, store.projectId(), UUID.randomUUID().toString())
        return CallPipelineJob(id, snapshot, CallFollowUpAuthorization(1, grant, 100_000), receipt,
            CallAsrPin(InstalledModel("asr-qwen-17b", "ASR", "/synthetic/asr.gguf", "d".repeat(64), true, "gguf"),
                InstalledModel("asr-qwen-17b-mmproj", "encoder", "/synthetic/encoder.gguf", "e".repeat(64), true, "gguf"), "ko", 2),
            InstalledModel("gemma-e2b", "text", "/synthetic/text.gguf", "f".repeat(64), true, "gguf"))
    }
    private fun raw(job: CallPipelineJob, text: String = "내일 회의 시간을 다시 확인하겠습니다.") = CallRawChunk(0, 0, 15_000,
        text, callDigest(text), job.snapshot.sha256, "a".repeat(64), job.asr.model.id, job.asr.model.sha256,
        job.asr.projector?.sha256, job.asr.language, false)

    @Test fun queueAndReportRouteAreIdempotentAcrossFreshStoreInstances() {
        val disk = Disk(); val first = disk.reopen(); val job = first.insert(job(first))
        val reopened = disk.reopen()
        val duplicateId = UUID.randomUUID().toString()
        val duplicate = job.copy(queueId = duplicateId, receipt = job.receipt.copy(queueId = duplicateId))
        assertEquals(job, reopened.insert(duplicate))
        assertEquals(job.receipt, reopened.recoverSnapshot(job.snapshot.snapshotId)?.receipt)
        assertEquals(listOf(job.queueId), reopened.ids())
        assertEquals(first.projectId(), reopened.projectId())
    }

    @Test fun queueCommitBeforeSnapshotIndexWriteIsRecoveredWithoutDuplicate() {
        val disk = Disk(); val first = disk.reopen(); val job = first.insert(job(first))
        disk.data.remove("snapshot.${job.snapshot.snapshotId}")
        assertNull(disk.reopen().findSnapshot(job.snapshot.snapshotId))
        assertEquals(job.queueId, disk.reopen().recoverSnapshot(job.snapshot.snapshotId)?.queueId)
        assertEquals(job.queueId, disk.reopen().findSnapshot(job.snapshot.snapshotId)?.queueId)
    }

    @Test fun rawChunkCommitBeforeCursorCrashRecoversExactUnchangedBytes() {
        val disk = Disk(); val first = disk.reopen(); val job = first.insert(job(first)); val originalJob = disk.data["job.${job.queueId}"]!!
        val raw = raw(job, "다음 주 월요일은 안 됩니다. 🦊")
        first.appendRaw(job.queueId, job.epoch, raw)
        disk.data["job.${job.queueId}"] = originalJob // Simulate death after raw AtomicFile commit, before cursor commit.
        val reopened = disk.reopen()
        assertEquals(0, reopened.job(job.queueId)?.chunkCount)
        assertEquals(raw.text, reopened.raw(job.queueId, 0)?.text)
        assertEquals(1, reopened.appendRaw(job.queueId, job.epoch, raw)?.chunkCount)
        assertEquals(raw.textSha256, reopened.raw(job.queueId, 0)?.textSha256)
    }

    @Test fun changedRawCannotOverwriteAnAlreadyCommittedExactChunk() {
        val disk = Disk(); val store = disk.reopen(); val job = store.insert(job(store)); val original = disk.data["job.${job.queueId}"]!!
        store.appendRaw(job.queueId, job.epoch, raw(job))
        disk.data["job.${job.queueId}"] = original
        assertThrows(IllegalArgumentException::class.java) { store.appendRaw(job.queueId, job.epoch, raw(job, "다른 원문")) }
        assertEquals(raw(job).text, store.raw(job.queueId, 0)?.text)
    }

    @Test fun pauseEpochRejectsLateChunkAndRetryPreservesPriorSourceVersion() {
        val disk = Disk(); val store = disk.reopen(); val job = store.insert(job(store))
        val paused = checkNotNull(store.changeControl(job.queueId, "PAUSED"))
        assertNull(store.appendRaw(job.queueId, job.epoch, raw(job)))
        val resumed = checkNotNull(store.changeControl(job.queueId, "QUEUED"))
        assertTrue(resumed.epoch > paused.epoch)
        assertEquals(job.snapshot, resumed.snapshot)
        assertEquals(1, store.appendRaw(job.queueId, resumed.epoch, raw(job))?.chunkCount)
    }

    @Test fun durableSourceFenceRejectsEvenTheFirstLateEnqueueAndSurvivesRestart() {
        val disk = Disk(); val store = disk.reopen(); val job = job(store)
        store.invalidateSource(job.snapshot.sourceKey, job.authorization.generation)
        assertThrows(IllegalStateException::class.java) { disk.reopen().insert(job) }
        assertTrue(disk.reopen().sourceInvalidated(job.snapshot.sourceKey, job.authorization.generation))
        assertTrue(disk.reopen().ids().isEmpty())
    }

    @Test fun forgetErasesExactRawMapsAndBodiesAndCannotReviveThem() {
        val disk = Disk(); val store = disk.reopen(); val job = store.insert(job(store))
        store.appendRaw(job.queueId, job.epoch, raw(job))
        store.update(job.queueId, job.epoch) { it.copy(publicationBody = "synthetic report", publicationSha256 = callDigest("synthetic report"),
            partialPublicationBody = "synthetic partial", partialPublicationSha256 = callDigest("synthetic partial")) }
        store.invalidateSource(job.snapshot.sourceKey, job.authorization.generation)
        store.markInvalidated(job.queueId); store.erasePayloads(job.queueId)
        assertNull(disk.reopen().raw(job.queueId, 0))
        assertNull(disk.reopen().job(job.queueId)?.publicationBody)
        assertNull(disk.reopen().job(job.queueId)?.partialPublicationBody)
        assertNull(disk.reopen().appendRaw(job.queueId, job.epoch, raw(job)))
    }

    @Test fun corruptEncryptedPayloadIsNeverReplacedByAnEmptyQueue() {
        val disk = Disk(); disk.data["index"] = "{\"unknown\":true}"
        assertThrows(Exception::class.java) { disk.reopen().ids() }
        assertEquals(1, disk.data.size)
    }

    @Test fun validatedMapCommitBeforeCursorCrashRecoversWithoutReinterpretingOrDuplicatingIt() {
        val disk = Disk(); val store = disk.reopen(); val job = store.insert(job(store))
        store.appendRaw(job.queueId, job.epoch, raw(job))
        store.update(job.queueId, job.epoch) { it.copy(transcriptionExhausted = true, status = "ANALYZING") }
        val before = disk.data["job.${job.queueId}"]!!
        val raw = "{\"synthetic\":\"immutable model-output checkpoint\"}"
        val map = CallMapCheckpoint(0, "a".repeat(64), job.textModel.sha256, raw, callDigest(raw))
        store.appendMap(job.queueId, job.epoch, map, emptyList())
        disk.data["job.${job.queueId}"] = before
        val reopened = disk.reopen()
        assertEquals(0, reopened.job(job.queueId)?.analyzedChunks)
        assertEquals(map, reopened.map(job.queueId, 0))
        assertEquals(1, reopened.appendMap(job.queueId, job.epoch, map, emptyList())?.analyzedChunks)
        assertEquals(raw, reopened.map(job.queueId, 0)?.raw)
    }

    @Test fun staleOrOtherModelMapCannotBeCommittedAsCurrentVerifiedAnalysis() {
        val disk = Disk(); val store = disk.reopen(); val job = store.insert(job(store))
        store.appendRaw(job.queueId, job.epoch, raw(job))
        store.update(job.queueId, job.epoch) { it.copy(transcriptionExhausted = true, status = "ANALYZING") }
        val raw = "{\"synthetic\":true}"
        val otherModel = CallMapCheckpoint(0, "a".repeat(64), "0".repeat(64), raw, callDigest(raw))
        assertThrows(IllegalArgumentException::class.java) { store.appendMap(job.queueId, job.epoch, otherModel, emptyList()) }
        assertNull(store.map(job.queueId, 0))
        store.changeControl(job.queueId, "PAUSED")
        assertNull(store.appendMap(job.queueId, job.epoch, otherModel.copy(modelSha256 = job.textModel.sha256), emptyList()))
    }
}
