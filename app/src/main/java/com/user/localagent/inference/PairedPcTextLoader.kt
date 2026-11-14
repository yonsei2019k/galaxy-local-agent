package com.user.localagent.inference

import com.user.localagent.models.*

/** Host-only typed bridge. The generic path-based inference interface and local helpers stay intact. */
interface PairedPcTextLoader {
    suspend fun loadPairedPcText(target: PairedPcText, options: InferenceOptions): PairedPcLoadReceipt
}

suspend fun InferenceEngine.loadTarget(target: InferenceModelTarget, options: InferenceOptions): PairedPcLoadReceipt? {
    target.requireSelectable()
    return when (target) {
        is LocalWeight -> { load(target.installed.path, options); null }
        is PairedPcText -> {
            val loader = this as? PairedPcTextLoader
                ?: throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_LOADER_UNAVAILABLE)
            val receipt = loader.loadPairedPcText(target, options)
            check(receipt.matches(target, options.contextSize)) { "PC 모델 준비 결과가 원래 선택과 다릅니다." }
            receipt
        }
    }
}
