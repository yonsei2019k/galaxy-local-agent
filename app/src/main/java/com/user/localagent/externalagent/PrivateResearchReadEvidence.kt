package com.user.localagent.externalagent

import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Creates a private, bounded input from confirmed reads after the cloud session is detached. */
internal object PrivateResearchReadEvidence {
    fun capture(reads: List<Pair<String, ToolResult>>): JsonArray? {
        if (reads.isEmpty() || reads.any { (tool, result) ->
                tool !in ExternalAgentContract.PUBLIC_RESEARCH_PHONE_TOOLS ||
                    !(result.success && result.verification == VerificationState.VERIFIED ||
                        !result.success && result.verification == VerificationState.FAILED && result.effectNotApplied)
            }) return null
        val verified = reads.filter { (_, result) -> result.success && result.content.isNotBlank() }.takeLast(3)
        if (verified.isEmpty()) return null
        return JsonArray(verified.mapIndexed { index, (tool, result) ->
            val maxChars = if (index == verified.lastIndex) 3600 else 900
            buildJsonObject {
                put("tool", tool)
                put("content", result.content.take(maxChars))
                put("truncated", result.content.length > maxChars)
            }
        })
    }
}
