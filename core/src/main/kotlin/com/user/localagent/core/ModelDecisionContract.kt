package com.user.localagent.core

import kotlinx.serialization.json.*

/** Codes and hints contain no source text, generated field values or private diagnostics. */
class ModelDecisionException(val code: String, val hint: String) : IllegalArgumentException(hint)

/** Sparse model choices become the existing canonical plan; no natural-language classification occurs here. */
object ModelDecisionContract {
    private val knownFields = setOf("action", "goal", "target", "targetType", "constraints", "publicQuery", "requiredTools", "sourceKind", "usesPrivateData", "question")
    private val publicTools = setOf("search_web", "search_shopping", "read_webpage", "open_url")+EvidenceToolCatalog.names
    private val searchTools = setOf("search_web", "search_shopping")+EvidenceToolCatalog.names
    private val contentReads = mapOf(
        SemanticSourceKind.SMS to setOf("get_sms_summary", "analyze_sms"),
        SemanticSourceKind.KAKAO to setOf("list_messenger_notifications", "search_conversation_imports", "analyze_conversation_import"),
        SemanticSourceKind.TELEGRAM to setOf("list_messenger_notifications", "search_conversation_imports", "analyze_conversation_import"),
        SemanticSourceKind.MEDITONG to setOf("list_messenger_notifications", "search_conversation_imports", "analyze_conversation_import"),
        SemanticSourceKind.ASSISTANT to setOf("search_conversation_history"),
    )

