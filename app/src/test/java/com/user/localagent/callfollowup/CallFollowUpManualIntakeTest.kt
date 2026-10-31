package com.user.localagent.callfollowup

import org.junit.Assert.*
import org.junit.Test

class CallFollowUpManualIntakeTest {
    private fun journal(data: MutableMap<String, String>) = CallFollowUpJournal.forTest { key, max -> object : CallFollowUpJournal.JournalRecord {
        override fun read() = data[key].orEmpty()
        override fun write(value: String) { require(value.length <= max); data[key] = value }
    } }

    @Test fun oldFileExceptionSelectsExactlyTheExplicitUriWithoutMakingOtherOldFilesEligible() {
        val grant = "a".repeat(64)
        fun observation(id: Int): CallRecordingObservation {
            val uri = "content://media/external_primary/audio/media/$id"
            return CallRecordingObservation(callDigest("$grant\n$uri"), grant, uri, 6000, 110_000, 100_000)
        }
        val first = observation(1); val selected = observation(2); val third = observation(3)
        val metadata = listOf(first, selected, third)
        assertEquals(selected, CallManualIntakePolicy.select(grant, selected.uri, metadata))
        assertFalse(metadata.any { CallFollowUpPolicy.eligible(it, 200_000) })
        assertThrows(IllegalStateException::class.java) {
            CallManualIntakePolicy.select(grant, "content://media/external_primary/audio/media/4", metadata)
        }
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.select("b".repeat(64), selected.uri, metadata) }
    }

    @Test fun ambiguousOrRelabelledSelectedMetadataCannotCreateManualAuthority() {
        val grant = "a".repeat(64); val uri = "content://media/external_primary/audio/media/2"
        val selected = CallRecordingObservation(callDigest("$grant\n$uri"), grant, uri, 6000, 110_000, 100_000)
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.select(grant, uri, listOf(selected, selected)) }
        assertThrows(IllegalArgumentException::class.java) { CallManualIntakePolicy.select(grant, uri, listOf(selected.copy(sourceKey = "f".repeat(64)))) }
    }

    @Test fun pickedSafDocumentUsesOnlyTheExactCurrentFolderChildIdentityWithRealReadGrant() {
        val child = CallManualIntakePolicy.DocumentIdentity("a".repeat(64), "content://synthetic/tree/root/document/child",
            "com.android.externalstorage.documents", "primary:Recordings/Call/selected.m4a")
        assertEquals(child.listedUri, CallManualIntakePolicy.selectDocument(child.grantId, child.authority, child.documentId, true, listOf(child)))
        assertThrows(IllegalArgumentException::class.java) { CallManualIntakePolicy.selectDocument(child.grantId, child.authority, child.documentId, false, listOf(child)) }
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.selectDocument("b".repeat(64), child.authority, child.documentId, true, listOf(child)) }
    }

    @Test fun anotherProviderOrSimilarlyNamedDocumentNeverMapsToTheSelectedTreeChild() {
        val child = CallManualIntakePolicy.DocumentIdentity("a".repeat(64), "content://synthetic/tree/root/document/child",
            "com.android.externalstorage.documents", "primary:Recordings/Call/selected.m4a")
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.selectDocument(child.grantId, "another.provider", child.documentId, true, listOf(child)) }
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.selectDocument(child.grantId, child.authority,
            "primary:Music/selected.m4a", true, listOf(child)) }
        assertThrows(IllegalStateException::class.java) { CallManualIntakePolicy.selectDocument(child.grantId, child.authority,
            child.documentId, true, listOf(child, child)) }
    }

    @Test fun equivalentMediaUriStillRequiresActualAudioVolumeAndExactFolder() {
        CallManualIntakePolicy.verifyMediaIdentity("external_primary", "Recordings/Call/", "external_primary", "Recordings/Call/", 42, "audio/mp4")
        assertThrows(IllegalArgumentException::class.java) {
            CallManualIntakePolicy.verifyMediaIdentity("external_primary", "Recordings/Call/", "ABCD-1234", "Recordings/Call/", 42, "audio/mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallManualIntakePolicy.verifyMediaIdentity("external_primary", "Recordings/Call/", "external_primary", "Music/", 42, "audio/mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallManualIntakePolicy.verifyMediaIdentity("external_primary", "Recordings/Call/", "external_primary", "Recordings/Call/", 42, "video/mp4")
        }
    }

    @Test fun oldManualQueueRecoveryIsLimitedToItsAlreadySelectedUnchangedSource() {
        val grant = "a".repeat(64); val uri = "content://media/external_primary/audio/media/2"
        val observed = CallRecordingObservation(callDigest("$grant\n$uri"), grant, uri, 6000, 110_000, 100_000)
        val snapshot = StableCallRecording(callDigest("${observed.sourceKey}\n${"b".repeat(64)}"), observed.sourceKey, grant,
            uri, "b".repeat(64), 6000, 110_000, 23_000, 220_000)
        val selected = RecordingJob(observed.sourceKey, 1, observed, RecordingJobState.ENQUEUED, 220_000,
            snapshot = snapshot, ownerSelectedAtMillis = 220_000)
        assertEquals(CallManualIntakePolicy.Recovery.FIND_EXISTING_PIPELINE, CallManualIntakePolicy.recovery(selected, observed, 1))
        assertEquals(CallManualIntakePolicy.Recovery.NONE, CallManualIntakePolicy.recovery(selected.copy(ownerSelectedAtMillis = null), observed, 1))
        assertEquals(CallManualIntakePolicy.Recovery.NONE, CallManualIntakePolicy.recovery(selected, observed.copy(byteCount = 8000), 1))
        assertEquals(CallManualIntakePolicy.Recovery.NONE, CallManualIntakePolicy.recovery(selected, null, 1))
        assertEquals(CallManualIntakePolicy.Recovery.NONE, CallManualIntakePolicy.recovery(selected, observed, 2))
        assertEquals(CallManualIntakePolicy.Recovery.NONE, CallManualIntakePolicy.recovery(selected.copy(state = RecordingJobState.FORGOTTEN), observed, 1))
        assertEquals(CallManualIntakePolicy.Recovery.PREPARE_SELECTED_SOURCE,
            CallManualIntakePolicy.recovery(selected.copy(state = RecordingJobState.CHECKING, snapshot = null), observed, 1))
    }

    @Test fun explicitlyChosenOldFileKeepsItsOwnerActionWithoutBackdatingAutomaticOptIn() {
        val data = mutableMapOf<String, String>(); val journal = journal(data)
        val grant = RecordingSourceGrant(RecordingSourceKind.MEDIA_FOLDER, "Recordings/Call/", ownerConfirmedCallRecordingFolder = true)
        journal.updateConfig { it.copy(enabled = true, generation = 1, source = grant, enabledAtMillis = 200_000) }
        val uri = "content://media/external_primary/audio/media/1234"
        val old = CallRecordingObservation(callDigest("${grant.id}\n$uri"), grant.id, uri, 6000, 110_000, 100_000)
        assertFalse(CallFollowUpPolicy.eligible(old, journal.config().enabledAtMillis))
        journal.updateJob(old.sourceKey, 1) {
            RecordingJob(old.sourceKey, 1, old, stableSinceMillis = 220_000, ownerSelectedAtMillis = 220_000)
        }
        val reopened = journal(data)
        assertEquals(200_000, reopened.config().enabledAtMillis)
        assertEquals(220_000L, reopened.job(old.sourceKey)?.ownerSelectedAtMillis)
        assertFalse(CallFollowUpPolicy.eligible(old, reopened.config().enabledAtMillis))
    }

    @Test fun manualSelectionDoesNotResurrectForgottenOrCancelledSource() {
        for (state in setOf(RecordingJobState.FORGOTTEN, RecordingJobState.CANCELLED, RecordingJobState.SOURCE_DELETED)) {
            val data = mutableMapOf<String, String>(); val journal = journal(data)
            val grant = RecordingSourceGrant(RecordingSourceKind.MEDIA_FOLDER, "Recordings/Call/", ownerConfirmedCallRecordingFolder = true)
            journal.updateConfig { it.copy(enabled = true, generation = 1, source = grant, enabledAtMillis = 200_000) }
            val uri = "content://media/external_primary/audio/media/1234"
            val old = CallRecordingObservation(callDigest("${grant.id}\n$uri"), grant.id, uri, 6000, 110_000, 100_000)
            journal.updateJob(old.sourceKey, 1) { RecordingJob(old.sourceKey, 1, old, stableSinceMillis = 220_000) }
            journal.tombstone(old.sourceKey, state, "owner_action")
            assertNull(journal(data).updateJob(old.sourceKey, 1) {
                RecordingJob(old.sourceKey, 1, old, stableSinceMillis = 230_000, ownerSelectedAtMillis = 230_000)
            })
            assertEquals(state, journal(data).job(old.sourceKey)?.state)
        }
    }
}
