package com.user.localagent.externalagent

import com.user.localagent.core.ConversationPrivacy
import com.user.localagent.core.CredentialRedaction
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.web.PublicWebSafety
import com.user.localagent.web.PublicCampMap
import kotlinx.serialization.json.*

/** The phone keeps the original search result. Only a safe subset of public source rows leaves it. */
internal object CloudPublicSearchProjection {
    fun from(tool: String, result: ToolResult): ToolResult? {
        if (!result.success || result.effectNotApplied || result.verification != VerificationState.VERIFIED) return null
        val data = runCatching { Json.parseToJsonElement(result.content) }.getOrNull() as? JsonObject ?: return null
        if (tool == "read_webpage") {
            if (data["untrustedSourceData"] != JsonPrimitive(true)) return null
            val url = safeUrl(data) ?: return null
            val excerpt = safeField(data, "publicExcerpt", 4000)
            val campMap=(data["campMap"] as? JsonObject)?.let {PublicCampMap.projected(url,it)}
            if(excerpt==null&&campMap==null)return null
            return result.copy(content = buildJsonObject {
                put("url", url)
                put("untrustedSourceData", true)
                put("partial", true)
                excerpt?.let {put("text",it)}
                campMap?.let {put("campMap",it.json())}
            }.toString())
        }
        if (tool != "search_web") return null
        if (data["success"] != JsonPrimitive(true) || data["shopping"] != JsonPrimitive(false) ||
            data["privateSourcesRead"] != JsonPrimitive(false) || data["untrustedSourceData"] != JsonPrimitive(true)) return null
        val items = data["items"] as? JsonArray ?: return null
        val safeRows = items.take(5).mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val url = safeUrl(row) ?: return@mapNotNull null
            val title = safeField(row, "title", 300) ?: return@mapNotNull null
            buildJsonObject {
                put("url", url)
                put("title", title)
                safeField(row, "snippet", 300)?.let { put("snippet", it) }
                safeField(row, "fetchedAt", 40)?.let { put("fetchedAt", it) }
            }
        }
        if (safeRows.isEmpty()) return null
        val outgoing = buildJsonObject {
            put("success", true)
            put("shopping", false)
            put("privateSourcesRead", false)
            put("untrustedSourceData", true)
            put("partial", true)
            put("items", JsonArray(safeRows))
        }.toString()
        if (ConversationPrivacy.sensitiveText(outgoing)) return null
        return result.copy(content = outgoing)
    }

    /** Only the phone's verified output supplies links for a partial report. */
    fun links(execution: PhoneAgentExecution): List<String> {
        val result = execution.result
        if (execution.withheldFromCloud || !result.success || result.effectNotApplied ||
            result.verification != VerificationState.VERIFIED) return emptyList()
        val tool = execution.proposal.tool
        val content = when (tool) {
            "search_web" -> from(tool, result)?.content ?: return emptyList()
            "read_webpage" -> result.content
            else -> return emptyList()
        }
        val data = runCatching { Json.parseToJsonElement(content) }.getOrNull() as? JsonObject ?: return emptyList()
        if (data["untrustedSourceData"] != JsonPrimitive(true)) return emptyList()
        return if (tool == "read_webpage") listOfNotNull(safeUrl(data))
        else (data["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::safeUrl) }
    }

    fun partialSources(executions:Collection<PhoneAgentExecution>):List<String> {
        val sources=linkedMapOf<String,Boolean>()
        for(execution in executions){
            val pageRead=execution.proposal.tool=="read_webpage"
            for(url in links(execution))sources[url]=sources[url]==true||pageRead
        }
        return sources.entries.sortedByDescending{it.value}.take(5).map{(url,pageRead)->
            "- <$url> · "+if(pageRead)"본문을 읽었어요" else "검색에서 찾았어요"
        }
    }

    private fun safeUrl(row: JsonObject): String? {
        val url = safeField(row, "url", 2048) ?: return null
        val uri = runCatching { PublicWebSafety.requireHttps(url) }.getOrNull() ?: return null
        return url.takeIf { '#' !in it && '%' !in it &&
            (uri.rawQuery == null || PublicWebSafety.isPublicLodgingLocator(uri))
        }
    }

    private fun safeField(row: JsonObject, key: String, limit: Int): String? {
        val value = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return value.takeIf {
            it.isNotBlank() && it.length <= limit && !ConversationPrivacy.sensitiveText(it) &&
                CredentialRedaction.redact(it).count == 0
        }
    }
}
