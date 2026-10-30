package com.user.localagent.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.user.localagent.core.LocalModelRouter
import com.user.localagent.core.ModelQualityTier
import com.user.localagent.core.ModelReadiness
import com.user.localagent.core.ModelRoute
import com.user.localagent.core.RequestProfile
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/** A choice only: the caller owns the inference gate and loads at most one model/session. */
data class ChosenInference(val model: InstalledModel, val options: InferenceOptions, val reason: String)

/** Local reports supply readiness evidence; installation or a model's size never establishes quality. */
class LocalOrchestration(
    context: Context,
    private val models: ModelManager,
    private val engine: InferenceEngine,
) {
    private val context = context.applicationContext
    @Volatile private var latestSummary = "모델 선택 전입니다. 검증한 CPU 벤치마크 보고서가 필요합니다."
    fun summary(): String = latestSummary

    /** Null means no eligible model; this method never downloads, selects, loads, or executes one. */
    suspend fun choose(text: String, explicitDeep: Boolean = false): ChosenInference? = withContext(Dispatchers.IO) {
        models.refresh()
        val installed = models.models.value.filter { it.verified && File(it.path).isFile }
        val reports = loadReports(installed.map { File(it.path).name }.toSet())
        val resident = engine.residentModelPath
        val readiness = installed.map { model ->
            val matching = reports.filter { it.modelFile == File(model.path).name }
            val base = matching.firstOrNull { it.contextSize == 4096 }
            val baseReports = matching.filter { it.contextSize == 4096 }
            val largeReports = matching.filter { it.contextSize == 8192 }
            ModelReadiness(
                id = model.id,
                runtime = model.runtime,
                validated = base != null,
                ttftMs = base?.ttftMs,
                decodeTPS = base?.decodeTPS,
                // The maximum observed peak is conservative across successful reports of the same context.
                peakPssMb = baseReports.maxOfOrNull { it.peakPssMb },
                endurancePassed = baseReports.any { it.endurancePassed },
                qualityTier = if (model.id in setOf("gemma-e4b", "qwen-4b")) ModelQualityTier.QUALITY else ModelQualityTier.FAST,
                warm = resident == model.path,
                backend = "CPU",
                context8192PeakPssMb = largeReports.maxOfOrNull { it.peakPssMb },
            )
        }
        val memory = ActivityManager.MemoryInfo()
        val availableMb = runCatching {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            memory.availMem.coerceAtLeast(0) / (1024L * 1024)
        }.getOrDefault(0)
        val power = context.getSystemService(PowerManager::class.java)
        val profile = RequestProfile(
            ruleMatched = false, // Registered deterministic commands are resolved by the host before this adapter.
            explicitDeep = explicitDeep,
            complexity = if (COMPLEXITY_HINTS.any { it in text.take(8_000) }) 2 else 0,
            thermalStatus = runCatching { power.currentThermalStatus.coerceAtLeast(0) }.getOrDefault(PowerManager.THERMAL_STATUS_MODERATE),
            powerSave = runCatching { power.isPowerSaveMode }.getOrDefault(true),
            availableMemoryMb = availableMb,
            contextChars = text.length,
        )
        val evidence = readiness.joinToString("\n") {
            "${it.id}: " + if (!it.validated) "일치하는 4K CPU 성공 보고서 없음"
            else "4K CPU 실측 있음 · 10분 내구성 ${if (it.endurancePassed) "통과" else "미확인"} · 8K 실측 ${if (it.context8192PeakPssMb != null) "있음" else "없음"}"
        }.ifBlank { "검증된 설치 모델이 없습니다." }
        when (val route = LocalModelRouter.route(profile, readiness)) {
            is ModelRoute.Selected -> {
                val model = installed.firstOrNull { it.id == route.modelId } ?: return@withContext null
                latestSummary = "선택: ${model.title}\n${route.reason}\n$evidence"
                ChosenInference(model, InferenceOptions(contextSize = route.contextSize, thinking = route.thinking,
                    backend = "CPU", maxOutputTokens = route.maxOutputTokens), route.reason)
            }
            is ModelRoute.NoReadyModel -> { latestSummary = "실측 조건을 충족하는 모델이 없습니다.\n${route.reason}\n$evidence"; null }
            is ModelRoute.RuleDirect -> { latestSummary = route.reason; null }
        }
    }

    private data class Report(
        val modelFile: String,
        val contextSize: Int,
        val ttftMs: Double,
        val decodeTPS: Double,
        val peakPssMb: Long,
        val endurancePassed: Boolean,
    )
    private suspend fun loadReports(modelFiles: Set<String>): List<Report> {
        if (modelFiles.isEmpty()) return emptyList()
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val files = directory.listFiles()?.asSequence()?.filter {
            it.isFile && it.name.startsWith("benchmark-") && it.extension == "json" && it.length() in 1..MAX_REPORT_BYTES.toLong()
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })?.take(MAX_REPORTS)?.toList().orEmpty()
        val reports = mutableListOf<Report>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            try { parseReport(readBounded(file), modelFiles)?.let { reports += it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Partial, obsolete, unsupported, or malformed reports provide no readiness evidence. */ }
        }
        return reports
    }
    private fun readBounded(file: File): String = file.inputStream().use { input ->
        val bytes = ByteArray(MAX_REPORT_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) continue
            size += count
        }
        require(size in 1..MAX_REPORT_BYTES)
        bytes.decodeToString(0, size, throwOnInvalidSequence = true)
    }
    private fun parseReport(raw: String, modelFiles: Set<String>): Report? {
        val value = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val modelFile = value.string("model_file") ?: return null
        if (modelFile !in modelFiles || File(modelFile).name != modelFile) return null
        if (value.string("backend") != "CPU" || value.string("stage") != "completed" ||
            value["failure"] != JsonNull || value["unload_failure"] != JsonNull ||
            value.string("device") != Build.MODEL || value.integer("android_api") != Build.VERSION.SDK_INT.toLong()) return null
        val contextSize = value.integer("context")?.takeIf { it == 4096L || it == 8192L }?.toInt() ?: return null
        val samples = value["samples"] as? JsonArray ?: return null
        if (samples.isEmpty() || samples.size > MAX_SAMPLES) return null
        val ttft = mutableListOf<Double>()
        val decode = mutableListOf<Double>()
        var peakKb = value.integer("peak_pss_kb")?.takeIf { it > 0 } ?: return null
        for (element in samples) {
            val sample = element as? JsonObject ?: return null
            if (sample.string("status") != "completed" || sample["failure"] != JsonNull ||
                (sample.integer("visible_characters") ?: 0) <= 0) return null
            ttft += sample.positive("ttft_ms") ?: return null
            decode += sample.positive("decode_tokens_per_sec") ?: return null
            val samplePeak = sample.integer("peak_pss_kb")?.takeIf { it > 0 } ?: return null
            peakKb = maxOf(peakKb, samplePeak)
        }
        val peakMb = peakKb / 1024 + if (peakKb % 1024 == 0L) 0 else 1
        val requestedMinutes = value.integer("requested_endurance_minutes") ?: return null
        val elapsed = value.integer("elapsed_ms")?.takeIf { it > 0 } ?: return null
        return Report(modelFile, contextSize, median(ttft), median(decode), peakMb,
            requestedMinutes >= 10 && elapsed >= 600_000)
    }
    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.integer(name: String): Long? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.positive(name: String): Double? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }
        ?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        // Upper median is an observed sample and avoids inventing an unobserved performance value.
        return sorted[sorted.size / 2]
    }
    companion object {
        private val COMPLEXITY_HINTS = listOf("비교", "분석", "계획", "문서", "코드", "보고서")
        private const val MAX_REPORTS = 64
        private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_SAMPLES = 4096
    }
}
