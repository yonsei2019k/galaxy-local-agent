package com.user.localagent

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly invoked trusted-channel OTA verification; never reads notes/messages/providers. */
@RunWith(AndroidJUnit4::class)
class UpdateValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (context.applicationContext as LocalAgentApplication).container
    private val builtinTools = setOf("save_note", "update_note", "create_reminder", "update_reminder")

    private fun approved(): Long {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit updateValidationApproved=true is required", args.getString("updateValidationApproved") == "true")
        return args.getString("expectedVersionCode", "1002").toLong().also { require(it > 0) }
    }
    private fun installedVersion(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private fun reportFile(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks/update-validation.json")
    private fun checkpoint(values: JsonObject, stage: String) {
        val output = reportFile()
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        val report = JsonObject(values.toMutableMap().apply {
            this["stage"] = JsonPrimitive(stage)
            this["timestamp"] = JsonPrimitive(Instant.now().toString())
        })
        val temporary = File(output.path + ".tmp")
        temporary.writeText(report.toString())
        check(temporary.renameTo(output))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Trusted update QA: $stage\n") })
    }

    @Test fun requestTrustedSelfUpdate() = runBlocking {
        val expected = approved()
        val current = installedVersion()
        assertTrue("The installed app must precede the expected trusted release", current < expected)
        assertFalse("Finish inference before OTA verification", RuntimeGate.inference.get())
        assertFalse("Finish benchmarks before OTA verification", RuntimeGate.benchmark.get())
        val oldHash = app.hostCodeHash
        val grants = app.repository.listGrants().filter { it.extensionId == "builtin" && it.codeHash == oldHash && it.scope == "default" }
        val before = buildJsonObject {
            put("expected_version_code", expected)
            put("old_version_code", current)
            put("old_host_code_hash", oldHash)
            put("old_builtin_default_grants", buildJsonObject { builtinTools.forEach { name -> put(name, grants.any { it.toolName == name }) } })
            put("request_returned", false)
            put("install_completion_asserted", false)
        }
        var recorded = before
        // This durable checkpoint precedes the updater invocation, which can replace/kill us.
        checkpoint(recorded, "requesting_trusted_self_update")
        try {
            val message = app.updates.checkAndApply(install = true)
            recorded = JsonObject(recorded.toMutableMap().apply {
                this["request_returned"] = JsonPrimitive(true)
                this["request_status"] = JsonPrimitive(message.take(500))
                this["updater_version_code"] = app.updates.state.value.version?.let(::JsonPrimitive) ?: JsonNull
            })
            checkpoint(recorded, "trusted_updater_invocation_returned")
        } catch (error: Throwable) {
            recorded = JsonObject(recorded.toMutableMap().apply { this["request_failure_class"] = JsonPrimitive(error.javaClass.name) })
            throw error
        } finally {
            // Best effort only: PackageInstaller can terminate instrumentation before finally.
            withContext(NonCancellable + Dispatchers.IO) { checkpoint(recorded, "trusted_update_request_checkpoint") }
        }
    }

    @Test fun verifyInstalledTrustedUpdate() = runBlocking {
        val expected = approved()
        val output = reportFile()
        val previous = if (output.isFile) {
            require(output.length() <= 128 * 1024)
            Json.parseToJsonElement(output.readText()).jsonObject
        } else error("Run requestTrustedSelfUpdate before post-install verification")
        val current = installedVersion()
        val currentHash = app.hostCodeHash
        val oldHash = previous["old_host_code_hash"]?.jsonPrimitive?.content ?: error("Missing pre-update host hash checkpoint")
        val trust = context.assets.open("update-trust.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val pinnedCertificate = trust.getValue("apkCertificateSha256").jsonPrimitive.content
        val signingInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val certificates = signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val signingMatches = certificates.size == 1 && certificates.single().equals(pinnedCertificate, true)
        val grants = app.repository.listGrants().filter { it.extensionId == "builtin" && it.codeHash == currentHash && it.scope == "default" }
        val carried = builtinTools.associateWith { name -> grants.any { it.toolName == name } }
        val oldGrantsPresent = previous["old_builtin_default_grants"]?.jsonObject?.let { values ->
            builtinTools.all { values[it]?.jsonPrimitive?.booleanOrNull == true }
        } == true
        var recorded = JsonObject(previous.toMutableMap().apply {
            this["installed_version_code"] = JsonPrimitive(current)
            this["new_host_code_hash"] = JsonPrimitive(currentHash)
            this["version_at_least_expected"] = JsonPrimitive(current >= expected)
            this["signing_certificate_matches_pinned_trust"] = JsonPrimitive(signingMatches)
            this["host_code_hash_changed"] = JsonPrimitive(currentHash != oldHash)
            this["old_builtin_grants_checkpoint_complete"] = JsonPrimitive(oldGrantsPresent)
            this["new_builtin_default_grants"] = buildJsonObject { carried.forEach { (name, present) -> put(name, present) } }
        })
        checkpoint(recorded, "verifying_installed_trusted_update")
        try {
            assertTrue("Installed package version is behind the expected release", current >= expected)
            assertTrue("Installed signing certificate must match the app's pinned public trust asset", signingMatches)
            assertTrue("An updated APK must have a new host code hash", currentHash != oldHash)
            assertTrue("Pre-update checkpoint must prove the four original builtin grants", oldGrantsPresent)
            assertTrue("All four builtin default scopes must be carried to the new APK hash", carried.values.all { it })
            assertFalse(RuntimeGate.inference.get())
            assertFalse(RuntimeGate.benchmark.get())
            val message = app.updates.checkAndApply(install = false)
            val latest = message.contains("최신 버전") && app.updates.state.value.version == current
            recorded = JsonObject(recorded.toMutableMap().apply {
                this["check_only_status"] = JsonPrimitive(message.take(500))
                this["latest_release_confirmed"] = JsonPrimitive(latest)
            })
            assertTrue("The trusted channel should report this release as latest", latest)
            checkpoint(recorded, "passed")
        } catch (error: Throwable) {
            recorded = JsonObject(recorded.toMutableMap().apply { this["verification_failure_class"] = JsonPrimitive(error.javaClass.name) })
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                checkpoint(recorded, if (recorded["verification_failure_class"] == null) "installed_update_verified" else "installed_update_verification_failed")
            }
        }
    }
}
