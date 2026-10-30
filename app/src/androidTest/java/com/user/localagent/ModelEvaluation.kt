package com.user.localagent

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.ToolRegistry
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Explicit, four-case public-data evaluation. Scores are observations, never a pass gate for model quality. */
@RunWith(AndroidJUnit4::class)
class ModelEvaluation {
    private data class Task(val id: String, val prompt: String, val tool: String? = null, val arguments: JsonObject? = null)
    private class OutputLimit : RuntimeException()

    @Test
    fun evaluateLocalModel() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit evaluationApproved=true is required", arguments.getString("evaluationApproved") == "true")
        val modelId = arguments.getString("modelId", "gemma-e2b")
        require(modelId in setOf("gemma-e2b", "gemma-e4b")) { "Evaluation supports only explicitly requested E2B/E4B models" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val report = File(directory, "model-evaluation-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        val results = mutableListOf<JsonObject>()
        val started = SystemClock.elapsedRealtime()
        var stage = "starting"
        var modelFile: String? = null
        var failureClass: String? = null
        var ownsBenchmark = false
        var ownsInference = false
        var primaryFailure: Throwable? = null

        fun export(next: String? = null) {
            if (next != null) stage = next
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create local evaluation directory" }
            val value = buildJsonObject {
                put("kind", "fixed_public_quality_evaluation_v1")
                put("timestamp", Instant.now().toString())
                put("device", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("model_id", modelId)
                put("model_file", modelFile)
                put("backend", "CPU")
                put("context", 4096)
                put("thinking", false)
                put("max_output_tokens", 256)
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("completed_cases", results.size)
                put("total_cases", 4)
                put("passed_cases", results.count { it["pass"]?.jsonPrimitive?.booleanOrNull == true })
                put("failure_class", failureClass)
                put("cases", JsonArray(results.toList()))
                put("limitation", "Four fixed public dry-run cases; not a general capability or safety benchmark. No tools were executed.")
            }
            val temporary = File(report.path + ".tmp")
            temporary.writeText(value.toString())
            check(temporary.renameTo(report)) { "Cannot finalize local evaluation JSON" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Model evaluation: $stage; report=${report.name}\n") })
        }

        try {
            export("checking_local_model")
            app.models.refresh()
            val model = app.models.models.value.firstOrNull { it.id == modelId && it.verified && it.runtime == "litertlm" && File(it.path).isFile }
                ?: error("Requested verified local model is not installed; evaluation never downloads it")
            modelFile = File(model.path).name
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "Another benchmark is active" }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "Another inference is active" }
            export("verifying_local_file")
            val actualHash = withContext(Dispatchers.IO) { fileHash(File(model.path)) }
            require(actualHash == model.sha256) { "Installed model hash no longer matches verification metadata" }
            export("loading_cpu_model")
            app.engine.unload()
            app.engine.load(model.path, InferenceOptions(contextSize = 4096, thinking = false, backend = "CPU", maxOutputTokens = 256))
            for (task in tasks()) {
                currentCoroutineContext().ensureActive()
                export("generating_${task.id}")
                val caseStart = SystemClock.elapsedRealtime()
                var firstAt: Long? = null
                val output = StringBuilder()
                var generated = false
                var generationFailure: String? = null
                var engineFailure: Throwable? = null
                try {
                    withTimeout(120_000L) {
                        app.engine.generate(task.prompt).collect { chunk ->
                            if (chunk.isNotEmpty() && firstAt == null) firstAt = SystemClock.elapsedRealtime()
                            if (output.length + chunk.length > 8192) throw OutputLimit()
                            output.append(chunk)
                        }
                    }
                    generated = true
                } catch (_: TimeoutCancellationException) { generationFailure = "generation_timeout"
                } catch (_: OutputLimit) { generationFailure = "bounded_output_exceeded"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { generationFailure = "engine_error"; engineFailure = error }
                val validation = if (!generated) generationFailure ?: "incomplete_generation" else validate(task, output.toString())
                val metrics = app.engine.lastMetrics.value.takeIf { generated }
                results += buildJsonObject {
                    put("case", task.id)
                    put("pass", validation == null)
                    put("score", if (validation == null) 1 else 0)
                    put("failure", validation)
                    put("engine_failure_class", engineFailure?.javaClass?.name)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - caseStart)
                    put("ttft_ms", firstAt?.minus(caseStart))
                    put("native_decode_tokens_per_sec", metrics?.decodeTokensPerSecond?.takeIf { it.isFinite() && it > 0 })
                    put("native_prefill_tokens_per_sec", metrics?.prefillTokensPerSecond?.takeIf { it.isFinite() && it > 0 })
                    put("output_sha256", if (output.isNotEmpty()) hash(output.toString().toByteArray(Charsets.UTF_8)) else null)
                }
                export("scored_${task.id}")
                if (engineFailure != null) error("Engine generation failed; class=${engineFailure.javaClass.name}. Raw output is not exported.")
            }
            export("completed")
        } catch (error: Throwable) {
            primaryFailure = error
            failureClass = error.javaClass.name
            stage = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                var cleanupFailure: Throwable? = null
                try { export() } catch (error: Throwable) { cleanupFailure = error }
                try {
                    if (ownsInference) { app.engine.cancel(); app.engine.unload() }
                } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                    failureClass = error.javaClass.name
                    if (primaryFailure == null) stage = "cleanup_failed"
                } finally {
                    if (ownsInference) RuntimeGate.inference.set(false)
                    if (ownsBenchmark) RuntimeGate.benchmark.set(false)
                }
                try { export() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
                if (cleanupFailure != null) {
                    if (primaryFailure != null) primaryFailure!!.addSuppressed(cleanupFailure!!) else throw cleanupFailure!!
                }
            }
        }
        // Incorrect model answers are valid evaluation data; only report integrity is asserted here.
        assertTrue("Evaluation report must exist", report.isFile && report.length() > 100)
    }

