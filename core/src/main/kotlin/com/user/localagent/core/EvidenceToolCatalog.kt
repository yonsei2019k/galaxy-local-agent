package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Public scholarly metadata only. Account/document contents are not implicit search inputs. */
object EvidenceToolCatalog {
    private fun text(required: Boolean = true, max: Int = 500) = Argument("string", required, max)
    val definitions = listOf(
        ToolDefinition("search_publications", Risk.LOW, mapOf(
            "query" to text(), "originalQuery" to text(false), "limit" to Argument("integer", false),
        ), "Search Europe PMC biomedical/life-science publications with a public topic query; no account/key needed. query is transmitted publicly; optional originalQuery preserves the public topic before translation/expansion. Returns at most5 titles, identifiers, dates, abstract excerpts, publication types and licenses. Metadata/abstracts are evidence, not proof of a claim or permission to reproduce full text.", 45_000),
        ToolDefinition("verify_reference", Risk.LOW, mapOf(
            "doi" to text(false, 250), "citation" to text(false, 500), "limit" to Argument("integer", false),
        ), "Look up one public DOI or find at most3 Crossref bibliographic candidates from a public citation. Supply exactly one of doi/citation. A citation consisting only of a DOI or doi.org URL is an identifier lookup. Returns deposited metadata and available update/retraction links. Candidates need assessment; not_found does not prove a citation is fabricated. No account/key needed.", 45_000),
    )
    val names = definitions.map { it.name }.toSet()

    /** Also used by the standalone handler; central registry must call this after basic validation. */
    fun validate(call: ToolCall) {
        val definition = definitions.firstOrNull { it.name == call.name }
            ?: error("지원하지 않는 공개 서지 도구입니다.")
        require(call.arguments.keys.all { it in definition.arguments }) { "허용되지 않은 서지 도구 인자입니다." }
        definition.arguments.forEach { (name, spec) ->
            val value = call.arguments[name]
            require(value != null || !spec.required) { "필수 인자가 없습니다: $name" }
            if (value != null) when (spec.type) {
                "string" -> require(value is JsonPrimitive && value.isString &&
                    value.content.isNotBlank() && value.content.length <= spec.maxLength &&
                    value.content.none { it.code < 32 || it.code == 127 }) { "$name 문자열 형식 또는 길이 오류" }
                "integer" -> require(value is JsonPrimitive && !value.isString && value.intOrNull != null) { "$name 정수 필요" }
            }
        }
        call.arguments["limit"]?.let { value ->
            val limit = (value as JsonPrimitive).intOrNull
            require(limit in 1..if (call.name == "search_publications") 5 else 3) { "서지 조회 개수 상한을 초과했습니다." }
        }
        if (call.name == "verify_reference") {
            require(call.arguments.containsKey("doi") xor call.arguments.containsKey("citation")) {
                "doi 또는 citation 중 하나만 입력해주세요."
            }
        }
    }
}