    /** One grammar branch avoids duplicating every field/tool across eight actions.
     * Action-specific requirements remain enforced by parse(), before any execution. */
    fun planningSchemaJson(availableTools: Set<String> = ToolRegistry.definitions.keys): String {
        val branches = Json.parseToJsonElement(schemaJson(availableTools)).jsonObject.getValue("oneOf").jsonArray
        val properties = linkedMapOf<String, JsonElement>()
        branches.forEach { branch -> properties.putAll(branch.jsonObject.getValue("properties").jsonObject) }
        properties["action"] = buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(branches.map { it.jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("enum").jsonArray.single() }))
        }
        return buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", JsonObject(properties))
            put("required", JsonArray(listOf("action", "goal", "targetType").map(::JsonPrimitive)))
        }.toString()
    }

    fun schemaJson(availableTools: Set<String> = ToolRegistry.definitions.keys): String {
        checkAvailable(availableTools)
        fun text(max: Int, min: Int = 0) = buildJsonObject { put("type", "string"); put("minLength", min); put("maxLength", max) }
        fun enumeration(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
        fun tools(names: Set<String>) = buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", minOf(12, names.size)); put("uniqueItems", true)
            put("items", enumeration(names.sorted()))
        }
        fun branch(action: SemanticAction): JsonObject {
            val required = mutableListOf("action", "goal", "targetType")
            return buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("action", enumeration(listOf(action.name))); put("goal", text(1000, 1))
                    put("target", text(200)); put("targetType", enumeration(SemanticTargetType.entries.map { it.name }))
                    put("constraints", buildJsonObject { put("type", "array"); put("maxItems", 6); put("uniqueItems", true); put("items", text(240, 1)) })
                    when (action) {
                        SemanticAction.PUBLIC_RESEARCH -> {
                            put("publicQuery", text(500, 1)); put("requiredTools", tools(availableTools.intersect(publicTools)))
                            required += listOf("publicQuery", "requiredTools")
                        }
                        SemanticAction.ANALYZE_RECORDS -> {
                            put("sourceKind", enumeration(SemanticSourceKind.entries.filter { it != SemanticSourceKind.NONE }.map { it.wireName }))
                            put("requiredTools", tools(availableTools.intersect(contentReads.values.flatten().toSet() + setOf("list_imported_conversations", "get_current_datetime"))))
                            required += listOf("sourceKind", "requiredTools")
                        }
                        SemanticAction.TOOL_TASK -> {
                            put("requiredTools", tools(availableTools)); put("sourceKind", enumeration(SemanticSourceKind.entries.map { it.wireName }))
                            put("publicQuery", text(500)); required += "requiredTools"
                        }
                        SemanticAction.CLARIFY -> { put("question", text(240, 1)); required += "question" }
                        SemanticAction.CONNECTION_STATUS, SemanticAction.CONNECT_APP -> required += "target"
                        SemanticAction.ANSWER, SemanticAction.CANCEL -> Unit
                    }
                })
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
        }
        val actions = SemanticAction.entries.filter { action -> when (action) {
            SemanticAction.PUBLIC_RESEARCH -> availableTools.any { it in searchTools }
            SemanticAction.ANALYZE_RECORDS -> availableTools.any { it in contentReads.values.flatten() }
            SemanticAction.TOOL_TASK -> availableTools.isNotEmpty()
            else -> true
        } }
        return buildJsonObject { put("oneOf", JsonArray(actions.map(::branch))) }.toString()
    }

    fun parse(raw: String, availableTools: Set<String> = ToolRegistry.definitions.keys): SemanticRequestPlan {
        checkAvailable(availableTools)
        if (raw.isBlank() || raw.length > 8192) fail("invalid_json_length", "Return one JSON object within the output length limit.")
        val objectValue = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
            ?: fail("invalid_json", "Return one JSON object without markdown or extra prose.")
        rejectDuplicateKeys(raw)
        if (objectValue.keys.any { it !in knownFields }) fail("unknown_field", "Use only fields defined by the decision contract.")
        fun text(key: String, default: String = ""): String {
            val value = objectValue[key] ?: return default
            if (value !is JsonPrimitive || !value.isString) fail("invalid_field_type", "Text fields must be JSON strings.")
            return value.content
        }
        fun strings(key: String): List<String> {
            val value = objectValue[key] ?: return emptyList()
            if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString }) fail("invalid_array_type", "Tool and constraint fields must be arrays of strings.")
            return value.map { it.jsonPrimitive.content }.distinct()
        }
        if ("action" !in objectValue) fail("missing_action", "Provide action and goal for the current request.")
        if ("goal" !in objectValue) fail("missing_goal", "Provide action and a concise nonempty goal.")
        if ("targetType" !in objectValue) fail("missing_target_type", "Provide targetType for the original target: GENERAL, PHYSICAL_PRODUCT, SERVICE, PERSONAL_RECORD, DEVICE or CONTENT.")
        val action = SemanticAction.entries.firstOrNull { it.name == text("action") }
            ?: fail("invalid_action", "Use an action from the decision contract.")
        val goal = text("goal"); val target = text("target"); val query = text("publicQuery"); val question = text("question")
        if (goal.isBlank()) fail("missing_goal", "Provide a concise nonempty goal for the current request.")
        val constraints = strings("constraints")
        if (goal.length > 1000 || target.length > 200 || query.length > 500 || question.length > 240 ||
            constraints.size > 6 || constraints.any { it.isBlank() || it.length > 240 }) {
            fail("invalid_field_length", "Keep goal within 1000 characters, target within 200, query within 500, question and each of at most six constraints within 240.")
        }
        val targetType = SemanticTargetType.entries.firstOrNull { it.name == text("targetType") }
            ?: fail("invalid_target_type", "Use a targetType from the decision contract.")
        val sourceText = text("sourceKind", "none")
        val source = if (action in setOf(SemanticAction.ANSWER, SemanticAction.CANCEL, SemanticAction.CLARIFY, SemanticAction.CONNECTION_STATUS, SemanticAction.CONNECT_APP)) SemanticSourceKind.NONE
            else SemanticSourceKind.entries.firstOrNull { it.wireName == sourceText }
                ?: fail("invalid_source_kind", "Use a supported lowercase sourceKind.")
        val tools = strings("requiredTools").toSet()
        if (tools.size > 12) fail("too_many_tools", "Choose at most twelve required tools.")
        if (tools.any { it !in ToolRegistry.definitions }) fail("unknown_tool", "Use only registered tool names offered by the host.")
        if (tools.any { it !in availableTools }) fail("unavailable_tool", "Use only tools available for this request.")
        val deprecatedPrivacy = objectValue["usesPrivateData"]?.let { value ->
            if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) fail("invalid_privacy_type", "If present, usesPrivateData must be a JSON Boolean.")
            value.boolean
        }
        if ("search_shopping" in tools && targetType != SemanticTargetType.PHYSICAL_PRODUCT) {
            fail("shopping_target_mismatch", "Use search_shopping only with targetType PHYSICAL_PRODUCT. Service research uses public search.")
        }
        var canonicalSource = SemanticSourceKind.NONE
        var canonicalQuery = ""
        var canonicalPrivacy = false
        var canonicalQuestion = ""
        when (action) {
            SemanticAction.PUBLIC_RESEARCH -> {
                if (query.isBlank()) fail("missing_public_query", "PUBLIC_RESEARCH requires a concise publicQuery.")
                if (tools.none { it in searchTools } || tools.any { it !in publicTools }) fail("invalid_public_tools",
                    "PUBLIC_RESEARCH permits only these offered research tools: ${availableTools.intersect(publicTools).sorted().joinToString(",")}. Use TOOL_TASK for other offered connector tools, preserving the original request and constraints.")
                if (source != SemanticSourceKind.NONE || deprecatedPrivacy == true) fail("public_private_conflict", "PUBLIC_RESEARCH must not use private records; omit sourceKind and usesPrivateData.")
                canonicalQuery = query
            }
            SemanticAction.ANALYZE_RECORDS -> {
                if (source == SemanticSourceKind.NONE) fail("missing_record_source", "ANALYZE_RECORDS requires the actual supported sourceKind.")
                val content = contentReads.getValue(source)
                val allowed = content + "get_current_datetime" + if (source in setOf(SemanticSourceKind.KAKAO, SemanticSourceKind.TELEGRAM, SemanticSourceKind.MEDITONG)) setOf("list_imported_conversations") else emptySet()
                if (tools.none { it in content }) fail("missing_content_read", "ANALYZE_RECORDS requires a content read for its source, not just a catalog count.")
                if (tools.any { it !in allowed }) fail("invalid_record_tools", "ANALYZE_RECORDS permits only reads matching its source; use TOOL_TASK for supported changes.")
                canonicalSource = source; canonicalPrivacy = true
            }
            SemanticAction.TOOL_TASK -> {
                if (tools.isEmpty()) fail("missing_required_tools", "TOOL_TASK requires the supported tools needed for the task.")
                canonicalSource = source
                canonicalPrivacy = source != SemanticSourceKind.NONE || tools.any(::isPrivateRead)
                if (tools.any { it in searchTools }) {
                    if (canonicalPrivacy) fail("mixed_private_public", "Plan the local private phase separately from external public search; preserve the remaining goal in constraints.")
                    if (query.isBlank()) fail("missing_public_query", "A tool task using public search requires publicQuery.")
                    canonicalQuery = query
                }
            }
            SemanticAction.CLARIFY -> {
                noTools(tools)
                if (question.isBlank()) fail("missing_question", "CLARIFY requires one essential question.")
                canonicalQuestion = question
            }
            SemanticAction.CONNECTION_STATUS, SemanticAction.CONNECT_APP -> {
                noTools(tools)
                if (target.isBlank()) fail("missing_connection_target", "Connection status and connection requests require the actual target.")
            }
            SemanticAction.ANSWER, SemanticAction.CANCEL -> noTools(tools)
        }
        val canonical = SemanticRequestPlan(action, goal, target, canonicalQuery, canonicalSource, tools, constraints,
            canonicalPrivacy, canonicalQuestion, targetType)
        return try {
            SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(canonical), availableTools)
        } catch (_: IllegalArgumentException) {
            fail("canonical_validation_failed", "Use the supported action, source, target type and required-tool combination from the contract.")
        }
    }

    private fun checkAvailable(available: Set<String>) {
        if (available.any { it !in ToolRegistry.definitions }) fail("invalid_host_tools", "The host must offer registered tools only.")
    }
    private fun noTools(tools: Set<String>) {
        if (tools.isNotEmpty()) fail("unexpected_tools", "This action does not execute tools. Omit requiredTools or use an empty array.")
    }
    private fun isPrivateRead(tool: String): Boolean = tool in GoalToolCatalog.names || tool in PrivateAnalysisToolCatalog.privateReadNames || tool in RecordingToolCatalog.names || ToolRegistry.definitions[tool]?.risk == Risk.LOW &&
        (tool.startsWith("get_") || tool.startsWith("list_") || tool.startsWith("search_") || tool.startsWith("read_")) &&
        tool !in publicTools && tool != "get_current_datetime" && tool !in SkillToolCatalog.names
    private fun fail(code: String, hint: String): Nothing = throw ModelDecisionException(code, hint)

    private fun rejectDuplicateKeys(raw: String) {
        val keys = mutableSetOf<String>(); var objects = 0; var arrays = 0; var expectKey = false; var index = 0
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
                        if (!keys.add(key)) fail("duplicate_key", "Return each JSON field once; duplicate keys are ambiguous.")
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
