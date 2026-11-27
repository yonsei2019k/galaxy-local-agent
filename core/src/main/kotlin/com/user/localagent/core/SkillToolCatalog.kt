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
            "Read one complete, hash-checked workflow from signed app assets by catalog id. For complex tasks, use relevant guidance before acting: browser-task for interactive sites, mail-triage for inbox follow-up, meeting-prep for meeting materials and actions, task-continuity for resuming persistent work. Guidance uses only tools offered to this request and does not complete it.")
    )
}