    private fun tasks(): List<Task> {
        fun toolTask(id: String, name: String, request: String, args: JsonObject): Task {
            val prompt = """
                실제 작업을 실행하지 마세요. 요청에 맞는 도구 계획 JSON 객체 하나만 출력하세요.
                마크다운·설명·추가 텍스트 없이 {"name":"도구 이름","arguments":{필요한 인자}} 형식을 사용하세요.
                아래 도구 하나만 허용합니다. 요청에서 지정한 값과 범위를 그대로 보존하세요.
                도구 스키마: ${ToolRegistry.schemaJson(listOf(name))}
                요청: $request
            """.trimIndent()
            return Task(id, prompt, name, args)
        }
        return listOf(
            toolTask("datetime_empty_arguments", "get_current_datetime", "지금 날짜와 시각을 확인할 계획을 만드세요. 인자는 비어 있는 객체여야 합니다.", buildJsonObject {}),
            toolTask("korean_note_exact_content", "save_note", "메모 내용은 정확히 '회의 자료 3부 인쇄'입니다. scope는 'default'를 명시하세요. title 등 다른 인자는 넣지 마세요.",
                buildJsonObject { put("content", "회의 자료 3부 인쇄"); put("scope", "default") }),
            toolTask("explicit_offset_reminder", "create_reminder", "제목은 정확히 '치과 예약', 시각은 정확히 '2026-11-02T15:00:00+09:00'인 알림 계획을 만드세요. scope는 'default'를 명시하세요.",
                buildJsonObject { put("title", "치과 예약"); put("datetime", "2026-11-02T15:00:00+09:00"); put("scope", "default") }),
            Task("integer_arithmetic_json", """
                17 + 25를 계산하세요. 도구를 호출하거나 실행하지 마세요.
                JSON 객체 하나만 출력하세요. 설명·마크다운·추가 키는 금지합니다.
                JSON 스키마: {"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"],"additionalProperties":false}
            """.trimIndent()),
        )
    }
    private fun validate(task: Task, raw: String): String? {
        if (raw.isBlank()) return "empty_output"
        if (task.tool != null) {
            val call = try { ToolRegistry.parseAndValidate(raw) } catch (_: Exception) { return "invalid_tool_json" }
            if (call.name != task.tool) return "wrong_tool"
            if (call.arguments != task.arguments) return "arguments_mismatch"
            return null
        }
        val value = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return "invalid_math_json"
        val answer = value["answer"] as? JsonPrimitive
        if (value.keys != setOf("answer") || answer == null || answer.isString || answer.intOrNull != 42 ||
            !Regex("\\{\\s*\"answer\"\\s*:\\s*42\\s*\\}").matches(raw.trim())) return "incorrect_math_json"
        return null
    }
    private suspend fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

