package com.user.localagent.inference

import com.user.localagent.compute.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

private suspend fun FlowCollector<GenerationEvent>.finish() {
    emit(GenerationEvent.Finished(GenerationSummary(UUID.randomUUID().toString(), GenerationStopReason.EOG,
        GenerationEvidence.HOST, false, 768, visibleChars = 1)))
}

class AutomaticComputeConcurrencyTest {
    private data class Query(val text: String, val system: String, val history: List<InferenceTurn>)
    private class Fake(private val label: String, private val loadedDelta: (Int) -> Unit = {}) : InferenceEngine {
        override val state = MutableStateFlow("$label idle")
        override val lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration = MutableStateFlow<GenerationSummary?>(null)
        override val residentModelPath: String? get() = path
        var progress:NativeGenerationProgress?=null
        override val nativeProgressSnapshot:NativeGenerationProgress? get()=if(path!=null)progress else null
        var path: String? = null
        var loadedOptions: InferenceOptions? = null
        var loads = 0
        var cancels = 0
        var unloadFails = false
        val queries = mutableListOf<Query>()
        var produce: suspend FlowCollector<GenerationEvent>.() -> Unit = { emit(GenerationEvent.Delta(label)); finish() }
        override suspend fun load(path: String, options: InferenceOptions) {
            if (this.path == null) loadedDelta(1)
            this.path = path; loadedOptions = options; loads++; state.value = "$label loaded"
        }
        override fun generate(prompt: String): Flow<String> = generateChatEvents(prompt, "", emptyList()).textDeltas()
        override fun generateChatEvents(userText: String, systemInstruction: String, history: List<InferenceTurn>): Flow<GenerationEvent> = flow {
            queries += Query(userText, systemInstruction, history.toList()); produce()
        }
        override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>) = 40
        override suspend fun unload() {
            if (unloadFails) throw IOException("synthetic native cleanup unavailable")
            if (path != null) loadedDelta(-1)
            path = null; loadedOptions = null; state.value = "$label unloaded"
        }
        override fun cancel() { cancels++ }
    }
    private class Fixture {
        val phone = OwnedComputeGate(); val pc = OwnedComputeGate()
        val broker = ComputeResourceBroker(phone, pc)
        val localModels = AtomicInteger(); val peakLocalModels = AtomicInteger()
        fun local(label: String) = Fake(label) { change ->
            val current = localModels.addAndGet(change)
            peakLocalModels.updateAndGet { maxOf(it, current) }
            check(current in 0..1) { "Two local models overlap" }
        }
        fun engine(owner: String, local: Fake, remote: Fake, eligible: Boolean = true) =
            AutomaticComputeEngine(local, remote, { _, options -> eligible && !options.deviceOnly }, { true }, {}, owner, broker)
    }
    private val path = "/synthetic/gemma-e2b-aaaaaaaaaaaa.gguf"
    private fun pcOnly(fixture: Fixture, local: Fake, remote: Fake, reachable: Boolean = true, eligible: Boolean = true) =
        AutomaticComputeEngine(local, remote, { _, _ -> eligible }, { reachable }, {}, UUID.randomUUID().toString(), fixture.broker, pcOnly = true)
    @Test fun pcOnlyRefusesInsteadOfLoadingThePhoneModel()=runBlocking {
        for ((reachable, eligible) in listOf(false to true, true to false)) {
            val fixture=Fixture();val local=fixture.local("phone");val remote=Fake("pc")
            val engine=pcOnly(fixture,local,remote,reachable,eligible)
            try {engine.load(path,options);fail("Phone fallback must not run")}catch(expected:PcRequired){/* stopped */}
            assertEquals(0,local.loads);assertEquals(0,remote.loads)
        }
    }
    @Test fun pcOnlyStopsWhenTheConnectionDropsMidGeneration()=runBlocking {
        val fixture=Fixture();val local=fixture.local("phone");val remote=Fake("pc")
        remote.produce={throw IOException("synthetic disconnect")}
        val engine=pcOnly(fixture,local,remote)
        try {
            engine.load(path,options)
            try {engine.generateChatEvents("q","",emptyList()).collect();fail("Phone fallback must not run")}catch(expected:PcRequired){/* stopped */}
            assertEquals(0,local.loads)
            try {engine.withPhoneResource({}){fail("Phone work must not start")};fail("Phone work must be refused")}catch(expected:PcRequired){/* stopped */}
        } finally {engine.unload()}
    }
    @Test fun numericProgressComesOnlyFromThisRequestsActiveBackend()=runBlocking {
        val fixture=Fixture();val local=fixture.local("phone");val remote=Fake("pc")
        val measured=NativeGenerationProgress(UUID.randomUUID().toString(),NativeGenerationPhase.PREFILL,64,256,0,0,0,1000)
        local.progress=measured;remote.progress=measured.copy(requestId=UUID.randomUUID().toString(),processedPromptTokens=128)
        val engine=fixture.engine(UUID.randomUUID().toString(),local,remote,eligible=false)
        assertNull(engine.nativeProgressSnapshot)
        try {engine.load(path,options);assertEquals(measured,engine.nativeProgressSnapshot)}
        finally {engine.unload()}
        assertNull(engine.nativeProgressSnapshot)
    }
    private val options = InferenceOptions(contextSize = 4096, maxOutputTokens = 768)
    private suspend fun waitForPhone(engine: AutomaticComputeEngine) = withTimeout(2000) {
        engine.waitingForResource.first { it == ComputeResource.PHONE }
    }

    @Test fun aPhoneRequestCompletesWhileTheFirstPcComputationIsStillPending() = runBlocking {
        val f = Fixture(); val pcStarted = CompletableDeferred<Unit>(); val pcRelease = CompletableDeferred<Unit>()
        val localA = f.local("phone-a"); val localB = f.local("phone-b")
        val remoteA = Fake("pc-a").apply { produce = { pcStarted.complete(Unit); pcRelease.await(); emit(GenerationEvent.Delta("pc-a")); finish() } }
        val remoteB = Fake("pc-b")
        val a = f.engine("run-a", localA, remoteA); val b = f.engine("run-b", localB, remoteB)
        try {
            a.load(path, options)
            val remoteResult = async { a.generate("public request A").toList().joinToString("") }
            withTimeout(2000) { pcStarted.await() }
            b.load(path, options)
            assertEquals("phone-b", b.generate("public request B").toList().joinToString(""))
            assertFalse(remoteResult.isCompleted)
            assertEquals(ComputeResource.PC, a.computeLocation.value)
            assertEquals(ComputeResource.PHONE, b.computeLocation.value)
            assertEquals(0, remoteB.loads); assertEquals(1, f.peakLocalModels.get())
            pcRelease.complete(Unit); assertEquals("pc-a", withTimeout(2000) { remoteResult.await() })
            a.unload()
            b.load(path, options.copy(maxOutputTokens = 1536))
            assertEquals(ComputeResource.PHONE, b.computeLocation.value) // Overflow is pinned for this request.
            assertEquals(0, remoteB.loads)
        } finally { pcRelease.complete(Unit); a.unload(); b.unload() }
        assertFalse(f.phone.get()); assertFalse(f.pc.get())
    }

    @Test fun aDroppedPcWaitsForTheExistingPhoneOwnerAndDoesNotPublishTwoReplies() = runBlocking {
        val f = Fixture(); val localA = f.local("phone-fallback"); val localB = f.local("other-phone")
        val remote = Fake("pc").apply { produce = { emit(GenerationEvent.Delta("discarded partial")); throw IOException("synthetic disconnect") } }
        val a = f.engine("run-a", localA, remote); val b = f.engine("run-b", localB, Fake("unused"), eligible = false)
        val history = listOf(InferenceTurn("user", "public history"))
        try {
            a.load(path, options); b.load(path, options.copy(deviceOnly = true))
            val output = mutableListOf<String>()
            val fallback = async { a.generateChat("same original input", "same original system", history).collect { output += it } }
            waitForPhone(a)
            assertEquals(0, localA.loads); assertTrue(output.isEmpty()); assertEquals("run-b", f.phone.owner.value)
            assertFalse(f.pc.get())
            b.unload(); withTimeout(2000) { fallback.await() }
            assertEquals(listOf("phone-fallback"), output)
            assertEquals(remote.queries.single(), localA.queries.single())
            assertEquals(path, localA.path); assertEquals(options, localA.loadedOptions)
            assertEquals(1, f.peakLocalModels.get())
        } finally { a.unload(); b.unload() }
    }

    @Test fun cancellingAFallbackWaitDoesNotCancelOrUnloadTheOtherPhoneRun() = runBlocking {
        val f = Fixture(); val localA = f.local("fallback"); val localB = f.local("active phone")
        val remote = Fake("pc").apply { produce = { throw IOException("synthetic disconnect") } }
        val a = f.engine("run-a", localA, remote); val b = f.engine("run-b", localB, Fake("unused"), false)
        try {
            a.load(path, options); b.load(path, options)
            val wait = launch { a.generate("public").collect() }
            waitForPhone(a); wait.cancelAndJoin(); a.unload()
            assertEquals(0, localA.loads); assertEquals("run-b", f.phone.owner.value)
            assertEquals(path, localB.path); assertEquals(0, localB.cancels)
            assertEquals("active phone", b.generate("still usable").toList().joinToString(""))
        } finally { a.unload(); b.unload() }
    }

    @Test fun aFailedNativeUnloadKeepsItsPhoneSlotUntilAnActualCleanupSucceeds() = runBlocking {
        val f = Fixture(); val localA = f.local("a"); val localB = f.local("b")
        val a = f.engine("run-a", localA, Fake("unused-a"), false)
        val b = f.engine("run-b", localB, Fake("unused-b"), false)
        a.load(path, options); localA.unloadFails = true
        try {
            try { a.unload(); fail("Unconfirmed cleanup must fail") } catch (_: IOException) {}
            assertEquals("run-a", f.phone.owner.value)
            val waiting = launch { b.load(path, options) }
            waitForPhone(b); assertEquals(0, localB.loads); waiting.cancelAndJoin()
            assertEquals(1, f.localModels.get())
            localA.unloadFails = false; a.unload(); b.load(path, options)
            assertEquals(1, f.peakLocalModels.get())
        } finally { localA.unloadFails = false; a.unload(); b.unload() }
    }

    @Test fun anExternalPhoneEngineIsUnloadedBeforeItsLeaseBecomesAvailable() = runBlocking {
        val f = Fixture(); val a = f.engine("run-a", f.local("a"), Fake("pc-a"))
        val localB = f.local("b"); val b = f.engine("run-b", localB, Fake("unused"), false)
        val externalStarted = CompletableDeferred<Unit>(); val releaseExternal = CompletableDeferred<Unit>()
        var externalLoaded = false
        val external = async {
            a.withPhoneResource(cleanup = { externalLoaded = false; f.localModels.decrementAndGet(); Unit }) {
                externalLoaded = true; check(f.localModels.incrementAndGet() == 1)
                externalStarted.complete(Unit); releaseExternal.await()
            }
        }
        try {
            externalStarted.await()
            val second = async { b.load(path, options) }
            waitForPhone(b); assertEquals(0, localB.loads); assertTrue(externalLoaded)
            releaseExternal.complete(Unit); external.await(); withTimeout(2000) { second.await() }
            assertFalse(externalLoaded); assertEquals(1, f.localModels.get())
        } finally { releaseExternal.complete(Unit); external.cancelAndJoin(); a.unload(); b.unload() }
    }

    @Test fun cleanupFailureCannotBeBypassedByLoadingAnotherModelInTheSameFacade() = runBlocking {
        val f = Fixture(); val local = f.local("local"); val engine = f.engine("run-a", local, Fake("pc"), false)
        var canClean = false
        val cleanup: suspend () -> Unit = { if (!canClean) throw IOException("synthetic external cleanup unconfirmed") }
        try {
            try { engine.withPhoneResource(cleanup) { "public external output" }; fail("Cleanup failure must surface") } catch (_: IOException) {}
            try { engine.load(path, options); fail("Unconfirmed external model still owns memory") } catch (_: IOException) {}
            assertEquals(0, local.loads); assertEquals("run-a", f.phone.owner.value)
            canClean = true; engine.unload(); engine.load(path, options)
            assertEquals(1, local.loads)
        } finally { canClean = true; engine.unload() }
    }

    @Test fun aFailedPhoneCleanupCannotBeMaskedAsAPcFailureAndReloadTheUncleanedModel() = runBlocking {
        val f = Fixture(); val local = f.local("local"); val remote = Fake("remote")
        var usePc = false
        val engine = AutomaticComputeEngine(local, remote, { _, _ -> usePc }, { true }, {}, "run-a", f.broker)
        try {
            engine.load(path, options); usePc = true; local.unloadFails = true
            try { engine.load(path, options); fail("Failed cleanup must keep its original resource") } catch (_: IOException) {}
            assertEquals(1, local.loads); assertEquals(0, remote.loads)
            assertEquals("run-a", f.phone.owner.value); assertFalse(f.pc.get())
        } finally { local.unloadFails = false; engine.unload() }
    }

    @Test fun idlePhoneCleanupNeverUnloadsOrCancelsAnOwnedPcSession() = runBlocking {
        val f = Fixture(); val remote = Fake("remote"); val engine = f.engine("run-a", f.local("unused"), remote)
        try {
            engine.load(path, options)
            engine.unloadPhoneResidency()
            assertEquals(path, remote.path); assertEquals(0, remote.cancels)
            assertEquals("run-a", f.pc.owner.value)
            assertEquals("remote", engine.generate("public input").toList().joinToString(""))
        } finally { engine.unload() }
    }

    @Test fun aPcPermissionChangeWhileWaitingPreventsTheExternalBlockFromSendingAnything() = runBlocking {
        val f = Fixture(); val occupying = f.engine("run-b", f.local("unused"), Fake("pc-b"))
        var allowed = true; var transmitted = false; var cleanups = 0
        val next = AutomaticComputeEngine(f.local("unused-a"), Fake("pc-a"), { _, _ -> true }, { true }, {},
            "run-a", f.broker, externalPcAuthority = { allowed })
        try {
            occupying.load(path, options)
            val blocked = async {
                runCatching { next.withPcResource(cleanup = { cleanups++ }) { transmitted = true } }.exceptionOrNull()
            }
            withTimeout(2000) { next.waitingForResource.first { it == ComputeResource.PC } }
            allowed = false; occupying.unload()
            assertTrue(withTimeout(2000) { blocked.await() } is PcUnavailable)
            assertFalse(transmitted); assertEquals(1, cleanups); assertFalse(f.pc.get())
        } finally { occupying.unload(); next.unload() }
    }
    @Test fun anOwnerlessCallerCannotReserveThePhoneWhileWaitingForRemoteCompute() = runBlocking {
        val f = Fixture(); val remote = Fake("remote"); val local = f.local("local")
        assertTrue(f.phone.compareAndSet(false,true))
        val engine=AutomaticComputeEngine(local,remote,{_,_->true},{true},{},null,f.broker)
        try{
            engine.load(path,options)
            assertEquals("phone",engine.executionLocation);assertEquals(0,remote.loads)
            assertFalse(f.pc.get());assertEquals("local",engine.generate("public").toList().joinToString(""))
        }finally{engine.unload();f.phone.set(false)}
    }
}
