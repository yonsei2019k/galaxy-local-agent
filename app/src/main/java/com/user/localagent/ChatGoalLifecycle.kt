package com.user.localagent

import com.user.localagent.automation.GoalLifecycleAction
import com.user.localagent.automation.GoalLifecycleReceipt
import com.user.localagent.automation.GoalLifecycleRequest
import com.user.localagent.core.ConversationSubmissionPolicy
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus
import com.user.localagent.data.ChatAccessScope
import com.user.localagent.data.ChatRunRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A linked ID alone does not authorize stopping a run belonging to different goal work. */
internal fun requireGoalRunBinding(goal:GoalDefinition,run:ChatRunRecord):QueuedChatRequest {
    check(run.id in goal.linkedRunIds&&run.threadId==goal.threadId&&run.projectId==goal.projectId){"목표 실행의 원래 대화·프로젝트 연결이 다릅니다."}
    val captured=QueuedChatRequest.fromJson(run.contextJson)
    val attached=checkNotNull(captured.goal){"목표가 연결된 실제 실행 문맥이 없습니다."}
    check(captured.runId==run.id&&captured.threadId==run.threadId&&captured.projectId==run.projectId&&captured.branchId==run.branchId&&
        captured.userMessageId==run.userMessageId&&captured.modelId==run.modelId&&captured.thinking==run.thinking&&
        attached.id==goal.id&&attached.threadId==goal.threadId&&attached.projectId==goal.projectId&&run.id in attached.linkedRunIds){"다른 목표·대화·분기의 실행을 변경하지 않았습니다."}
    return captured
}

/** Host callback. Metadata edits after PAUSE/CLEAR/UPDATE belong to ScopedGoalTools' guarded CAS. */
internal suspend fun ChatRunCoordinator.applyGoalLifecycle(req:GoalLifecycleRequest):GoalLifecycleReceipt {
    val launch=goalMutation.withLock {
        currentCoroutineContext().ensureActive()
        val caller=checkNotNull(capturedActiveRequest){"원래 목표 요청 실행이 없습니다."}
        check(req.callerRunId!=null&&caller.runId==req.callerRunId&&caller.threadId==req.scope.threadId&&caller.projectId==req.scope.projectId){"목표 요청의 원래 실행·대화·프로젝트가 변경되었습니다."}
        check(app.repository.chat.accessScope(caller.threadId)==req.scope){"목표 요청의 자료 접근 범위가 변경되었습니다."}
        val callerJob=checkNotNull(requestJob){"목표를 요청한 작업이 종료되었습니다."}
        check(callerJob.isActive){"취소된 요청으로 목표 실행을 시작하지 않았습니다."}
        check(goals.loadGuarded(req.scope)==req.goal){"목표의 저장 버전이 변경되었습니다. 최신 목표를 다시 읽어주세요."}
        when(req.action) {
            GoalLifecycleAction.START,GoalLifecycleAction.RESUME -> {
                if(req.action==GoalLifecycleAction.RESUME)check(req.goal.status==GoalStatus.PAUSED){"일시 정지한 목표만 재개할 수 있습니다."}
                queueBoundedGoalRun(req.scope,req.goal,caller.branchId,caller.modelId,caller.thinking,caller.workspaceBinding,callerJob,caller.runId)
            }
            GoalLifecycleAction.PAUSE,GoalLifecycleAction.CLEAR,GoalLifecycleAction.UPDATE -> null
        }
    }
    return launch?:quiesceGoalRuns(req.scope,req.goal,req.callerRunId)
}

