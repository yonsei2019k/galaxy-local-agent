package com.user.localagent.models

import kotlinx.serialization.json.*

/** Selection metadata owned by the host. It is neither tool authority nor proof of a loaded model. */
sealed interface InferenceModelTarget {
    val id: String
    val title: String
    val runtime: String
    fun requireSelectable()
}

data class LocalWeight(val installed: InstalledModel) : InferenceModelTarget {
    override val id get() = installed.id
    override val title get() = installed.title
    override val runtime get() = installed.runtime
    override fun requireSelectable() { require(installed.verified) { "검증된 로컬 모델이 필요합니다." } }
}

/** A versioned, app-owned profile, never a model/path supplied by a tool or a health reply. */
object PairedPcTextProfile {
    const val SELECTION_ID = "paired-pc-text-v1"
    const val MODEL_ID = "pc-text"
    const val MODEL_SHA256 = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    const val TITLE = "PC · Qwen3.5 9B"
}

/** No file path or downloaded/verified flag. Actual preparation belongs to the request engine. */
class PairedPcText private constructor(
    val authorizationRevision: Long,
    internal val profile: PairedPcTextProfileDescriptor,
) : InferenceModelTarget {
    override val id = profile.selectionId
    override val title = profile.title
    override val runtime = "paired_pc_text"
    override fun requireSelectable() { check(authorizationRevision >= 0) }
    override fun toString() = "PairedPcText(host logical profile)"

    companion object {
        /** Inputs come from the captured request and current host settings, never model output. */
        fun forRequest(selectionId: String, capturedRevision: Long?, allowCloudText: Boolean,
                       currentRevision: Long, paired: Boolean): PairedPcText? {
            val profile = PairedPcTextProfileDescriptor.forSelection(selectionId) ?: return null
            if (allowCloudText) return null // Preserve the separate explicitly authorized cloud route.
            if (capturedRevision == null) {
                if (selectionId != "auto")
                    throw InferencePrerequisiteMissing(InferencePrerequisiteReason.ORIGINAL_PC_AUTHORITY_MISSING)
                return null // A legacy auto queue does not acquire today's pairing.
            }
            if (capturedRevision != currentRevision)
                throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED)
            if (!paired) throw InferencePrerequisiteMissing(InferencePrerequisiteReason.PC_PAIRING_REQUIRED)
            require(capturedRevision >= 0)
            return PairedPcText(capturedRevision, profile)
        }
    }
}

enum class InferencePrerequisiteReason(val code: String, val notice: String) {
    MODEL_SELECTION_MISSING("inference_model_not_ready", "요청을 처리할 모델이 준비되지 않았습니다. 모델과 PC 연결 상태를 확인한 뒤 다시 요청해주세요."),
    PC_PAIRING_REQUIRED("inference_pc_pairing_required", "등록한 PC가 필요합니다. 내 PC 연결을 준비한 뒤 다시 요청해주세요."),
    ORIGINAL_PC_AUTHORITY_MISSING("inference_original_pc_authority_missing", "이 요청의 원래 PC 사용 권한을 확인하지 못했습니다. 현재 설정을 확인한 뒤 새로 요청해주세요."),
    PC_AUTHORITY_CHANGED("inference_pc_authority_changed", "요청 이후 PC 사용 권한이 바뀌어 실행하지 않았습니다. 현재 설정을 확인한 뒤 새로 요청해주세요."),
    PC_LOADER_UNAVAILABLE("inference_pc_loader_unavailable", "이 실행 경로에는 PC 텍스트 모델 준비 기능이 연결되지 않았습니다."),
    PC_PROFILE_CONTRACT_UNAVAILABLE("inference_pc_profile_contract_unavailable", "선택한 PC 모델의 생성 설정 확인 기능이 준비되지 않았습니다. PC 작업기를 업데이트한 뒤 다시 요청해주세요."),
    PC_MODEL_SELECTION_CHANGED("inference_pc_model_selection_changed", "원래 요청과 이어쓰기의 모델이 달라 실행하지 않았습니다. 사용할 모델을 확인한 뒤 새로 요청해주세요."),
    PC_ANALYSIS_PROVENANCE_UNAVAILABLE("inference_pc_analysis_provenance_unavailable", "이 분석 경로는 실제 PC 모델의 검증 기록 연결이 준비되지 않아 분석을 실행하지 않았습니다.");
}

class InferencePrerequisiteMissing(val reason: InferencePrerequisiteReason) : IllegalStateException(reason.notice)

/** The production selector's PC branch is independent of the phone's installed-file list. */
object RequestInferenceSelection {
    fun pairedText(selectionId: String, capturedRevision: Long?, allowCloudText: Boolean,
                   currentRevision: Long, paired: Boolean, thinking: Boolean): ChosenInference? {
        val target = PairedPcText.forRequest(selectionId, capturedRevision, allowCloudText, currentRevision, paired) ?: return null
        return ChosenInference(target, com.user.localagent.inference.InferenceOptions(contextSize = 8192,
            backend = "CPU", thinking = thinking, maxOutputTokens = if (thinking) 2048 else 768), "원래 요청의 로컬 PC 텍스트 선택")
    }
}

/** Only the original still-running run can be paused; an error/cancellation always takes precedence. */
object InferencePrerequisiteFinalization {
    fun pauseReason(currentStatus: String?, interruptedState: String?, reason: InferencePrerequisiteReason?) =
        reason?.takeIf { currentStatus == "running" && interruptedState == null }
}

/** An expected profile is not this receipt. Construct only after the authenticated load reply. */
class PairedPcLoadReceipt private constructor(val profileId: String, val modelSha256: String,
    val contextSize: Int, val authorizationRevision: Long) {
    fun matches(target: PairedPcText, context: Int) = profileId == target.id &&
        modelSha256 == target.profile.modelSha256 && contextSize == context &&
        authorizationRevision == target.authorizationRevision
    override fun toString() = "PairedPcLoadReceipt(verified host profile)"
    companion object {
        /** Preserve raw wire types before converting the authenticated response to a typed receipt. */
        fun fromAuthenticatedLoadReply(target:PairedPcText,reply:JsonObject,expectedContext:Int):PairedPcLoadReceipt {
            fun text(key:String)=(reply[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
            fun integer(key:String)=(reply[key] as? JsonPrimitive)?.takeUnless{it.isString}?.intOrNull
            fun flag(key:String)=(reply[key] as? JsonPrimitive)?.takeUnless{it.isString}?.booleanOrNull
            return fromAuthenticatedLoad(target,text("modelId"),text("modelSha256"),integer("contextSize"),flag("structuredOutput"),expectedContext)
        }
        fun fromAuthenticatedLoad(target: PairedPcText, modelId: String?, modelSha256: String?,
                                  contextSize: Int?, structured: Boolean?, expectedContext: Int): PairedPcLoadReceipt {
            require(target.profile.matchesModelIdentity(modelId, modelSha256))
            require(expectedContext in setOf(4096, 8192) && contextSize == expectedContext && structured == true)
            return PairedPcLoadReceipt(target.id, requireNotNull(modelSha256), expectedContext, target.authorizationRevision)
        }
    }
}
