package com.user.localagent.benchmark

import android.content.*
import android.os.*
import com.user.localagent.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object RuntimeGate {
    val benchmark = AtomicBoolean(false)
    val inference = AtomicBoolean(false)
    val foreground = AtomicBoolean(false)
}

class BenchmarkRunner(private val context: Context, private val engine: InferenceEngine) {
    /** Reports are checkpointed before native calls, then finalized even on coroutine cancellation. */
    suspend fun run(
        modelPath: String,
        options: InferenceOptions = InferenceOptions(),
        enduranceMinutes: Int = 0,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val reportFile = File(directory, "benchmark-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        val samples = mutableListOf<JsonObject>()
        val started = SystemClock.elapsedRealtime()
        val effectiveOptions = if (enduranceMinutes == 0) options.copy(maxOutputTokens = minOf(options.maxOutputTokens, 128)) else options
        var ownsBenchmark = false
        var ownsInference = false
        var startBattery: JsonObject? = null
        var peakPss = 0L
        var loadMs: Long? = null
        var iterations = 0
        var stage = "starting"
        var failure: Throwable? = null
        var unloadFailure: Throwable? = null

        fun releaseGates() {
            if (ownsInference) { ownsInference = false; RuntimeGate.inference.set(false) }
            if (ownsBenchmark) { ownsBenchmark = false; RuntimeGate.benchmark.set(false) }
        }

        fun export() {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create benchmark report directory" }
            val report = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("device", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("model_file", File(modelPath).name)
                put("backend", effectiveOptions.backend)
                put("context", effectiveOptions.contextSize)
                put("thinking", effectiveOptions.thinking)
                put("max_output_tokens", effectiveOptions.maxOutputTokens)
                put("stage", stage)
                put("load_ms", loadMs)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("requested_endurance_minutes", enduranceMinutes)
                put("iterations", iterations)
                put("peak_pss_kb", peakPss)
                put("start_battery", startBattery ?: JsonNull)
                put("end_battery", runCatching { battery() }.getOrNull() ?: JsonNull)
                put("failure", failure?.let { describe(it) } ?: JsonNull)
                put("unload_failure", unloadFailure?.let { describe(it) } ?: JsonNull)
                put("samples", JsonArray(samples))
                put("unsupported_metrics", JsonArray(listOf(JsonPrimitive("energy_joules"), JsonPrimitive("gpu_memory_bytes"))))
            }
            val temporary = File(reportFile.path + ".tmp")
            temporary.writeText(report.toString())
            check(temporary.renameTo(reportFile)) { "Cannot finalize benchmark JSON report" }
            val keys = listOf("iteration", "status", "ttft_ms", "total_ms", "peak_pss_kb", "prefill_tokens", "decode_tokens", "prefill_tokens_per_sec", "decode_tokens_per_sec")
            val csv = keys.joinToString(",") + "\r\n" + samples.joinToString("\r\n") { sample ->
                keys.joinToString(",") { key ->
                    val value = sample[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
                    "\"" + value.replace("\"", "\"\"") + "\""
                }
            } + "\r\n"
            File(directory, reportFile.nameWithoutExtension + ".csv").writeText(csv)
        }
        fun checkpoint(next: String) {
            stage = next
            export()
            runCatching { onProgress("$next · ${reportFile.name}") }
        }

        try {
            require(enduranceMinutes in 0..10)
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "이미 벤치마크가 실행 중입니다." }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "다른 추론 작업이 실행 중입니다." }
            startBattery = battery()
            checkpoint("unloading_previous_model")
            engine.unload()
            checkpoint("loading_native_engine")
            val loadStart = SystemClock.elapsedRealtime()
            try { engine.load(modelPath, effectiveOptions) } finally { loadMs = SystemClock.elapsedRealtime() - loadStart }
            checkpoint("native_engine_loaded")
            val until = SystemClock.elapsedRealtime() + enduranceMinutes * 60_000L
            do {
                currentCoroutineContext().ensureActive()
                val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
                check(thermal < PowerManager.THERMAL_STATUS_SEVERE) { "열 상태가 높아 벤치마크를 중단했습니다." }
                checkpoint("generating_iteration_${iterations + 1}")
                val start = SystemClock.elapsedRealtime()
                var first: Long? = null
                var visibleCharacters = 0L
                val iterationPeak = java.util.concurrent.atomic.AtomicLong(0)
                var iterationFailure: Throwable? = null
                try {
                    coroutineScope {
                        val sampleJob = launch {
                            while (isActive) {
                                iterationPeak.updateAndGet { previous -> maxOf(previous, Debug.getPss()) }
                                delay(250)
                            }
                        }
                        try {
                            withTimeout(180_000L) {
                                engine.generate("한국어로 답하세요. 기기 안에서 실행되는 개인 비서의 장점을 한 문장으로 설명해주세요.").collect { chunk ->
                                    if (first == null) {
                                        first = SystemClock.elapsedRealtime()
                                        checkpoint("visible_text_iteration_${iterations + 1}")
                                    }
                                    visibleCharacters += chunk.length
                                }
                            }
                        } finally {
                            withContext(NonCancellable) { sampleJob.cancelAndJoin() }
                        }
                    }
                } catch (error: Throwable) {
                    iterationFailure = error
                    throw error
                } finally {
                    peakPss = maxOf(peakPss, iterationPeak.get())
                    iterations++
                    val native = engine.lastMetrics.value
                    samples += buildJsonObject {
                        put("iteration", iterations)
                        put("status", if (iterationFailure == null) "completed" else "failed")
                        put("failure", iterationFailure?.let { describe(it) } ?: JsonNull)
                        put("ttft_ms", first?.minus(start))
                        put("total_ms", SystemClock.elapsedRealtime() - start)
                        put("visible_characters", visibleCharacters)
                        put("peak_pss_kb", iterationPeak.get())
                        put("thermal_status", thermal)
                        put("battery", runCatching { battery() }.getOrNull() ?: JsonNull)
                        put("prefill_tokens", native?.prefillTokens?.takeIf { it >= 0 })
                        put("decode_tokens", native?.decodeTokens?.takeIf { it >= 0 })
                        put("prefill_tokens_per_sec", native?.prefillTokensPerSecond?.takeIf { it.isFinite() && it >= 0 })
                        put("decode_tokens_per_sec", native?.decodeTokensPerSecond?.takeIf { it.isFinite() && it >= 0 })
                    }
                }
                checkpoint("iteration_${iterations}_completed")
            } while (enduranceMinutes > 0 && SystemClock.elapsedRealtime() < until)
            stage = "completed"
        } catch (error: Throwable) {
            failure = error
            stage = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // Persist before native shutdown: a stuck shutdown must not erase the diagnosis.
                    var exportFailure: Throwable? = null
                    try { export() } catch (error: Throwable) { exportFailure = error }
                    try {
                        if (ownsInference) engine.unload()
                    } catch (error: Throwable) {
                        unloadFailure = error
                        if (failure == null) stage = "unload_failed"
                    } finally {
                        // Release even if cleanup or the filesystem failed.
                        releaseGates()
                    }
                    try { export() } catch (error: Throwable) {
                        exportFailure?.let { error.addSuppressed(it) }
                        if (failure != null) failure!!.addSuppressed(error) else throw error
                    }
                    runCatching { onProgress("report_saved · ${reportFile.name}") }
                    if (failure == null && unloadFailure != null) throw unloadFailure!!
                }
            } catch (cleanupError: Throwable) {
                if (failure != null) failure!!.addSuppressed(cleanupError) else throw cleanupError
            } finally {
                releaseGates()
            }
        }
        reportFile
    }

    private fun describe(error: Throwable): JsonObject = buildJsonObject {
        put("class", error.javaClass.name)
        put("message", safe(error.message.orEmpty()))
        put("stack", error.stackTrace.take(32).joinToString("\n") { it.toString() })
        val causes = mutableListOf<JsonElement>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause = error.cause
        while (cause != null && causes.size < 8 && visited.add(cause)) {
            val current = cause
            causes += buildJsonObject { put("class", current.javaClass.name); put("message", safe(current.message.orEmpty())) }
            cause = current.cause
        }
        put("causes", JsonArray(causes))
    }

    private fun safe(text: String) = text.replace(Regex("https?://\\S+"), "[url]")
        .replace(context.filesDir.path, "[app-files]").replace(context.cacheDir.path, "[app-cache]").take(4000)

    private fun battery(): JsonObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun value(name: String): Int? = intent?.getIntExtra(name, -1)?.takeIf { it >= 0 }
        return buildJsonObject {
            put("level", value(BatteryManager.EXTRA_LEVEL))
            put("scale", value(BatteryManager.EXTRA_SCALE))
            put("temperature_c", value(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0))
        }
    }
}



