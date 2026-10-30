package com.user.localagent.core

/** Character count is an input-size hint, not a token-count or context-fit guarantee. */
data class RequestProfile(
    val ruleMatched: Boolean,
    val explicitDeep: Boolean,
    val complexity: Int,
    val thermalStatus: Int,
    val powerSave: Boolean,
    val availableMemoryMb: Long,
    val contextChars: Int,
) {
    init {
        require(complexity in 0..3)
        require(thermalStatus >= 0)
        require(availableMemoryMb >= 0)
        require(contextChars >= 0)
    }
}

/** Purpose labels only; QUALITY is not an automatically inferred accuracy benchmark. */
enum class ModelQualityTier { FAST, QUALITY }

data class ModelReadiness(
    val id: String,
    val runtime: String,
    val validated: Boolean,
    val ttftMs: Double?,
    val decodeTPS: Double?,
    val peakPssMb: Long?,
    val endurancePassed: Boolean,
    val qualityTier: ModelQualityTier,
    /** True only for the currently resident, reusable CPU model, never merely an installed file. */
    val warm: Boolean = false,
    val backend: String = "CPU",
    /** Separate measured 8k-context peak; a 4k benchmark does not establish 8k safety. */
    val context8192PeakPssMb: Long? = null,
)

sealed interface ModelRoute {
    val reason: String
    data class RuleDirect(override val reason: String) : ModelRoute
    data class Selected(
        val modelId: String,
        val thinking: Boolean,
        val contextSize: Int,
        val maxOutputTokens: Int,
        override val reason: String,
    ) : ModelRoute
    data class NoReadyModel(override val reason: String) : ModelRoute
}

/** Pure, deterministic policy. No downloads, engine loads, model switching, or automatic actions. */
object LocalModelRouter {
    private const val MODERATE_THERMAL_STATUS = 2
    private const val LARGE_CONTEXT_CHARS = 12_000
    private const val MIN_RESERVE_MB = 512L

    fun route(profile: RequestProfile, models: List<ModelReadiness>): ModelRoute {
        if (profile.ruleMatched) return ModelRoute.RuleDirect("A registered deterministic rule matched; no model is needed.")
        if (profile.thermalStatus >= 3) return ModelRoute.NoReadyModel("Inference is deferred while device thermal status is severe or higher.")
        val ready = models.filter { supportedAndMeasured(it) }
        val fast = ready.filter { it.qualityTier == ModelQualityTier.FAST && fits(profile.availableMemoryMb, it, it.peakPssMb!!) }
            .sortedWith(candidateOrder).firstOrNull()
        val wantsQuality = profile.explicitDeep || profile.complexity >= 2
        val unrestricted = !profile.powerSave && profile.thermalStatus < MODERATE_THERMAL_STATUS
        val quality = if (wantsQuality && unrestricted) ready.filter {
            it.qualityTier == ModelQualityTier.QUALITY && it.endurancePassed && fits(profile.availableMemoryMb, it, it.peakPssMb!!)
        }.sortedWith(candidateOrder).firstOrNull() else null
        // Do not run a heavyweight quality-purpose model for a routine request just because it is installed.
        val selected = quality ?: fast ?: return ModelRoute.NoReadyModel(
            "No supported CPU model has validated smoke measurements and sufficient memory headroom for this request.")
        val thinking = profile.explicitDeep && quality != null && unrestricted
        val outputCap = when {
            profile.explicitDeep -> 1024
            profile.complexity >= 2 -> 512
            else -> 192
        }
        val peak8k = selected.context8192PeakPssMb
        val context = if (unrestricted && profile.contextChars >= LARGE_CONTEXT_CHARS && peak8k != null &&
            peak8k >= selected.peakPssMb!! && fits(profile.availableMemoryMb, selected, peak8k)) 8192 else 4096
        val reason = buildString {
            when {
                quality != null -> append("Selected a validated quality-purpose model with passed endurance and measured memory headroom.")
                profile.powerSave -> append("Power saver keeps the request on the validated fast CPU model.")
                profile.thermalStatus >= MODERATE_THERMAL_STATUS -> append("Thermal state keeps the request on the validated fast CPU model.")
                wantsQuality -> append("Quality eligibility or memory evidence was insufficient; selected the validated fast CPU model.")
                selected.warm -> append("Reused the warm validated fast CPU model to avoid a model load.")
                else -> append("Selected a validated fast CPU model for a routine request.")
            }
            append(" Output cap: ").append(outputCap).append(" tokens.")
            if (context == 8192) append(" Separate 8k peak measurement and current headroom permit 8k context.")
            else append(" Context stays at 4k; the caller must budget or truncate the prompt to fit.")
        }
        return ModelRoute.Selected(selected.id, thinking, context, outputCap, reason)
    }

    private fun supportedAndMeasured(model: ModelReadiness): Boolean = model.id.isNotBlank() &&
        model.runtime in setOf("litertlm","gguf") && model.backend == "CPU" && model.validated &&
        model.ttftMs?.let { it.isFinite() && it > 0 } == true &&
        model.decodeTPS?.let { it.isFinite() && it > 0 } == true &&
        model.peakPssMb?.let { it > 0 } == true

    private fun fits(available: Long, model: ModelReadiness, targetPeak: Long): Boolean {
        val measuredPeak = model.peakPssMb ?: return false
        // A genuinely resident model already owns its measured base allocation. Context growth still needs room.
        val additional = if (model.warm) (targetPeak - measuredPeak).coerceAtLeast(0) else targetPeak
        val reserve = maxOf(MIN_RESERVE_MB, targetPeak / 4)
        return available >= additional && available - additional >= reserve
    }
    private val candidateOrder = compareByDescending<ModelReadiness> { it.warm }
        .thenBy { it.ttftMs ?: Double.MAX_VALUE }
        .thenByDescending { it.decodeTPS ?: 0.0 }
        .thenBy { it.peakPssMb ?: Long.MAX_VALUE }
        .thenBy { if (it.id == "gemma-e2b") 0 else 1 }
        .thenBy { it.id }
}
