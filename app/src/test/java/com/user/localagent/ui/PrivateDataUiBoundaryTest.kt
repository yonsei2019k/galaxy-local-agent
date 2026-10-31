package com.user.localagent.ui

import com.user.localagent.audio.PrivateDataPickerScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivateDataUiBoundaryTest {
    private fun origin() = PrivateDataPickerScope("thread-a", "branch-a", "project-a", "project:project-a", false, 4, true)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected captured-scope rejection") } catch (_: IllegalStateException) { }
    }

    @Test fun stableFullScopeIsObservedTwiceBeforeItCanLaunchAPicker() = runBlocking {
        var reads = 0
        val result = PrivateDataUiScopeCapture.capture("thread-a") { requested ->
            assertEquals("thread-a", requested); reads++; origin()
        }
        assertEquals(2, reads); assertEquals(origin(), result)
    }

    @Test fun branchChangesAreRejectedEvenWhenThreadRevisionIsUnchanged() = runBlocking {
        var reads = 0
        rejected { PrivateDataUiScopeCapture.capture("thread-a") { if (reads++ == 0) origin() else origin().copy(branchId = "branch-b") } }
    }

    @Test fun projectMoveCannotBecomeASilentPickerDestination() = runBlocking {
        var reads = 0
        rejected { PrivateDataUiScopeCapture.capture("thread-a") {
            if (reads++ == 0) origin() else origin().copy(projectId = "project-b", scopeKey = "project:project-b", threadRevision = 5)
        } }
    }

    @Test fun accessPolicyChangesAreRejectedWithoutNeedingAThreadRenameOrRevision() = runBlocking {
        for (changed in listOf(origin().copy(useOwnerMemory = true), origin().copy(memoryEnabled = false), origin().copy(scopeKey = "thread:thread-a"))) {
            var reads = 0
            rejected { PrivateDataUiScopeCapture.capture("thread-a") { if (reads++ == 0) origin() else changed } }
        }
    }

    @Test fun aStableDifferentRoomDoesNotSatisfyTheClickedOrigin() = runBlocking {
        rejected { PrivateDataUiScopeCapture.capture("thread-a") { origin().copy(threadId = "thread-b") } }
    }

    @Test fun cancellationDoesNotReadAFallbackRoomOrConvertToASuccess() = runBlocking {
        var reads = 0; val expected = CancellationException("Synthetic picker cancellation")
        try {
            PrivateDataUiScopeCapture.capture("thread-a") { reads++; throw expected }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(expected, actual) }
        assertEquals(1, reads)
    }

    @Test fun privateSourceCheckpointAndManifestCollectionsAreAbsentFromOrdinaryNotes() {
        listOf("private-analysis", "private-analysis-sources", "recording-transcription-manifests", "audio-transcripts",
            "imported-chats", "native-sms", "planning-feedback", "chat-goals").forEach {
            assertFalse("Internal collection must not render as an ordinary note", PrivateDataUiNotes.ordinaryCollection(it))
        }
    }

    @Test fun ordinaryUserNotesRemainVisibleWithoutBroadPrefixSuppression() {
        listOf("default", "meeting-notes", "private-study", "private-analysis-notes").forEach {
            assertTrue("User collection must remain visible", PrivateDataUiNotes.ordinaryCollection(it))
        }
    }
}
