package com.user.localagent.audio

import com.user.localagent.core.RecordingToolCatalog
import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class RecordingSourceContractTest {
    private fun item(uri: String = "content://media/external/audio/media/42") = RecordingListingItem("합성 녹음", uri, 31_000)
    private fun chunk(index: Int, start: Long = index * 15_000L, end: Long = start + 15_000L) =
        AudioTranscriptChunk(index, UUID.randomUUID().toString(), start, end, RecordingTranscriptManifest.sha256("synthetic-$index"))
    private fun fails(block: () -> Unit) { try { block(); fail("Expected source/contract rejection") } catch (_: Exception) { } }

    @Test fun listingProducesOpaqueIdsAndOnlyResolvesActualListedMediaStoreEntry() {
        val store = ListedRecordingStore("thread-a") { "run-a" }
        val listing = store.register(listOf(item())).single()
        assertFalse(listing.recordingId.contains("content:"))
        val selected = store.resolve(listing.recordingId)
        assertEquals("content://media/external/audio/media/42", selected.contentUri)
        assertEquals("thread-a", selected.threadId); assertEquals("run-a", selected.requestRunId)
        fails { store.resolve("content://media/external/audio/media/42") }
        fails { store.resolve(UUID.randomUUID().toString()) }
    }

    @Test fun replacingListingInvalidatesPreviousTokensIncludingTheSameProviderEntry() {
        val store = ListedRecordingStore("thread-a") { "run-a" }
        val old = store.resolve(store.register(listOf(item())).single().recordingId)
        val next = store.register(listOf(item())).single()
        assertNotEquals(old.recordingId, next.recordingId)
        fails { store.requireCurrent(old) }; fails { store.resolve(old.recordingId) }
        store.resolve(next.recordingId)
    }

    @Test fun endingOrReplacingRunRevokesPriorSelectionWithoutRetainingUriAuthority() {
        var run: String? = "run-a"
        val store = ListedRecordingStore("thread-a") { run }
        val selected = store.resolve(store.register(listOf(item())).single().recordingId)
        run = "run-b"; fails { store.requireCurrent(selected) }
        run = null; fails { store.resolve(selected.recordingId) }; fails { store.register(listOf(item())) }
    }

    @Test fun anotherExecutorOrThreadCannotUseASelectionEvenWithTheSameRunName() {
        val first = ListedRecordingStore("thread-a") { "run-a" }
        val second = ListedRecordingStore("thread-b") { "run-a" }
        val selected = first.resolve(first.register(listOf(item())).single().recordingId)
        second.register(listOf(item()))
        fails { second.resolve(selected.recordingId) }; fails { second.requireCurrent(selected) }
    }

    @Test fun arbitraryProvidersPathsQueryFragmentsAndOversizedListingsAreRejected() {
        for (uri in listOf("file:///data/private.wav", "content://evil/external/audio/media/1", "content://media/internal/audio/media/1",
            "content://media/external/audio/media/1?other=1", "content://media/external/audio/media/1#fragment", "https://example.com/audio")) {
            fails { ListedRecordingStore("thread-a") { "run-a" }.register(listOf(item(uri))) }
        }
        fails { ListedRecordingStore("thread-a") { "run-a" }.register(List(21) { item() }) }
    }

    @Test fun emptySuccessfulListingRevokesPreviouslyListedItems() {
        val store = ListedRecordingStore("thread-a") { "run-a" }
        val old = store.register(listOf(item())).single().recordingId
        assertTrue(store.register(emptyList()).isEmpty()); fails { store.resolve(old) }
    }

    @Test fun transcriptCatalogRejectsUriInjectionExtraArgumentsAndUnlistedTokenSyntax() {
        val token = UUID.randomUUID().toString()
        RecordingToolCatalog.validate(ToolCall("test", "transcribe_recording", buildJsonObject { put("recordingId", token) }))
        fails { RecordingToolCatalog.validate(ToolCall("test", "transcribe_recording", buildJsonObject { put("uri", "content://media/external/audio/media/1") })) }
        fails { RecordingToolCatalog.validate(ToolCall("test", "transcribe_recording", buildJsonObject { put("recordingId", token); put("uri", "content://media/external/audio/media/1") })) }
        fails { RecordingToolCatalog.validate(ToolCall("test", "transcribe_recording", buildJsonObject { put("recordingId", "content://media/external/audio/media/1") })) }
    }

    @Test fun chunkManifestPreservesFullCoverageWithAnActualShortFinalDecoderWindow() {
        val chunks = listOf(chunk(0), chunk(1), chunk(2, 30_000, 31_234))
        RecordingTranscriptManifest.validateChunks(chunks, 31_234)
        val page = Json.parseToJsonElement(RecordingTranscriptManifest.pageJson(UUID.randomUUID().toString(), 0, chunks)).jsonObject
        assertEquals(31_234, page.getValue("chunks").jsonArray.last().jsonObject.getValue("endMillis").jsonPrimitive.int)
        assertEquals(chunks.last().noteSha256, page.getValue("chunks").jsonArray.last().jsonObject.getValue("noteSha256").jsonPrimitive.content)
    }

    @Test fun missingOverlappingReorderedOrRepeatedChunksCannotClaimCompleteCoverage() {
        fails { RecordingTranscriptManifest.validateChunks(listOf(chunk(0), chunk(1, 16_000, 30_000)), 30_000) }
        fails { RecordingTranscriptManifest.validateChunks(listOf(chunk(0), chunk(1, 14_000, 30_000)), 30_000) }
        fails { RecordingTranscriptManifest.validateChunks(listOf(chunk(1), chunk(0)), 30_000) }
        val first = chunk(0)
        fails { RecordingTranscriptManifest.validateChunks(listOf(first, first.copy(index = 1, startMillis = 15_000, endMillis = 30_000)), 30_000) }
        fails { RecordingTranscriptManifest.validateChunks(listOf(chunk(0)), 30_000) }
    }

    @Test fun fullTwoHourManifestIsPagedBelowRoomNoteLimitAndDeclaresChunkTimestampLimits() {
        val transcript = UUID.randomUUID().toString()
        val chunks = (0 until 480).map { chunk(it) }
        RecordingTranscriptManifest.validateChunks(chunks, 7_200_000)
        val pages = chunks.chunked(20).mapIndexed { index, values ->
            val text = RecordingTranscriptManifest.pageJson(transcript, index, values)
            assertTrue(text.length <= 12_000)
            TranscriptManifestPage(UUID.randomUUID().toString(), RecordingTranscriptManifest.sha256(text))
        }
        val store = ListedRecordingStore("thread-a") { "run-a" }
        val selected = store.resolve(store.register(listOf(item())).single().recordingId)
        val root = Json.parseToJsonElement(RecordingTranscriptManifest.rootJson(transcript, selected, 7_200_000,
            "asr-qwen-17b", RecordingTranscriptManifest.sha256("synthetic full text"), 480, pages)).jsonObject
        assertEquals(24, root.getValue("pages").jsonArray.size)
        assertFalse(root.getValue("wordTimestamps").jsonPrimitive.boolean)
        assertFalse(root.getValue("speakerDiarization").jsonPrimitive.boolean)
        assertFalse(root.getValue("originalSpeechCompletenessVerified").jsonPrimitive.boolean)
        assertTrue(root.toString().length <= 12_000)
    }

    @Test fun orderedTranscriptHashCanBeRecomputedAndDetectsReorderOrModification() {
        val texts = listOf("전사 자료 합성 · 0–15초\n첫 번째 🛰️", "전사 자료 합성 · 15–20초\n두 번째")
        val digest = MessageDigest.getInstance("SHA-256").digest((texts.joinToString("\n") + "\n").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, RecordingTranscriptManifest.sourceTextSha256(texts))
        assertNotEquals(digest, RecordingTranscriptManifest.sourceTextSha256(texts.reversed()))
        assertNotEquals(digest, RecordingTranscriptManifest.sourceTextSha256(listOf(texts.first(), texts.last() + " changed")))
    }

    @Test fun cleanupResultCannotClaimVerifiedWhenEditedOrUnverifiedOutputsWereRetained() {
        assertTrue(AudioCleanupResult(deleted = 2, missing = 1).verified)
        assertFalse(AudioCleanupResult(retainedChanged = 1).verified)
        assertFalse((AudioCleanupResult(deleted = 2) + AudioCleanupResult(retainedUnverified = 1)).verified)
    }
}
