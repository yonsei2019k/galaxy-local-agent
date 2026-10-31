package com.user.localagent

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.BenchmarkRunner
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.inference.LlamaCppEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit owner-requested model replacement; never reads private conversations or sends messages. */
@RunWith(AndroidJUnit4::class)
class GemmaReplacementValidation {
    @Test fun installAndValidate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Owner-requested replacement only", args.getString("gemmaReplacementApproved") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val id = args.getString("modelId") ?: error("modelId required")
        val expected = when (id) {
            "gemma-e2b" -> "0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b"
            "gemma-e4b" -> "d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96"
            else -> error("Only requested Gemma artifacts are supported")
        }
        fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Gemma replacement: $id $text\n") })
        app.cancelIdleUnload()
        app.updates.setBusy(true)
        var benchmarkOwned = false
        var inferenceOwned = false
        try {
            check(!RuntimeGate.inference.get() && !RuntimeGate.benchmark.get())
            app.engine.unload()
            val backup = File(context.getExternalFilesDir(null), "model-replacement-backup")
            check(backup.isDirectory || backup.mkdirs())
            val index = File(context.filesDir, "models/installed.json")
            val prior = File(backup, "installed-before-$id.json")
            if (index.isFile && !prior.exists()) index.copyTo(prior)
            status("refresh")
            app.models.refresh()
            if (id == "gemma-e2b") {
                assertTrue("Original multimodal E2B must remain available", app.models.models.value.any { it.id == "gemma-e2b-media" && it.runtime == "litertlm" && it.verified && File(it.path).isFile })
            }
            val candidate = app.models.candidates.single { it.id == id }
            val descriptor = app.models.resolve(candidate)
            assertEquals(expected, descriptor.sha256)
            assertEquals("gguf", descriptor.runtime)
            val directory = File(context.filesDir, "models")
            val destination = File(directory, "$id-${expected.take(12)}.gguf")
            val staged = File(context.getExternalFilesDir(null), "model-replacement/$id.gguf")
            if (!destination.isFile && staged.isFile && staged.length() == descriptor.sizeBytes) {
                val partial = File(destination.path + ".part")
                status("copy_verified_external_artifact")
                staged.inputStream().use { input -> partial.outputStream().use { output -> input.copyTo(output, 1024 * 1024); output.fd.sync() } }
                // ModelManager hashes the whole partial before it atomically registers the replacement.
            }
            status("verify_and_register")
            var lastPercent = -10
            val progress = launch(Dispatchers.IO) {
                app.models.progress.collect { value ->
                    if (value != null && value.total > 0) {
                        val percent = (value.bytes * 100 / value.total).toInt()
                        if (percent >= lastPercent + 10) { lastPercent = percent; status("${value.phase} $percent%") }
                    }
                }
            }
            val model = try { withTimeout(30 * 60_000L) { app.models.download(descriptor) } }
                finally { withContext(NonCancellable) { progress.cancelAndJoin() } }
            assertEquals(expected, model.sha256)
            assertEquals("gguf", model.runtime)
            app.models.select(id)
            assertEquals(expected, app.models.selectedModel.value?.sha256)
            if (staged.isFile) check(staged.delete())
            status("cpu_smoke")
            val report = BenchmarkRunner(context, app.engine).run(model.path, InferenceOptions(maxOutputTokens = 48)) { status(it) }
            val result = Json.parseToJsonElement(report.readText()).jsonObject
            assertEquals("completed", result["stage"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, result["failure"])
            assertTrue(result.getValue("samples").jsonArray.isNotEmpty())
            benchmarkOwned = RuntimeGate.benchmark.compareAndSet(false, true)
            check(benchmarkOwned)
            inferenceOwned = RuntimeGate.inference.compareAndSet(false, true)
            check(inferenceOwned)
            val engine = LlamaCppEngine(context)
            try {
                status("native_system_history_and_tokenizer")
                engine.load(model.path, InferenceOptions(maxOutputTokens = 64))
                val system = "You are ZipHap.Agent. Answer the synthetic user's question briefly in Korean."
                val history = listOf(InferenceTurn("user", "가상 일정은 오후 3시에 독서입니다."), InferenceTurn("assistant", "네, 가상 독서 일정은 오후 3시입니다."))
                val prompt = "가상 독서 일정이 몇 시인가요? 한 문장으로 답해주세요."
                val count = engine.countChatTokens(prompt, system, history)
                assertTrue(count > 0)
                val answer = StringBuilder()
                withTimeout(240_000) { engine.generateChat(prompt, system, history).collect { answer.append(it) } }
                assertTrue(answer.isNotBlank())
                assertEquals(count, engine.lastMetrics.value?.prefillTokens)
                val qa = buildJsonObject {
                    put("model_id", id); put("sha256", expected); put("input_tokens", count)
                    put("synthetic_prompt", prompt); put("synthetic_response", answer.toString())
                    put("multimodal_model_preserved", app.models.models.value.any { it.id == "gemma-e2b-media" && it.verified })
                }
                File(context.getExternalFilesDir(null), "benchmarks/$id-aggressive-qa.json").writeText(qa.toString())
                // Check explicit cancellation after the first visible token, without affecting the caller.
                status("native_cancellation")
                engine.load(model.path, InferenceOptions(maxOutputTokens = 1024))
                val visible = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Boolean>()
                val generation = launch(Dispatchers.IO) {
                    try {
                        engine.generateChat("1부터 1000까지 번호를 붙여 짧은 한국어 문장을 나열하세요.", system).collect { if (it.isNotBlank()) visible.complete(Unit) }
                        cancelled.complete(false)
                    } catch (_: CancellationException) { cancelled.complete(true) }
                }
                try {
                    withTimeout(180_000) { visible.await() }
                    engine.cancel()
                    withTimeout(20_000) { generation.join() }
                    assertTrue(cancelled.await())
                    currentCoroutineContext().ensureActive()
                } finally { engine.cancel(); withContext(NonCancellable) { generation.cancelAndJoin() } }
            } finally { withContext(NonCancellable) { engine.unload() } }
            status("passed")
        } finally {
            withContext(NonCancellable) { app.engine.unload() }
            if (inferenceOwned) RuntimeGate.inference.set(false)
            if (benchmarkOwned) RuntimeGate.benchmark.set(false)
            app.updates.setBusy(false)
        }
    }

    @Test fun selectReplacementDefault() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gemmaReplacementApproved") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        val e2b = app.models.models.value.single { it.id == "gemma-e2b" }
        assertTrue(app.models.models.value.any { it.id == "gemma-e4b" && it.verified && it.runtime == "gguf" && it.sha256 == "d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96" })
        assertEquals("gguf", e2b.runtime)
        assertEquals("0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b", e2b.sha256)
        app.models.select(e2b.id)
        check(context.getSharedPreferences("chat-preferences", android.content.Context.MODE_PRIVATE).edit().putString("model", "gemma-e2b").commit())
        assertEquals(e2b.id, app.models.selectedModel.value?.id)
    }
}
