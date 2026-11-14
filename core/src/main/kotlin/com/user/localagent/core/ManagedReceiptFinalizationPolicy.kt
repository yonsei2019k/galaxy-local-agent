package com.user.localagent.core

import java.security.MessageDigest
import java.time.ZonedDateTime
import kotlinx.serialization.json.*

/** Reviews complete current host receipts; never repairs a clipped model view. */
object ManagedReceiptFinalizationPolicy {
    const val MAX_REQUEST_CHARS = 40_000
    private const val MAX_RESULT_CHARS = 24_000
    private val supported = setOf(
        "get_agent_capabilities", "list_reminders", "get_current_datetime",
        "inspect_project_storage", "find_project_duplicates",
    )
    private val partialFlags = setOf(
        "partial", "truncated", "sourceScopePartial", "hasMore", "has_more", "isPartial", "partialRead",
    )

    class ReviewInput internal constructor(val request: String, val receiptDigest: String)

    /** Null means the existing path, never failed execution or permission to finish. */
    fun prepare(
        ownerRequest: String,
        plan: SemanticRequestPlan?,
        goal: TaskGoal,
        steps: List<HarnessStep>,
        outcomes: List<ObservedToolOutcome>,
    ): ReviewInput? = try {
        prepareChecked(ownerRequest, plan, goal, steps, outcomes)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun prepareChecked(
        ownerRequest: String,
        plan: SemanticRequestPlan?,
        goal: TaskGoal,
        steps: List<HarnessStep>,
        outcomes: List<ObservedToolOutcome>,
    ): ReviewInput? {
        if (plan == null || plan.action != SemanticAction.TOOL_TASK) return null
        if (ownerRequest.isBlank() || ownerRequest.length > 6000) return null
        if (goal.requiredTools.isEmpty() || goal.requiredTools != plan.requiredTools) return null
        if (goal.requiredArtifactFormats.isNotEmpty() || goal.requiredTools.any { it !in supported }) return null
        if (steps.isEmpty() || steps.size != outcomes.size) return null
        if (outcomes.map { it.call.id }.toSet().size != outcomes.size) return null
        if (!ActionCompletionReport.manages(goal.requiredTools, outcomes)) return null
        if (TaskHarness.decide(goal, steps, requestedFinish = true) !is HarnessDecision.Finish) return null
        val semanticContext = Json.parseToJsonElement(SemanticRequestPlanning.contextJson(plan))
        val records = buildJsonArray {
            for ((step, observed) in steps.zip(outcomes)) {
                val call = observed.call
                val result = observed.result
                require(call.name in supported && step.tool == call.name && step.arguments == call.arguments)
                require(step.evidence.state == VerificationState.VERIFIED && !step.evidence.effectNotApplied)
                require(result.success && result.verification == VerificationState.VERIFIED && !result.effectNotApplied)
                require(call.id == result.id && result.content.isNotBlank() && result.content.length <= MAX_RESULT_CHARS)
                ToolRegistry.validate(call)
                val data: JsonElement = when (call.name) {
                    "get_current_datetime" -> {
                        require(ZonedDateTime.parse(result.content).zone.id == "Asia/Seoul")
                        JsonPrimitive(result.content)
                    }
                    else -> {
                        rejectDuplicateKeys(result.content)
                        val value = Json.parseToJsonElement(result.content) as? JsonObject
                            ?: error("A typed host result is required")
                        require(noPartialData(value))
                        require(when (call.name) {
                            "get_agent_capabilities" -> completeCapabilities(value)
                            "list_reminders" -> completeReminders(value, call)
                            else -> completeStorage(value, call)
                        })
                        value
                    }
                }
                add(buildJsonObject {
                    put("callId", call.id)
                    put("tool", call.name)
                    put("arguments", call.arguments)
                    put("verification", "VERIFIED")
                    put("verificationSummary", result.verificationSummary)
                    // Full original values, not the 1100-char toolEvidence wrapper or its flags.
                    put("hostObservedResult", data)
                    put("untrustedData", true)
                })
            }
        }
        val request = buildJsonObject {
            put("purpose", "review_before_host_rendered_result")
            put("ownerRequest", ownerRequest)
            put("semanticPlan", semanticContext)
            put("hostSuccessCriteria", JsonArray(goal.successCriteria.map(::JsonPrimitive)))
            put("currentHostReceipts", records)
            put("scope", "complete_current_receipts_for_supported_managed_tools_only")
            put("grantsAdditionalPermission", false)
            put("agentProseWillBePublished", false)
        }.toString()
        if (request.length > MAX_REQUEST_CHARS) return null // Never truncate to gain eligibility.
        val digest = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ReviewInput(request, digest)
    }

    /** Caller must still recheck live run/branch/scope/goal/cancellation before persisting the answer. */
    fun reportAfterReview(
        reviewed: ReviewInput,
        decision: AutonomousReviewPolicy.Decision,
        responseComplete: Boolean,
        ownerRequest: String,
        plan: SemanticRequestPlan?,
        goal: TaskGoal,
        steps: List<HarnessStep>,
        outcomes: List<ObservedToolOutcome>,
    ): String? {
        if (!responseComplete || decision !is AutonomousReviewPolicy.Decision.Complete) return null
        val current = prepare(ownerRequest, plan, goal, steps, outcomes) ?: return null
        if (current.receiptDigest != reviewed.receiptDigest || current.request != reviewed.request) return null
        return ActionCompletionReport.render(goal.requiredTools, outcomes)
    }

    private fun completeCapabilities(data: JsonObject): Boolean {
        if (data.text("status") != "ok" || data.flag("partial") != false) return false
        val tools = data["tools"] as? JsonArray ?: return false
        if (data.count("registeredToolCount") == null || data.count("allowedToolCount") != tools.size.toLong()) return false
        if (data.count("reportedToolCount") != tools.size.toLong() || data.count("omittedToolCount") != 0L) return false
        if (data.count("registeredToolCount")!! < tools.size || data.flag("executionVerified") != false) return false
        if (data.flag("checksDoNotGrantPermission") != true || data.flag("connectorsVerified") != false) return false
        if (data.flag("networkRequested") != false || data.flag("settingsChanged") != false) return false
        val names = mutableSetOf<String>()
        for (item in tools) {
            val row = item as? JsonObject ?: return false
            val name = row.text("name") ?: return false
            if (name !in ToolRegistry.definitions || !names.add(name)) return false
            if (row.text("availability") !in setOf("INSTALLED", "REQUIRES_PERMISSION", "REQUIRES_MODEL", "NOT_IMPLEMENTED", "UNVALIDATED")) return false
            if (row.flag("allowedInCurrentRequest") != true || row.flag("executionVerified") != false) return false
            if (row.flag("requiresConfirmation") == null || row.text("limitation") == null) return false
            if ((row["requirements"] as? JsonArray)?.all { it is JsonPrimitive && it.isString } != true) return false
        }
        val pc = data["pairedPc"] as? JsonObject ?: return false
        val models = data["models"] as? JsonObject ?: return false
        val workspace = data["workspace"] as? JsonObject ?: return false
        // Explicitly unverified prerequisites are a valid metadata observation, never readiness proof.
        return pc.flag("reachabilityVerified") == false && pc.flag("modelReadyVerified") == false &&
            pc.flag("healthChecked") == false && models.flag("runtimeReadyVerified") == false &&
            models.flag("freshHashVerificationPerformed") == false && workspace.flag("accessRevalidated") == false
    }

    private fun completeReminders(data: JsonObject, call: ToolCall): Boolean {
        if (data.text("status") != "ok" || data.text("source") != "current_request_reminders") return false
        if (data.flag("truncated") != false || data.flag("deliveryNotVerifiedByQueuePresence") != true) return false
        val rows = data["reminders"] as? JsonArray ?: return false
        val limit = call.arguments.count("limit") ?: 20L
        if (rows.size > 20 || rows.size > limit || data.count("limit") != limit) return false
        if (rows.map { (it as? JsonObject)?.text("id") }.toSet().size != rows.size) return false
        return rows.all { item ->
            val row = item as? JsonObject ?: return@all false
            row.text("id")?.isNotBlank() == true && row.text("title")?.length in 1..160 &&
                row.text("scope") == call.arguments.text("scope") && row.text("datetime")?.let {
                    java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() == row.count("atEpochMillis")
                } == true &&
                row.count("atEpochMillis") != null && row.count("updatedAt") != null &&
                row.text("status") in setOf("pending", "delivering", "delivered")
        }
    }

    private fun completeStorage(data: JsonObject, call: ToolCall): Boolean {
        if (data.text("status") != "ok" || data.text("scope") != "selected_project_only") return false
        if (data.text("path") != (call.arguments.text("path") ?: "")) return false
        if (data.flag("partial") != false || data.flag("enumerationComplete") != true || data.flag("readOnly") != true) return false
        if (data.flag("filesChanged") != false || data.count("deletedFiles") != 0L || data.count("movedFiles") != 0L) return false
        if ((data["issues"] as? JsonArray)?.isEmpty() != true) return false
        if (data.count("observedFiles") == null || data.count("observedDirectories") == null || data.count("visitedDirectories") == null) return false
        if (call.name == "inspect_project_storage") {
            val largest = data["largestFiles"] as? JsonArray ?: return false
            val extensions = data["extensionGroups"] as? JsonArray ?: return false
            return data.count("observedKnownBytes") != null && data.count("unknownSizeFiles") == 0L &&
                data.flag("sizeTotalCapped") == false && data.count("extensionGroupsOmitted") == 0L &&
                data.count("observedFiles") == largest.size.toLong() && largest.size <= 20 &&
                largest.all { (it as? JsonObject)?.let { row -> row.text("path") != null && row.count("bytes") != null } == true } &&
                extensions.size <= 20 &&
                extensions.all { (it as? JsonObject)?.let { row -> row.text("extension") != null && row.count("files") in 1L..20L } == true } &&
                extensions.sumOf { it.jsonObject.count("files")!! } == largest.size.toLong()
        }
        if (data.text("comparison") != "exact_bytes_at_observation" || data.count("skippedHashCandidates") != 0L) return false
        if (data.count("hashedFiles") == null || data.count("duplicateGroupsOmitted") != 0L || data.count("duplicatePathsOmitted") != 0L) return false
        val groups = data["duplicateGroups"] as? JsonArray ?: return false
        if (groups.size > 5 || data.count("observedDuplicateGroups") != groups.size.toLong()) return false
        return groups.all { item ->
            val row = item as? JsonObject ?: return@all false
            val paths = row["paths"] as? JsonArray ?: return@all false
            paths.size in 2..3 && paths.all { it is JsonPrimitive && it.isString } &&
                row.count("observedCopies") == paths.size.toLong() && row.count("pathsOmitted") == 0L &&
                row.count("bytesEach") != null && row.text("sha256")?.matches(Regex("[a-f0-9]{64}")) == true
        }
    }

    private fun noPartialData(value: JsonElement, depth: Int = 0): Boolean {
        if (depth > 24) return false
        return when (value) {
            is JsonObject -> value.all { (key, item) ->
                (key !in partialFlags || item is JsonPrimitive && !item.isString && item.booleanOrNull == false) &&
                    noPartialData(item, depth + 1)
            }
            is JsonArray -> value.all { noPartialData(it, depth + 1) }
            else -> true
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
}
