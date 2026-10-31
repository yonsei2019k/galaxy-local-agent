package com.user.localagent.core

/** Reads of application-bundled guidance. Never private-data reads or completed owner actions. */
object SkillToolCatalog {
    val names:Set<String> = setOf("list_skills", "read_skill")
    val definitions:List<ToolDefinition> = listOf(
        ToolDefinition("list_skills", Risk.LOW,
            mapOf("query" to Argument("string", required=false, maxLength=200)),
            "List the app's reviewed workflow metadata and related tools. Does not install software, grant access, or perform the owner's task."),
        ToolDefinition("read_skill", Risk.LOW,
            mapOf("id" to Argument("string", maxLength=64)),
            "Read one complete, hash-checked workflow from signed app assets by catalog id. Instructions guide existing tools; reading is not task completion or a new permission.")
    )
}
