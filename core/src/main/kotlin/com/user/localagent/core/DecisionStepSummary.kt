package com.user.localagent.core

/** Host-observed step summary for the owner. It states facts about the loop, never model thoughts or arguments. */
object DecisionStepSummary {
    fun beforeTool(tool:String,round:Int,maxRounds:Int,remainingRequired:Set<String>,lastTool:String?,lastState:VerificationState?):String=buildString {
        append("단계 ").append(round+1).append('/').append(maxRounds).append(" · 선택: ").append(tool)
        append("\n이유: ")
        append(when{
            tool in remainingRequired->"이 요청에 아직 확인되지 않은 필수 도구입니다."
            lastTool!=null&&lastState!=VerificationState.VERIFIED->"직전 $lastTool 결과가 검증되지 않아 모델이 다음 행동으로 골랐습니다."
            else->"모델이 현재 결과를 보고 다음 행동으로 골랐습니다."
        })
        append("\n다음 확인: ")
        val others=remainingRequired-tool
        append(if(others.isEmpty())"이 결과를 검증한 뒤 답변이나 추가 확인을 판단합니다." else "이 결과 검증 후 남은 필수 도구 ${others.sorted().joinToString()}")
    }
}
