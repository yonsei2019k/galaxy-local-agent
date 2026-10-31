package com.user.localagent.core

import java.util.Collections
import kotlinx.serialization.json.*

enum class SemanticAction { PUBLIC_RESEARCH, ANALYZE_RECORDS, CONNECTION_STATUS, CONNECT_APP, ANSWER, TOOL_TASK, CLARIFY, CANCEL }
enum class SemanticTargetType { GENERAL, PHYSICAL_PRODUCT, SERVICE, PERSONAL_RECORD, DEVICE, CONTENT }
enum class SemanticSourceKind(val wireName: String) {
    NONE("none"), SMS("sms"), KAKAO("kakao"), TELEGRAM("telegram"), MEDITONG("meditong"), ASSISTANT("assistant")
}

/** A model-proposed interpretation, never a permission grant, tool result or completion claim. */
data class SemanticRequestPlan(
    val action: SemanticAction,
    val goal: String,
    val target: String,
    val publicQuery: String,
    val sourceKind: SemanticSourceKind,
    val requiredTools: Set<String>,
    val constraints: List<String>,
    val usesPrivateData: Boolean,
    val question: String,
    val targetType: SemanticTargetType = SemanticTargetType.GENERAL,
)

/** Converts a validated meaning plan without reclassifying any natural-language words. */
object SemanticRequestPlanning {
    private val legacyFields = setOf("action", "goal", "target", "publicQuery", "sourceKind", "requiredTools", "constraints", "usesPrivateData", "question")
    private val fields = legacyFields + "targetType"
    private val publicTools = setOf("search_web", "search_shopping", "read_webpage", "open_url")+EvidenceToolCatalog.names
    private val searchTools = setOf("search_web", "search_shopping")+EvidenceToolCatalog.names
    private val privateReads = setOf(
        "get_sms_summary", "get_call_log_summary", "search_contacts", "list_messenger_notifications",
        "list_imported_conversations", "search_conversation_imports", "search_conversation_history",
        "list_audio_recordings", "search_audio_transcripts", "list_calendar_events", "search_notes",
        "search_memories", "list_project_files", "read_project_file",
    ) + com.user.localagent.core.connections.ConnectionCatalog.privateReadNames + com.user.localagent.core.connections.PcToolCatalog.names + PrivateAnalysisToolCatalog.privateReadNames + RecordingToolCatalog.names + GoalToolCatalog.names
    private fun contentTools(source: SemanticSourceKind): Set<String> = when (source) {
        SemanticSourceKind.SMS -> setOf("get_sms_summary", "analyze_sms")
        SemanticSourceKind.KAKAO, SemanticSourceKind.TELEGRAM, SemanticSourceKind.MEDITONG -> setOf("list_messenger_notifications", "search_conversation_imports", "analyze_conversation_import")
        SemanticSourceKind.ASSISTANT -> setOf("search_conversation_history")
        SemanticSourceKind.NONE -> emptySet()
    }
    private fun sourceReadTools(source: SemanticSourceKind): Set<String> = contentTools(source) +
        (if (source in setOf(SemanticSourceKind.KAKAO, SemanticSourceKind.TELEGRAM, SemanticSourceKind.MEDITONG)) setOf("list_imported_conversations") else emptySet()) + "get_current_datetime"

