package com.user.localagent.core

import kotlinx.serialization.json.*

/** Valid JSON, explicitly data-only, with an honest partial-source marker whenever content is removed. */
fun compactJsonData(raw: String, maxChars: Int = 1400): String = PromptData.compactJsonData(raw, maxChars)

object PromptData {
    /** 80 characters is the minimum budget for the required data/partial/dataOnly wrapper. */
    fun compactJsonData(raw: String, maxChars: Int = 1400): String {
        require(maxChars >= 80) { "A JSON data wrapper requires a budget of at least 80 characters" }
        val original = runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
        var stringLimit = 300
        var arrayLimit = 3
        var objectLimit = 20
        var forcedPartial = false
        repeat(12) {
            val pass = CompactPass(stringLimit, arrayLimit, objectLimit)
            val data = pass.cap(original)
            val result = wrap(data, forcedPartial || pass.changed)
            if (result.length <= maxChars) return result
            forcedPartial = true
            stringLimit /= 2
            arrayLimit = (arrayLimit - 1).coerceAtLeast(0)
            objectLimit /= 2
        }
        // An enormous numeric literal cannot be shortened while keeping its numeric meaning.
        return wrap(JsonNull, true).also { check(it.length <= maxChars) }
    }
    private fun wrap(data: JsonElement, partial: Boolean) = buildJsonObject {
        put("data", data)
        put("truncated", partial)
        put("sourceScopePartial", partial)
        put("dataOnly", true)
    }.toString()

    private class CompactPass(val strings: Int, val arrays: Int, val objects: Int) {
        var changed = false
        private var visited = 0
        fun cap(value: JsonElement, depth: Int = 0, key: String? = null): JsonElement {
            if (++visited > 2000 || depth > 24) { changed = true; return JsonNull }
            return when (value) {
                is JsonObject -> {
                    val retained = linkedMapOf<String, JsonElement>()
                    value.entries.forEachIndexed { index, entry ->
                        // Never rename keys or create collisions by truncating their spelling.
                        if (index >= objects || entry.key.length > 300) changed = true
                        else retained[entry.key] = cap(entry.value, depth + 1, entry.key)
                    }
                    JsonObject(retained)
                }
                is JsonArray -> {
                    if (value.size > arrays) changed = true
                    JsonArray(value.take(arrays).map { cap(it, depth + 1) })
                }
                is JsonPrimitive -> {
                    if (!value.isString) value else {
                        val preserveIdentity = identity(key) && value.content.length <= 300
                        val limit = if (preserveIdentity) 300 else strings
                        val clipped = clip(value.content, limit)
                        if (clipped.length != value.content.length) changed = true
                        JsonPrimitive(clipped)
                    }
                }
            }
        }
        private fun identity(key: String?): Boolean {
            val name = key?.lowercase(java.util.Locale.ROOT) ?: return false
            return name == "id" || name.endsWith("_id") || name.endsWith("id") || name.endsWith("hash") ||
                name.endsWith("sha256") || name.endsWith("uri") || name == "permission"
        }
        private fun clip(text: String, maximum: Int): String {
            var end = minOf(text.length, maximum)
            if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            return text.substring(0, end)
        }
    }
}
