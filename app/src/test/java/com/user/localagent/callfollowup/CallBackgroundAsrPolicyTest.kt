package com.user.localagent.callfollowup

import com.user.localagent.models.AsrCatalog
import org.junit.Assert.*
import org.junit.Test

class CallBackgroundAsrPolicyTest {
    @Test fun selectedMlKitNeverBecomesBackgroundEngineEvenWhenItIsAvailable() {
        val choice = CallBackgroundAsrPolicy.select(AsrCatalog.DEVICE_MODEL,
            setOf(AsrCatalog.DEVICE_MODEL, "asr-qwen-17b", "asr-qwen-06b"))
        assertEquals("asr-qwen-17b", choice?.modelId)
        assertEquals("ko", choice?.language)
        assertEquals(CallBackgroundAsrReason.VERIFIED_BASELINE_17B, choice?.reason)
    }

    @Test fun onlyAvailableMlKitDoesNotClaimBackgroundReadiness() {
        assertNull(CallBackgroundAsrPolicy.select(AsrCatalog.DEVICE_MODEL, setOf(AsrCatalog.DEVICE_MODEL)))
        assertNull(CallBackgroundAsrPolicy.select(null, emptySet()))
    }

    @Test fun selectedVerifiedSmallQwenIsHonoredOverTheBaseline() {
        val choice = CallBackgroundAsrPolicy.select("asr-qwen-06b", setOf("asr-qwen-06b", "asr-qwen-17b"))
        assertEquals("asr-qwen-06b", choice?.modelId)
        assertEquals(CallBackgroundAsrReason.SELECTED_VERIFIED_QWEN, choice?.reason)
    }

    @Test fun selectedVerifiedLargeQwenIsHonored() {
        val choice = CallBackgroundAsrPolicy.select("asr-qwen-17b", setOf("asr-qwen-17b", "asr-qwen-06b"))
        assertEquals("asr-qwen-17b", choice?.modelId)
        assertEquals(CallBackgroundAsrReason.SELECTED_VERIFIED_QWEN, choice?.reason)
    }

    @Test fun unavailableSelectionFallsBackToOnlyVerifiedCachedSmallQwen() {
        val choice = CallBackgroundAsrPolicy.select("asr-qwen-17b", setOf("asr-qwen-06b"))
        assertEquals("asr-qwen-06b", choice?.modelId)
        assertEquals(CallBackgroundAsrReason.VERIFIED_FALLBACK_06B, choice?.reason)
    }

    @Test fun encoderIdOrOrdinaryLlmCannotStandInForVerifiedAsrPair() {
        val incomplete = setOf("asr-qwen-17b-mmproj", "gemma-e2b", "qwen-4b")
        assertNull(CallBackgroundAsrPolicy.select("gemma-e2b", incomplete))
    }

    @Test fun nullOrUnknownGlobalSelectionUsesTheDocumentedBaselineNotArbitrarySetOrder() {
        val ready = linkedSetOf("asr-qwen-06b", "asr-qwen-17b")
        assertEquals(AsrCatalog.DEFAULT_MODEL, CallBackgroundAsrPolicy.select(null, ready)?.modelId)
        assertEquals(AsrCatalog.DEFAULT_MODEL, CallBackgroundAsrPolicy.select("unknown-global-model", ready)?.modelId)
    }
}
