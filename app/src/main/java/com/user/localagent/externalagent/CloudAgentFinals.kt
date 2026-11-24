package com.user.localagent.externalagent

/** Owner-facing endings of a cloud phone-agent run that are decided without new authority. */
object CloudAgentFinals {
    const val PREPARED_PAUSE="작성 화면을 열어뒀어요. 내용을 확인한 뒤 그 앱에서 보내거나 저장해 주세요. 발송이나 저장이 끝났는지는 아직 확인하지 못했어요."
    private const val LEGACY_TOOL_FREE_PREFIX="휴대폰 도구로 확인하지 않고 클라우드 모델이 생성한 답변입니다. 기기 자료나 현재 시각을 조회한 근거는 없습니다."

    /**
     * The model reported done without a plan and without any phone call: a plain answer.
     * Any recorded phone step means work happened, so the normal evidence check must decide instead.
     */
    fun isToolFreeAnswer(resultState:String,hasPlan:Boolean,phoneCalls:Int,recordedSteps:Int):Boolean=
        resultState=="reported_done"&&!hasPlan&&phoneCalls==0&&recordedSteps==0

    fun toolFreeAnswer(answer:String):String=visibleAnswer(answer).trim()

    /** Removes the old host prefix from display and sharing without mutating saved replies. */
    fun visibleAnswer(answer: String): String {
        var body = answer
        while (body.trimStart().startsWith(LEGACY_TOOL_FREE_PREFIX)) {
            body = body.trimStart().removePrefix(LEGACY_TOOL_FREE_PREFIX).trimStart()
        }
        return body
    }

    /** True when an executed tool changed or handed off something; then the host report is authoritative. */
    fun needsHostReport(executedTools:Collection<String>):Boolean=executedTools.any{name->
        name in com.user.localagent.core.ActionCompletionReport.managedTools&&!com.user.localagent.core.ToolReadPolicy.isReadOnly(name)
    }
}
