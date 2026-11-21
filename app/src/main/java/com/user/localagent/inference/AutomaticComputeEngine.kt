package com.user.localagent.inference

import android.content.Context
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.models.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stops work instead of computing on the phone; the phone never acts as a fallback in PC-only operation. */
class PcRequired(message: String = "PC에 연결되어 있지 않아 실행하지 않았습니다. PC를 켜고 연결한 뒤 다시 요청해주세요.",cause:Throwable?=null) : IllegalStateException(message){
    init {if(cause!=null)initCause(cause)}
}

/** A request owns its backend and state. Only its local native work reserves the phone slot. */
class AutomaticComputeEngine internal constructor(
    private val local: InferenceEngine,
    private val pc: InferenceEngine,
    private val eligibleForPc: (String, InferenceOptions) -> Boolean,
    private val pcPreflight: suspend () -> Boolean,
    private val report: (String) -> Unit,
    private val ownerRunId: String?,
    broker: ComputeResourceBroker,
    private val beforePhoneLoad: suspend () -> Unit = {},
    private val externalPcAuthority: () -> Boolean = { true },
    private val pcOnly: Boolean = false,
    private val analysisPcTarget: (() -> PairedPcText?)? = null,
) : ComputeExecutionEngine, PairedPcTextLoader, AnalysisModelPreparation {
    constructor(context: Context, ownerRunId: String? = null, beforePhoneLoad: suspend () -> Unit = {},
        capturedPcRevision:Long?=null,allowCloudText:Boolean=false,capturedPcTextDefault:String?=null) :
        this(components(context.applicationContext, ownerRunId,capturedPcRevision,allowCloudText,capturedPcTextDefault), ownerRunId, beforePhoneLoad)
    private constructor(parts: Components, ownerRunId: String?, beforePhoneLoad: suspend () -> Unit) :
        this(parts.local, parts.pc, parts.eligible, parts.preflight, parts.report, ownerRunId, RuntimeGate.compute, beforePhoneLoad, parts.externalPcAuthority, pcOnly = true, analysisPcTarget = parts.analysisPcTarget)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resource = RequestComputeLease(ownerRunId, broker)
    override val computeLocation: StateFlow<ComputeResource?> = resource.location
    override val waitingForResource: StateFlow<ComputeResource?> = resource.waiting
    @Volatile private var active: InferenceEngine? = null
    private var observer: Job? = null
    private var requestedPath: String? = null
    private var requestedOptions: InferenceOptions? = null
    private var retryAfter = 0L
    private var phoneOverflowPinned = false
    private var pendingExternalCleanup: (suspend () -> Unit)? = null
    private var nativeCleanupUnconfirmed = false
    private var lastPcFailure: Exception? = null
    private val status = MutableStateFlow("모델 없음")
    private val metrics = MutableStateFlow<InferenceMetrics?>(null)
    private val generation = MutableStateFlow<GenerationSummary?>(null)
    private val unavailablePcCleanup = MutableStateFlow<PcCleanupEvidence?>(null)
    val lastPcCleanup: StateFlow<PcCleanupEvidence?> get() = (pc as? PcInferenceEngine)?.lastCleanup ?: unavailablePcCleanup.asStateFlow()
    internal fun pcCancelOutcome(requestId:String):PcCancelOutcome? =
        (pc as? PcInferenceEngine)?.cancelOutcome(requestId)?.takeIf{it.key.owner==ownerRunId}
    override val state: StateFlow<String> = status.asStateFlow()
    override val lastMetrics: StateFlow<InferenceMetrics?> = metrics.asStateFlow()
    override val lastGeneration: StateFlow<GenerationSummary?> = generation.asStateFlow()
    override val residentModelPath: String? get() = active?.residentModelPath
    override val nativeProgressSnapshot:NativeGenerationProgress? get()=active?.nativeProgressSnapshot
    override val lastReasoning:String? get()=active?.lastReasoning
    override val supportsStructuredOutput: Boolean get() = active?.supportsStructuredOutput == true
    override val executionLocation: String get() = if (computeLocation.value == ComputeResource.PC) "pc" else "phone"

    private suspend fun dropActive() {
        // A failed native unload retains its lease and backend; another local model must not load.
        check(active !== pc || resource.location.value == ComputeResource.PC) { "PC 모델을 정리할 원래 계산 사용권이 없습니다." }
        try { active?.unload() } catch (error: Throwable) { nativeCleanupUnconfirmed = true; throw error }
        nativeCleanupUnconfirmed = false
        observer?.cancelAndJoin(); observer = null; active = null
    }
    private suspend fun bind(next: InferenceEngine, destination: ComputeResource, wait: Boolean = true): Boolean {
        val previous = resource.location.value
        if (previous != destination) status.value = if (destination == ComputeResource.PHONE)
            "휴대전화 작업 차례를 기다리는 중" else "PC 작업 차례를 확인하는 중"
        if (!resource.moveTo(destination, wait = wait, cleanupPrevious = ::dropActive)) return false
        if (active === next) return true
        if (destination == ComputeResource.PHONE && previous != destination) releaseIdlePhoneModel()
        active = next
        observer = scope.launch {
            launch { next.state.collect { status.value = it } }
            launch { next.lastMetrics.collect { metrics.value = it } }
            launch { next.lastGeneration.collect { generation.value = it } }
        }
        return true
    }

    /**
     * PC-only requests wait briefly for the PC lease, then report PcBusy. Long PC work such as an image
     * generation holds the lease for its whole run; waiting for it made chat fail as a load timeout.
     */
    private suspend fun bindPcOrBusy(maxWaitMs: Long = PC_LEASE_WAIT_MS) {
        // The ordinary waiting bind keeps waitingForResource visible; only its duration is bounded.
        withTimeoutOrNull(maxWaitMs) { check(bind(pc, ComputeResource.PC)) } ?: throw PcBusy()
    }

    override suspend fun load(path: String, options: InferenceOptions) = mutex.withLock {
        recoverPendingCleanup()
        requestedPath = path; requestedOptions = options
        if (pcOnly) {
            if (!eligibleForPc(path, options)) throw PcRequired("이 작업은 아직 PC에서 처리할 수 없어 실행하지 않았습니다. 휴대폰에서는 계산하지 않습니다.")
            val reachable = try { pcPreflight() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
            if (!reachable) throw PcRequired()
            currentCoroutineContext().ensureActive()
            bindPcOrBusy()
            pc.load(path, options); snapshot(); return@withLock
        }
        if (ownerRunId != null && !phoneOverflowPinned && eligibleForPc(path, options) && System.currentTimeMillis() >= retryAfter) {
            try {
                if (!pcPreflight()) throw PcUnavailable()
                currentCoroutineContext().ensureActive()
                // A busy PC leaves the second request runnable on the phone, not queued behind it.
                if (bind(pc, ComputeResource.PC, wait = ownerRunId == null)) {
                    pc.load(path, options); snapshot(); return@withLock
                }
                phoneOverflowPinned = ownerRunId != null
                report("PC 계산 중 · 이번 요청은 휴대전화에서 병렬 처리합니다.")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (nativeCleanupUnconfirmed || pendingExternalCleanup != null) throw error
                retryAfter = System.currentTimeMillis() + 30_000
                if (ownerRunId != null) phoneOverflowPinned = true
                report("PC 응답 없음 · 휴대전화 작업 차례를 확인합니다.")
            }
        }
        check(bind(local, ComputeResource.PHONE))
        local.load(path, options); snapshot()
    }
    override suspend fun loadPairedPcText(target:com.user.localagent.models.PairedPcText,options:InferenceOptions):com.user.localagent.models.PairedPcLoadReceipt = mutex.withLock {
        recoverPendingCleanup()
        if(!pcOnly||ownerRunId==null||!externalPcAuthority())
            throw com.user.localagent.models.InferencePrerequisiteMissing(com.user.localagent.models.InferencePrerequisiteReason.PC_AUTHORITY_CHANGED)
        val loader=pc as? PairedPcTextLoader
            ?:throw com.user.localagent.models.InferencePrerequisiteMissing(com.user.localagent.models.InferencePrerequisiteReason.PC_LOADER_UNAVAILABLE)
        requestedPath=null;requestedOptions=options
        // The concrete typed loader performs a fresh authenticated availability/authority check below.
        // Only an already-active PC in this same request-owned lease avoids the duplicate outer probe.
        val sameBoundProductionPc=pc is PcInferenceEngine&&active===pc&&resource.location.value==ComputeResource.PC
        if(!sameBoundProductionPc&&!pcPreflight())throw PcRequired()
        currentCoroutineContext().ensureActive()
        bindPcOrBusy()
        val receipt=loader.loadPairedPcText(target,options)
        check(externalPcAuthority()&&receipt.matches(target,options.contextSize)){"원래 PC의 모델 준비 결과를 확인하지 못했습니다."}
        snapshot()
        receipt
    }

    /** Select before any source is sent. A stored analysis always keeps the originally bound model hash. */
    internal suspend fun memoryAnalysisTarget(selected:InferenceModelTarget,previousSha256:String?):InferenceModelTarget {
        if(selected !is PairedPcText||!pcOnly||!externalPcAuthority())return selected
        val lightweight=selected.forMemoryAnalysis()
        if(previousSha256!=null)return if(previousSha256==lightweight.profile.modelSha256)lightweight else selected
        val supports=try {(pc as? PcInferenceEngine)?.advertisesMemoryAnalysisTarget(lightweight)==true}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){false}
        return if(supports)lightweight else selected
    }

    /** Uses the existing typed loader's mutex/lease. Do not nest that load inside this mutex. */
    override suspend fun prepareAnalysisModel(target: InferenceModelTarget, options: InferenceOptions): PreparedAnalysisModel {
        requirePrivateAnalysisText(target, options)
        require(!options.deviceOnly) { "휴대전화 전용 작업을 PC 분석으로 바꾸지 않습니다." }
        val owner = ownerRunId?.takeIf { it.isNotBlank() }
            ?: throw InferencePrerequisiteMissing(InferencePrerequisiteReason.ORIGINAL_PC_AUTHORITY_MISSING)
        if (!pcOnly) throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_ANALYSIS_PROVENANCE_UNAVAILABLE)
        val capturedTarget = analysisPcTarget?.invoke()
            ?: throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_ANALYSIS_PROVENANCE_UNAVAILABLE)
        val actualTarget = when (target) {
            is PairedPcText -> target.also {
                check(it.authorizationRevision == capturedTarget.authorizationRevision) {
                    "분석 모델 선택과 원래 PC 사용 권한이 다릅니다."
                }
            }
            is LocalWeight -> capturedTarget
        }
        // LocalWeight is the owner's selection label. It is never the PC model's actual hash.
        val receipt = loadPairedPcText(actualTarget, options)
        currentCoroutineContext().ensureActive()
        return PreparedAnalysisModel.pairedPc(this, target, actualTarget, options, receipt, owner)
    }

    private fun snapshot() {
        status.value = active?.state?.value ?: "모델 없음"
        metrics.value = active?.lastMetrics?.value; generation.value = active?.lastGeneration?.value
    }
    private suspend fun releaseIdlePhoneModel() {
        pendingExternalCleanup = beforePhoneLoad
        beforePhoneLoad()
        pendingExternalCleanup = null
    }
    private suspend fun recoverPendingCleanup() {
        pendingExternalCleanup?.invoke(); pendingExternalCleanup = null
        if (nativeCleanupUnconfirmed) dropActive()
    }
    private suspend fun fallback() {
        if (pcOnly) throw PcRequired(if (lastPcFailure is PcBusy) lastPcFailure!!.message!! else"PC 계산을 완료하지 못했습니다. 휴대폰으로 넘기지 않고 멈췄습니다.",lastPcFailure)
        retryAfter = System.currentTimeMillis() + 30_000
        if (ownerRunId != null) phoneOverflowPinned = true
        report("PC 연결이 끊겼습니다. 휴대전화의 기존 작업 뒤에 이어 계산합니다.")
        check(bind(local, ComputeResource.PHONE))
        local.load(checkNotNull(requestedPath), checkNotNull(requestedOptions))
    }
    private fun events(block: (InferenceEngine) -> Flow<GenerationEvent>): Flow<GenerationEvent> = flow {
        mutex.withLock {
            metrics.value = null; generation.value = null
            try {
                if (active === pc && pcOnly) {
                    // This route already refuses phone fallback. Deliver provisional text now;
                    // only the original verified terminal event can establish completion.
                    var characters = 0
                    emitAll(block(pc).onEach { event ->
                        if (event is GenerationEvent.Delta) {
                            characters += event.text.length
                            check(characters <= 4 * 1024 * 1024) { "PC 응답의 저장 예산을 초과했습니다." }
                        }
                    }.catch { error ->
                        if (error is CancellationException || error !is Exception) throw error
                        lastPcFailure = error
                        fallback() // pcOnly always stops; never starts a second reply.
                    })
                } else if (active === pc) {
                    val buffered = mutableListOf<GenerationEvent>()
                    var bufferedCharacters = 0
                    try {
                        block(pc).collect { event ->
                            if (event is GenerationEvent.Delta) {
                                bufferedCharacters += event.text.length
                                check(bufferedCharacters <= 4 * 1024 * 1024) { "PC 응답의 저장 예산을 초과했습니다." }
                            }
                            buffered += event
                        }
                        currentCoroutineContext().ensureActive()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { lastPcFailure = error; buffered.clear(); fallback() }
                    if (active === pc) buffered.forEach { emit(it) } else emitAll(block(local))
                } else emitAll(block(checkNotNull(active)))
            } finally { snapshot() }
        }
    }
    override fun generate(prompt: String): Flow<String> = generateChat(prompt, "", emptyList())
    override fun generateChat(userText: String, systemInstruction: String, history: List<InferenceTurn>): Flow<String> =
        generateChatEvents(userText, systemInstruction, history).textDeltas()
    override fun generateChatEvents(userText: String, systemInstruction: String, history: List<InferenceTurn>): Flow<GenerationEvent> =
        events { it.generateChatEvents(userText, systemInstruction, history) }
    override fun generateStructured(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>): Flow<String> =
        generateStructuredEvents(userText, systemInstruction, schema, history).textDeltas()
    override fun generateStructuredEvents(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>): Flow<GenerationEvent> =
        events { it.generateStructuredEvents(userText, systemInstruction, schema, history) }
    override suspend fun countChatTokens(userText: String, systemInstruction: String, history: List<InferenceTurn>): Int? = mutex.withLock {
        try { checkNotNull(active).countChatTokens(userText, systemInstruction, history) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (active !== pc) throw error; lastPcFailure = error; fallback(); local.countChatTokens(userText, systemInstruction, history) }
    }
    override suspend fun countStructuredChatTokens(userText: String, systemInstruction: String, schema: String, history: List<InferenceTurn>): Int? = mutex.withLock {
        try { checkNotNull(active).countStructuredChatTokens(userText, systemInstruction, schema, history) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (active !== pc) throw error; lastPcFailure = error; fallback(); local.countStructuredChatTokens(userText, systemInstruction, schema, history) }
    }
    override fun transcribeAudio(wav: File): Flow<String> = flow { mutex.withLock { try { emitAll(checkNotNull(active).transcribeAudio(wav)) } finally { snapshot() } } }
    override fun describeImage(image: File, prompt: String): Flow<String> = flow { mutex.withLock { try { emitAll(checkNotNull(active).describeImage(image, prompt)) } finally { snapshot() } } }

    /** The external block uses its own native/remote engine, never this facade recursively. */
    override suspend fun <T> withPhoneResource(cleanup: suspend () -> Unit, block: suspend () -> T): T = external(ComputeResource.PHONE, cleanup, block)
    override suspend fun <T> withPcResource(cleanup: suspend () -> Unit, block: suspend () -> T): T = external(ComputeResource.PC, cleanup, block)
    private suspend fun <T> external(destination: ComputeResource, cleanup: suspend () -> Unit, block: suspend () -> T): T = mutex.withLock {
        recoverPendingCleanup()
        if (pcOnly && destination == ComputeResource.PHONE) throw PcRequired("이 작업은 휴대폰에서 계산하지 않습니다. PC에서 처리할 수 있을 때 실행합니다.")
        if (destination == ComputeResource.PC && !externalPcAuthority()) throw PcUnavailable()
        check(resource.moveTo(destination, force = true, cleanupPrevious = ::dropActive))
        if (destination == ComputeResource.PHONE) releaseIdlePhoneModel()
        requestedPath = null; requestedOptions = null; metrics.value = null; generation.value = null
        status.value = if (destination == ComputeResource.PC) "PC에서 작업 중 · 휴대전화 작업 가능" else "휴대전화에서 작업 중"
        pendingExternalCleanup = cleanup
        try {
            if (destination == ComputeResource.PC && !externalPcAuthority()) throw PcUnavailable()
            block()
        } finally {
            withContext(NonCancellable) { resource.releaseAfter { cleanup(); pendingExternalCleanup = null } }
            status.value = "계산 작업 정리됨"
        }
    }
    override fun cancel() { active?.cancel() }
    /** Idle phone maintenance must never issue a global remote unload against another PC run. */
    suspend fun unloadPhoneResidency() = mutex.withLock {
        if (active !== local && resource.location.value != ComputeResource.PHONE) return@withLock
        active?.cancel()
        withContext(NonCancellable) { resource.releaseAfter {
            pendingExternalCleanup?.invoke(); pendingExternalCleanup = null
            dropActive()
        } }
        metrics.value = null; generation.value = null; status.value = "휴대전화 모델 메모리 해제됨"
    }
    override suspend fun unload() {
        active?.cancel()
        mutex.withLock {
            withContext(NonCancellable) { resource.releaseAfter {
                pendingExternalCleanup?.invoke(); pendingExternalCleanup = null
                dropActive()
            } }
            requestedPath = null; requestedOptions = null; metrics.value = null; generation.value = null
            status.value = "계산 모델 메모리 해제됨"
        }
    }

    private data class Components(val local: InferenceEngine, val pc: InferenceEngine,
        val eligible: (String, InferenceOptions) -> Boolean, val preflight: suspend () -> Boolean, val report: (String) -> Unit,
        val externalPcAuthority: () -> Boolean, val analysisPcTarget: () -> PairedPcText?)
    companion object {
        /** Bounded below the 90 s planning load budget so the owner sees "PC busy", not a timeout. */
        const val PC_LEASE_WAIT_MS = 15_000L
        private fun components(context: Context, owner: String?,capturedPcRevision:Long?,allowCloudText:Boolean,capturedPcTextDefault:String?): Components {
            val settings = PcComputeSettings.get(context)
            val original = settings.snapshot()
            val authority=PcTextAuthority(capturedPcRevision?:settings.authorizationRevision,allowCloudText)
            fun samePair(candidate: PcConnection): Boolean = (owner == null&&capturedPcRevision==null&&!allowCloudText) ||
                original != null && authority.matches(settings.authorizationRevision) &&
                    candidate.serverId == original.serverId && candidate.token == original.token &&
                    candidate.certificateSha256 == original.certificateSha256 && settings.snapshot()?.let {
                        it.serverId == original.serverId && it.token == original.token && it.certificateSha256 == original.certificateSha256
                    } == true
            val pc = PcInferenceEngine(context, ::samePair, owner,authority,targetCapturedPcRevision=capturedPcRevision,
                capturedPcTextDefault=capturedPcTextDefault)
            return Components(LocalInferenceEngine(context), pc,
                eligible = { _, _ -> settings.snapshot()?.let(::samePair) == true },
                preflight = pc::preflight, report = settings::report,
                externalPcAuthority = { settings.snapshot()?.let(::samePair) == true },
                analysisPcTarget = {
                    PairedPcText.forRequest(capturedPcTextDefault ?: "auto", capturedPcRevision, allowCloudText,
                        settings.authorizationRevision, settings.snapshot()?.let(::samePair) == true)
                })
        }
    }
}
