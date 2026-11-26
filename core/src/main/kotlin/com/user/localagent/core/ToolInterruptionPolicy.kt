package com.user.localagent.core

/** Classifies the host's actual tool timeout. No result, permission or completion is invented.
 * A read deadline is not an uncertain save/send. Unknown tools and all effects stay uncertain.
 * Deliberately enumerated registered contracts; neither LOW risk nor a read-like name is enough.
 */
object ToolInterruptionPolicy {
    private val noMutationOnTimeout=setOf(
        "get_current_datetime","calculate","search_web","search_shopping","read_webpage",
        "search_notes","read_note","read_project_file","list_reminders","read_app_browser_page","read_pc_browser_page",
    )

    fun hasNoMutation(name:String):Boolean =
        name in noMutationOnTimeout && ToolRegistry.definitions[name]?.risk == Risk.LOW

    fun observed(call:ToolCall,result:ToolResult):ToolResult {
        if(call.id!=result.id || !hasNoMutation(call.name) || result.success ||
            result.verification !in setOf(VerificationState.FAILED,VerificationState.UNVERIFIED))return result
        return result.copy(verification=VerificationState.FAILED,effectNotApplied=true)
    }

    fun timedOut(call:ToolCall):ToolResult {
        return if(hasNoMutation(call.name))ToolResult(call.id,false,
            "조회가 제한 시간 안에 끝나지 않아 결과를 얻지 못했습니다. 이 도구는 데이터를 변경하지 않았습니다. 기존 요청 범위에서 다른 조회 방법을 선택할 수 있습니다.",
            VerificationState.FAILED,"read_timeout_no_mutation",effectNotApplied=true)
        else ToolResult(call.id,false,
            "제한 시간 안에 실행 결과를 확인하지 못했습니다. 같은 작업을 다시 실행하기 전에 기존 결과를 확인해야 합니다.",
            VerificationState.UNVERIFIED,"tool_timeout_unknown_effect")
    }
}
