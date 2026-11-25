package com.user.localagent.core.connections

import java.util.UUID
import kotlinx.serialization.json.*

internal object NotionBlocks {
    val headers = mapOf("Notion-Version" to "2026-03-11")
    private val uuid = Regex("[a-fA-F0-9]{8}(-?)[a-fA-F0-9]{4}\\1[a-fA-F0-9]{4}\\1[a-fA-F0-9]{4}\\1[a-fA-F0-9]{12}")

    fun blockId(raw: String): String {
        require(uuid.matches(raw))
        val hex = raw.replace("-", "")
        return UUID.fromString(listOf(hex.take(8), hex.substring(8,12), hex.substring(12,16), hex.substring(16,20), hex.drop(20)).joinToString("-")).toString()
    }

    fun offset(raw: String): Int {
        require(Regex("0|[1-9][0-9]{0,7}").matches(raw))
        return raw.toInt()
    }

    fun children(parsed: JsonElement?): JsonArray {
        val page = requireNotNull(parsed).jsonObject
        require(page["object"]?.jsonPrimitive?.content == "list")
        val more = requireNotNull(page["has_more"]?.jsonPrimitive?.booleanOrNull)
        if (more) require(!page["next_cursor"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        val rows = page.getValue("results").jsonArray
        require(rows.size <= 8)
        return JsonArray(rows.map { project(it.jsonObject, 0, 160) })
    }

    fun single(parsed: JsonElement?, expected: String, start: String): JsonObject {
        val block = requireNotNull(parsed).jsonObject
        require(blockId(block.getValue("id").jsonPrimitive.content) == blockId(expected))
        return project(block, offset(start), 1600)
    }

    private fun project(block: JsonObject, start: Int, size: Int): JsonObject {
        require(block.getValue("object").jsonPrimitive.content == "block")
        val id = blockId(block.getValue("id").jsonPrimitive.content)
        val type = block.getValue("type").jsonPrimitive.content
        val content = block.getValue(type).jsonObject
        val hasChildren = requireNotNull(block["has_children"]?.jsonPrimitive?.booleanOrNull)
        fun richText(runs: JsonElement): String = runs.jsonArray.joinToString("") {
            it.jsonObject.getValue("plain_text").jsonPrimitive.content
        }
        val text = when {
            "rich_text" in content -> richText(content.getValue("rich_text"))
            "caption" in content -> richText(content.getValue("caption"))
            type == "table_row" -> content.getValue("cells").jsonArray.joinToString(" | ") { richText(it) }
            type in setOf("child_page","child_database") -> content.getValue("title").jsonPrimitive.content
            type == "equation" -> content.getValue("expression").jsonPrimitive.content
            else -> null
        }
        return buildJsonObject {
            put("id", id)
            put("type", type)
            put("hasChildren", hasChildren)
            if (type == "to_do") put("checked", content.getValue("checked").jsonPrimitive.boolean)
            put("textAvailable", text != null)
            if (text == null) {
                require(start == 0)
            } else {
                require(start <= text.length && (start == text.length || !text[start].isLowSurrogate()))
                var end = minOf(text.length, start + size)
                if (end < text.length && text[end].isLowSurrogate()) end--
                put("text", text.substring(start, end))
                if (end < text.length) put("nextOffset", end.toString())
            }
        }
    }
}
