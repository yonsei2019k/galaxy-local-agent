package com.user.localagent

import com.user.localagent.compute.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisModelPreparationTest {
    private fun target(revision: Long = 7) = checkNotNull(PairedPcText.forRequest("auto", revision, false, revision, true))
    private val options = InferenceOptions(contextSize = 8192, maxOutputTokens = 1024)
    private fun local(id: String = "gemma-e2b") = LocalWeight(InstalledModel(id, "synthetic phone selection",
        "/synthetic/models/phone.gguf", "a".repeat(64), true, "gguf"))
    private class Backend : InferenceEngine, PairedPcTextLoader {
        override val state = MutableStateFlow("synthetic")
        override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
        override val residentModelPath: String? = null
        override val supportsStructuredOutput = true
        var typedLoads = 0
        var pathLoads = 0
        var cancellations = 0
        var generations = 0
        var counts = 0
        var loadFailure: Throwable? = null
        var rawContext: JsonElement? = null
        override suspend fun load(path: String, options: InferenceOptions) { pathLoads++ }
        override suspend fun loadPairedPcText(target: PairedPcText, options: InferenceOptions): PairedPcLoadReceipt {
            typedLoads++
            loadFailure?.let { throw it }
            val reply = buildJsonObject {
                put("modelId", PairedPcTextProfile.MODEL_ID)
                put("modelSha256", PairedPcTextProfile.MODEL_SHA256)
                put("contextSize", rawContext ?: JsonPrimitive(options.contextSize))
                put("structuredOutput", true)
            }
            return PairedPcLoadReceipt.fromAuthenticatedLoadReply(target, reply, options.contextSize)
        }
        override fun generate(prompt: String): Flow<String> { generations++;return emptyFlow() }
        override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>): Int {
            counts++;return 10
        }
        override suspend fun unload() {}
        override fun cancel() { cancellations++ }
    }
    private fun facade(local: Backend, pc: InferenceEngine, captured: Long? = 7,
        current: () -> Long = { 7 }, cloud: Boolean = false, owner: String? = "synthetic-analysis-owner",
        valid: () -> Boolean = { true }, pcOnly: Boolean = true) = AutomaticComputeEngine(
        local, pc, { _, _ -> true }, { true }, {}, owner,
        ComputeResourceBroker(OwnedComputeGate(), OwnedComputeGate()),
        externalPcAuthority = valid, pcOnly = pcOnly,
        analysisPcTarget = { PairedPcText.forRequest("auto", captured, cloud, current(), true) })
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block();fail("Expected analysis preparation rejection") }
        catch (_: IllegalStateException) {}
        catch (_: IllegalArgumentException) {}
    }

    @Test fun pairedTargetNeedsNoPhoneWeightAndUsesActualTypedReply() = runBlocking {
        val phone = Backend();val pc = Backend();val engine = facade(phone, pc)
        try {
            val pin = engine.preparePrivateAnalysis(target(), options)
            assertEquals(PairedPcTextProfile.MODEL_SHA256, pin.modelSha256)
            assertEquals(1, pc.typedLoads);assertEquals(0, pc.pathLoads);assertEquals(0, phone.pathLoads)
        } finally { engine.unload() }
    }
    @Test fun phoneSelectionIsPinnedToActualPcHashWithoutLoadingPhoneFile() = runBlocking {
        val phone = Backend();val pc = Backend();val engine = facade(phone, pc);val chosen = local()
        try {
            val pin = engine.preparePrivateAnalysis(chosen, options)
            assertEquals(PairedPcTextProfile.MODEL_SHA256, pin.modelSha256)
            assertNotEquals(chosen.installed.sha256, pin.modelSha256)
            assertEquals(1, pc.typedLoads);assertEquals(0, pc.pathLoads);assertEquals(0, phone.pathLoads)
        } finally { engine.unload() }
    }
    @Test fun everyPreparationReentersActualLoaderEvenWithSameOptionsAndPin() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc);val chosen = target()
        try {
            val pin = engine.preparePrivateAnalysis(chosen, options)
            engine.preparePrivateAnalysis(chosen, options, pin)
            engine.preparePrivateAnalysis(chosen, options.copy(maxOutputTokens = 512), pin)
            assertEquals(3, pc.typedLoads);assertEquals(0, pc.counts);assertEquals(0, pc.generations)
        } finally { engine.unload() }
    }
    @Test fun changedOriginalRevisionStopsBeforePcLoad() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc, current = { 8 })
        try {
            rejected { engine.preparePrivateAnalysis(local(), options) }
            assertEquals(0, pc.typedLoads)
        } finally { engine.unload() }
    }
    @Test fun legacyNullCaptureAndCloudAuthorityCannotAcquireLocalPcAnalysis() = runBlocking {
        for (engine in listOf(facade(Backend(), Backend(), captured = null), facade(Backend(), Backend(), cloud = true))) {
            try { rejected { engine.preparePrivateAnalysis(local(), options) } } finally { engine.unload() }
        }
    }
    @Test fun missingOwnerOrNonPcOnlyFacadeCannotCreatePcAnalysisIdentity() = runBlocking {
        for (engine in listOf(facade(Backend(), Backend(), owner = null), facade(Backend(), Backend(), pcOnly = false))) {
            try { rejected { engine.preparePrivateAnalysis(target(), options) } } finally { engine.unload() }
        }
    }
    @Test fun selectedTargetCannotSubstituteAnotherCapturedRevision() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc)
        try {
            rejected { engine.preparePrivateAnalysis(target(8), options) }
            assertEquals(0, pc.typedLoads)
        } finally { engine.unload() }
    }
    @Test fun changedPairAuthorityStopsBeforeActualLoad() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc, valid = { false })
        try {
            rejected { engine.preparePrivateAnalysis(target(), options) }
            assertEquals(0, pc.typedLoads)
        } finally { engine.unload() }
    }
    @Test fun mediaDeviceOnlyAndProjectorOptionsCannotBecomeOrdinaryPcAnalysis() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc)
        try {
            val prohibited = listOf(options.copy(audioEnabled = true), options.copy(visionEnabled = true),
                options.copy(imagePreparation = true), options.copy(deviceOnly = true), options.copy(projectorPath = "/synthetic/helper"))
            for (value in prohibited) rejected { engine.preparePrivateAnalysis(target(), value) }
            assertEquals(0, pc.typedLoads)
        } finally { engine.unload() }
    }
    @Test fun auxiliaryModelSelectionCannotBecomeOrdinaryPcAnalysis() = runBlocking {
        val pc = Backend();val engine = facade(Backend(), pc)
        try {
            for (id in listOf("asr-qwen-06b", "gemma-e2b-mmproj", "gemma-e2b-media"))
                rejected { engine.preparePrivateAnalysis(local(id), options) }
            assertEquals(0, pc.typedLoads)
        } finally { engine.unload() }
    }
    @Test fun unknownWrapperAndMissingTypedBackendFailWithoutPathLoadOrPrivateInput() = runBlocking {
        val unknown = object : InferenceEngine by Backend() {}
        rejected { unknown.preparePrivateAnalysis(target(), options) }
        val phone = Backend();val pc = Backend();val noTyped = object : InferenceEngine by pc {}
        val engine = facade(phone, noTyped)
        try {
            rejected { engine.preparePrivateAnalysis(target(), options) }
            assertEquals(0, pc.pathLoads);assertEquals(0, pc.counts);assertEquals(0, pc.generations)
        } finally { engine.unload() }
    }
    @Test fun transparentObservationWrapperForwardsUnderlyingIdentityWithoutRebinding() = runBlocking {
        val pc = Backend();val actual = facade(Backend(), pc);val chosen = target()
        val observed = object : InferenceEngine by actual, AnalysisModelPreparation {
            override suspend fun prepareAnalysisModel(target: InferenceModelTarget, options: InferenceOptions) =
                actual.prepareAnalysisModel(target, options)
        }
        try {
            val pin = observed.preparePrivateAnalysis(chosen, options)
            actual.preparePrivateAnalysis(chosen, options, pin)
            observed.preparePrivateAnalysis(chosen, options, pin)
            assertEquals(3, pc.typedLoads)
        } finally { observed.unload() }
    }
    @Test fun quotedWireContextCannotCreateAnalysisPin() = runBlocking {
        val pc = Backend().apply { rawContext = JsonPrimitive("8192") };val engine = facade(Backend(), pc)
        try {
            rejected { engine.preparePrivateAnalysis(target(), options) }
            assertEquals(1, pc.typedLoads);assertEquals(0, pc.counts);assertEquals(0, pc.generations)
        } finally { engine.unload() }
    }
    @Test fun cancelledPreparationCancelsSameBackendAndDoesNotSendPrivateInput() = runBlocking {
        val pc = Backend().apply { loadFailure = CancellationException("synthetic cancellation") }
        val engine = facade(Backend(), pc)
        try {
            try { engine.preparePrivateAnalysis(target(), options);fail("Cancellation accepted") }
            catch (_: CancellationException) {}
            assertTrue(pc.cancellations > 0);assertEquals(0, pc.counts);assertEquals(0, pc.generations)
        } finally { engine.unload() }
    }
}
