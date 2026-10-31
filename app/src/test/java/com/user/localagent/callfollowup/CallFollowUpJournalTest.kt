package com.user.localagent.callfollowup

import org.junit.Assert.*
import org.junit.Test

class CallFollowUpJournalTest {
    private class Disk {
        val data = mutableMapOf<String, String>()
        fun reopen() = CallFollowUpJournal.forTest { key, max -> object : CallFollowUpJournal.JournalRecord {
            override fun read() = data[key].orEmpty()
            override fun write(value: String) { require(value.length <= max); data[key] = value }
        } }
    }
    private val grant = RecordingSourceGrant(RecordingSourceKind.MEDIA_FOLDER, "Recordings/Call/", ownerConfirmedCallRecordingFolder = true)
    private fun observation(bytes: Long = 6000, modified: Long = 120_000) = "content://media/external_primary/audio/media/42".let { uri ->
        CallRecordingObservation(callDigest("${grant.id}\n$uri"), grant.id, uri, bytes, modified, 110_000)
    }
    private fun enabled(disk: Disk): CallFollowUpJournal = disk.reopen().also {
        it.updateConfig { config -> config.copy(enabled = true, generation = 1, enabledAtMillis = 100_000, source = grant) }
    }
    private fun observed(journal: CallFollowUpJournal, item: CallRecordingObservation = observation()) =
        checkNotNull(journal.updateJob(item.sourceKey, 1) { RecordingJob(item.sourceKey, 1, item, stableSinceMillis = 120_000) })

    @Test fun completedReceiptSurvivesFreshJournalAndKeepsSameSnapshotIdentity() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        val sha = "a".repeat(64)
        val snapshot = StableCallRecording(callDigest("${job.sourceKey}\n$sha"), job.sourceKey, grant.id, job.observation.uri,
            sha, 6000, 120_000, 30_000, 160_000)
        val receipt = CallFollowUpEnqueueReceipt(snapshot.snapshotId, "queue-1", "private-project", "report-room")
        journal.updateJob(job.sourceKey, 1) { checkNotNull(it).copy(snapshot = snapshot, receipt = receipt, state = RecordingJobState.ENQUEUED) }
        val reloaded = checkNotNull(disk.reopen().job(job.sourceKey))
        assertEquals(snapshot.snapshotId, reloaded.snapshot?.snapshotId)
        assertEquals(receipt, reloaded.receipt)
        assertEquals(RecordingJobState.ENQUEUED, reloaded.state)
        assertEquals(listOf(job.sourceKey), disk.reopen().config().sourceKeys)
    }

    @Test fun queueCommittedBeforeJournalFinalizationRetainsStableLookupIdAfterRestart() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        val sha = "b".repeat(64)
        val snapshot = StableCallRecording(callDigest("${job.sourceKey}\n$sha"), job.sourceKey, grant.id, job.observation.uri,
            sha, 6000, 120_000, 30_000, 160_000)
        journal.updateJob(job.sourceKey, 1) { checkNotNull(it).copy(snapshot = snapshot, state = RecordingJobState.CHECKING) }
        assertEquals(snapshot.snapshotId, disk.reopen().job(job.sourceKey)?.snapshot?.snapshotId)
        assertNull(disk.reopen().job(job.sourceKey)?.receipt)
    }

    @Test fun disablingAndReenablingCannotPublishAnOldGeneration() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        journal.updateConfig { it.copy(enabled = false, generation = 2) }
        assertNull(journal.updateJob(job.sourceKey, 1) { error("stale publication executed") })
        journal.updateConfig { it.copy(enabled = true, generation = 3) }
        assertNull(journal.updateJob(job.sourceKey, 1) { error("old generation revived") })
        assertEquals(RecordingJobState.OBSERVED, disk.reopen().job(job.sourceKey)?.state)
    }

    @Test fun forgottenSourceNeverReentersAfterRestartOrNewEnableGeneration() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        journal.tombstone(job.sourceKey, RecordingJobState.FORGOTTEN, "owner_forgot_source")
        val reopened = disk.reopen(); reopened.updateConfig { it.copy(generation = 4) }
        assertNull(reopened.updateJob(job.sourceKey, 4) { error("forgotten source reentered") })
        assertEquals(RecordingJobState.FORGOTTEN, reopened.job(job.sourceKey)?.state)
    }

    @Test fun deletionWinsAgainstLateWorkerReceiptPublication() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        journal.tombstone(job.sourceKey, RecordingJobState.SOURCE_DELETED, "source_deleted")
        assertNull(journal.updateJob(job.sourceKey, 1) { error("late receipt resurrected deleted source") })
        assertEquals(RecordingJobState.SOURCE_DELETED, disk.reopen().job(job.sourceKey)?.state)
    }

    @Test fun cancellationIsPermanentEvenIfSameSourceBytesChange() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        journal.tombstone(job.sourceKey, RecordingJobState.CANCELLED, "owner_cancelled")
        val newer = observation(bytes = 8000, modified = 180_000)
        assertNull(journal.updateJob(newer.sourceKey, 1) { RecordingJob(newer.sourceKey, 1, newer, stableSinceMillis = 180_000) })
    }

    @Test fun sourceCannotWriteARecordUnderAnotherSourceKey() {
        val disk = Disk(); val journal = enabled(disk); val job = observed(journal)
        assertThrows(IllegalArgumentException::class.java) {
            journal.updateJob("f".repeat(64), 1) { job }
        }
    }

    @Test fun corruptJournalDoesNotBecomeDefaultEnabledStateOrAnAbsentJob() {
        val disk = Disk(); disk.data["config"] = "{\"enabled\":true,\"unexpected\":1}"
        assertThrows(Exception::class.java) { disk.reopen().config() }
        assertEquals(1, disk.data.size)
    }

    @Test fun sourceGrantRejectsRootTraversalAndUnconfirmedFolder() {
        assertThrows(IllegalArgumentException::class.java) { grant.copy(location = "../Music/").validate() }
        assertThrows(IllegalArgumentException::class.java) { grant.copy(location = "/").validate() }
        assertThrows(IllegalArgumentException::class.java) { grant.copy(volume = "external").validate() }
        assertThrows(IllegalArgumentException::class.java) { grant.copy(ownerConfirmedCallRecordingFolder = false).validate() }
        assertThrows(IllegalArgumentException::class.java) { grant.copy(kind = RecordingSourceKind.SAF_TREE, location = "https://remote.example/recordings").validate() }
    }

    @Test fun snapshotAndQueueReceiptCannotBeRelabelledForDifferentSource() {
        val item = observation(); val sha = "a".repeat(64)
        val snapshot = StableCallRecording(callDigest("${item.sourceKey}\n$sha"), item.sourceKey, grant.id, item.uri, sha,
            item.byteCount, item.modifiedAtMillis, 30_000, 160_000)
        snapshot.validate()
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(snapshotId = "f".repeat(64)).validate() }
        assertThrows(IllegalArgumentException::class.java) { CallFollowUpEnqueueReceipt("f".repeat(64), "q", "p", "r").validate(snapshot) }
    }
}
