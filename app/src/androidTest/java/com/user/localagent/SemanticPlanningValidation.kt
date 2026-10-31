package com.user.localagent

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.SemanticAction
import com.user.localagent.core.SemanticRequestPlan
import com.user.localagent.core.SemanticSourceKind
import com.user.localagent.core.SemanticTargetType
import com.user.localagent.core.ToolRegistry
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.models.ChosenInference
import com.user.localagent.planning.LocalSemanticPlanner
import com.user.localagent.planning.SemanticPlanningException
import com.user.localagent.planning.SemanticPlanningResult
import com.user.localagent.planning.SemanticPlanningTelemetry
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in semantic evaluation through the actual cached GGUF model. Only synthetic text enters
 * LocalSemanticPlanner; no tool executor, phone record provider or conversation store is queried.
 * Ignored local reports include validated plans for these fixed synthetic cases and measured
 * metadata. They never contain personal conversation history, provider data or raw model output.
 */
@RunWith(AndroidJUnit4::class)
class SemanticPlanningValidation {
    private data class Case(
        val id: String,
        val request: String,
        val history: List<InferenceTurn> = emptyList(),
        val actions: Set<SemanticAction>,
        val source: SemanticSourceKind? = null,
        val targetType: SemanticTargetType? = null,
        val topicGroups: List<Set<String>> = emptyList(),
        val publicQueryGroups: List<Set<String>> = emptyList(),
        val goalGroups: List<Set<String>> = emptyList(),
        val requiredTools: Set<String> = emptySet(),
        val anyContentTools: Set<String> = emptySet(),
        val forbiddenTools: Set<String> = emptySet(),
        val excludedQueryTopics: Set<String> = emptySet(),
        val privateData: Boolean? = null,
        val localContextOnlyForToolTask: Boolean = false,
        val clarificationTopics: Set<String> = emptySet(),
        val requiresTable: Boolean = false,
    )

