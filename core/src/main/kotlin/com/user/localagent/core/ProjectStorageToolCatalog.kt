package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Observation of the selected project only. Neither tool deletes, moves or grants new storage access. */
object ProjectStorageToolCatalog {
    val definitions=listOf(
        ToolDefinition("inspect_project_storage",Risk.LOW,mapOf("path" to Argument("string",false,500)),
            "Inspect files only inside the currently selected project: observed sizes, largest files and extension groups. Bounded depth4/500 entries; report partial coverage. Does not inspect the whole phone, delete, move, or classify photo contents."),
        ToolDefinition("find_project_duplicates",Risk.LOW,mapOf("path" to Argument("string",false,500)),
            "Find byte-identical duplicate files only inside the selected project, including selected-folder photos. Compare actual size and SHA-256 with depth4/500 entries,16MiB/file and64MiB total hashing limits. Reports observed candidates and skipped coverage; never deletes or finds visually similar photos.",120_000)
    )
    val names=definitions.map {it.name}.toSet()
    fun validate(call:ToolCall){
        require(call.name in names){"지원하지 않는 프로젝트 저장소 도구입니다."}
        require(call.arguments.keys.all {it=="path"}){"허용되지 않은 프로젝트 저장소 인자입니다."}
        call.arguments["path"]?.let {value->
            require(value is JsonPrimitive&&value.isString&&value.content.length<=500&&'\u0000' !in value.content){"프로젝트 상대 경로 문자열이 필요합니다."}
            if(value.content.isNotBlank())ProjectPaths.segments(value.content)
        }
    }
    fun path(call:ToolCall):String {
        validate(call)
        return call.arguments["path"]?.jsonPrimitive?.content?.takeUnless {it.isBlank()}.orEmpty()
    }
}
