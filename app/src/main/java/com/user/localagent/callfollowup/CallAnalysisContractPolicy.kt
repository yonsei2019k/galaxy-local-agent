package com.user.localagent.callfollowup

/** A changed extractor cannot silently reinterpret an already successful map ledger. */
internal object CallAnalysisContractPolicy {
    fun permitsRetry(errorCode: String?): Boolean = errorCode != "ANALYSIS_CONTRACT_CHANGED"
    enum class Decision { SAME_CONTRACT, ADOPT_UNPROCESSED_LEGACY, REQUIRES_EXPLICIT_REANALYSIS }
    fun decide(savedSha: String?, actualSha: String, analyzedChunks: Int, hasLegacyMapCheckpoint: Boolean): Decision {
        require(actualSha.matches(Regex("[a-f0-9]{64}")) && analyzedChunks >= 0)
        return when {
            savedSha == actualSha -> Decision.SAME_CONTRACT
            savedSha == null && analyzedChunks == 0 && !hasLegacyMapCheckpoint -> Decision.ADOPT_UNPROCESSED_LEGACY
            else -> Decision.REQUIRES_EXPLICIT_REANALYSIS
        }
    }
}
