package com.user.localagent.core

/** Capability relation after a verified read. Never interprets owner wording or bypasses exact confirmation. */
object PostReadActions {
    fun offered(plan:SemanticRequestPlan?,steps:List<HarnessStep>,capturedOffered:Set<String>):Set<String> {
        if(plan?.action!=SemanticAction.TOOL_TASK||"search_contacts" !in plan.requiredTools||"prepare_sms" in plan.requiredTools)return emptySet()
        if(steps.any{it.evidence.state==VerificationState.PREPARED}||steps.none{it.tool=="search_contacts"&&it.evidence.state==VerificationState.VERIFIED})return emptySet()
        return setOf("prepare_sms").intersect(capturedOffered)
    }
    fun bind(plan:SemanticRequestPlan,steps:List<HarnessStep>,selected:String,capturedOffered:Set<String>):SemanticRequestPlan {
        require(selected in offered(plan,steps,capturedOffered)){"실제로 조회한 자료와 원래 허용 도구 범위의 후속 행동만 연결할 수 있습니다."}
        val updated=plan.copy(requiredTools=plan.requiredTools+selected)
        return SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(updated),capturedOffered)
    }
}
