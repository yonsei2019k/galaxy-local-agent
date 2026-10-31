package com.user.localagent.audio

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrivateDataPickerOriginsTest {
    private fun scope() = PrivateDataPickerScope("thread-a", "branch-a", "project-a", "project:project-a", false, 7, true)
    private fun fails(block: () -> Unit) { try { block(); fail("Expected picker-origin rejection") } catch (_: Exception) { } }

    @Test fun originPreservesCapturedScopeWithoutConsultingAnotherCurrentRoom() {
        val registry = PrivateDataPickerOrigins()
        var selected = scope()
        val id = registry.begin(selected, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        selected = selected.copy(threadId = "thread-b", branchId = "branch-b", projectId = "project-b", scopeKey = "project:project-b", threadRevision = 8)
        val origin = registry.consume(id, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        assertEquals("thread-b", selected.threadId)
        assertEquals("thread-a", origin.scope.threadId); assertEquals("branch-a", origin.scope.branchId)
        assertEquals(7L, origin.scope.threadRevision); assertFalse(origin.scope.useOwnerMemory)
        assertEquals("project:project-a", origin.scope.scopeKey); assertEquals(id, origin.selectionId)
    }

    @Test fun eachPickerCallbackCanConsumeItsOwnOriginExactlyOnce() {
        val registry = PrivateDataPickerOrigins()
        val id = registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        registry.consume(id, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        fails { registry.consume(id, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }
        assertEquals(0, registry.pendingCount)
    }

    @Test fun audioAndConversationOriginsRemainIndependentAndCannotBeCrossConsumed() {
        val registry = PrivateDataPickerOrigins()
        val audio = registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        val conversation = registry.begin(scope().copy(threadId = "thread-c"), PrivateDataPickerPurpose.CONVERSATION_IMPORT)
        assertNotEquals(audio, conversation)
        fails { registry.consume(audio, PrivateDataPickerPurpose.CONVERSATION_IMPORT) }
        fails { registry.consume(audio, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }
        assertEquals("thread-c", registry.consume(conversation, PrivateDataPickerPurpose.CONVERSATION_IMPORT).scope.threadId)
    }

    @Test fun savedUuidAfterProcessLossAndInventedUuidBothRejectWithoutFallback() {
        val savedUuid = PrivateDataPickerOrigins().begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        val recreatedProcess = PrivateDataPickerOrigins()
        fails { recreatedProcess.consume(savedUuid, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }
        fails { recreatedProcess.consume(UUID.randomUUID().toString(), PrivateDataPickerPurpose.CONVERSATION_IMPORT) }
        assertEquals(0, recreatedProcess.pendingCount)
    }

    @Test fun cancelRevokesOnlyThatPendingOrigin() {
        val registry = PrivateDataPickerOrigins()
        val audio = registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        val text = registry.begin(scope(), PrivateDataPickerPurpose.CONVERSATION_IMPORT)
        assertTrue(registry.cancel(audio)); assertFalse(registry.cancel(audio))
        fails { registry.consume(audio, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }
        registry.consume(text, PrivateDataPickerPurpose.CONVERSATION_IMPORT)
    }

    @Test fun capacityRejectsNewPickerWithoutEvictingPreviouslyAuthorizedOrigin() {
        val registry = PrivateDataPickerOrigins(1)
        val first = registry.begin(scope(), PrivateDataPickerPurpose.CONVERSATION_IMPORT)
        fails { registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }
        assertEquals(first, registry.consume(first, PrivateDataPickerPurpose.CONVERSATION_IMPORT).selectionId)
        registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
    }

    @Test fun racingDuplicateCallbacksDoNotDuplicateSourceProcessing() {
        val registry = PrivateDataPickerOrigins()
        val id = registry.begin(scope(), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
        val start = CountDownLatch(1); val done = CountDownLatch(8); val consumed = AtomicInteger()
        repeat(8) {
            Thread {
                try { start.await(); registry.consume(id, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION); consumed.incrementAndGet() }
                catch (_: Exception) { /* duplicate rejected */ }
                finally { done.countDown() }
            }.start()
        }
        start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, consumed.get()); assertEquals(0, registry.pendingCount)
    }

    @Test fun malformedScopeCannotRegisterAnAmbiguousOrigin() {
        fails { scope().copy(threadId = "") }; fails { scope().copy(branchId = "../other") }
        fails { scope().copy(threadRevision = -1) }; fails { scope().copy(scopeKey = "owner\u0000other") }
    }

    @Test fun userSelectedManifestHasNoFabricatedRunOrMediaStoreCapability() {
        val source = UUID.randomUUID().toString(); val picker = UUID.randomUUID().toString()
        val page = TranscriptManifestPage(UUID.randomUUID().toString(), RecordingTranscriptManifest.sha256("synthetic page"))
        val root = Json.parseToJsonElement(RecordingTranscriptManifest.rootForUserSelection(source, "thread-a", picker,
            "선택한 합성 녹음", 12_345, "asr-qwen-17b", RecordingTranscriptManifest.sha256("synthetic transcript"), 1, listOf(page))).jsonObject
        assertEquals("recording_transcript", root.getValue("kind").jsonPrimitive.content)
        assertEquals("recording-transcript:$source", root.getValue("sourceIdentity").jsonPrimitive.content)
        assertEquals("user_selected_document", root.getValue("origin").jsonPrimitive.content)
        assertEquals(picker, root.getValue("pickerSelectionId").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("requestRunId")); assertFalse(root.containsKey("recordingId"))
        assertFalse(root.toString().contains("content://"))
        assertFalse(root.getValue("wordTimestamps").jsonPrimitive.boolean)
        assertTrue(root.getValue("fullTextPersisted").jsonPrimitive.boolean)
    }

    @Test fun userSelectedManifestRequiresValidIdentityAndCompleteOrderedPages() {
        val transcript = UUID.randomUUID().toString(); val picker = UUID.randomUUID().toString()
        val page = TranscriptManifestPage(UUID.randomUUID().toString(), RecordingTranscriptManifest.sha256("page"))
        fun root(token: String = picker, pages: List<TranscriptManifestPage> = listOf(page)) =
            RecordingTranscriptManifest.rootForUserSelection(transcript, "thread-a", token, "synthetic", 15_000,
                "asr-qwen-17b", RecordingTranscriptManifest.sha256("text"), 1, pages)
        fails { root("invented-picker") }; fails { root(pages = emptyList()) }
        assertTrue(root().length < 12_000)
    }
}
