package com.user.localagent

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceMetrics
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.inference.LlamaCppEngine
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native QA: installed model metadata and synthetic prompts only; no private records/tools. */
@RunWith(AndroidJUnit4::class)
class QwenValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (context.applicationContext as LocalAgentApplication).container

    @Test fun qwen4bSyntheticChatTokenizationAndCancellation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit qwenValidationApproved=true is required", arguments.getString("qwenValidationApproved") == "true")
        val started = SystemClock.elapsedRealtime()
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val reportFile = File(directory, "qwen-evaluation-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        val results = mutableListOf<JsonElement>()
        var stage = "starting"
        var failure: Throwable? = null
        var ownsBenchmark = false
        var ownsInference = false
        var native: LlamaCppEngine? = null
        var modelSha: String? = null
        var modelFile: String? = null
        var cancellationObserved = false
        var facadeReloadedAfterNativeRelease = false

        fun releaseGates() {
            if (ownsInference) { ownsInference = false; RuntimeGate.inference.set(false) }
            if (ownsBenchmark) { ownsBenchmark = false; RuntimeGate.benchmark.set(false) }
        }
        fun save(next: String? = null) {
            if (next != null) {
                stage = next
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Qwen QA: $stage\n") })
            }
            check(directory.isDirectory || directory.mkdirs())
            val report = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("synthetic_inputs_only", true)
                put("model_id", "qwen-4b")
                put("model_sha256", modelSha)
                put("model_file", modelFile)
                put("backend", "llama.cpp CPU")
                put("context_tokens", 4096)
                put("thinking", false)
                put("results", JsonArray(results.toList()))
                put("cancellation_observed", cancellationObserved)
                put("facade_reloaded_after_native_release", facadeReloadedAfterNativeRelease)
                put("failure", failure?.let { errorJson(it) } ?: JsonNull)
            }
            val temporary = File(reportFile.path + ".tmp")
            temporary.writeText(report.toString())
            check(temporary.renameTo(reportFile)) { "Unable to finalize synthetic Qwen evaluation report" }
        }

        try {
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "Another benchmark is running" }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "Another inference request is running" }
            save("refresh_installed_model_integrity")
            app.models.refresh()
            val model = app.models.models.value.singleOrNull { it.id == "qwen-4b" && it.runtime == "gguf" && it.verified }
                ?: error("Install and verify the canonical qwen-4b GGUF model before this opt-in test")
            require(model.sha256.matches(Regex("[a-f0-9]{64}")))
            val file = File(model.path).canonicalFile
            require(file.parentFile == File(context.filesDir, "models").canonicalFile && file.isFile)
            require(file.name == "qwen-4b-${model.sha256.take(12)}.gguf") { "Expected canonical managed Qwen4B artifact" }
            arguments.getString("qwenExpectedSha256")?.let { expected ->
                require(expected.matches(Regex("[a-fA-F0-9]{64}")) && expected.equals(model.sha256, true)) { "Installed Qwen artifact does not match the externally expected SHA-256" }
            }
            modelSha = model.sha256
            modelFile = file.name
            save("unload_app_runtime_before_direct_native_test")
            app.engine.unload()
            currentCoroutineContext().ensureActive()
            assertNull("The facade must release its model before a direct native engine loads", app.engine.residentModelPath)
            val engine = LlamaCppEngine(context)
            native = engine
            val options = InferenceOptions(contextSize = 4096, backend = "CPU", thinking = false, maxOutputTokens = 64)
            save("load_verified_qwen4b_native_cpu")
            withTimeout(180_000L) { engine.load(file.path, options) }
            assertEquals(file.path, engine.residentModelPath)
            assertNull("Two runtime models must not be resident", app.engine.residentModelPath)

            // Exercise the native singleton guard without allocating a second set of weights.
            val second = LlamaCppEngine(context)
            try {
                val attempt = runCatching { second.load(file.path, options) }
                assertTrue("A second concurrent native model handle must be rejected", attempt.isFailure)
                assertNull(second.residentModelPath)
                assertEquals(file.path, engine.residentModelPath)
            } finally { second.unload() }

            val system = "당신은 ZipHap.Agent라는 한국어 글쓰기 도우미입니다. 이 테스트는 가상 문장만 다룹니다. 요청에 바로 짧게 답하세요. 앱의 역할을 물으면 앱의 역할을 설명하고, 모델 개발사나 학습 데이터는 추측하지 마세요."
            val history = listOf(
                InferenceTurn("user", "가상 글쓰기 연습에서는 창가에서 책을 읽는 장면을 사용합니다."),
                InferenceTurn("assistant", "네, 창가에서 책을 읽는 가상 장면으로 글쓰기 연습을 하겠습니다."),
            )
            data class Case(val name: String, val prompt: String, val turns: List<InferenceTurn> = emptyList())
            val cases = listOf(
                Case("korean_plain_writing", "위 가상 장면에서 빗소리가 들리는 모습을 한국어 한 문장으로 써주세요.", history),
                Case("system_identity_observation", "이 앱은 어떤 일을 도와주나요? 한 문장으로 답해주세요."),
                Case("unicode_literal_observation", "다음 문구를 한 번 그대로 적어주세요: ZipHap 한글 😀 🧑‍💻"),
            )
            for (case in cases) {
                save("tokenize_${case.name}")
                val count = withTimeout(30_000L) { engine.countChatTokens(case.prompt, system, case.turns) }
                assertTrue("Real formatted tokenizer count must be positive", count > 0)
                assertTrue("The synthetic request must fit the actual context budget", count + options.maxOutputTokens + 8 <= 4096)
                val answer = StringBuilder()
                val caseStart = SystemClock.elapsedRealtime()
                var firstVisible: Long? = null
                save("generate_${case.name}")
                withTimeout(180_000L) {
                    engine.generateChat(case.prompt, system, case.turns).collect { chunk ->
                        assertUnicode(chunk)
                        if (firstVisible == null && chunk.isNotEmpty()) firstVisible = SystemClock.elapsedRealtime()
                        answer.append(chunk)
                    }
                }
                val text = answer.toString()
                val metrics = engine.lastMetrics.value
                // Observe model identity/instruction following in the artifact; do not assert claims.
                results += buildJsonObject {
                    put("name", case.name)
                    put("synthetic_system_instruction", system)
                    put("synthetic_user_prompt", case.prompt)
                    put("synthetic_history", JsonArray(case.turns.map { turn -> buildJsonObject { put("role", turn.role); put("text", turn.text) } }))
                    put("response", text)
                    put("formatted_input_tokens", count)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - caseStart)
                    put("first_visible_ms", firstVisible?.minus(caseStart))
                    put("supplementary_unicode_emitted", text.any { Character.isHighSurrogate(it) })
                    put("metrics", metrics?.let(::metricsJson) ?: JsonNull)
                }
                save("recorded_${case.name}")
                assertTrue("Synthetic writing must produce visible text", text.isNotBlank())
                assertUnicode(text)
                assertNotNull("Successful native generation must provide measured metrics", metrics)
                assertEquals("Tokenizer planning must match actual untrimmed native prefill", count, metrics!!.prefillTokens)
                assertTrue(metrics.decodeTokens in 1..options.maxOutputTokens)
                assertTrue(metrics.timeToFirstTokenSeconds.isFinite() && metrics.timeToFirstTokenSeconds >= 0)
            }

            save("native_tokenizer_growth_check")
            val shortCount = engine.countChatTokens("한글 😀", system)
            val longerCount = engine.countChatTokens("한글 😀 ".repeat(20), system)
            assertTrue("Actual tokenizer counts must respond to additional Unicode input", longerCount > shortCount)
            results += buildJsonObject { put("name", "actual_tokenizer_growth"); put("short_tokens", shortCount); put("longer_tokens", longerCount) }

            save("prepare_native_cancellation")
            engine.load(file.path, options.copy(maxOutputTokens = 1024))
            val firstChunk = CompletableDeferred<Unit>()
            val generationError = AtomicReference<Throwable?>(null)
            val partial = StringBuilder()
            supervisorScope {
                val request = launch(Dispatchers.IO) {
                    try {
                        engine.generateChat("가상 글쓰기 연습입니다. 1번부터 1000번까지 번호를 붙여 서로 다른 한국어 장면을 길게 나열해주세요.", system).collect { chunk ->
                            assertUnicode(chunk)
                            if (partial.length < 4000) partial.append(chunk.take(4000 - partial.length))
                            if (chunk.isNotBlank()) firstChunk.complete(Unit)
                        }
                    } catch (error: Throwable) {
                        generationError.set(error)
                    } finally {
                        if (!firstChunk.isCompleted) firstChunk.completeExceptionally(generationError.get() ?: IllegalStateException("No visible native output before completion"))
                    }
                }
                try {
                    withTimeout(120_000L) { firstChunk.await() }
                    save("cancel_active_native_generation")
                    val cancelStart = SystemClock.elapsedRealtime()
                    engine.cancel()
                    withTimeout(20_000L) { request.join() }
                    cancellationObserved = generationError.get() is CancellationException
                    results += buildJsonObject {
                        put("name", "native_cancel")
                        put("cancellation_observed", cancellationObserved)
                        put("cancel_elapsed_ms", SystemClock.elapsedRealtime() - cancelStart)
                        put("synthetic_partial_response", partial.toString())
                        put("failure_class", generationError.get()?.javaClass?.name)
                    }
                    assertTrue("Explicit native stop must cancel the active request", cancellationObserved)
                    currentCoroutineContext().ensureActive()
                    assertTrue("Native request cancellation must leave the QA caller active", currentCoroutineContext().isActive)
                } finally {
                    engine.cancel()
                    withContext(NonCancellable) { request.cancelAndJoin() }
                }
            }
            save("unload_direct_native_model")
            engine.unload()
            currentCoroutineContext().ensureActive()
            assertNull(engine.residentModelPath)
            assertNull(app.engine.residentModelPath)
            save("reload_through_runtime_facade_after_native_release")
            withTimeout(180_000L) { app.engine.load(file.path, options) }
            facadeReloadedAfterNativeRelease = app.engine.residentModelPath == file.path && engine.residentModelPath == null
            assertTrue("The facade must load only after the direct native model was released", facadeReloadedAfterNativeRelease)
            app.engine.unload()
            currentCoroutineContext().ensureActive()
            assertNull(app.engine.residentModelPath)
            save("passed")
        } catch (error: Throwable) {
            failure = error
            stage = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // Persist synthetic results before shutdown, including failures during model load.
                    runCatching { save() }.exceptionOrNull()?.let { exportError ->
                        if (failure != null) failure!!.addSuppressed(exportError) else failure = exportError
                    }
                    val cleanupErrors = mutableListOf<Throwable>()
                    native?.let { engine -> runCatching { engine.unload() }.exceptionOrNull()?.let(cleanupErrors::add) }
                    if (ownsInference) runCatching { app.engine.unload() }.exceptionOrNull()?.let(cleanupErrors::add)
                    releaseGates()
                    cleanupErrors.forEach { cleanupError ->
                        if (failure != null) failure!!.addSuppressed(cleanupError) else failure = cleanupError
                    }
                    save()
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Qwen synthetic evaluation saved: ${reportFile.name}\n") })
                    if (stage == "passed" && failure != null) throw failure!!
                }
            } finally { releaseGates() }
        }
    }

    private fun assertUnicode(text: String) {
        assertFalse("Native UTF conversion must not emit U+FFFD", text.contains('\uFFFD'))
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue("A supplementary scalar must preserve its UTF-16 pair", index + 1 < text.length && Character.isLowSurrogate(text[index + 1]))
                index += 2
            } else {
                assertFalse("An unpaired low surrogate was emitted", Character.isLowSurrogate(char))
                index++
            }
        }
    }

    private fun metricsJson(metrics: InferenceMetrics) = buildJsonObject {
        put("time_to_first_visible_token_seconds", metrics.timeToFirstTokenSeconds)
        put("prefill_tokens", metrics.prefillTokens)
        put("decode_tokens", metrics.decodeTokens)
        put("prefill_tokens_per_second", metrics.prefillTokensPerSecond)
        put("decode_tokens_per_second", metrics.decodeTokensPerSecond)
    }

    private fun errorJson(error: Throwable) = buildJsonObject {
        put("class", error.javaClass.name)
        put("message", error.message.orEmpty().replace(Regex("https?://\\S+"), "[url]").replace(context.filesDir.path, "[app-files]").take(3000))
        put("stack", error.stackTrace.take(24).joinToString("\n"))
        put("cause_class", error.cause?.javaClass?.name)
    }
}
