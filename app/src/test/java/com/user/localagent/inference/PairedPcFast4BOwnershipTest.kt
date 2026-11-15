package com.user.localagent.inference

import com.user.localagent.compute.*
import com.user.localagent.models.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Actual broker/automatic engine with synthetic backends. No Android, native process, API or model. */
class PairedPcFast4BOwnershipTest {
    private class Backend(private val occupancy: (Int) -> Unit = {}) : InferenceEngine, PairedPcTextLoader {
        override val state = MutableStateFlow("synthetic")
        override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration = MutableStateFlow<GenerationSummary?>(null)
        override val residentModelPath: String? = null
        override val supportsStructuredOutput = true
        var pathLoads = 0
        var typedLoads = 0
        var generations = 0
        var cancels = 0
        var loaded: PairedPcText? = null
        var returnLegacyReceipt = false
        var holdGeneration = false
        private var cancelled = false
        val started = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        override suspend fun load(path: String, options: InferenceOptions) {
            pathLoads++
            error("A paired profile must not fabricate a phone path")
        }
        override suspend fun loadPairedPcText(target: PairedPcText, options: InferenceOptions): PairedPcLoadReceipt {
            typedLoads++
            if (loaded == null) occupancy(1)
            loaded = target
            val receiptTarget = if (returnLegacyReceipt)
                checkNotNull(PairedPcText.forRequest("paired-pc-text-v1", target.authorizationRevision, false, target.authorizationRevision, true))
            else target
            val (alias, sha) = when (receiptTarget.id) {
                "paired-pc-text-v1" -> "pc-text" to "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
                "paired-pc-text-fast4b-v1" -> "pc-text-4b" to "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
                else -> error("Unknown synthetic profile")
            }
            return PairedPcLoadReceipt.fromAuthenticatedLoad(receiptTarget, alias, sha, options.contextSize, true, options.contextSize)
        }
        override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>) = 32
        override fun generate(prompt: String): Flow<String> = flowOf("synthetic")
        override fun generateChatEvents(userText: String, systemInstruction: String, history: List<InferenceTurn>): Flow<GenerationEvent> = flow {
            generations++
            emit(GenerationEvent.Delta("synthetic-draft"))
            started.complete(Unit)
            if (holdGeneration) release.await()
            if (cancelled) throw CancellationException("Synthetic owner cancellation")
            val end = GenerationSummary("synthetic-profile-event", GenerationStopReason.EOG, GenerationEvidence.HOST, false, 768)
            lastGeneration.value = end
            emit(GenerationEvent.Finished(end))
        }
        override fun generateStructuredEvents(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>) =
            generateChatEvents(userText, systemInstruction, history)
        override suspend fun unload() {
            if (loaded != null) occupancy(-1)
            loaded = null
        }
        override fun cancel() { cancels++; cancelled = true; release.complete(Unit) }
    }

    private class Fixture {
        val broker = ComputeResourceBroker(OwnedComputeGate(), OwnedComputeGate())
        val resident = AtomicInteger()
        val peak = AtomicInteger()
        fun pc() = Backend { delta ->
            val count = resident.addAndGet(delta)
            peak.updateAndGet { maxOf(it, count) }
            check(count in 0..1) { "Two synthetic PC backends overlapped" }
        }
        fun engine(owner: String, phone: Backend, pc: Backend, authority: () -> Boolean = { true }) =
            AutomaticComputeEngine(phone, pc, { _, _ -> true }, { true }, {}, owner, broker,
                externalPcAuthority = authority, pcOnly = true)
    }
    private val options = InferenceOptions(contextSize = 8192, maxOutputTokens = 768)
    private fun target(id: String = "paired-pc-text-fast4b-v1") =
        checkNotNull(PairedPcText.forRequest(id, 17, false, 17, true))

    @Test fun runningFastRequestCancelsItsOwnBackendWithoutPhoneFallbackOrInventedFinish() = runBlocking {
        val f = Fixture(); val phone = Backend(); val pc = f.pc().apply { holdGeneration = true }
        val engine = f.engine("qa-fast-owner", phone, pc)
        val events = mutableListOf<GenerationEvent>()
        var running: Deferred<Unit>? = null
        try {
            engine.loadPairedPcText(target(), options)
            assertEquals("qa-fast-owner", f.broker.owner(ComputeResource.PC).value)
            val work = async { engine.generateStructuredEvents("q", "s", "{}", emptyList()).collect { events += it } }
            running = work
            withTimeout(2000) { pc.started.await() }
            engine.cancel()
            withTimeout(2000) { work.join() }
            assertTrue(work.isCancelled)
            val failure = runCatching { work.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, pc.cancels)
            assertEquals(0, phone.cancels)
            assertEquals(0, phone.pathLoads)
            assertEquals(listOf("synthetic-draft"), events.filterIsInstance<GenerationEvent.Delta>().map { it.text })
            assertTrue(events.none { it is GenerationEvent.Finished })
        } finally { running?.cancelAndJoin(); engine.unload() }
        assertNull(f.broker.owner(ComputeResource.PC).value)
        assertEquals(0, f.resident.get())
    }

    @Test fun aWaitingFastRequestsCancellationCannotCancelTheCurrent9BOwner() = runBlocking {
        val f = Fixture(); val phoneA = Backend(); val phoneB = Backend(); val pcA = f.pc(); val pcB = f.pc()
        val a = f.engine("qa-9b-owner", phoneA, pcA); val b = f.engine("qa-fast-owner", phoneB, pcB)
        var waiting: Deferred<PairedPcLoadReceipt>? = null
        try {
            a.loadPairedPcText(target("paired-pc-text-v1"), options)
            val pending = async { b.loadPairedPcText(target(), options) }
            waiting = pending
            withTimeout(2000) { b.waitingForResource.first { it == ComputeResource.PC } }
            assertEquals(0, pcB.typedLoads)
            b.cancel()
            pending.cancelAndJoin()
            assertEquals(0, pcA.cancels)
            assertEquals(0, pcB.cancels)
            assertEquals("qa-9b-owner", f.broker.owner(ComputeResource.PC).value)
            assertEquals(1, f.resident.get())
            assertEquals(0, phoneA.pathLoads + phoneB.pathLoads)
        } finally { waiting?.cancelAndJoin(); a.unload(); b.unload() }
        assertEquals(0, f.resident.get())
    }

    @Test fun fastLoadsOnlyAfterThePreviousOwnersCleanupReleasesTheSinglePcSlot() = runBlocking {
        val f = Fixture(); val pcA = f.pc(); val pcB = f.pc()
        val a = f.engine("qa-9b-owner", Backend(), pcA); val b = f.engine("qa-fast-owner", Backend(), pcB)
        var waiting: Deferred<PairedPcLoadReceipt>? = null
        try {
            a.loadPairedPcText(target("paired-pc-text-v1"), options)
            val pending = async { b.loadPairedPcText(target(), options) }
            waiting = pending
            withTimeout(2000) { b.waitingForResource.first { it == ComputeResource.PC } }
            assertEquals(0, pcB.typedLoads)
            a.unload()
            val receipt = withTimeout(2000) { pending.await() }
            assertTrue(receipt.matches(target(), 8192))
            assertEquals("qa-fast-owner", f.broker.owner(ComputeResource.PC).value)
            assertEquals(1, f.peak.get())
            val oldCancels = pcA.cancels
            b.cancel()
            assertEquals(oldCancels, pcA.cancels)
            assertEquals(1, pcB.cancels)
        } finally { waiting?.cancelAndJoin(); a.unload(); b.unload() }
        assertEquals(0, f.resident.get())
    }

    @Test fun changedAuthorityBlocksFastPreparationBeforeItCanTakeThePcSlot() = runBlocking {
        val f = Fixture(); val phone = Backend(); val pc = f.pc()
        val engine = f.engine("qa-fast-owner", phone, pc) { false }
        try {
            try { engine.loadPairedPcText(target(), options); fail("Changed authority must stop preparation") }
            catch (error: InferencePrerequisiteMissing) { assertEquals(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED, error.reason) }
            assertEquals(0, pc.typedLoads)
            assertEquals(0, phone.pathLoads)
            assertNull(f.broker.owner(ComputeResource.PC).value)
        } finally { engine.unload() }
    }

    @Test fun aLegacyTypedReceiptCannotAuthorizeFastGenerationInTheAutomaticEngine() = runBlocking {
        val f = Fixture(); val phone = Backend(); val pc = f.pc().apply { returnLegacyReceipt = true }
        val engine = f.engine("qa-fast-owner", phone, pc)
        try {
            assertTrue(runCatching { engine.loadPairedPcText(target(), options) }.isFailure)
            assertEquals(1, pc.typedLoads)
            assertEquals(0, pc.generations)
            assertEquals(0, phone.pathLoads)
        } finally { engine.unload() }
        assertEquals(0, f.resident.get())
    }
}
