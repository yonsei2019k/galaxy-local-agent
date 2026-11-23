package com.user.localagent.core

import kotlinx.serialization.json.*

/** Keeps exact snapshot and element references visible without flooding the local planning history. */
object ScreenObservationData {
    private const val MAX_CHARS = 4200
    private const val MAX_ELEMENTS = 30
    private const val MAX_TEXTS = 8

    fun forModel(raw: String): String {
        val screen = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return compactJsonData(raw, MAX_CHARS)
        val elements = (screen["elements"] as? JsonArray).orEmpty()
        val texts = (screen["texts"] as? JsonArray).orEmpty()
        val visibleElements = mutableListOf<JsonElement>()
        val visibleTexts = mutableListOf<JsonElement>()
        val base = screen.filterKeys { it in setOf(
            "untrustedScreenData", "packageName", "app", "snapshotId", "lastAction", "screenChanged", "waitStatus", "note",
        ) }
        fun render(): String {
            val partial = screen["truncated"] == JsonPrimitive(true) ||
                visibleElements.size < elements.size || visibleTexts.size < texts.size
            val observation = JsonObject(base + mapOf(
                "truncated" to JsonPrimitive(partial),
                "elements" to JsonArray(visibleElements),
                "texts" to JsonArray(visibleTexts),
            ))
            return buildJsonObject {
                put("data", observation)
                put("truncated", partial)
                put("sourceScopePartial", partial)
                put("dataOnly", true)
            }.toString()
        }
        for (element in elements.take(MAX_ELEMENTS)) {
            visibleElements.add(element)
            if (render().length > MAX_CHARS - 600) {
                visibleElements.removeAt(visibleElements.lastIndex)
                break
            }
        }
        for (text in texts.take(MAX_TEXTS)) {
            visibleTexts.add(text)
            if (render().length > MAX_CHARS) {
                visibleTexts.removeAt(visibleTexts.lastIndex)
                break
            }
        }
        return render().takeIf { it.length <= MAX_CHARS } ?: compactJsonData(raw, MAX_CHARS)
    }
}