    fun schemaJson(availableTools: Set<String> = ToolRegistry.definitions.keys): String {
        require(availableTools.all { it in ToolRegistry.definitions }) { "알 수 없는 도구의 의미 계획 스키마를 만들 수 없습니다." }
        fun string(max: Int, min: Int = 0) = buildJsonObject { put("type", "string"); put("minLength", min); put("maxLength", max) }
        return buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(SemanticAction.entries.map { JsonPrimitive(it.name) })) })
                put("goal", string(1000, 1)); put("target", string(200)); put("publicQuery", string(500))
                put("sourceKind", buildJsonObject { put("type", "string"); put("enum", JsonArray(SemanticSourceKind.entries.map { JsonPrimitive(it.wireName) })) })
                put("requiredTools", buildJsonObject {
                    put("type", "array"); put("maxItems", minOf(12, availableTools.size)); put("uniqueItems", true)
                    put("items", buildJsonObject {
                        put("type", "string")
                        if (availableTools.isNotEmpty()) put("enum", JsonArray(availableTools.sorted().map(::JsonPrimitive)))
                    })
                })
                put("constraints", buildJsonObject { put("type", "array"); put("maxItems", 6); put("uniqueItems", true); put("items", string(240, 1)) })
                put("usesPrivateData", buildJsonObject { put("type", "boolean") }); put("question", string(240))
                put("targetType", buildJsonObject { put("type", "string"); put("enum", JsonArray(SemanticTargetType.entries.map { JsonPrimitive(it.name) })) })
            })
            put("required", JsonArray(fields.map(::JsonPrimitive)))
        }.toString()
    }

    fun parse(raw: String, availableTools: Set<String> = ToolRegistry.definitions.keys): SemanticRequestPlan {
        require(raw.isNotBlank() && raw.length <= 8192) { "의미 계획 JSON 길이가 허용 범위를 벗어났습니다." }
        val value = try { Json.parseToJsonElement(raw) } catch (error: Exception) { throw IllegalArgumentException("올바른 의미 계획 JSON 객체가 필요합니다.", error) }
        require(value is JsonObject) { "의미 계획은 JSON 객체여야 합니다." }
        rejectDuplicateKeys(raw)
        // Stored nine-field plans remain readable; new model decoding always requires targetType.
        require(value.keys == fields || value.keys == legacyFields) { "의미 계획에 누락되거나 알 수 없는 필드가 있습니다." }
        fun text(key: String): String {
            val item = value.getValue(key)
            require(item is JsonPrimitive && item.isString) { "$key 필드는 문자열이어야 합니다." }
            return item.content
        }
        fun strings(key: String): List<String> {
            val array = value.getValue(key)
            require(array is JsonArray) { "$key 필드는 문자열 배열이어야 합니다." }
            return array.map { item -> require(item is JsonPrimitive && item.isString) { "$key 배열은 문자열만 허용합니다." }; item.content }
        }
        val action = SemanticAction.entries.firstOrNull { it.name == text("action") } ?: throw IllegalArgumentException("알 수 없는 의미 계획 행동입니다.")
        val source = SemanticSourceKind.entries.firstOrNull { it.wireName == text("sourceKind") } ?: throw IllegalArgumentException("알 수 없는 의미 계획 자료 종류입니다.")
        val targetType = if ("targetType" in value) SemanticTargetType.entries.firstOrNull { it.name == text("targetType") }
            ?: throw IllegalArgumentException("알 수 없는 의미 계획 대상 종류입니다.") else SemanticTargetType.GENERAL
        val tools = strings("requiredTools")
        require(tools.size == tools.toSet().size) { "필수 도구가 중복되었습니다." }
        val privacy = value.getValue("usesPrivateData")
        require(privacy is JsonPrimitive && !privacy.isString && privacy.booleanOrNull != null) { "usesPrivateData는 실제 Boolean이어야 합니다." }
        val plan = SemanticRequestPlan(action, text("goal"), text("target"), text("publicQuery"), source,
            Collections.unmodifiableSet(tools.toSet()), Collections.unmodifiableList(strings("constraints")), privacy.boolean, text("question"), targetType)
        validate(plan, availableTools)
        return plan
    }

    fun allowedTools(plan: SemanticRequestPlan): Set<String> = allowedTools(plan, ToolRegistry.definitions.keys)

    fun allowedTools(plan: SemanticRequestPlan, availableTools: Set<String>): Set<String> {
        validate(plan, availableTools)
        return toolClosure(plan.requiredTools, availableTools)
    }

    fun toTaskGoal(plan: SemanticRequestPlan, stepBudget: Int = 6): TaskGoal {
        validate(plan, ToolRegistry.definitions.keys)
        return TaskGoal(plan.goal, listOf(evidenceCriterion(plan)) + plan.constraints, stepBudget, plan.requiredTools)
    }

    fun toIntentPlan(plan: SemanticRequestPlan, capabilities: IntentCapabilities = IntentCapabilities()): IntentPlan {
        validate(plan, capabilities.availableTools)
        val reads = toolClosure(plan.requiredTools, capabilities.availableTools).filter(::isRead)
        val deferred = plan.requiredTools.filterNot(::isRead) + reads.drop(6)
        val domain = when (plan.action) {
            SemanticAction.PUBLIC_RESEARCH -> "web"
            SemanticAction.ANALYZE_RECORDS -> "messages"
            SemanticAction.TOOL_TASK -> plan.requiredTools.firstOrNull()?.let(IntentPlanning::domainForTool) ?: "general"
            else -> "general"
        }
        val sources = linkedSetOf<IntentSource>()
        if (plan.action == SemanticAction.PUBLIC_RESEARCH || plan.requiredTools.any { it in publicTools }) sources += IntentSource.WEB
        if (plan.usesPrivateData) {
            when (plan.sourceKind) {
                SemanticSourceKind.SMS -> sources += IntentSource.SMS
                SemanticSourceKind.ASSISTANT -> sources += IntentSource.ASSISTANT_HISTORY
                SemanticSourceKind.KAKAO, SemanticSourceKind.TELEGRAM, SemanticSourceKind.MEDITONG -> {
                    val pair = when (plan.sourceKind) {
                        SemanticSourceKind.KAKAO -> IntentSource.KAKAO_IMPORT to IntentSource.KAKAO_NOTIFICATIONS
                        SemanticSourceKind.TELEGRAM -> IntentSource.TELEGRAM_IMPORT to IntentSource.TELEGRAM_NOTIFICATIONS
                        else -> IntentSource.MEDITONG_IMPORT to IntentSource.MEDITONG_NOTIFICATIONS
                    }
                    if (plan.requiredTools.any { it in setOf("list_imported_conversations", "search_conversation_imports") }) sources += pair.first
                    if ("list_messenger_notifications" in plan.requiredTools) sources += pair.second
                }
                SemanticSourceKind.NONE -> Unit
            }
            plan.requiredTools.forEach { tool -> when (tool) {
                "search_notes" -> sources += IntentSource.NOTES
                "search_memories" -> sources += IntentSource.MEMORIES
                "list_calendar_events" -> sources += IntentSource.CALENDAR
                "list_audio_recordings", "search_audio_transcripts" -> sources += IntentSource.AUDIO_TRANSCRIPTS
                "list_project_files", "read_project_file" -> sources += IntentSource.PROJECT
                else -> Unit
            } }
        }
        return IntentPlan(domain, plan.goal, listOf(evidenceCriterion(plan)) + plan.constraints, reads.take(6), deferred,
            mapOf("semanticAction" to plan.action.name, "target" to plan.target, "publicQuery" to plan.publicQuery,
                "sourceKind" to plan.sourceKind.wireName, "usesPrivateData" to plan.usesPrivateData.toString(), "targetType" to plan.targetType.name),
            if (plan.action == SemanticAction.CLARIFY) listOf(IntentMissingSlot("semantic_clarification", plan.question)) else emptyList(),
            emptyList(), 1.0, evidenceCriterion(plan), sources.toList())
    }

    /** Preserve the full goal and constraints when building model context; bounded UI summaries may truncate. */
    fun contextJson(plan: SemanticRequestPlan): String {
        validate(plan, ToolRegistry.definitions.keys)
        return buildJsonObject {
            put("action", plan.action.name); put("goal", plan.goal); put("target", plan.target); put("publicQuery", plan.publicQuery)
            put("sourceKind", plan.sourceKind.wireName); put("requiredTools", JsonArray(plan.requiredTools.map(::JsonPrimitive)))
            put("constraints", JsonArray(plan.constraints.map(::JsonPrimitive))); put("usesPrivateData", plan.usesPrivateData); put("question", plan.question)
            put("targetType", plan.targetType.name)
        }.toString()
    }

    private fun validate(plan: SemanticRequestPlan, availableTools: Set<String>) {
        require(availableTools.all { it in ToolRegistry.definitions }) { "알 수 없는 사용 가능 도구입니다." }
        require(plan.goal.isNotBlank() && plan.goal.length <= 1000 && plan.target.length <= 200 && plan.publicQuery.length <= 500 && plan.question.length <= 240) { "의미 계획 문자열 길이가 허용 범위를 벗어났습니다." }
        require(plan.requiredTools.size <= 12 && plan.requiredTools.all { it in ToolRegistry.definitions && it in availableTools }) { "등록되지 않았거나 사용할 수 없는 필수 도구입니다." }
        require(toolClosure(plan.requiredTools, availableTools).all { it in availableTools }) { "필요한 선행 도구가 비활성화되어 의미 계획을 실행할 수 없습니다." }
        if ("search_shopping" in plan.requiredTools) require(plan.targetType == SemanticTargetType.PHYSICAL_PRODUCT) { "상품 가격 비교 도구는 PHYSICAL_PRODUCT 대상만 지원합니다. 서비스·예약·운임 조사는 지원하는 공개 검색 도구를 선택해야 합니다." }
        require(plan.constraints.size <= 6 && plan.constraints.all { it.isNotBlank() && it.length <= 240 } && plan.constraints.size == plan.constraints.toSet().size) { "의미 계획 제약 목록이 잘못되었습니다." }
        if (plan.action != SemanticAction.CLARIFY) require(plan.question.isEmpty()) { "확인 질문은 CLARIFY 행동에서만 허용합니다." }
        when (plan.action) {
            SemanticAction.PUBLIC_RESEARCH -> {
                require(plan.publicQuery.isNotBlank() && plan.sourceKind == SemanticSourceKind.NONE && !plan.usesPrivateData) { "공개 조사는 공개 검색어만 사용하고 개인 자료를 읽지 않아야 합니다." }
                require(plan.requiredTools.any { it in searchTools } && plan.requiredTools.all { it in publicTools }) { "공개 조사에는 실제 공개 검색 도구가 필요합니다." }
            }
            SemanticAction.ANALYZE_RECORDS -> {
                require(plan.sourceKind != SemanticSourceKind.NONE && plan.usesPrivateData && plan.publicQuery.isEmpty()) { "자료 분석에는 명시한 실제 로컬 출처가 필요합니다." }
                require(plan.requiredTools.any { it in contentTools(plan.sourceKind) } && plan.requiredTools.all { it in sourceReadTools(plan.sourceKind) }) { "자료 종류에 맞는 읽기 도구가 필요하며 목록 개수만으로 분석을 대체할 수 없습니다." }
            }
            SemanticAction.TOOL_TASK -> {
                require(plan.requiredTools.isNotEmpty()) { "도구 작업에는 필수 도구가 필요합니다." }
                if (plan.sourceKind != SemanticSourceKind.NONE || plan.requiredTools.any { it in privateReads }) require(plan.usesPrivateData) { "로컬 자료를 읽는 작업은 개인 자료 사용을 명시해야 합니다." }
                if (plan.requiredTools.any { it in searchTools }) {
                    require(plan.publicQuery.isNotBlank() && !plan.usesPrivateData && plan.sourceKind == SemanticSourceKind.NONE) { "개인 자료와 외부 검색은 자동 결합하지 않습니다." }
                } else require(plan.publicQuery.isEmpty()) { "검색 도구 없이 외부 검색어를 사용할 수 없습니다." }
            }
            SemanticAction.CLARIFY -> {
                require(plan.question.isNotBlank()) { "CLARIFY에는 확인할 질문 한 가지가 필요합니다." }
                noDataTools(plan, requireNoSource = true)
            }
            SemanticAction.ANSWER, SemanticAction.CANCEL -> noDataTools(plan, requireNoSource = true)
            SemanticAction.CONNECTION_STATUS, SemanticAction.CONNECT_APP -> {
                require(plan.target.isNotBlank()) { "연결 작업에는 명시한 대상이 필요합니다." }
                noDataTools(plan, requireNoSource = false)
            }
        }
    }

    private fun noDataTools(plan: SemanticRequestPlan, requireNoSource: Boolean) {
        require(plan.requiredTools.isEmpty() && !plan.usesPrivateData && plan.publicQuery.isEmpty()) { "이 행동은 자료 조회나 도구 실행을 허가하지 않습니다." }
        if (requireNoSource) require(plan.sourceKind == SemanticSourceKind.NONE) { "이 행동에 개인 자료 출처를 연결할 수 없습니다." }
    }

    /** Capability prerequisites, not extra semantic goals or permission grants. Metadata never replaces content evidence. */
    private fun toolClosure(required: Set<String>, availableTools:Set<String> = ToolRegistry.definitions.keys): Set<String> = buildSet {
        if (required.any { it in GoalToolCatalog.mutationNames }) add(GoalToolCatalog.GET)
        if ("search_conversation_imports" in required) add("list_imported_conversations")
        if ("transcribe_recording" in required) add("list_audio_recordings")
        if(required.any {it in PrivateAnalysisToolCatalog.analysisNames}){
            addAll(setOf("list_analysis_sources","read_analysis_evidence").intersect(availableTools))
            if("analyze_conversation_import" in required&&"list_imported_conversations" in availableTools)add("list_imported_conversations")
        }
        addAll(required)
    }

    private fun isRead(tool: String): Boolean = ToolReadPolicy.isReadOnly(tool)

    private fun evidenceCriterion(plan: SemanticRequestPlan): String = when (plan.action) {
        SemanticAction.PUBLIC_RESEARCH -> "필수 공개 조사 도구의 실제 출처·반환 범위를 확인한다. 알림이나 가져온 파일 개수는 공개 조사 완료의 근거가 아니다."
        SemanticAction.ANALYZE_RECORDS -> "명시한 자료 출처의 실제 반환 내용과 범위로 분석하고, 목록·일부 발췌를 전체 분석이라고 주장하지 않는다."
        SemanticAction.TOOL_TASK -> "모든 필수 도구의 실제 실행·검증 근거를 확인한다. 변경 승인·인자 검사·재조회는 별도로 적용한다."
        SemanticAction.CONNECTION_STATUS -> "실제 연결 준비 상태의 근거만 설명한다. 개인 메시지를 조회하거나 연결 완료를 추측하지 않는다."
        SemanticAction.CONNECT_APP -> "검증한 연결 수단과 필요한 시스템 동의를 확인한다. 지원되지 않은 연결을 완료했다고 말하지 않는다."
        SemanticAction.CLARIFY -> "누락된 필수 정보 한 가지만 질문하고 답변 전에 도구를 실행하지 않는다."
        SemanticAction.CANCEL -> "요청한 작업을 중단하고 새 도구를 실행하지 않는다."
        SemanticAction.ANSWER -> "현재 목표와 제약에 답하고, 실행하지 않은 작업을 완료했다고 주장하지 않는다."
    }

    /** JsonObject would otherwise silently retain the last of duplicate root keys. */
    private fun rejectDuplicateKeys(raw: String) {
        val seen = mutableSetOf<String>()
        var objects = 0; var arrays = 0; var expectKey = false; var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '"' -> {
                    val start = index++
                    while (index < raw.length) {
                        if (raw[index] == '\\') { index += 2; continue }
                        if (raw[index] == '"') break
                        index++
                    }
                    if (objects == 1 && arrays == 0 && expectKey) {
                        val key = Json.parseToJsonElement(raw.substring(start, index + 1)).jsonPrimitive.content
                        require(seen.add(key)) { "의미 계획 JSON 키가 중복되었습니다: $key" }
                        expectKey = false
                    }
                }
                '{' -> { objects++; if (objects == 1 && arrays == 0) expectKey = true }
                '}' -> objects--
                '[' -> arrays++
                ']' -> arrays--
                ',' -> if (objects == 1 && arrays == 0) expectKey = true
            }
            index++
        }
    }
}