    @Test
    fun cachedLocalModelUnderstandsRequestsAndConversationCorrections() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("semanticPlanningApproved=true is required", arguments.getString("semanticPlanningApproved") == "true")
        val modelId = arguments.getString("semanticPlanningModelId", "gemma-e2b") ?: "gemma-e2b"
        val supportedModelIds = setOf("gemma-e2b", "gemma-e4b", "qwen-2b", "qwen-4b")
        require(modelId in supportedModelIds) { "Select a cached verified Gemma E2B/E4B or Qwen 2B/4B GGUF text model" }
        val budgetMs = (arguments.getString("semanticPlanningBudgetSeconds")?.toLongOrNull() ?: 900L)
            .coerceIn(60L, 900L) * 1000L
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val allCases = cases()
        val requestedCaseIds = arguments.getString("semanticPlanningCaseIds")?.let { raw ->
            val ids = raw.split(',').map { it.trim() }
            require(ids.isNotEmpty() && ids.none { it.isBlank() }) { "semanticPlanningCaseIds must select a nonempty suite" }
            require(ids.size == ids.toSet().size) { "semanticPlanningCaseIds must not contain duplicate IDs" }
            require(ids.all { id -> allCases.any { it.id == id } }) { "semanticPlanningCaseIds contains an unknown case ID" }
            ids
        }
        val suite = requestedCaseIds?.map { id -> allCases.single { it.id == id } } ?: allCases
        require(suite.isNotEmpty()) { "The semantic evaluation suite cannot be empty" }
        val records = mutableListOf<JsonObject>()
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        val report = File(directory, "semantic-planning-$modelId-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        val started = SystemClock.elapsedRealtime()
        var stage = "starting"
        var ownsBenchmark = false
        var ownsInference = false
        var ownsUpdateBusy = false
        var selectedModelId: String? = null
        var evaluatedModelSha256: String? = null
        var availableGemmaIds = emptyList<String>()
        var availableGgufIds = emptyList<String>()
        var suiteFailure: String? = null
        var primaryFailure: Throwable? = null
        var activeCaseId: String? = null
        var activeCaseStarted = 0L
        var activeTelemetry: SemanticPlanningTelemetry? = null
        val activeProgression = mutableListOf<JsonObject>()
        val visibleAttempts = mutableSetOf<Int>()
        var lastProgressKey: String? = null
        var lastTelemetryCheckpointAt = 0L
        var lastStatusAt = 0L

        fun export(nextStage: String? = null) {
            nextStage?.let { stage = it }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private evaluation report directory" }
            val value = buildJsonObject {
                put("kind", "actual_local_semantic_planning_v1")
                put("timestamp", Instant.now().toString())
                put("model_id", modelId)
                put("selected_model_id", selectedModelId)
                put("model_sha256", evaluatedModelSha256)
                put("available_gguf_ids", JsonArray(availableGgufIds.map(::JsonPrimitive)))
                put("available_gemma_ids", JsonArray(availableGemmaIds.map(::JsonPrimitive)))
                put("runtime", "gguf")
                put("backend", "CPU")
                put("cpu_threads", 4)
                put("context", 4096)
                put("max_output_tokens", 384)
                put("thinking", false)
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("budget_ms", budgetMs)
                put("case_count", suite.size)
                put("available_case_count", allCases.size)
                put("selected_case_ids", JsonArray(suite.map { JsonPrimitive(it.id) }))
                put("completed_cases", records.size)
                put("passed_cases", records.count { it.getValue("pass").jsonPrimitive.boolean })
                put("suite_failure", suiteFailure)
                put("private_sources_read", false)
                put("tools_executed", false)
                put("model_downloaded", false)
                put("cases", JsonArray(records.toList()))
                put("active_case", activeCaseId?.let { id -> buildJsonObject {
                    put("case_id", id)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - activeCaseStarted)
                    telemetryFields(activeTelemetry, activeProgression.toList()).forEach { (key, value) -> put(key, value) }
                } } ?: JsonNull)
                put("limitation", "Synthetic task interpretation with semantic concept checks; no live research, service quote or provider workflow was executed.")
            }
            val temporary = File(report.path + ".tmp")
            temporary.writeText(value.toString())
            check(temporary.renameTo(report)) { "Cannot finalize semantic evaluation report" }
            val now = SystemClock.elapsedRealtime()
            if (lastStatusAt == 0L || now - lastStatusAt >= 1000L) {
                lastStatusAt = now
                val telemetry = activeTelemetry
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Semantic planning: $stage; case=${activeCaseId ?: "none"}; last_stage=${telemetry?.stage ?: "none"}; input_tokens=${telemetry?.inputTokens ?: -1}; visible_chars=${telemetry?.visibleChars ?: 0}; attempt=${telemetry?.attempt ?: 0}; elapsed_ms=${telemetry?.elapsedMs ?: 0}; cases=${records.size}/${suite.size}; report=${report.name}\n")
                })
            }
        }

        fun markUnfinished(code: String) {
            val completed = records.map { it.getValue("case_id").jsonPrimitive.content }.toSet()
            suite.filter { it.id !in completed }.forEach { task ->
                val elapsed = if (task.id == activeCaseId) SystemClock.elapsedRealtime() - activeCaseStarted else 0L
                records += metadata(task.id, null, elapsed, listOf(code),
                    activeTelemetry.takeIf { task.id == activeCaseId },
                    if (task.id == activeCaseId) activeProgression.toList() else emptyList())
            }
        }

        try {
            export("acquiring_runtime")
            ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
            check(ownsBenchmark) { "Another benchmark owns the runtime" }
            ownsInference = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsInference) { "Another inference owns the runtime" }
            app.cancelIdleUnload()
            app.updates.setBusy(true)
            ownsUpdateBusy = true
            withTimeout(budgetMs) {
                export("checking_cached_model")
                app.models.refresh()
                selectedModelId = app.models.selectedModel.value?.id
                val eligible = app.models.models.value.filter {
                    it.id in supportedModelIds && it.runtime == "gguf" && it.verified && File(it.path).isFile
                }
                availableGgufIds = eligible.map { it.id }.sorted()
                availableGemmaIds = availableGgufIds.filter { it in setOf("gemma-e2b", "gemma-e4b") }
                val model = eligible.firstOrNull { it.id == modelId }
                    ?: error("Requested verified cached model is unavailable; this evaluation never downloads models")
                evaluatedModelSha256 = model.sha256
                val chosen = ChosenInference(
                    model,
                    InferenceOptions(contextSize = 4096, thinking = false, backend = "CPU", maxOutputTokens = 384,
                        cpuThreads = 4),
                    "Explicit synthetic semantic evaluation",
                )
                app.engine.unload()
                val planner = LocalSemanticPlanner(app) { observed ->
                    if (activeCaseId != null) {
                        val previous = activeTelemetry
                        val attempt = observed.attempt.takeIf { it > 0 } ?: previous?.attempt ?: 0
                        val measured = observed.copy(
                            inputTokens = observed.inputTokens ?: previous?.inputTokens,
                            visibleChars = if (previous != null && previous.attempt == attempt) maxOf(previous.visibleChars, observed.visibleChars) else observed.visibleChars,
                            attempt = attempt,
                        )
                        activeTelemetry = measured
                        val key = "${measured.attempt}:${measured.stage}"
                        val changedStage = key != lastProgressKey
                        if (changedStage) {
                            activeProgression += telemetryCheckpoint(measured)
                            lastProgressKey = key
                        }
                        val firstVisible = measured.visibleChars > 0 && visibleAttempts.add(measured.attempt)
                        if (firstVisible && measured.stage != "first_delta") {
                            activeProgression += telemetryCheckpoint(measured.copy(stage = "first_delta"))
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (changedStage || firstVisible || now - lastTelemetryCheckpointAt >= 1000L) {
                            lastTelemetryCheckpointAt = now
                            export()
                        }
                    }
                }
                for (task in suite) {
                    currentCoroutineContext().ensureActive()
                    val caseStarted = SystemClock.elapsedRealtime()
                    activeCaseId = task.id
                    activeCaseStarted = caseStarted
                    activeTelemetry = null
                    activeProgression.clear()
                    visibleAttempts.clear()
                    lastProgressKey = null
                    lastTelemetryCheckpointAt = 0L
                    export("planning_${task.id}")
                    var result: SemanticPlanningResult? = null
                    val failures = try {
                        val generated = planner.plan(task.request, task.history, chosen, ToolRegistry.definitions.keys)
                        result = generated
                        validate(task, generated.plan)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (rejected: SemanticPlanningException) {
                        listOf("planner_${rejected.reasonCode}")
                    } catch (_: Exception) {
                        listOf("planner_exception")
                    }
                    records += metadata(task.id, result, SystemClock.elapsedRealtime() - caseStarted, failures,
                        activeTelemetry, activeProgression.toList())
                    activeCaseId = null
                    export("scored_${task.id}")
                }
            }
            export("completed")
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                primaryFailure = timeout
                suiteFailure = "cancelled"
                stage = "cancelled"
                markUnfinished("cancelled_before_completion")
                throw timeout
            }
            suiteFailure = "total_budget_exceeded"
            markUnfinished("total_budget_exceeded")
            stage = "budget_exceeded"
        } catch (cancel: CancellationException) {
            primaryFailure = cancel
            suiteFailure = "cancelled"
            markUnfinished("cancelled_before_completion")
            stage = "cancelled"
            throw cancel
        } catch (_: Exception) {
            suiteFailure = "setup_or_report_failed"
            markUnfinished("setup_or_report_failed")
            stage = "failed"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                var cleanupFailure: Throwable? = null
                try {
                    if (ownsInference) {
                        app.engine.cancel()
                        app.engine.unload()
                    }
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                    suiteFailure = "cleanup_failed"
                    stage = "cleanup_failed"
                } finally {
                    if (ownsInference) RuntimeGate.inference.set(false)
                    if (ownsBenchmark) RuntimeGate.benchmark.set(false)
                    if (ownsUpdateBusy) app.updates.setBusy(false)
                }
                try { export() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
                }
                cleanupFailure?.let { failure ->
                    primaryFailure?.addSuppressed(failure) ?: throw failure
                }
            }
        }
        val failed = records.filterNot { it.getValue("pass").jsonPrimitive.boolean }.map {
            it.getValue("case_id").jsonPrimitive.content + ":" + it.getValue("failure_codes").jsonArray.joinToString(",") { code -> code.jsonPrimitive.content }
        }
        assertTrue("Semantic case results must all be recorded", records.size == suite.size && report.isFile)
        assertTrue("Semantic planning failed cases: ${failed.joinToString("; ")}", failed.isEmpty() && suiteFailure == null)
    }

    private fun metadata(id: String, result: SemanticPlanningResult?, elapsedMs: Long, failures: List<String>,
        telemetry: SemanticPlanningTelemetry? = null, progression: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("case_id", id)
        put("action", result?.plan?.action?.name)
        put("source", result?.plan?.sourceKind?.wireName)
        put("target_type", result?.plan?.targetType?.name)
        put("tools", JsonArray(result?.plan?.requiredTools.orEmpty().sorted().map(::JsonPrimitive)))
        put("elapsed_ms", elapsedMs)
        put("attempts", result?.attempts)
        put("constrained_decoding", result?.constrainedDecoding)
        put("synthetic_plan", result?.plan?.let { plan ->
            Json.parseToJsonElement(com.user.localagent.core.SemanticRequestPlanning.contextJson(plan))
        } ?: JsonNull)
        put("pass", failures.isEmpty())
        put("failure_codes", JsonArray(failures.map(::JsonPrimitive)))
        telemetryFields(telemetry, progression).forEach { (key, value) -> put(key, value) }
    }

    private fun telemetryFields(telemetry: SemanticPlanningTelemetry?, progression: List<JsonObject>): JsonObject = buildJsonObject {
        put("last_stage", telemetry?.stage)
        put("input_tokens", telemetry?.inputTokens)
        put("visible_chars", telemetry?.visibleChars ?: 0)
        put("attempt", telemetry?.attempt ?: 0)
        put("planner_elapsed_ms", telemetry?.elapsedMs)
        put("stage_progression", JsonArray(progression))
    }

    private fun telemetryCheckpoint(telemetry: SemanticPlanningTelemetry): JsonObject = buildJsonObject {
        put("stage", telemetry.stage)
        put("elapsed_ms", telemetry.elapsedMs)
        put("input_tokens", telemetry.inputTokens)
        put("visible_chars", telemetry.visibleChars)
        put("attempt", telemetry.attempt)
    }

    private fun validate(task: Case, plan: SemanticRequestPlan): List<String> = buildList {
        if (plan.action !in task.actions) add("wrong_action")
        if (task.source != null && plan.sourceKind != task.source) add("wrong_source")
        if (task.targetType != null && plan.targetType != task.targetType) add("wrong_target_type")
        if (task.privateData != null && plan.usesPrivateData != task.privateData) add("wrong_privacy_scope")
        if (!plan.requiredTools.containsAll(task.requiredTools)) add("missing_required_tools")
        if (task.anyContentTools.isNotEmpty() && plan.requiredTools.none { it in task.anyContentTools }) add("missing_content_read")
        if (plan.requiredTools.any { it in task.forbiddenTools }) add("forbidden_tool")
        val topic = normalized(plan.target + " " + plan.goal + " " + plan.publicQuery + " " + plan.constraints.joinToString(" "))
        task.topicGroups.forEachIndexed { index, aliases -> if (aliases.none { normalized(it) in topic }) add("missing_topic_$index") }
        val goal = normalized(plan.goal + " " + plan.constraints.joinToString(" "))
        task.goalGroups.forEachIndexed { index, aliases -> if (aliases.none { normalized(it) in goal }) add("lost_goal_condition_$index") }
        val query = normalized(plan.publicQuery)
        if (plan.action == SemanticAction.PUBLIC_RESEARCH) {
            task.publicQueryGroups.forEachIndexed { index, aliases ->
                if (aliases.none { normalized(it) in query }) add("wrong_query_subject_$index")
            }
        }
        if (task.excludedQueryTopics.any { normalized(it) in query }) add("stale_query_topic")
        if (task.requiresTable && !hasTableOutput(plan.goal + " " + plan.constraints.joinToString(" "))) add("lost_table_output")
        if (plan.action == SemanticAction.CLARIFY && task.clarificationTopics.isNotEmpty()) {
            val question = normalized(plan.question)
            if (task.clarificationTopics.none { normalized(it) in question }) add("unrelated_essential_question")
        }
        if (task.localContextOnlyForToolTask && plan.action == SemanticAction.TOOL_TASK) {
            val contextReads = setOf("list_calendar_events", "search_notes", "search_memories")
            if (plan.requiredTools.none { it in contextReads } || plan.requiredTools.any { it !in contextReads + "get_current_datetime" }) {
                add("unsupported_service_quote_tool")
            }
        }
    }

    private fun normalized(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Output intent, not the syllable in unrelated words such as 목표 or 지표. */
    private fun hasTableOutput(text: String): Boolean {
        val compact = normalized(text)
        if (setOf("비교표", "대조표", "요약표", "정리표", "도표")
                .any { normalized(it) in compact }) return true
        if (Regex("(?<![목지발대상투수차])표(?:형식|형태|로정리|로비교|로제시|작성|를작성|를만들)").containsMatchIn(compact)) return true
        if (Regex("(?i)(?<![a-z])(?:table|tabular|matrix)(?![a-z])").containsMatchIn(text)) return true
        return Regex("(?:^|[\\s,:;·/()])표(?=$|[\\s,:;·/()]|로|를|에|와|의|도|만|형태|형식)").containsMatchIn(text)
    }

    private fun cases(): List<Case> {
        val kakao = setOf("카카오", "카톡", "kakao")
        val telegram = setOf("텔레그램", "telegram")
        val research = setOf(SemanticAction.PUBLIC_RESEARCH)
        val publicTools = setOf("search_web", "search_shopping", "read_webpage", "open_url")
        val privateTools = setOf("get_sms_summary", "get_call_log_summary", "search_contacts", "list_messenger_notifications",
            "list_imported_conversations", "search_conversation_imports", "search_conversation_history", "list_calendar_events",
            "list_audio_recordings", "search_audio_transcripts", "search_notes", "search_memories", "list_project_files", "read_project_file",
            "prepare_sms", "prepare_email", "reply_messenger_notification")
        val noTools = ToolRegistry.definitions.keys.toSet()
        val methodRequest = "카카오톡에 직접 연결할 수 있는 방법을 찾아봐"
        val mistakenStatus = "카카오톡 연결 자료를 확인했습니다. 현재 수신 알림 0건이며 대화 내보내기 파일을 자료 연결 화면에서 가져오면 분석할 수 있습니다."
        fun method(id: String, request: String, history: List<InferenceTurn> = emptyList(), topics: List<Set<String>> = listOf(kakao),
            conditions: List<Set<String>> = emptyList(), excluded: Set<String> = emptySet()) = Case(
            id, request, history, research, SemanticSourceKind.NONE, topicGroups = topics, publicQueryGroups = topics, goalGroups = conditions,
            requiredTools = setOf("search_web"), forbiddenTools = privateTools + "search_shopping", excludedQueryTopics = excluded, privateData = false,
        )
        return listOf(
            method("direct_connection_method", methodRequest),
            method("paraphrased_automatic_receipt", "내가 매번 파일을 옮기지 않아도 카톡을 받아보게 만들 수 있을지 알아보고 싶어. 가능한 수단을 확인해줄래?"),
            method("correct_assistant_status", "몇 건인지 묻는 게 아니야. 앱과 바로 붙이는 길을 조사해 달라는 거야",
                listOf(InferenceTurn("user", methodRequest), InferenceTurn("assistant", mistakenStatus))),
            method("exclude_manual_import", "그 방식은 원하지 않아. 사람이 옮기지 않아도 되는 대안을 확인해줘",
                listOf(InferenceTurn("user", "카톡 대화를 이어서 받아보고 싶어"), InferenceTurn("assistant", "내보내기 TXT를 가져오세요.")),
                conditions = listOf(setOf("수동", "자동", "사람이옮기지", "파일없이", "내보내기제외", "manual", "automatic"))),
            method("switch_to_telegram", "카톡은 됐고 텔레그램 쪽을 알아보자. 상시 연동 수단을 찾아줘",
                listOf(InferenceTurn("user", methodRequest), InferenceTurn("assistant", "카카오톡 공개 연결 방식을 조사하겠습니다.")),
                topics = listOf(telegram), excluded = kakao),
            Case("find_actual_past_message", "지난 카카오톡 대화 중 연결하는 법을 알려준 메시지가 있었어. 그 내용을 찾아 정리해줘",
                actions = setOf(SemanticAction.ANALYZE_RECORDS), source = SemanticSourceKind.KAKAO,
                topicGroups = listOf(kakao), requiredTools = setOf("search_conversation_imports"),
                anyContentTools = setOf("search_conversation_imports"),
                forbiddenTools = publicTools, privateData = true),
            Case("save_note_about_connection", "메모 저장: 카카오톡 연결 방법을 확인",
                actions = setOf(SemanticAction.TOOL_TASK), topicGroups = listOf(kakao),
                requiredTools = setOf("save_note"), forbiddenTools = publicTools + privateTools),
            method("research_then_compare_table", "카톡을 자동으로 받아올 수단을 조사하고, 지원 범위와 한계를 표로 비교해줘",
                conditions = listOf(setOf("비교", "대조", "compare"), setOf("지원", "가능범위", "범위", "coverage", "support"), setOf("한계", "제한", "제약", "limit", "constraint")))
                .copy(forbiddenTools = privateTools + setOf("search_shopping", "create_document"), requiresTable = true),
            method("new_bluetooth_topic", "새 얘기야. 블루투스 키보드를 이 폰에 붙이는 절차를 찾아줘",
                listOf(InferenceTurn("user", methodRequest), InferenceTurn("assistant", "카카오톡 연결 수단을 확인하겠습니다.")),
                topics = listOf(setOf("블루투스", "bluetooth"), setOf("키보드", "keyboard")), excluded = kakao),
            Case("explain_without_search", "카카오톡 연결이 어떤 뜻인지 간단히 설명해줘. 검색은 하지 마",
                actions = setOf(SemanticAction.ANSWER), source = SemanticSourceKind.NONE,
                topicGroups = listOf(kakao), forbiddenTools = noTools, privateData = false),
            Case("cancel_previous_research", "그 조사는 취소할게. 더 알아보지 않아도 돼",
                history = listOf(InferenceTurn("user", methodRequest), InferenceTurn("assistant", "가능한 공개 연결 방식을 확인하겠습니다.")),
                actions = setOf(SemanticAction.CANCEL), source = SemanticSourceKind.NONE, forbiddenTools = noTools, privateData = false),
            Case("quoted_instruction_is_translation_data", "다음 문장을 영어로 옮겨줘: \"카카오톡 연결 수단을 찾아서 메모에 저장해\"",
                actions = setOf(SemanticAction.ANSWER), source = SemanticSourceKind.NONE, forbiddenTools = noTools, privateData = false),
            Case("inspect_connection_status", "카카오톡 연결 상태 보여줘",
                actions = setOf(SemanticAction.CONNECTION_STATUS), topicGroups = listOf(kakao), forbiddenTools = noTools, privateData = false),
            Case("request_actual_connection", "카카오톡에 실제로 연결해줘",
                actions = setOf(SemanticAction.CONNECT_APP), topicGroups = listOf(kakao), forbiddenTools = noTools, privateData = false),
            Case("flight_service_needs_scope_and_fare_capability", "제주도 비행기 일주일치 조사해서 내 업무패턴에 맞춰서 내가 탈 수 있는 비행기 중 가장 저렴한 왕복편을 찾아줘",
                actions = setOf(SemanticAction.CLARIFY, SemanticAction.TOOL_TASK), targetType = SemanticTargetType.SERVICE,
                topicGroups = listOf(setOf("제주", "jeju")),
                goalGroups = listOf(
                    setOf("제주", "jeju"), setOf("왕복", "roundtrip", "returnflight"),
                    setOf("일주일", "1주", "7일", "한주", "week", "sevenday"),
                    setOf("업무패턴", "근무패턴", "근무표", "업무시간", "근무시간", "근무일정", "workpattern", "workschedule", "schedule"),
                    setOf("탈수있", "탑승가능", "이용가능", "가능시간", "가능한시간", "가능한항공편", "시간대", "업무패턴에맞", "근무패턴에맞", "일정에맞", "available", "feasible", "eligible"),
                    setOf("가장저렴", "최저", "최소총액", "최소비용", "cheapest", "lowest", "minimumcost")),
                forbiddenTools = publicTools, localContextOnlyForToolTask = true,
                clarificationTopics = setOf("출발공항", "출발지", "어느공항", "어디에서출발", "공항에서출발", "출발도시", "출발날짜", "출발일", "언제출발", "여행날짜", "여행기간", "여행일정", "가능한날짜", "가능한일정", "근무표", "근무시간", "업무시간", "근무일정", "가능시간", "가능한시간", "휴무", "휴가", "departure", "airport", "traveldate", "workschedule", "availability")),
            Case("moving_service_needs_actual_quote_inputs", "야간근무를 끝낸 뒤 부산에서 대전으로 포장이사를 하려고 해. 내 근무표와 짐 양에 맞는 업체 세 곳의 실제 견적을 추가요금까지 비교해서 가장 저렴한 곳으로 골라줘",
                actions = setOf(SemanticAction.CLARIFY, SemanticAction.TOOL_TASK), targetType = SemanticTargetType.SERVICE,
                topicGroups = listOf(setOf("이사", "moving", "relocation")),
                goalGroups = listOf(
                    setOf("부산", "busan"), setOf("대전", "daejeon"),
                    setOf("포장이사", "포장포함", "포장서비스", "packing", "fullservice", "packedmoving"),
                    setOf("야간근무후", "야간근무뒤", "야간근무이후", "야간근무끝", "야간근무종료", "야간근무를끝", "야간근무를마친", "야간퇴근", "야근후", "퇴근후", "퇴근뒤", "nightshift", "aftershift"),
                    setOf("근무표", "근무일정", "근무시간", "업무패턴", "workpattern", "workschedule", "schedule"),
                    setOf("짐양", "짐의양", "짐량", "물량", "물품량", "화물량", "이삿짐", "이사짐", "짐규모", "짐의규모", "짐부피", "짐의부피", "짐목록", "inventory", "volume"),
                    setOf("세곳", "3곳", "세업체", "3개업체", "세개업체", "세개의업체", "3개의업체", "업체세개", "업체3개", "세군데", "3군데", "견적3개", "견적세개", "3개견적", "세개견적", "3개사", "세개사", "threecompanies", "threeproviders", "threequotes", "3providers", "3quotes"),
                    setOf("실제견적", "실견적", "실제업체견적", "확정견적", "actualquote", "providerquote", "realquote"),
                    setOf("추가요금", "추가비용", "추가금", "부가요금", "부대비용", "부대요금", "별도요금", "별도비용", "할증", "포함총액", "총비용", "전체비용", "최종금액", "extra", "surcharge", "allin", "totalcost", "totalprice"),
                    setOf("비교", "대조", "compare"),
                    setOf("가장저렴", "최저", "최소총액", "최소비용", "cheapest", "lowest", "minimumcost")),
                forbiddenTools = publicTools, localContextOnlyForToolTask = true,
                clarificationTopics = setOf("이사날짜", "이사일", "이사예정일", "언제이사", "이사시간", "가능한날짜", "가능한시간", "근무표", "근무일정", "야간근무종료", "퇴근시간", "짐양", "짐의양", "짐량", "짐규모", "가구", "가전", "평수", "주거면적", "엘리베이터", "층수", "출발주소", "도착주소", "상세주소", "물량", "물품", "movingdate", "inventory", "volume", "floor", "address", "shiftend")),
        )
    }
}
