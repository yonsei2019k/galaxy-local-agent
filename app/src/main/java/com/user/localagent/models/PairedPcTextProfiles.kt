package com.user.localagent.models

/** Explicit comparison identity. This never replaces the existing auto profile. */
internal object PairedPcFast4BProfile {
    const val SELECTION_ID = "paired-pc-text-fast4b-v1"
    const val MODEL_ID = "pc-text-4b"
    const val MODEL_SHA256 = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    const val TITLE = "PC · Qwen3.5 4B (비교)"
}

/** Immutable host metadata, not proof of model load, permissions or native thinking. */
internal enum class PairedPcTextProfileDescriptor(
    val selectionId: String,
    val modelId: String,
    val modelSha256: String,
    val title: String,
) {
    LEGACY_V1(
        PairedPcTextProfile.SELECTION_ID,
        PairedPcTextProfile.MODEL_ID,
        PairedPcTextProfile.MODEL_SHA256,
        PairedPcTextProfile.TITLE,
    ),
    FAST_4B_V1(
        PairedPcFast4BProfile.SELECTION_ID,
        PairedPcFast4BProfile.MODEL_ID,
        PairedPcFast4BProfile.MODEL_SHA256,
        PairedPcFast4BProfile.TITLE,
    );

    fun matchesModelIdentity(actualModelId: String?, actualModelSha256: String?): Boolean =
        actualModelId == modelId && actualModelSha256 == modelSha256

    companion object {
        fun forSelection(selectionId: String?): PairedPcTextProfileDescriptor? =
            if (selectionId == "auto") LEGACY_V1 else forExplicitSelection(selectionId)

        fun forExplicitSelection(selectionId: String?): PairedPcTextProfileDescriptor? =
            when (selectionId) {
                PairedPcTextProfile.SELECTION_ID -> LEGACY_V1
                PairedPcFast4BProfile.SELECTION_ID -> FAST_4B_V1
                else -> null
            }

        fun forModelAlias(modelId: String?): PairedPcTextProfileDescriptor? =
            when (modelId) {
                PairedPcTextProfile.MODEL_ID -> LEGACY_V1
                PairedPcFast4BProfile.MODEL_ID -> FAST_4B_V1
                else -> null
            }
    }
}

/** Fence the newly introduced 4B route without reinterpreting legacy non-4B snapshots. */
internal object PairedPcContinuationProfileGuard {
    fun compatible(requestSelectionId: String, continuationSelectionId: String): Boolean {
        val original = PairedPcTextProfileDescriptor.forSelection(requestSelectionId)
        val continued = PairedPcTextProfileDescriptor.forExplicitSelection(continuationSelectionId)
        val fast = PairedPcTextProfileDescriptor.FAST_4B_V1
        return if (original == fast || continued == fast) original != null && original == continued else true
    }
}
