package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.serialization.json.*

private val goalLaunchTools=setOf(GoalToolCatalog.START,GoalToolCatalog.RESUME)
private val goalLaunchUuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

/** A durable control receipt binds one child run; it is never business-goal completion evidence. */
internal fun goalToolStepEvidence(call:ToolCall,result:ToolResult):StepEvidence {
    if(call.name !in goalLaunchTools||!result.success||result.verification!=VerificationState.VERIFIED)
        return AnalysisCompletionEvidence.fromResult(call,result)
    require(result.id==call.id&&result.content.length<=32_000){"목표 실행 영수증의 호출 연결이 올바르지 않습니다."}
    val payload=Json.parseToJsonElement(result.content).jsonObject
    fun trueBoolean(key:String):Boolean=payload[key]?.let {it is JsonPrimitive&&!it.isString&&it.booleanOrNull==true}==true
    require(trueBoolean("scopeBound")&&trueBoolean("queuedOneBoundedRun")){"범위가 지정된 실제 목표 실행 예약을 확인하지 못했습니다."}
    val child=payload["queuedRunId"]?.let {it as? JsonPrimitive}?.takeIf {it.isString}?.content
    require(child!=null&&goalLaunchUuid.matches(child)){"예약된 목표 실행의 실제 식별자가 올바르지 않습니다."}
    require(payload["goalId"]?.jsonPrimitive?.content==call.arguments["goalId"]?.jsonPrimitive?.content){"목표 실행 영수증의 목표가 요청과 다릅니다."}
    return StepEvidence(call.name,result.verification,result.verificationSummary,"goal-run:$child")
}

/** Pure launch fence. Caller loads the live guarded goal and actual parent row before invoking it. */
internal fun requireGoalLaunchBinding(request:QueuedChatRequest,current:GoalDefinition?,parent:ChatRunRecord?,callerCancelled:Boolean) {
    val captured=request.goal?:return
    check(!callerCancelled){"목표 시작을 요청한 작업이 취소되어 새 실행을 시작하지 않았습니다."}
    val goal=checkNotNull(current){"연결된 목표가 삭제되어 실행을 시작하지 않았습니다."}
    check(goal.status==GoalStatus.ACTIVE&&sameGoalWork(captured,goal)&&request.runId in goal.linkedRunIds){"목표의 작업·상태·실행 연결이 변경되어 시작하지 않았습니다."}
    val parentId=request.parentGoalStartRunId?:return // Existing explicit GUI submissions retain their old captured mode.
    val row=checkNotNull(parent){"목표 시작 요청의 실제 실행 기록이 없습니다."}
    check(row.id==parentId&&row.id!=request.runId&&row.threadId==request.threadId&&row.projectId==request.projectId&&
        row.branchId==request.branchId&&row.modelId==request.modelId&&row.thinking==request.thinking&&
        row.status in setOf("completed","failed")){"목표 시작 요청이 취소·중단되었거나 원래 실행 범위가 다릅니다."}
    val original=QueuedChatRequest.fromJson(row.contextJson)
    check(original.runId==row.id&&original.threadId==row.threadId&&original.projectId==row.projectId&&original.branchId==row.branchId&&
        original.userMessageId==row.userMessageId&&original.modelId==row.modelId&&original.thinking==row.thinking&&
        original.allowTools&&original.mode in setOf("act","debug")&&original.workspaceBinding==request.workspaceBinding){"목표 시작 요청의 불변 문맥과 실행 범위가 일치하지 않습니다."}
    check(original.previousSteps.any {step->
        step.tool in goalLaunchTools&&step.evidence.state==VerificationState.VERIFIED&&
            step.arguments["goalId"]?.jsonPrimitive?.content==goal.id&&step.evidence.continuationId=="goal-run:${request.runId}"
    }){"이 목표 실행 한 건에 연결된 실제 시작·재개 영수증이 없습니다."}
}
