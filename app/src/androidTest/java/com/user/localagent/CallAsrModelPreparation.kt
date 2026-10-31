package com.user.localagent

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.models.AsrCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit model preparation only. No ASR/LLM inference, recording access, or selector mutation. */
@RunWith(AndroidJUnit4::class)
class CallAsrModelPreparation {
    @Test fun preparePinnedQwenPairWithoutChangingAsrSelection() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("callAsrDownloadApproved=true required", arguments.getString("callAsrDownloadApproved") == "true")
        val id = when (arguments.getString("asrModel") ?: "17b") {
            "17b", "asr-qwen-17b" -> "asr-qwen-17b"
            "06b", "asr-qwen-06b" -> "asr-qwen-06b"
            else -> error("asrModel must be 17b or 06b")
        }
        val context = instrumentation.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val preferences = context.getSharedPreferences("model-selection", Context.MODE_PRIVATE)
        fun selection() = preferences.getString("asr-id", null) to preferences.getString("asr-language", null)
        val before = selection()
        val started = SystemClock.elapsedRealtime()
        val verified = mutableListOf<JsonObject>()
        var stage = "STARTING"
        var activeArtifact: String? = null
        var failure: String? = null
        var owned = false
        var ready = false
        fun checkpoint() {
            val value = buildJsonObject {
                put("stage", stage); put("model_id", id); put("active_artifact", activeArtifact)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started); put("max_total_ms", 30 * 60_000L)
                put("failure_class", failure); put("asr_ready", ready); put("artifacts", JsonArray(verified))
                put("selection_preserved", selection() == before)
                put("model_download_authorized", true); put("inference_executed", false)
                put("personal_audio_read", false); put("accuracy_validated", false)
            }
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
            check(directory.isDirectory || directory.mkdirs())
            val report = AtomicFile(File(directory, "call-asr-model-preparation.json"))
            val output = report.startWrite()
            try { output.write(value.toString().toByteArray(Charsets.UTF_8)); report.finishWrite(output) }
            catch (error: Throwable) { report.failWrite(output); throw error }
        }
        checkpoint() // Replace any previous success before acquiring resources or requesting bytes.
        try {
            withTimeout(30 * 60_000L) {
                owned = RuntimeGate.benchmark.compareAndSet(false, true)
                check(owned && !RuntimeGate.inference.get() && !app.runs.busy.value && !app.imageStudio.state.value.running) {
                    "Another runtime task is active"
                }
                app.models.refresh()
                for (artifactId in listOf(id, "$id-mmproj")) {
                    activeArtifact = artifactId; stage = "RESOLVING_PINNED_DESCRIPTOR"; checkpoint()
                    val pin = AsrCatalog.descriptors.getValue(artifactId)
                    val actual = app.models.resolve(app.models.candidates.single { it.id == artifactId })
                    assertEquals("Resolver must preserve the reviewed immutable artifact", pin, actual)
                    stage = "DOWNLOADING_OR_VERIFYING_CACHED_ARTIFACT"; checkpoint()
                    val installed = app.models.download(actual)
                    assertEquals(artifactId, installed.id)
                    assertTrue(installed.verified)
                    assertEquals(pin.sha256, installed.sha256)
                    val file = File(installed.path)
                    assertTrue(file.isFile)
                    assertEquals(pin.sizeBytes, file.length())
                    stage = "FULL_INSTALLED_FILE_SHA256"; checkpoint()
                    val hash = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count)
                        }
                    }
                    val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals("Installed bytes must match the complete pinned SHA-256", pin.sha256, digest)
                    verified += buildJsonObject {
                        put("id", artifactId); put("bytes", file.length()); put("sha256", digest)
                        put("commit", pin.revision); put("full_sha256_verified", true)
                    }
                    stage = "ARTIFACT_VERIFIED"; checkpoint()
                }
                app.models.refresh()
                ready = app.models.asrReady(id)
                assertTrue("Both pinned model and encoder must be ready", ready)
                assertEquals("Preparation must preserve the exact global ASR preference", before, selection())
                stage = "PREPARED_NOT_INFERRED"; activeArtifact = null
            }
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName; stage = "FAILED"; throw error
        } finally {
            if (owned) RuntimeGate.benchmark.set(false)
            checkpoint()
            assertEquals("Preparation must never change the selected ASR or language", before, selection())
        }
    }
}
