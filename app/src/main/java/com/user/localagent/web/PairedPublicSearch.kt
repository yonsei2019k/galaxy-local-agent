package com.user.localagent.web

import android.content.Context
import com.user.localagent.compute.PcTransport
import com.user.localagent.core.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Only bound by the cloud run coordinator after it captures the public search tool grant. */
internal class PairedPublicSearch(
    private val context: Context,
    private val allowed: suspend (String) -> Boolean,
) {
    suspend fun read(call: ToolCall): JsonObject? {
        val store = call.arguments["store"]?.jsonPrimitive?.content
        val kind = when {
            call.name == "search_web" && call.arguments["source"]?.jsonPrimitive?.content == "kopis" -> "kopis"
            call.name == "search_web" && call.arguments["source"]?.jsonPrimitive?.content == "naver_places" -> "local"
            call.name == "search_shopping" && (store == null || store == "naver") -> "shop"
            else -> return null
        }
        if (!allowed(call.name)) return null
        val transport = PcTransport(context)
        val config = try {
            transport.available()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return null
        val kopis = kind == "kopis"
        val healthKey = if (kopis) "kopisPublicSearch" else "naverPublicSearch"
        if (transport.lastHealth?.get(healthKey) != JsonPrimitive(true)) return null
        check(allowed(call.name)) { "검색 연결 권한이 변경됐어요." }
        val body = buildJsonObject {
            if (kopis) {
                for (key in listOf("startDate", "endDate", "page")) call.arguments[key]?.let { put(key, it) }
            } else put("kind", kind)
            put("query", call.arguments.getValue("query"))
        }
        val result = try {
            transport.json(config, if (kopis) "/v1/public-search/kopis" else "/v1/public-search/naver", "POST", body, 6500)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return null
        }
        check(allowed(call.name)) { "검색 연결 권한이 변경됐어요." }
        return result.takeIf { it["success"] == JsonPrimitive(true) && it["items"] is JsonArray &&
            it.getValue("items").jsonArray.size in 1..5 }
    }

    companion object {
        fun combinedShopping(naver: JsonObject, other: JsonObject): JsonObject {
            val prior = (other["items"] as? JsonArray).orEmpty()
            val listed = naver.getValue("items").jsonArray
            val priorByUrl = prior.filterIsInstance<JsonObject>().associateBy { it["url"] }
            val listingRows = listed.map { row ->
                val listing = row.jsonObject
                val details = priorByUrl[listing["url"]]
                // A matching search hit cannot displace stronger observations from the actual product page.
                if (details == null) listing else JsonObject(details + ("naverListing" to listing))
            }
            val mixed = (listingRows.take(2) + prior + listingRows.drop(2))
                .distinctBy { it.jsonObject["url"] }.take(5)
            return JsonObject(other + mapOf("success" to JsonPrimitive(true), "items" to JsonArray(mixed),
                "partial" to JsonPrimitive(other["partial"] == JsonPrimitive(true) ||
                    other["success"] != JsonPrimitive(true) || (listed + prior).distinctBy { it.jsonObject["url"] }.size > mixed.size)))
        }
    }
}
