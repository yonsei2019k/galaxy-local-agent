package com.user.localagent

import android.Manifest
import android.content.pm.PackageManager
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
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in APK-permission-denied verification. Never changes Wi-Fi, mobile data or ADB. */
@RunWith(AndroidJUnit4::class)
class OfflineValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (context.applicationContext as LocalAgentApplication).container

    @Test fun cachedModelAndEncryptedMemoryWithoutInternet() = runBlocking {
        assumeTrue("Explicit offlineValidationApproved=true is required",
            InstrumentationRegistry.getArguments().getString("offlineValidationApproved") == "true")
        val token = "qa-offline-${UUID.randomUUID()}"
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val reportFile = File(directory, "offline-validation.json")
        val started = SystemClock.elapsedRealtime()
        var stage = "starting"
        var ownsBenchmark = false
        var ownsInference = false
        var failure: Throwable? = null
        var noteId: String? = null
        var internetDenied: Boolean? = null
        var noteRoundTrip = false
        var noteDeleted = false
        var encryptedHeaderObserved = false
        var modelSha: String? = null
        var answer = ""
        var measured: InferenceMetrics? = null
        val prompt = "안녕하세요. 당신이 도와주는 일을 한국어 한 문장으로 소개해주세요."
        var policy = ""

        fun releaseGates() {
            if (ownsInference) { ownsInference = false; RuntimeGate.inference.set(false) }
            if (ownsBenchmark) { ownsBenchmark = false; RuntimeGate.benchmark.set(false) }
        }
        fun save(next: String? = null) {
            if (next != null) {
                stage = next
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Offline QA: $stage\n") })
            }
            check(directory.isDirectory || directory.mkdirs())
            val report = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("internet_permission_denied", internetDenied)
                put("device_connectivity_modified", false)
                put("network_resolve_or_download_called", false)
                put("model_id", "gemma-e2b")
                put("model_sha256", modelSha)
                put("backend", "CPU")
                put("context_tokens", 4096)
                put("max_output_tokens", 128)
                put("thinking", false)
                put("synthetic_inputs_only", true)
                put("synthetic_user_prompt", prompt)
                put("system_instruction", policy)
                put("response", answer)
                put("encrypted_note_roundtrip", noteRoundTrip)
                put("own_note_deleted", noteDeleted)
                put("non_plaintext_sqlite_header_observed", encryptedHeaderObserved)
                put("metrics", measured?.let { value -> buildJsonObject {
                    put("time_to_first_visible_token_seconds", value.timeToFirstTokenSeconds)
                    put("prefill_tokens", value.prefillTokens)
                    put("decode_tokens", value.decodeTokens)
                    put("prefill_tokens_per_second", value.prefillTokensPerSecond)
                    put("decode_tokens_per_second", value.decodeTokensPerSecond)
                } } ?: JsonNull)
                put("failure", failure?.let { error -> buildJsonObject {
                    put("class", error.javaClass.name)
                    put("message", error.message.orEmpty().replace(Regex("https?://\\S+"), "[url]").replace(context.filesDir.path, "[app-files]").take(3000))
                    put("stack", error.stackTrace.take(24).joinToString("\n"))
                    put("cause_class", error.cause?.javaClass?.name)
                } } ?: JsonNull)
            }.toString()
            val temporary = File(reportFile.path + ".tmp")
            temporary.writeText(report)
            check(temporary.renameTo(reportFile)) { "Unable to finalize offline verification report" }
        }

        try {
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "Another benchmark is running" }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "Another inference request is running" }
            internetDenied = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED
            save("assert_apk_internet_permission_denied")
            assertEquals("Install the offlineVerification APK; disabling device Wi-Fi alone is insufficient", true, internetDenied)

            save("encrypted_own_note_roundtrip")
            val body = "$token 합성 오프라인 메모 — 한글 !%_\\ 검증"
            val note = app.repository.saveNote("합성 오프라인 검증", body, "__qa_offline__")
            noteId = note.id
            assertEquals(body, app.repository.getNote(note.id)?.body)
            assertTrue(app.repository.searchNotes(token).any { it.id == note.id && it.body == body })
            noteRoundTrip = true
            val database = context.getDatabasePath("local-agent.db")
            val header = ByteArray(16)
            val headerRead = database.inputStream().use { it.read(header) }
            encryptedHeaderObserved = headerRead == 16 && !header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            assertTrue("SQLCipher storage must not expose a plaintext SQLite header", encryptedHeaderObserved)
            app.repository.deleteNote(note.id)
            assertNull(app.repository.getNote(note.id))
            noteDeleted = true

            save("verify_cached_e2b_integrity_without_network")
            app.models.refresh() // App-private index/hash only; never resolve or download.
            val model = app.models.models.value.singleOrNull { it.id == "gemma-e2b" && it.runtime == "litertlm" && it.verified }
                ?: error("The verified E2B artifact must already be cached before installing the offline APK")
            require(model.sha256.matches(Regex("[a-f0-9]{64}")))
            val file = File(model.path).canonicalFile
            require(file.parentFile == File(context.filesDir, "models").canonicalFile && file.isFile)
            modelSha = model.sha256
            save("load_cached_cpu_model")
            app.engine.unload()
            withTimeout(180_000L) {
                app.engine.load(file.path, InferenceOptions(contextSize = 4096, maxOutputTokens = 128, backend = "CPU", thinking = false))
            }
            policy = AssistantPolicy.build(emptySet(), ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())
            save("generate_synthetic_greeting_without_internet")
            val output = StringBuilder()
            withTimeout(180_000L) {
                app.engine.generateChat(prompt, policy, emptyList()).collect { output.append(it) }
            }
            answer = output.toString()
            measured = app.engine.lastMetrics.value
            save("recorded_synthetic_response")
            assertTrue("Cached local inference must produce visible output with INTERNET denied", answer.isNotBlank())
            val metrics = measured ?: error("Native measured metrics are required")
            assertTrue(metrics.prefillTokens > 0 && metrics.decodeTokens > 0)
            assertTrue(metrics.timeToFirstTokenSeconds.isFinite() && metrics.timeToFirstTokenSeconds > 0)
            assertTrue(metrics.prefillTokensPerSecond.isFinite() && metrics.prefillTokensPerSecond > 0)
            assertTrue(metrics.decodeTokensPerSecond.isFinite() && metrics.decodeTokensPerSecond > 0)
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
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
                    val cleanupErrors = mutableListOf<Throwable>()
                    noteId?.let { id ->
                        runCatching { app.repository.deleteNote(id); noteDeleted = app.repository.getNote(id) == null }
                            .exceptionOrNull()?.let(cleanupErrors::add)
                    }
                    if (ownsInference) runCatching { app.engine.unload() }.exceptionOrNull()?.let(cleanupErrors::add)
                    releaseGates()
                    cleanupErrors.forEach { cleanupError ->
                        if (failure != null) failure!!.addSuppressed(cleanupError) else failure = cleanupError
                    }
                    save()
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Offline verification saved: ${reportFile.name}\n") })
                    if (stage == "passed" && failure != null) throw failure!!
                }
            } finally { releaseGates() }
        }
    }
}