/** Caller holds goalMutation. The persisted envelope is published only after exact link/readback. */
internal suspend fun ChatRunCoordinator.queueBoundedGoalRun(access:ChatAccessScope,goal:GoalDefinition,
    branchId:String,modelId:String,thinking:Boolean,expectedWorkspaceBinding:String?,callerJob:Job,parentGoalStartRunId:String?=null,
    ownerInstruction:String=goal.outcome,capturedAllowTools:Boolean=true,capturedMode:String=ConversationSubmissionPolicy.newRequestMode().wireName):GoalLifecycleReceipt {
    var reserved:String?=null
    var writtenGoal:GoalDefinition?=null
    val ownedContexts=linkedSetOf<String>()
    var published=false
    try {
        return queueMutation.withLock {
            currentCoroutineContext().ensureActive();check(callerJob.isActive){"취소된 요청의 목표 실행을 시작하지 않았습니다."}
            check(goals.loadGuarded(access)==goal){"목표의 실제 버전이 변경되었습니다."}
            val thread=checkNotNull(app.repository.chat.thread(access.threadId)){"목표의 대화가 삭제되었습니다."}
            check(thread.projectId==access.projectId&&thread.activeBranchId==branchId){"목표 요청의 원래 대화 분기가 변경되었습니다."}
            val binding=app.workspaceFor(access.projectId).bindingFingerprint()
            check(expectedWorkspaceBinding==null||expectedWorkspaceBinding==binding){"목표 요청 이후 프로젝트 폴더가 변경되었습니다."}
            val sources=goal.linkedRunIds.map {id->
                val run=checkNotNull(app.repository.chat.run(id)){"목표에 연결된 실행이 삭제되었습니다."}
                requireGoalRunBinding(goal,run)
                check(run.status !in setOf("queued","running","waiting_approval")){"같은 목표의 작업이 아직 대기·진행 중입니다. 먼저 중지하거나 완료해주세요."}
                GoalRunEvidenceSource(run.id,run.threadId,run.projectId,run.status,run.contextJson)
            }
            val queuedIds=app.repository.chat.queuedRuns().map {it.id}.toSet()
            check(goal.linkedRunIds.none {it in queuedIds}){"같은 목표의 이어쓰기 요청이 아직 대기 중입니다."}
            val priorSteps=inheritVerifiedGoalSteps(goal,sources,binding)
            val active=withContext(NonCancellable) {
                goals.saveGuarded(access,goal.copy(status=GoalStatus.ACTIVE)).also {writtenGoal=it}
            }
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            val runId=UUID.randomUUID().toString().also {reserved=it}
            val pendingGoal=active.copy(linkedRunIds=(active.linkedRunIds+runId).distinct())
            var captured:QueuedChatRequest?=null
            app.repository.chat.enqueueUserRun(access.threadId,branchId,ownerInstruction,modelId,thinking,runId=runId){user,run->
                QueuedChatRequest(run.id,access.threadId,branchId,access.projectId,ownerInstruction,modelId,thinking,emptyList(),user.id,
                    allowTools=capturedAllowTools,mode=capturedMode,previousSteps=priorSteps,
                    workspaceBinding=binding,goal=pendingGoal,parentGoalStartRunId=parentGoalStartRunId).also {captured=it}.toJson().also {ownedContexts+=it}
            }
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            val linked=withContext(NonCancellable) {
                goals.saveGuarded(access,pendingGoal).also {writtenGoal=it}
            }
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            val request=checkNotNull(captured).copy(goal=GoalDefinition.parse(linked.toJson()))
            val context=request.toJson().also {ownedContexts+=it}
            app.repository.chat.updateRunContext(runId,context)
            val saved=checkNotNull(app.repository.chat.run(runId))
            check(saved.status=="queued"&&saved.startedAt==null&&saved.contextJson==context&&
                requireGoalRunBinding(linked,saved)==request&&goals.loadGuarded(access)==linked){"목표와 실제 대기 작업의 저장 결과를 확인하지 못했습니다."}
            check(app.repository.chat.accessScope(access.threadId)==access)
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            // drain checks this original job before startRun, including cancellation after return.
            goalRunCallerJobs[runId]=callerJob
            queued.value=queued.value+request
            published=true
            if(selectedThreadId.value==access.threadId&&selectedProjectId.value==access.projectId.orEmpty())goalState.value=linked
            GoalLifecycleReceipt(runId,queuedOneBoundedRun=true)
        }
    } catch(error:Throwable) {
        if(!published)withContext(NonCancellable) {
            try {
                queueMutation.withLock {
                    reserved?.let {id->
                        goalRunCallerJobs.remove(id)
                        queued.value=queued.value.filterNot {it.runId==id}
                        app.repository.chat.run(id)?.let {run->
                            check(run.threadId==access.threadId&&run.projectId==access.projectId&&run.branchId==branchId&&run.contextJson in ownedContexts){"예약 작업이 변경되어 추정 취소하지 않았습니다."}
                            app.repository.chat.cancelQueuedRun(id)
                            check(app.repository.chat.queuedRuns().none {it.id==id}){"미완성 목표 예약의 취소를 확인하지 못했습니다."}
                        }
                    }
                    writtenGoal?.let {written->
                        if(goals.loadGuarded(access)==written) {
                            val restored=goals.saveGuarded(access,goal.copy(updatedAt=written.updatedAt))
                            if(selectedThreadId.value==access.threadId&&selectedProjectId.value==access.projectId.orEmpty())goalState.value=restored
                        }
                    }
                }
            } catch(cleanup:Throwable) {error.addSuppressed(cleanup)}
        }
        throw error
    }
}

