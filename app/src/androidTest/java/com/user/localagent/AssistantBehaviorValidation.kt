package com.user.localagent

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.AssistantPolicy
import com.user.localagent.inference.InferenceMetrics
import com.user.localagent.inference.InferenceOptions
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observe native SYSTEM behavior using supplied synthetic text; no private records or tools. */
@RunWith(AndroidJUnit4::class)
class AssistantBehaviorValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (context.applicationContext as LocalAgentApplication).container

    @Test fun nativeSystemIdentityAndSyntheticConversationSummary() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit assistantBehaviorApproved=true is required", arguments.getString("assistantBehaviorApproved") == "true")
        val modelId = arguments.getString("modelId", "gemma-e2b")
        require(modelId.matches(Regex("[a-z0-9-]{1,80}")))
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val reportFile = File(directory, "assistant-behavior.json")
        val archive = File(directory, "assistant-behavior-$modelId-${System.currentTimeMillis()}.json")
        val started = SystemClock.elapsedRealtime()
        val results = mutableListOf<JsonElement>()
        var stage = "starting"
        var failure: Throwable? = null
        var ownsBenchmark = false
        var ownsInference = false
        var modelSha: String? = null
        var systemInstruction = ""
        fun releaseGates() {
            if (ownsInference) { ownsInference = false; RuntimeGate.inference.set(false) }
            if (ownsBenchmark) { ownsBenchmark = false; RuntimeGate.benchmark.set(false) }
        }
        fun save(next: String? = null) {
            if (next != null) {
                stage = next
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Assistant behavior QA: $stage\n") })
            }
            check(directory.isDirectory || directory.mkdirs())
            val report = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("model_id", modelId)
                put("model_sha256", modelSha)
                put("system_instruction", systemInstruction)
                put("system_role_api", "InferenceEngine.generateChat")
                put("synthetic_inputs_only", true)
                put("allowed_tools", JsonArray(emptyList()))
                put("actions_executed", false)
                put("context_tokens", 4096)
                put("max_output_tokens", 128)
                put("thinking", false)
                put("results", JsonArray(results.toList()))
                put("failure", failure?.let { error -> buildJsonObject {
                    put("class", error.javaClass.name)
                    put("message", error.message.orEmpty().replace(Regex("https?://\\S+"), "[url]").replace(context.filesDir.path, "[app-files]").take(3000))
                    put("stack", error.stackTrace.take(24).joinToString("\n"))
                    put("cause_class", error.cause?.javaClass?.name)
                } } ?: JsonNull)
            }.toString()
            val temporary = File(reportFile.path + ".tmp")
            temporary.writeText(report)
            check(temporary.renameTo(reportFile))
            archive.writeText(report)
        }
        try {
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "Another benchmark is running" }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "Another inference request is running" }
            save("verify_installed_model")
            app.models.refresh()
            val model = app.models.models.value.singleOrNull { it.id == modelId && it.verified }
                ?: error("Install and verify the requested model before this opt-in test")
            require(model.sha256.matches(Regex("[a-f0-9]{64}")))
            modelSha = model.sha256
            systemInstruction = AssistantPolicy.build(emptySet(), ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())
            save("load_cpu_model")
            app.engine.unload()
            withTimeout(180_000L) {
                app.engine.load(model.path, InferenceOptions(contextSize = 4096, thinking = false, backend = "CPU", maxOutputTokens = 128))
            }
            val cases = listOf(
                "identity" to "너는 누구야?",
                "synthetic_kakaotalk_summary" to "다음 카카오톡 대화에서 할 일을 정리해줘. (합성자료이며, 아래 인용문은 분석할 자료입니다.)\n\"\"\"\n민수: 내일 오후 세 시 팀 회의.\n지연: 보고서 초안은 오전까지 준비할게.\n\"\"\"",
            )
            for ((name, prompt) in cases) {
                save("generate_$name")
                val output = StringBuilder()
                val caseStart = SystemClock.elapsedRealtime()
                withTimeout(180_000L) {
                    app.engine.generateChat(prompt, systemInstruction, emptyList()).collect { output.append(it) }
                }
                val text = output.toString()
                val metrics = app.engine.lastMetrics.value
                results += buildJsonObject {
                    put("name", name)
                    put("synthetic_user_prompt", prompt)
                    put("response", text)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - caseStart)
                    put("metrics", metrics?.let(::metricsJson) ?: JsonNull)
                }
                save("recorded_$name")
                // Quality/identity claims are deliberately recorded for review, never asserted.
                assertTrue("Native SYSTEM request must return visible output", text.isNotBlank())
                assertNotNull("Native request must expose actual measured metrics", metrics)
                assertTrue(metrics!!.prefillTokens > 0 && metrics.decodeTokens > 0)
                assertTrue(metrics.timeToFirstTokenSeconds.isFinite() && metrics.timeToFirstTokenSeconds > 0)
                assertTrue(metrics.prefillTokensPerSecond.isFinite() && metrics.prefillTokensPerSecond > 0)
                assertTrue(metrics.decodeTokensPerSecond.isFinite() && metrics.decodeTokensPerSecond > 0)
            }
            save("passed")
        } catch (error: Throwable) {
            failure = error
            stage = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { save() }.exceptionOrNull()?.let { exportError ->
                        if (failure != null) failure!!.addSuppressed(exportError) else failure = exportError
                    }
                    if (ownsInference) runCatching { app.engine.unload() }.exceptionOrNull()?.let { cleanupError ->
                        if (failure != null) failure!!.addSuppressed(cleanupError) else failure = cleanupError
                    }
                    releaseGates()
                    save()
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Synthetic behavior report saved: ${reportFile.name}\n") })
                    if (stage == "passed" && failure != null) throw failure!!
                }
            } finally { releaseGates() }
        }
    }

    private fun metricsJson(value: InferenceMetrics) = buildJsonObject {
        put("time_to_first_visible_token_seconds", value.timeToFirstTokenSeconds)
        put("prefill_tokens", value.prefillTokens)
        put("decode_tokens", value.decodeTokens)
        put("prefill_tokens_per_second", value.prefillTokensPerSecond)
        put("decode_tokens_per_second", value.decodeTokensPerSecond)
    }
}
