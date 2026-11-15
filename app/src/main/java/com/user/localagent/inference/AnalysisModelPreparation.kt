package com.user.localagent.inference

import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.models.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Host-only preparation. An observation wrapper must forward the actual facade's identity unchanged. */
interface AnalysisModelPreparation {
    suspend fun prepareAnalysisModel(target: InferenceModelTarget, options: InferenceOptions): PreparedAnalysisModel
}

/** Not serializable or tool authority. Keeps the actual preparing engine, not a wrapper/status label. */
class PreparedAnalysisModel private constructor(
    private val engine: InferenceEngine,
    private val requestedTarget: InferenceModelTarget,
    val modelSha256: String,
    private val contextSize: Int,
    private val actualModelId: String,
    private val route: String,
    private val owner: String?,
    private val authorizationRevision: Long?,
    private val structured: Boolean,
) {
    @Volatile private var preparationFailure: Exception? = null
    internal fun requireUsable() { preparationFailure?.let { throw it } }
    @Synchronized internal fun rejectPreparation(failure: Exception) {
        if (preparationFailure == null) preparationFailure = failure
    }
    internal fun requireRequest(target: InferenceModelTarget, options: InferenceOptions) {
        check(requestedTarget === target && contextSize == options.contextSize) {
            "분석 모델 준비가 원래 요청 또는 문맥 크기와 다릅니다."
        }
    }
    internal fun requireSameActualModel(next: PreparedAnalysisModel) {
        requireUsable();next.requireUsable()
        check(engine === next.engine && requestedTarget === next.requestedTarget &&
            modelSha256 == next.modelSha256 && contextSize == next.contextSize &&
            actualModelId == next.actualModelId && route == next.route && owner == next.owner &&
            authorizationRevision == next.authorizationRevision && structured == next.structured) {
            "분석 도중 실제 실행 모델 또는 원래 실행 권한이 바뀌었습니다."
        }
    }
    override fun toString() = "PreparedAnalysisModel(host execution identity; private binding omitted)"

    companion object {
        internal fun pairedPc(engine: InferenceEngine, requested: InferenceModelTarget,
            actual: PairedPcText, options: InferenceOptions, receipt: PairedPcLoadReceipt,
            owner: String): PreparedAnalysisModel {
            check(owner.isNotBlank() && receipt.matches(actual, options.contextSize))
            return PreparedAnalysisModel(engine, requested, receipt.modelSha256, receipt.contextSize,
                receipt.profileId, "authenticated_local_pc", owner, receipt.authorizationRevision, true)
        }
        internal fun verifiedLocal(engine: LocalInferenceEngine, requested: LocalWeight,
            options: InferenceOptions): PreparedAnalysisModel {
            val installed = requested.installed
            check(installed.verified && HASH.matches(installed.sha256) &&
                engine.residentModelPath?.let { File(it).canonicalFile } == File(installed.path).canonicalFile)
            return PreparedAnalysisModel(engine, requested, installed.sha256, options.contextSize,
                installed.id, "verified_local_file", null, null, engine.supportsStructuredOutput)
        }
        private val HASH = Regex("[a-f0-9]{64}")
    }
}

/** Preserve ordinary text analysis; media helpers/device-only work do not gain a PC text route. */
internal fun requirePrivateAnalysisText(target: InferenceModelTarget, options: InferenceOptions) {
    target.requireSelectable()
    require(!options.audioEnabled && !options.visionEnabled && !options.imagePreparation &&
        options.projectorPath == null)
    if (target is LocalWeight) require(target.runtime in setOf("litertlm", "gguf") &&
        !GemmaMediaCatalog.auxiliary(target.id)) { "일반 텍스트 분석 모델이 필요합니다." }
}

internal fun InferenceOptions.privateAnalysisOptions(maximumOutput: Int) = copy(
    thinking = false, maxOutputTokens = maximumOutput, audioEnabled = false,
    visionEnabled = false, projectorPath = null)

/** No residency/health cache. Each call re-enters the same request engine's actual load boundary. */
internal suspend fun InferenceEngine.preparePrivateAnalysis(target: InferenceModelTarget,
    options: InferenceOptions, expected: PreparedAnalysisModel? = null): PreparedAnalysisModel {
    expected?.requireUsable()
    try {
        requirePrivateAnalysisText(target, options)
        currentCoroutineContext().ensureActive()
        val prepared = when (this) {
            is AnalysisModelPreparation -> prepareAnalysisModel(target, options)
            is LocalInferenceEngine -> {
                check(RuntimeGate.inference.get()) { "현재 분석 요청의 모델 실행 범위가 필요합니다." }
                val local = target as? LocalWeight ?: throw InferencePrerequisiteMissing(
                    InferencePrerequisiteReason.PC_ANALYSIS_PROVENANCE_UNAVAILABLE)
                load(local.installed.path, options)
                currentCoroutineContext().ensureActive()
                PreparedAnalysisModel.verifiedLocal(this, local, options)
            }
            else -> throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_ANALYSIS_PROVENANCE_UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        prepared.requireUsable()
        prepared.requireRequest(target, options)
        expected?.requireSameActualModel(prepared)
        return prepared
    } catch (cancel: CancellationException) {
        expected?.rejectPreparation(cancel)
        this.cancel()
        throw cancel
    } catch (failure: Exception) {
        expected?.rejectPreparation(failure)
        throw failure
    }
}