/** Locks protect selection/CAS, but neither lock is held while waiting for a running job. */
internal suspend fun ChatRunCoordinator.quiesceGoalRuns(access:ChatAccessScope,goal:GoalDefinition,callerRunId:String?):GoalLifecycleReceipt {
    currentCoroutineContext().ensureActive()
    check(goals.loadGuarded(access)==goal){"중지할 목표의 실제 버전이 변경되었습니다."}
    var runningJob:Job?=null
    var runningId:String?=null
    var deferCaller=false
    goalMutation.withLock {queueMutation.withLock {
        check(goals.loadGuarded(access)==goal){"중지할 목표가 변경되었습니다. 최신 목표를 다시 읽어주세요."}
        check(app.repository.chat.accessScope(access.threadId)==access)
        val records=goal.linkedRunIds.map {id->checkNotNull(app.repository.chat.run(id)){"목표에 연결된 실제 실행을 찾지 못했습니다."}}
        // Validate every candidate before changing any queue or job.
        records.forEach {requireGoalRunBinding(goal,it)}
        val waiting=app.repository.chat.queuedRuns().map {it.id}.toSet()
        for(run in records.filter {it.id in waiting}) {
            app.repository.chat.cancelQueuedRun(run.id)
            check(app.repository.chat.queuedRuns().none {it.id==run.id}){"목표에 연결된 대기 작업의 취소를 확인하지 못했습니다."}
            queued.value=queued.value.filterNot {it.runId==run.id};goalRunCallerJobs.remove(run.id)
        }
        val active=capturedActiveRequest
        if(active!=null&&active.runId in goal.linkedRunIds) {
            check(active.threadId==access.threadId&&active.projectId==access.projectId&&active.goal?.id==goal.id){"다른 목표의 진행 작업을 중지하지 않았습니다."}
            if(active.runId==callerRunId) {
                pendingGoalStopRunId=active.runId;deferCaller=true
            } else {
                runningJob=checkNotNull(requestJob){"목표 실행의 현재 작업을 확인하지 못했습니다."}
                runningId=active.runId
                stop()
            }
        }
        check(records.none {it.status in setOf("running","waiting_approval")&&it.id!=active?.runId}){"현재 호스트가 소유하지 않은 목표 실행이 있어 중지를 확인하지 못했습니다."}
    }}
    runningJob?.join()
    currentCoroutineContext().ensureActive()
    runningId?.let {id->check(app.repository.chat.run(id)?.status !in setOf("queued","running","waiting_approval")){"목표 실행이 아직 종료되지 않았습니다."}}
    goalMutation.withLock {
        check(app.repository.chat.accessScope(access.threadId)==access&&goals.loadGuarded(access)==goal){"중지 중 목표 또는 자료 범위가 변경되었습니다."}
        check(app.repository.chat.queuedRuns().none {it.id in goal.linkedRunIds}){"목표와 연결된 대기 작업이 남아 있습니다."}
    }
    return GoalLifecycleReceipt(quiesced=true,deferredCallerStop=deferCaller)
}
