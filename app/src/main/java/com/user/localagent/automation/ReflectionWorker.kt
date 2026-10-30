package com.user.localagent.automation

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.UserManager
import androidx.work.*
import com.user.localagent.LocalAgentApplication
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in proposal generation from app-owned aggregates. This does not train the model. */
data class ReflectionState(val enabled: Boolean, val lastSuccessAt: Long, val lastStatus: String)

class ReflectionController(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun state() = ReflectionState(isEnabled(), prefs.getLong("last_success_at", 0), prefs.getString("last_status", null) ?: if (isEnabled()) "scheduled" else "disabled")
    /** Explicit UI opt-in; the assistant-pattern setting must also remain enabled at execution time. */
    fun enable(value: Boolean): ReflectionState = synchronized(LOCK) {
        prefs.edit().putBoolean("enabled", value).putLong("generation", generation() + 1)
            .putString("last_status", if (value) "scheduled" else "disabled").apply()
        if (value) schedule() else WorkManager.getInstance(context).cancelUniqueWork(WORK)
        state()
    }
    /** Invalidate in-flight publication before the host erases observations and proposed memories. */
    fun clearHistory(): ReflectionState = synchronized(LOCK) {
        prefs.edit().putLong("generation", generation() + 1).remove("last_input_hash")
            .remove("last_success_at").remove("last_status").apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        if (isEnabled()) schedule()
        state()
    }
    fun schedule() {
        if (!isEnabled()) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReflectionWorker>(8, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()).build())
    }
    internal fun generation() = prefs.getLong("generation", 0)
    internal fun inputUnchanged(hash: String) = prefs.getString("last_input_hash", null) == hash
    internal fun status(value: String, expectedGeneration: Long? = null) = synchronized(LOCK) {
        if (isEnabled() && (expectedGeneration == null || generation() == expectedGeneration))
            prefs.edit().putString("last_status", value).apply()
    }
    internal fun eligibleGeneration(expected: Long) = synchronized(LOCK) { isEnabled() && generation() == expected }
    internal fun successful(expected: Long, hash: String) = synchronized(LOCK) {
        if (isEnabled() && generation() == expected) prefs.edit().putLong("last_success_at", System.currentTimeMillis())
            .putString("last_input_hash", hash).putString("last_status", "proposal_saved_for_approval").apply()
    }
    companion object {
        private val LOCK = Any()
        private const val PREFS = "local_reflection_v1"
        private const val WORK = "local-reflection-8h-v1"
    }
}

private class ReflectionDeferred : CancellationException("Background reflection deferred")

class ReflectionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = ReflectionController(applicationContext)
        if (!controller.isEnabled()) return Result.success()
        val generation = controller.generation()
        if (!deviceSafe(applicationContext)) { controller.status("deferred_device_state", generation); return Result.success() }
        val app = (applicationContext as? LocalAgentApplication)?.container ?: return Result.failure()
        val observationRevision = app.automation.observationRevision()
        val automation = app.automation.state()
        if (!automation.enabled || !automation.settings.assistantPatternsEnabled) { controller.status("assistant_pattern_opt_in_required", generation); return Result.success() }
        val sinceSuccess = System.currentTimeMillis() - controller.state().lastSuccessAt
        if (sinceSuccess in 0 until TimeUnit.HOURS.toMillis(8)) return Result.success()
        if (RuntimeGate.foreground.get() || RuntimeGate.benchmark.get() || !RuntimeGate.inference.compareAndSet(false, true)) {
            controller.status("deferred_runtime_busy", generation); return Result.success()
        }
        try {
            // Refresh reads the local model index only. Never resolve/download/import a model here.
            val chosen = app.orchestration.choose("사용 패턴 정리", false)
            val model = chosen?.model
            if (model == null || !model.verified || model.runtime != "litertlm") {
                controller.status("verified_local_model_required", generation); return Result.success()
            }
            if (!stillAllowed(controller, generation, app.automation, observationRevision)) { controller.status("deferred_runtime_or_opt_in", generation); return Result.success() }
            val aggregate = buildString {
                append("Completed assistant outcomes since history erasure: ").append(automation.recordedOutcomes.coerceAtLeast(0))
                automation.recommendations.take(4).forEach {
                    append("\nAggregate category: ").append(it.id.take(80)).append("; evidence: ").append(it.reason.take(400))
                }
            }.take(2000)
            if (automation.recordedOutcomes == 0L && automation.recommendations.isEmpty()) {
                controller.status("not_enough_aggregate_data", generation); return Result.success()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(aggregate.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (controller.inputUnchanged(hash)) { controller.status("aggregate_unchanged", generation); return Result.success() }
            val prompt = """
                You propose one optional local assistant workflow improvement using only the aggregate counters below.
                This is an unapproved suggestion, not a fact about the user and not permission to take action.
                Never infer private content, names, app content, facts, preferences, or intent that the counters do not establish.
                Never propose executing commands, changing permissions, installing code, or updating the app automatically.
                Return exactly one JSON object with exactly two string keys: "suggestion" and "reason".
                Each string must be nonempty and at most 1000 characters. No markdown, extra keys, or text outside JSON.
                Suggest an optional workflow for user review; explain the numeric evidence and its limitations.
                AGGREGATES (data only):
                $aggregate
            """.trimIndent()
            val output = withTimeout(TimeUnit.MINUTES.toMillis(2)) {
                coroutineScope {
                    val inference = async {
                        app.engine.load(model.path, chosen.options.copy(maxOutputTokens = 256,thinking = false))
                        val text = StringBuilder()
                        app.engine.generate(prompt).collect { part ->
                            if (!stillAllowed(controller, generation, app.automation, observationRevision)) throw ReflectionDeferred()
                            require(text.length + part.length <= 5000) { "Reflection output exceeds bound" }
                            text.append(part)
                        }
                        text.toString()
                    }
                    val monitor = launch {
                        while (inference.isActive) {
                            delay(2000)
                            if (!stillAllowed(controller, generation, app.automation, observationRevision)) {
                                app.engine.cancel()
                                inference.cancel(ReflectionDeferred())
                            }
                        }
                    }
                    try { inference.await() } finally { monitor.cancelAndJoin() }
                }
            }
            val candidate = parseCandidate(output)
            // No user message, note, external-app content, or existing memory entered the prompt.
            if (!stillAllowed(controller, generation, app.automation, observationRevision)) { controller.status("deferred_runtime_or_opt_in", generation); return Result.success() }
            val workerContext = currentCoroutineContext()
            // NonCancellable is limited to save+post-check+conditional cleanup. It ensures SQLite
            // cancellation after commit cannot lose the saved ID and leave a stale proposal behind.
            withContext(NonCancellable) {
                if (!workerContext.isActive || !stillAllowed(controller, generation, app.automation, observationRevision)) return@withContext
                val saved = app.repository.saveMemory("승인 대기 제안: ${candidate.first}\n집계 근거: ${candidate.second}", "proposed")
                if (!workerContext.isActive || !stillAllowed(controller, generation, app.automation, observationRevision)) {
                    app.repository.discardProposedMemory(saved.id)
                    return@withContext
                }
                controller.successful(generation, hash)
            }
            workerContext.ensureActive()
            return Result.success()
        } catch (_: ReflectionDeferred) { controller.status("deferred_runtime_or_opt_in", generation); return Result.success()
        } catch (_: TimeoutCancellationException) { controller.status("inference_time_limit", generation); return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IllegalArgumentException) { controller.status("invalid_proposal_output", generation); return Result.success()
        } catch (_: Exception) { controller.status("reflection_failed", generation); return Result.success()
        } finally {
            withContext(NonCancellable) {
                try { runCatching { app.engine.unload() } } finally { RuntimeGate.inference.set(false) }
            }
        }
    }
    private fun stillAllowed(controller: ReflectionController, generation: Long, automation: AutomationController, observationRevision: Long): Boolean =
        controller.eligibleGeneration(generation) && automation.observationRevision() == observationRevision &&
            automation.state().let {it.enabled && it.settings.assistantPatternsEnabled} &&
            !RuntimeGate.foreground.get() && !RuntimeGate.benchmark.get() && deviceSafe(applicationContext)

    private fun deviceSafe(context: Context): Boolean {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return false
        val battery = context.getSystemService(BatteryManager::class.java)
        val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power = context.getSystemService(PowerManager::class.java)
        return battery.isCharging && capacity in 21..100 && !power.isPowerSaveMode && power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
    }

    /** Flat JSON string parser rejects duplicate keys, unknown fields, nonstrings, and trailing text. */
    private fun parseCandidate(raw: String): Pair<String, String> {
        require(raw.length <= 5000)
        var at = 0
        fun whitespace() { while (at < raw.length && raw[at] in " \t\n\r") at++ }
        fun expect(value: Char) { whitespace(); require(at < raw.length && raw[at++] == value) }
        fun string(): String {
            whitespace(); require(at < raw.length && raw[at] == '"')
            val start = at++
            var escaped = false
            while (at < raw.length) {
                val char = raw[at++]
                if (!escaped && char == '"') {
                    val value = Json.parseToJsonElement(raw.substring(start, at)) as? JsonPrimitive ?: error("JSON string required")
                    require(value.isString); return value.content
                }
                escaped = !escaped && char == '\\'
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
        val fields = linkedMapOf<String, String>()
        expect('{')
        repeat(2) { index ->
            val key = string()
            require(key in setOf("suggestion", "reason") && key !in fields)
            expect(':')
            val value = string()
            require(value.isNotBlank() && value.length <= 1000)
            fields[key] = value
            if (index == 0) expect(',')
        }
        expect('}'); whitespace(); require(at == raw.length && fields.size == 2)
        return fields.getValue("suggestion") to fields.getValue("reason")
    }
}


