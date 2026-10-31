package com.user.localagent

import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.callfollowup.*
import com.user.localagent.data.DraftStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic encrypted state only: no telephony, MediaStore, audio, model, owner room or permission UI. */
class CallFollowUpLifecycleValidation {
    @Test fun encryptedJournalRestartAndForgetFenceSurviveLateWorkerPublication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("callFollowUpLifecycleApproved") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-call-follow-up-" + UUID.randomUUID()
        val owned = linkedMapOf<String, DraftStore>()
        fun reopen() = CallFollowUpJournal.forTest { key, max ->
            val store = owned.getOrPut(key) { DraftStore(context, "$prefix.$key", max) }
            object : CallFollowUpJournal.JournalRecord {
                override fun read() = store.readStrict()
                override fun write(value: String) { store.write(value); check(store.readStrict() == value) }
            }
        }
        try {
            val grant = RecordingSourceGrant(RecordingSourceKind.MEDIA_FOLDER, "Recordings/SyntheticCall/", ownerConfirmedCallRecordingFolder = true)
            val uri = "content://media/external_primary/audio/media/999999999"
            val key = callDigest("${grant.id}\n$uri")
            val observation = CallRecordingObservation(key, grant.id, uri, 6000, 120_000, 110_000)
            val journal = reopen()
            journal.updateConfig { it.copy(enabled = true, generation = 1, enabledAtMillis = 100_000, source = grant) }
            journal.updateJob(key, 1) { RecordingJob(key, 1, observation, stableSinceMillis = 120_000) }
            val sha = "a".repeat(64)
            val snapshot = StableCallRecording(callDigest("$key\n$sha"), key, grant.id, uri, sha, 6000, 120_000, 30_000, 160_000)
            val receipt = CallFollowUpEnqueueReceipt(snapshot.snapshotId, "synthetic-queue", "synthetic-project", "synthetic-thread")
            journal.updateJob(key, 1) { checkNotNull(it).copy(snapshot = snapshot, receipt = receipt, state = RecordingJobState.ENQUEUED) }
            assertEquals(receipt, reopen().job(key)?.receipt)
            assertEquals(listOf(key), reopen().config().sourceKeys)
            reopen().tombstone(key, RecordingJobState.FORGOTTEN, "owner_forgot_source")
            assertNull(reopen().updateJob(key, 1) { error("forgotten source revived") })
            reopen().updateConfig { it.copy(enabled = false, generation = 2) }
            assertNull(reopen().updateJob(key, 1) { error("stale worker wrote") })
            assertEquals(RecordingJobState.FORGOTTEN, reopen().job(key)?.state)
        } finally {
            // Delete only this test's exactly named encrypted records, never owner data or recordings.
            owned.values.forEach { store -> store.write(""); assertEquals("", store.readStrict()) }
        }
    }
}
