package com.user.localagent

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.BenchmarkRunner
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (context.applicationContext as LocalAgentApplication).container

    @Test fun unloadDoesNotCancelCaller() = runBlocking {
        app.engine.unload()
        currentCoroutineContext().ensureActive()
        assertTrue("Unloading a model must leave the caller active", currentCoroutineContext().isActive)
    }

    @Test fun encryptedMemoryRoundTrip() = runBlocking {
        val token = "qa-" + UUID.randomUUID()
        val noteIds = mutableListOf<String>()
        val memoryIds = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            InstrumentationRegistry.getArguments().getString("cleanupQaToken")?.let {oldToken->
                require(oldToken.matches(Regex("qa-[a-f0-9-]{36}")))
                app.repository.messages.first().filter {it.role=="user"&&it.text==oldToken}.forEach {app.repository.deleteMessage(it.id)}
            }
            status("data_roundtrip_start")
            val source = app.repository.addMessage("user", "$token source original")
            val memory = app.repository.saveMemory("$token original", "qa", source)
            memoryIds += memory.id
            assertTrue(app.repository.searchMemories("$token original").any { it.id == memory.id })
            val corrected = app.repository.editMemory(memory.id, "$token corrected", "qa")
            assertEquals(source, corrected.sourceMessageId)
            assertEquals("$token corrected", corrected.text)
            assertTrue(app.repository.searchMemories("$token corrected").any { it.id == memory.id && it.text == corrected.text })
            assertFalse(app.repository.searchMemories("$token original").any { it.id == memory.id })
            assertFalse(app.repository.sourceMessages(token).any { it.id == source })
            app.repository.forgetMemory(memory.id)
            assertFalse(app.repository.searchMemories("$token corrected").any { it.id == memory.id })
            assertFalse(app.repository.sourceMessages(token).any { it.id == source })

            // Each SQL LIKE metacharacter is tested against a deliberate near-match decoy.
            val cases = listOf(
                "$token!bang" to "${token}bang",
                "$token%percent" to "${token}XYZpercent",
                "${token}_under" to "${token}Xunder",
                "$token\\slash" to "${token}slash",
                "$token!%_\\combined" to "${token}AXYcombined",
            )
            for ((literal, decoy) in cases) {
                val wantedNote = app.repository.saveNote("QA literal", literal, "__qa__$token")
                noteIds += wantedNote.id
                val otherNote = app.repository.saveNote("QA decoy", decoy, "__qa__$token")
                noteIds += otherNote.id
                val noteResults = app.repository.searchNotes(literal)
                assertTrue("Literal note was not found", noteResults.any { it.id == wantedNote.id })
                assertFalse("LIKE wildcard matched a note decoy", noteResults.any { it.id == otherNote.id })
                val wantedMemory = app.repository.saveMemory(literal, "qa")
                memoryIds += wantedMemory.id
                val otherMemory = app.repository.saveMemory(decoy, "qa")
                memoryIds += otherMemory.id
                val memoryResults = app.repository.searchMemories(literal)
                assertTrue("Literal memory was not found", memoryResults.any { it.id == wantedMemory.id })
                assertFalse("LIKE wildcard matched a memory decoy", memoryResults.any { it.id == otherMemory.id })
            }
            status("data_roundtrip_passed")
        } catch (error: Throwable) {
            failure = error
            status("data_roundtrip_failed", error)
            throw error
        } finally {
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<Throwable>()
                memoryIds.forEach { id -> runCatching { app.repository.forgetMemory(id) }.exceptionOrNull()?.let(cleanupErrors::add) }
                noteIds.forEach { id -> runCatching { app.repository.deleteNote(id) }.exceptionOrNull()?.let(cleanupErrors::add) }
                if (cleanupErrors.isNotEmpty()) {
                    status("data_cleanup_failed", cleanupErrors.first())
                    if (failure != null) cleanupErrors.forEach { failure!!.addSuppressed(it) }
                    else {
                        cleanupErrors.drop(1).forEach { cleanupErrors.first().addSuppressed(it) }
                        throw cleanupErrors.first()
                    }
                }
                status("data_cleanup_complete")
            }
        }
    }

    @Test fun benchmarkE2B() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val runId = UUID.randomUUID().toString().take(8)
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val reportFile = File(directory, "device-validation-${System.currentTimeMillis()}-$runId.json")
        val stages = mutableListOf<JsonElement>()
        val reportLock = Any()
        var stage = "starting"
        var failure: Throwable? = null
        var benchmarkFile: String? = null
        var progressObserver: Job? = null

        fun checkpoint(next: String? = null) {
            synchronized(reportLock) {
                if (next != null) {
                    stage = next
                    stages += buildJsonObject { put("stage", stage); put("elapsed_ms", SystemClock.elapsedRealtime() - started) }
                    status(stage)
                }
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create device validation report directory" }
                val report = buildJsonObject {
                    put("timestamp", Instant.now().toString())
                    put("stage", stage)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    put("benchmark_file", benchmarkFile)
                    put("failure", failure?.let { errorJson(it) } ?: JsonNull)
                    put("stages", JsonArray(stages.toList()))
                    put("inference_gate", RuntimeGate.inference.get())
                    put("benchmark_gate", RuntimeGate.benchmark.get())
                }
                val temporary = File(reportFile.path + ".tmp")
                temporary.writeText(report.toString())
                check(temporary.renameTo(reportFile)) { "Cannot finalize device validation report" }
            }
        }

        try {
            checkpoint("initializing_model_manager")
            val candidateId=InstrumentationRegistry.getArguments().getString("modelId","gemma-e2b")
            val candidate = app.models.candidates.first { it.id == candidateId }
            app.models.refresh()
            checkpoint("resolving_pinned_model_metadata")
            val descriptor=withTimeout(120_000L){app.models.resolve(candidate)}
            checkpoint("model_metadata_resolved")
            val existing = app.models.models.value.find { it.id == candidate.id && it.verified && it.sha256==descriptor.sha256 }
            val model = if (existing != null) {
                checkpoint("cached_verified_metadata_found")
                existing
            } else {
                var lastPercent = -1
                var lastProgress = 0L
                progressObserver = launch(Dispatchers.IO) {
                    app.models.progress.collect { progress ->
                        if (progress != null) {
                            val now = SystemClock.elapsedRealtime()
                            val percent = if (progress.total > 0) (progress.bytes.toDouble() / progress.total * 100).toInt() else 0
                            if (percent >= lastPercent + 5 || now - lastProgress >= 5000L || progress.bytes == progress.total) {
                                lastPercent = percent
                                lastProgress = now
                                checkpoint(if (progress.bytes == progress.total) "download_bytes_complete_hash_verification" else "downloading_${progress.bytes}_of_${progress.total}_bytes")
                            }
                        }
                    }
                }
                checkpoint("downloading_then_verifying_artifact_sha256")
                try {
                    withTimeout(30 * 60_000L) { app.models.download(descriptor) }
                } finally {
                    withContext(NonCancellable) { progressObserver?.cancelAndJoin(); progressObserver = null }
                }.also { checkpoint("download_sha256_verified") }
            }
            checkpoint("verifying_selected_file_sha256")
            withTimeout(180_000L) { app.models.select(model.id) }
            checkpoint("selected_file_sha256_verified")
            val minutes = InstrumentationRegistry.getArguments().getString("enduranceMinutes", "0").toInt()
            val file = BenchmarkRunner(context, app.engine).run(
                model.path,
                InferenceOptions(maxOutputTokens = if (minutes == 0) 128 else 512),
                minutes,
            ) { progress -> checkpoint(progress) }
            benchmarkFile = file.name
            assertTrue(file.length() > 100)
            checkpoint("passed")
        } catch (error: Throwable) {
            failure = error
            stage = if (error is CancellationException) "cancelled" else "failed"
            status(stage, error)
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                progressObserver?.cancelAndJoin()
                try {
                    checkpoint()
                    status("device_report_saved_${reportFile.name}")
                } catch (exportError: Throwable) {
                    status("device_report_export_failed", exportError)
                    if (failure != null) failure!!.addSuppressed(exportError) else throw exportError
                }
            }
        }
    }

    private fun safe(message: String): String = message.replace(Regex("https?://\\S+"), "[url]")
        .replace(context.filesDir.path, "[app-files]").replace(context.cacheDir.path, "[app-cache]").take(4000)

    private fun errorJson(error: Throwable): JsonObject = buildJsonObject {
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

    private fun status(stage: String, error: Throwable? = null) {
        val text = buildString {
            append("QA stage: ").append(stage).append('\n')
            if (error != null) {
                append(error.javaClass.name).append(": ").append(safe(error.message.orEmpty())).append('\n')
                var cause = error.cause
                var count = 0
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                while (cause != null && count++ < 8 && seen.add(cause)) {
                    append("Caused by ").append(cause.javaClass.name).append(": ").append(safe(cause.message.orEmpty())).append('\n')
                    cause = cause.cause
                }
                error.stackTrace.take(32).forEach { append("  at ").append(it).append('\n') }
            }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", text) })
    }
}

