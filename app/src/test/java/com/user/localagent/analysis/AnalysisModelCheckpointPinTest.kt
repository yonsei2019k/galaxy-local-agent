package com.user.localagent.analysis

import com.user.localagent.core.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory host evidence only; does not claim actual PC transport, model or device execution. */
class AnalysisModelCheckpointPinTest {
    private fun target(revision: Long = 7) = checkNotNull(PairedPcText.forRequest("auto", revision, false, revision, true))
    private val options = InferenceOptions(contextSize = 8192)
    private fun local() = LocalWeight(InstalledModel("gemma-e2b", "synthetic phone label", "/synthetic/phone.gguf",
        "a".repeat(64), true, "gguf"))
    private class Host : InferenceEngine, AnalysisModelPreparation {
        override val state = MutableStateFlow("synthetic")
        override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
        override val residentModelPath: String? = null
        var revision = 7L
        var owner = "synthetic-owner"
        var preparations = 0
        var privateInputs = 0
        override suspend fun prepareAnalysisModel(target: InferenceModelTarget, options: InferenceOptions): PreparedAnalysisModel {
            preparations++
            val actual = checkNotNull(PairedPcText.forRequest("auto", revision, false, revision, true))
            val receipt = PairedPcLoadReceipt.fromAuthenticatedLoadReply(actual, buildJsonObject {
                put("modelId", PairedPcTextProfile.MODEL_ID);put("modelSha256", PairedPcTextProfile.MODEL_SHA256)
                put("contextSize", options.contextSize);put("structuredOutput", true)
            }, options.contextSize)
            return PreparedAnalysisModel.pairedPc(this, target, actual, options, receipt, owner)
        }
        override suspend fun load(path: String, options: InferenceOptions) = error("No path load expected")
        override fun generate(prompt: String): Flow<String> { privateInputs++;return emptyFlow() }
        override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>): Int {
            privateInputs++;return 10
        }
        override suspend fun unload() {}
        override fun cancel() {}
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block();fail("Changed analysis identity accepted") } catch (_: IllegalStateException) {}
    }
    @Test fun replacingRequestEngineEvenWithSameModelAndRevisionCannotUsePreviousPin() = runBlocking {
        val chosen = target();val first = Host();val second = Host()
        val pin = first.preparePrivateAnalysis(chosen, options)
        rejected {
            second.preparePrivateAnalysis(chosen, options, pin)
            second.countChatTokens("synthetic private chunk", "", emptyList())
        }
        assertEquals(0, second.privateInputs)
    }
    @Test fun replacingRequestedTargetObjectCannotUsePreviousPin() = runBlocking {
        val host = Host();val pin = host.preparePrivateAnalysis(target(), options)
        rejected { host.preparePrivateAnalysis(target(), options, pin) }
        assertEquals(0, host.privateInputs)
    }
    @Test fun changedContextFailsBeforeAnyPrivateTokenCount() = runBlocking {
        val host = Host();val chosen = target();val pin = host.preparePrivateAnalysis(chosen, options)
        rejected {
            host.preparePrivateAnalysis(chosen, options.copy(contextSize = 4096), pin)
            host.countChatTokens("synthetic private chunk", "", emptyList())
        }
        assertEquals(0, host.privateInputs)
    }
    @Test fun localSelectionCannotHideChangedActualPcRevisionOrOwner() = runBlocking {
        for (changeOwner in listOf(false, true)) {
            val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
            if (changeOwner) host.owner = "replacement-owner" else host.revision = 8
            rejected { host.preparePrivateAnalysis(chosen, options, pin) }
            assertEquals(0, host.privateInputs)
        }
    }
    @Test fun failedPreparationCannotHealByRetryingTheSamePinWithOriginalSettings() = runBlocking {
        val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
        host.revision = 8
        rejected { host.preparePrivateAnalysis(chosen, options, pin) }
        val preparations = host.preparations
        host.revision = 7
        rejected { host.preparePrivateAnalysis(chosen, options, pin) }
        assertEquals(preparations, host.preparations);assertEquals(0, host.privateInputs)
    }

    private class Journal : PrivateAnalysisJournal {
        var current: PrivateAnalysisCheckpoint? = null
        var saves = 0
        override suspend fun load(id: String) = current?.takeIf { it.id == id }
        override suspend fun save(checkpoint: PrivateAnalysisCheckpoint) { current = checkpoint;saves++ }
    }
    private val locator = buildJsonObject { put("snapshotId", "synthetic-snapshot") }
    private val skill = "b".repeat(64)
    private fun source() = PrivateAnalysisSource.create("synthetic-scope", "synthetic-source", PrivateEvidenceKind.SMS,
        "x".repeat(9000), PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
    private val emptyOutput = "{\"findings\":[],\"limitations\":[]}"

    @Test fun invalidJsonAndUnsupportedQuoteStillUseExistingBoundedRepairWithoutClosingPin() = runBlocking {
        val badOutputs = listOf("{", "{\"findings\":[{\"text\":\"unsupported\",\"quote\":\"missing quote\"}],\"limitations\":[]}")
        for (bad in badOutputs) {
            val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
            var calls = 0
            val original = PrivateAnalysisSource.create("synthetic-scope", "synthetic-source", PrivateEvidenceKind.SMS,
                "actual quote", PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
            val result = PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, repair ->
                calls++;host.preparePrivateAnalysis(chosen, options, pin)
                if (repair) emptyOutput else bad
            }, Journal()).run(original, locator, "synthetic question", pin.modelSha256, skill)
            pin.requireUsable()
            assertEquals(2, calls);assertTrue(result.coverage.completeWithinDeclaredScope)
            assertEquals(1, result.checkpoint.processed.size)
        }
    }
    @Test fun originalPreparationCancellationIsNotWrappedByPinOrWorkflow() = runBlocking {
        val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
        val original = CancellationException("synthetic original cancellation")
        val cancelled = object : InferenceEngine by host, AnalysisModelPreparation {
            override suspend fun prepareAnalysisModel(target: InferenceModelTarget, options: InferenceOptions): PreparedAnalysisModel =
                throw original
        }
        val journal = Journal()
        try {
            PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, _ ->
                cancelled.preparePrivateAnalysis(chosen, options, pin)
                emptyOutput
            }, journal).run(source(), locator, "synthetic question", pin.modelSha256, skill)
            fail("Cancellation became ordinary partial result")
        } catch (caught: CancellationException) { assertSame(original, caught) }
        assertTrue(journal.current!!.processed.isEmpty());assertEquals(0, host.privateInputs)
        try { pin.requireUsable();fail("Cancelled pin stayed usable") }
        catch (caught: CancellationException) { assertSame(original, caught) }
    }

    @Test fun boundedOutputRepairCannotHidePreparationFailureAsOrdinaryPartialAnalysis() = runBlocking {
        val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
        host.revision = 8
        val journal = Journal()
        val result = PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, _ ->
            host.preparePrivateAnalysis(chosen, options, pin)
            emptyOutput
        }, journal).run(source(), locator, "synthetic question", pin.modelSha256, skill)
        assertTrue(result.coverage.partial);assertTrue(result.checkpoint.processed.isEmpty())
        rejected { pin.requireUsable() }
        assertEquals(0, host.privateInputs)
    }
    @Test fun overviewFallbackCannotHidePreparationFailureBeforeReportPublication() = runBlocking {
        val host = Host();val chosen = local();val pin = host.preparePrivateAnalysis(chosen, options)
        val original = PrivateAnalysisSource.create("synthetic-scope", "synthetic-source", PrivateEvidenceKind.SMS,
            "actual quote", PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
        val result = PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, _ ->
            "{\"findings\":[{\"text\":\"supported finding\",\"quote\":\"actual quote\"}],\"limitations\":[]}"
        }, Journal()).run(original, locator, "synthetic question", pin.modelSha256, skill)
        host.revision = 8
        val overview = PrivateAnalysisOverview(PrivateOverviewDecider { _, _ ->
            host.preparePrivateAnalysis(chosen, options, pin)
            "{\"selectedIds\":[\"F0\"]}"
        }).select(result.checkpoint)
        assertFalse(overview.verified)
        rejected { pin.requireUsable() }
        assertEquals(0, host.privateInputs)
    }

    @Test fun workflowStoresActualPcHashInsteadOfPhoneSelectionHashAndCanResumeSamePin() = runBlocking {
        val host = Host();val selected = local();val pin = host.preparePrivateAnalysis(selected, options)
        val journal = Journal();var calls = 0
        val workflow = PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, _ -> calls++;emptyOutput }, journal)
        val original = source()
        val first = workflow.run(original, locator, "synthetic question", pin.modelSha256, skill, maxChunks = 1)
        assertEquals(PairedPcTextProfile.MODEL_SHA256, journal.current!!.modelSha256)
        assertNotEquals(selected.installed.sha256, journal.current!!.modelSha256)
        val fresh = host.preparePrivateAnalysis(selected, options, pin)
        val resumed = workflow.run(original, locator, "synthetic question", fresh.modelSha256, skill,
            analysisId = first.checkpoint.id, maxChunks = 32)
        assertEquals(first.checkpoint.id, resumed.checkpoint.id)
        assertEquals(first.checkpoint.processed.single(), resumed.checkpoint.processed.first())
        assertEquals(3, calls);assertTrue(resumed.coverage.completeWithinDeclaredScope)
    }
    @Test fun oldPhoneHashCheckpointCannotBeSilentlyRewrittenOrResumedAsPcHash() = runBlocking {
        val journal = Journal();var calls = 0
        val workflow = PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider { _, _, _ -> calls++;emptyOutput }, journal)
        val original = source();val selected = local()
        val old = workflow.run(original, locator, "synthetic question", selected.installed.sha256, skill, maxChunks = 1).checkpoint
        val saves = journal.saves;val beforeCalls = calls
        val pin = Host().preparePrivateAnalysis(selected, options)
        try {
            workflow.run(original, locator, "synthetic question", pin.modelSha256, skill, analysisId = old.id)
            fail("Old hash checkpoint resumed")
        } catch (_: IllegalArgumentException) {}
        assertEquals(old, journal.current);assertEquals(saves, journal.saves);assertEquals(beforeCalls, calls)
        assertEquals(selected.installed.sha256, journal.current!!.modelSha256)
    }
}
