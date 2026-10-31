package com.user.localagent

import com.user.localagent.core.*

/** Host Room run metadata and its encrypted context, never a user message or imported document. */
internal data class GoalRunEvidenceSource(val runId:String,val threadId:String,val projectId:String?,val status:String,val contextJson:String)

internal fun sameGoalWork(left:GoalDefinition,right:GoalDefinition):Boolean =
    left.id==right.id&&left.threadId==right.threadId&&left.projectId==right.projectId&&
        left.outcome==right.outcome&&left.constraints==right.constraints&&
        left.criteria.map {it.id to it.text}==right.criteria.map {it.id to it.text}

/** Only same-work, same-scope host-verified steps can suppress a repeated write. */
internal fun inheritVerifiedGoalSteps(goal:GoalDefinition,sources:List<GoalRunEvidenceSource>,workspaceBinding:String):List<HarnessStep> {
    goal.validate()
    val result=mutableListOf<HarnessStep>()
    for(source in sources){
        if(source.runId !in goal.linkedRunIds||source.threadId!=goal.threadId||source.projectId!=goal.projectId)continue
        val request=runCatching {QueuedChatRequest.fromJson(source.contextJson)}.getOrNull()?:continue
        val previousGoal=request.goal?:continue
        if(request.runId!=source.runId||request.threadId!=source.threadId||request.projectId!=source.projectId||
            !request.allowTools||request.mode !in setOf("act","debug")||!sameGoalWork(goal,previousGoal))continue
        check(source.status !in setOf("queued","running")){"같은 목표의 실행이 아직 대기·진행 중입니다. 완료하거나 중지한 뒤 다시 추진해주세요."}
        for(step in request.previousSteps){
            if(step.evidence.state!=VerificationState.VERIFIED||step.tool in SkillToolCatalog.names||step.tool in GoalToolCatalog.names)continue
            if(step.tool in setOf("list_project_files","read_project_file","write_project_file")&&request.workspaceBinding!=workspaceBinding)continue
            val call=runCatching {ToolRegistry.validate(ToolCall("goal-evidence",step.tool,step.arguments))}.getOrNull()?:continue
            if(result.none {it.tool==call.name&&it.arguments==call.arguments})result+=HarnessStep(call,
                StepEvidence(call.name,VerificationState.VERIFIED,"이전 동일 목표 실행에서 검증됨 · 전체 목표 완료를 뜻하지 않음 · "+step.evidence.summary.safeHead(3800)))
        }
    }
    check(result.size<=6){"같은 목표의 이전 검증 단계가 실행 예산을 넘습니다. 기존 결과를 확인하고 남은 목표를 나누어 추진해주세요. 검증 근거를 버리고 작업을 반복하지 않았습니다."}
    return result.toList()
}
