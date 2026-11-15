package com.user.localagent.core

/** Capability relation after a verified read. Never interprets owner wording or bypasses exact confirmation. */
object PostReadActions {
    private val related=mapOf(
        "search_contacts" to setOf("prepare_sms"),
        "search_notes" to setOf("save_note","update_note","delete_note","read_note"),
        "read_note" to setOf("patch_note"),
        "list_reminders" to setOf("create_reminder","update_reminder","delete_reminder"),
        "get_current_datetime" to setOf("create_reminder","create_alarm","prepare_calendar_event"),
        "calculate" to setOf("save_note"),
        "list_project_files" to setOf("read_project_file","write_project_file","inspect_project_storage","find_project_duplicates"),
        "read_project_file" to setOf("write_project_file")
    )
    fun offered(plan:SemanticRequestPlan?,steps:List<HarnessStep>,capturedOffered:Set<String>):Set<String> {
        if(plan?.action!=SemanticAction.TOOL_TASK||steps.any{it.evidence.state==VerificationState.PREPARED})return emptySet()
        return steps.filter{it.tool in plan.requiredTools&&it.evidence.state==VerificationState.VERIFIED}
            .flatMap{related[it.tool].orEmpty()}.toSet().intersect(capturedOffered)-plan.requiredTools
    }
    fun bind(plan:SemanticRequestPlan,steps:List<HarnessStep>,selected:String,capturedOffered:Set<String>):SemanticRequestPlan {
        require(selected in offered(plan,steps,capturedOffered)){"실제로 조회한 자료와 원래 허용 도구 범위의 후속 행동만 연결할 수 있습니다."}
        val updated=plan.copy(requiredTools=plan.requiredTools+selected)
        return SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(updated),capturedOffered)
    }
}
