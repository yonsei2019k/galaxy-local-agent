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
    GEMMA_E4B_V1(
        "paired-pc-gemma-e4b-v1",
        "gemma-e4b",
        com.user.localagent.core.GemmaImagePreparation.hashes.getValue("gemma-e4b"),
        "PC · Gemma E4B",
    ),
    FAST_4B_V1(
        PairedPcFast4BProfile.SELECTION_ID,
        PairedPcFast4BProfile.MODEL_ID,
        PairedPcFast4BProfile.MODEL_SHA256,
        PairedPcFast4BProfile.TITLE,
    );

    fun matchesModelIdentity(actualModelId: String?, actualModelSha256: String?): Boolean =
        actualModelId == modelId && actualModelSha256 == modelSha256

    val supportsQwenThinking: Boolean get() = this == LEGACY_V1 || this == FAST_4B_V1

    fun requireRunnable() {
        if (this != GEMMA_E4B_V1)
            throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_MODEL_RETIRED)
    }

    companion object {
        fun forSelection(selectionId: String?): PairedPcTextProfileDescriptor? =
            if (selectionId == "auto") LEGACY_V1 else forExplicitSelection(selectionId)

        fun forExplicitSelection(selectionId: String?): PairedPcTextProfileDescriptor? =
            entries.firstOrNull { it.selectionId == selectionId }

        fun forModelAlias(modelId: String?): PairedPcTextProfileDescriptor? =
            entries.firstOrNull { it.modelId == modelId }

        fun resolveRequestSelection(selectionId: String, capturedDefault: String?): String =
            if (selectionId == "auto" || selectionId == com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION)
                capturedDefault ?: "auto" else selectionId

        fun directTextProfile(capturedDefault: String?, requestOwned: Boolean): PairedPcTextProfileDescriptor {
            val profile = if (requestOwned) forSelection(capturedDefault ?: "auto") else GEMMA_E4B_V1
            return checkNotNull(profile).also { it.requireRunnable() }
        }
    }
}

/** Keep newer profiles bound to the original selection without reinterpreting legacy snapshots. */
internal object PairedPcContinuationProfileGuard {
    fun compatible(requestSelectionId: String, continuationSelectionId: String): Boolean {
        val original = PairedPcTextProfileDescriptor.forSelection(requestSelectionId)
        val continued = PairedPcTextProfileDescriptor.forExplicitSelection(continuationSelectionId)
        val boundProfiles = setOf(PairedPcTextProfileDescriptor.FAST_4B_V1, PairedPcTextProfileDescriptor.GEMMA_E4B_V1)
        return if (original in boundProfiles || continued in boundProfiles) original == continued else true
    }
}
