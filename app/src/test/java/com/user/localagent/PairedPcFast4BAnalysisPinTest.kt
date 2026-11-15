package com.user.localagent

import com.user.localagent.inference.*
import com.user.localagent.models.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic authenticated receipts exercise host identity guards, not real model preparation. */
class PairedPcFast4BAnalysisPinTest {
    private val fastId = "paired-pc-text-fast4b-v1"
    private val legacyId = "paired-pc-text-v1"
    private val fastHash = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    private val legacyHash = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    private val options = InferenceOptions(contextSize = 8192, maxOutputTokens = 768)
    private fun target(id: String = fastId, revision: Long = 17) =
        checkNotNull(PairedPcText.forRequest(id, revision, false, revision, true))
    private fun receipt(actual: PairedPcText) = PairedPcLoadReceipt.fromAuthenticatedLoad(
        actual, if (actual.id == fastId) "pc-text-4b" else "pc-text",
        if (actual.id == fastId) fastHash else legacyHash, 8192, true, 8192,
    )
    private fun localLabel() = LocalWeight(InstalledModel("gemma-e2b", "synthetic phone label",
        "/synthetic/models/phone.gguf", "a".repeat(64), true, "gguf"))

    @Test fun sameExplicitFastTargetAndEngineKeepTheActualFullHashAcrossPreparation() {
        val engine = PairedPcTargetRecordingEngine(); val selected = target()
        val first = PreparedAnalysisModel.pairedPc(engine, selected, selected, options, receipt(selected), "qa-owner")
        val next = PreparedAnalysisModel.pairedPc(engine, selected, selected, options, receipt(selected), "qa-owner")
        assertEquals(fastHash, first.modelSha256)
        first.requireRequest(selected, options)
        first.requireSameActualModel(next)
    }

    @Test fun validReceiptsForAnotherProfileCannotRelabelEitherRequestedProfile() {
        val engine = PairedPcTargetRecordingEngine(); val fast = target(); val legacy = target(legacyId)
        assertTrue(runCatching {
            PreparedAnalysisModel.pairedPc(engine, fast, legacy, options, receipt(legacy), "qa-owner")
        }.isFailure)
        assertTrue(runCatching {
            PreparedAnalysisModel.pairedPc(engine, legacy, fast, options, receipt(fast), "qa-owner")
        }.isFailure)
    }

    @Test fun aLegacyPhoneLabelKeepsIts9BCompatibilityButCannotAcquireTheNewFastProfile() {
        val engine = PairedPcTargetRecordingEngine(); val label = localLabel(); val legacy = target(legacyId); val fast = target()
        val old = PreparedAnalysisModel.pairedPc(engine, label, legacy, options, receipt(legacy), "qa-owner")
        assertEquals(legacyHash, old.modelSha256)
        assertTrue(runCatching {
            PreparedAnalysisModel.pairedPc(engine, label, fast, options, receipt(fast), "qa-owner")
        }.isFailure)
    }

    @Test fun fastPreparationCannotBorrowAnotherRevisionOrResumeUnderAnotherOwner() {
        val engine = PairedPcTargetRecordingEngine(); val selected = target(); val changed = target(revision = 18)
        assertTrue(runCatching {
            PreparedAnalysisModel.pairedPc(engine, selected, changed, options, receipt(changed), "qa-owner")
        }.isFailure)
        val first = PreparedAnalysisModel.pairedPc(engine, selected, selected, options, receipt(selected), "qa-owner")
        val another = PreparedAnalysisModel.pairedPc(engine, selected, selected, options, receipt(selected), "qa-other-owner")
        assertTrue(runCatching { first.requireSameActualModel(another) }.isFailure)
    }
}
