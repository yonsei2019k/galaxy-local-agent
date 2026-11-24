package com.user.localagent.core

import kotlinx.serialization.json.*

/** Nested goal arguments sent across the phone/PC bridge. Each variant has a closed field set. */
internal object GoalExecutionSchemas {
    private fun text(max: Int, min: Int = 1) = buildJsonObject {
        put("type", "string")
        put("minLength", min)
        put("maxLength", max)
    }

    private fun choices(vararg values: String) = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun variant(fields: Map<String, JsonObject>, vararg required: String) = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    private fun variants(vararg values: JsonObject) = buildJsonObject {
        put("oneOf", JsonArray(values.toList()))
    }

    private val status = choices(*LedgerStatus.entries.map { it.name.lowercase() }.toTypedArray())
    private val evidence = buildJsonObject {
        put("type", "array")
        put("maxItems", GoalProgressLimits.MAX_EVIDENCE)
        put("items", text(GoalProgressLimits.MAX_ID))
    }
    private val common = mapOf("id" to text(100), "status" to status, "note" to text(200, 0), "evidence" to evidence)
    private val operations = buildJsonObject {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", 20)
        put("items", variants(
            variant(common + mapOf("op" to choices("add"), "course" to text(100), "unit" to text(200),
                "kind" to choices(*LedgerKind.entries.map { it.name.lowercase() }.toTypedArray())),
                "op", "id", "course", "unit", "kind"),
            variant(common + ("op" to choices("set")), "op", "id", "status"),
            variant(mapOf("op" to choices("setMeta"), "requirements" to text(2000, 0), "deadline" to text(40),
                "dailyLimit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", GoalProgressLimits.MAX_DAILY_LIMIT) }), "op")
        ))
    }
    private val condition = variants(
        variant(mapOf("kind" to choices("media_end"), "pageUrl" to text(2048), "playerSignature" to text(200),
            "ledgerItemId" to text(100)), "kind", "pageUrl", "playerSignature", "ledgerItemId"),
        variant(mapOf("kind" to choices("at_time"), "atEpochMillis" to buildJsonObject { put("type", "integer"); put("minimum", 1) },
            "needsPlayback" to buildJsonObject { put("type", "boolean") }), "kind", "atEpochMillis", "needsPlayback"),
        variant(mapOf("kind" to choices("owner_auth"), "handoffId" to text(100)), "kind", "handoffId")
    )

    fun argument(tool: String, key: String): JsonObject? = when {
        tool == GoalProgressToolCatalog.UPDATE && key == "ops" -> operations
        tool == GoalWaitToolCatalog.WAIT && key == "condition" -> condition
        else -> null
    }
}
