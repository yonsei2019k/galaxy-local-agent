package com.user.localagent.callfollowup

import com.user.localagent.models.AsrCatalog

internal enum class CallBackgroundAsrReason { SELECTED_VERIFIED_QWEN, VERIFIED_BASELINE_17B, VERIFIED_FALLBACK_06B }
internal data class CallBackgroundAsrChoice(val modelId: String, val language: String = "ko", val reason: CallBackgroundAsrReason)

/**
 * Background call processing cannot use foreground-only ML Kit GenAI inference, including via FGS.
 * Caller supplies only IDs whose Qwen model AND encoder are installed and verified. Baseline order
 * is an explicit compatibility policy, not a measured claim that 1.7B is best on this phone.
 * This pure choice never downloads or mutates the owner's global ASR model/language preferences.
 */
internal object CallBackgroundAsrPolicy {
    fun select(selectedId: String?, verifiedReadyIds: Set<String>): CallBackgroundAsrChoice? {
        val ready = verifiedReadyIds.intersect(AsrCatalog.modelIds)
        if (selectedId != null && selectedId in ready) return CallBackgroundAsrChoice(selectedId, reason = CallBackgroundAsrReason.SELECTED_VERIFIED_QWEN)
        if (AsrCatalog.DEFAULT_MODEL in ready) return CallBackgroundAsrChoice(AsrCatalog.DEFAULT_MODEL, reason = CallBackgroundAsrReason.VERIFIED_BASELINE_17B)
        if ("asr-qwen-06b" in ready) return CallBackgroundAsrChoice("asr-qwen-06b", reason = CallBackgroundAsrReason.VERIFIED_FALLBACK_06B)
        return null
    }
}
