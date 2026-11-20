package com.user.localagent.externalagent

import kotlinx.serialization.json.*

/** The provider can suggest one change. Only the settings screen can apply it. */
internal data class SettingsProposal(val setting: String, val value: String) {
    companion object {
        const val TOOL = "suggest_setting"
        private val values = mapOf(
            "language" to setOf("ko", "en", "auto"),
            "voice" to setOf("asr-mlkit", "asr-qwen-06b", "asr-qwen-17b"),
            "image" to setOf("pc", "phone"),
            "open" to setOf("connections", "privacy", "automation", "updates", "voice", "manual"),
        )

        val schema = buildJsonObject {
            put("name", TOOL)
            put("description", "Suggest one app setting change or link to its manual settings.")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("setting", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(values.keys.map(::JsonPrimitive)))
                    })
                    put("value", buildJsonObject { put("type", "string"); put("maxLength", 40) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("setting"), JsonPrimitive("value"))))
            })
        }

        fun parse(arguments: JsonObject): SettingsProposal {
            require(arguments.keys == setOf("setting", "value"))
            val setting = arguments.getValue("setting").jsonPrimitive
            val value = arguments.getValue("value").jsonPrimitive
            require(setting.isString && value.isString && value.content in values[setting.content].orEmpty())
            return SettingsProposal(setting.content, value.content)
        }
    }
}
