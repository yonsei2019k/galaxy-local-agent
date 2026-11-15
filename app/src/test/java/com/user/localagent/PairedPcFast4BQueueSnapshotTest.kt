package com.user.localagent

import com.user.localagent.inference.InferenceOptions
import com.user.localagent.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PairedPcFast4BQueueSnapshotTest {
    private val fast = "paired-pc-text-fast4b-v1"
    private val legacy = "paired-pc-text-v1"
    private fun request(model: String, revision: Long? = 17) = QueuedChatRequest(
        "qa-run", "qa-thread", "qa-branch", "qa-project", "합성 요청", model, false,
        emptyList(), "qa-owner-message", allowTools = false, mode = "plan", queueOrder = 9,
        capturedPcRevision = revision, allowCloudText = false,
    )

    @Test fun eachStoredSelectionKeepsItsOriginalAuthorityAndReadOnlyRestrictions() {
        for (id in listOf("auto", legacy, fast)) {
            val original = request(id)
            val restored = QueuedChatRequest.fromJson(original.toJson())
            assertEquals(original, restored)
            assertFalse(restored.allowTools)
            assertFalse(restored.allowCloudText)
            assertEquals("plan", restored.mode)
            val chosen = checkNotNull(RequestInferenceSelection.pairedText(restored.modelId,
                restored.capturedPcRevision, restored.allowCloudText, 17, true, restored.thinking))
            assertEquals(if (id == fast) fast else legacy, chosen.model.id)
        }
    }

    @Test fun legacyAutoDoesNotGainTodaysPairOrTheNewFastModel() {
        val original = request("auto")
        val oldJson = JsonObject(Json.parseToJsonElement(original.toJson()).jsonObject - setOf("capturedPcRevision", "allowCloudText"))
        val restored = QueuedChatRequest.fromJson(oldJson.toString())
        assertEquals("auto", restored.modelId)
        assertNull(restored.capturedPcRevision)
        assertFalse(restored.allowTools)
        assertNull(RequestInferenceSelection.pairedText(restored.modelId, restored.capturedPcRevision,
            restored.allowCloudText, 17, true, false))
    }

    @Test fun copyingAContinuationOrSteerNeverReplacesTheCapturedSelection() {
        val original = request(fast)
        val snapshot = ContinuationSnapshot("qa-answer", fast, "이어서", "합성 지침", emptyList(),
            InferenceOptions(contextSize = 8192, maxOutputTokens = 768), 2)
        val copied = original.copy(runId = "qa-continuation", text = original.text + "\n추가 지시", continuation = snapshot)
        val restored = QueuedChatRequest.fromJson(copied.toJson())
        assertEquals(fast, restored.modelId)
        assertEquals(snapshot, restored.continuation)
        assertEquals(original.capturedPcRevision, restored.capturedPcRevision)
        assertFalse(restored.allowTools)
        assertFalse(restored.allowCloudText)
    }

    @Test fun continuationsCannotCrossBetweenFastAndAnyOtherCapturedSelection() {
        for ((requestId, snapshotId) in listOf(
            fast to legacy, fast to "auto", fast to "local-helper", fast to "unknown",
            legacy to fast, "auto" to fast, "local-helper" to fast, "unknown" to fast,
        )) assertFalse("$requestId -> $snapshotId", PairedPcContinuationProfileGuard.compatible(requestId, snapshotId))
        assertTrue(PairedPcContinuationProfileGuard.compatible(fast, fast))
    }

    @Test fun legacyContinuationInterpretationIsNotRetargetedOrTightenedByTheNewProfile() {
        assertTrue(PairedPcContinuationProfileGuard.compatible("auto", legacy))
        assertTrue(PairedPcContinuationProfileGuard.compatible(legacy, legacy))
        assertTrue(PairedPcContinuationProfileGuard.compatible("local-helper", "local-helper"))
        assertTrue(PairedPcContinuationProfileGuard.compatible("legacy-label", legacy))
    }
}
