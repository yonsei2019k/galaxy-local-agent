package com.user.localagent.core

import kotlinx.serialization.json.*

/** Tool that parks a persistent goal until a host-observed condition. The condition never grants new authority. */
object GoalWaitToolCatalog {
    const val WAIT = "wait_for_goal_condition"
    val definitions = listOf(
        ToolDefinition(WAIT, Risk.LOW,
            mapOf("goalId" to Argument("string", true, 100), "expectedUpdatedAt" to Argument("string", true, 19),
                "condition" to Argument("object", true, 2000)),
            "Park an active persistent goal until a future time. Copy goalId and goalRevision from read_goal_progress (send goalRevision as expectedUpdatedAt). Use condition={kind:at_time,atEpochMillis:future epoch milliseconds,needsPlayback:false}. Waiting adds no permissions and is not completion evidence."))

    private val kinds = mapOf(
        "media_end" to setOf("kind", "pageUrl", "playerSignature", "ledgerItemId"),
        "at_time" to setOf("kind", "atEpochMillis", "needsPlayback"),
        "owner_auth" to setOf("kind", "handoffId"),
    )

    fun parse(call: ToolCall): WaitCondition {
        require(call.name == WAIT) { "대기 도구가 아닙니다." }
        val definition = definitions.single()
        require(call.arguments.keys.all { it in definition.arguments }) { "대기 도구에 허용되지 않은 인자가 있습니다." }
        text(call.arguments["goalId"], 100); text(call.arguments["expectedUpdatedAt"], 19)
        val condition = call.arguments["condition"] as? JsonObject ?: throw IllegalArgumentException("대기 조건은 객체여야 합니다.")
        val kind = text(condition["kind"], 40)
        val allowed = kinds[kind] ?: throw IllegalArgumentException("지원하지 않는 대기 조건입니다.")
        require(condition.keys.all { it in allowed }) { "대기 조건에 허용되지 않은 키가 있습니다." }
        return when (kind) {
            "media_end" -> {
                val url = text(condition["pageUrl"], 2048)
                require(url.startsWith("https://") && WebAgentPolicy.urlAllowed(url)) { "재생 대기 주소가 허용되지 않습니다." }
                WaitCondition.MediaEnd(url, text(condition["playerSignature"], 200), text(condition["ledgerItemId"], 100))
            }
            "at_time" -> {
                val at = condition["atEpochMillis"] as? JsonPrimitive
                require(at != null && !at.isString && at.longOrNull != null && at.long > 0) { "대기 시각이 올바르지 않습니다." }
                val play = condition["needsPlayback"] as? JsonPrimitive
                require(play != null && !play.isString && play.booleanOrNull != null) { "재생 필요 여부가 올바르지 않습니다." }
                WaitCondition.AtTime(at.long, play.boolean)
            }
            else -> WaitCondition.OwnerAuth(text(condition["handoffId"], 100))
        }
    }

    private fun text(value: JsonElement?, max: Int): String {
        require(value is JsonPrimitive && value.isString && value.content.isNotBlank() && value.content.length <= max && '\u0000' !in value.content) {
            "대기 조건 문자열의 형식이나 크기가 올바르지 않습니다."
        }
        return value.content
    }
}
