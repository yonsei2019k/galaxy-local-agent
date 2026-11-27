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
                queueBoundedGoalRun(req.scope,req.goal,caller.branchId,caller.modelId,caller.thinking,caller.workspaceBinding,callerJob,caller.runId,
                    ownerInstruction=if(req.goal.linkedRunIds.isEmpty()&&caller.text.length<=6000)caller.text else req.goal.outcome,
                    capturedAllowTools=caller.allowTools,capturedMode=caller.mode,capturedPcDataBinding=caller.pcDataBinding,
                    capturedPcRevision=caller.capturedPcRevision,allowCloudText=caller.allowCloudText,
                    capturedAllowExternalAgent=caller.allowExternalAgent,capturedExternalAgentRevision=caller.externalAgentRevision,
                    capturedKakaoReadBinding=caller.kakaoReadBinding,capturedPcImageRecoveryAllowed=caller.pcImageRecoveryAllowed,
                    capturedPcTextDefault=caller.capturedPcTextDefault)
            }
            GoalLifecycleAction.PAUSE,GoalLifecycleAction.CLEAR,GoalLifecycleAction.UPDATE -> null
        }
    }
    return launch?:quiesceGoalRuns(req.scope,req.goal,req.callerRunId)
}

/** Caller holds goalMutation. The persisted envelope is published only after exact link/readback. */
internal suspend fun ChatRunCoordinator.queueBoundedGoalRun(access:ChatAccessScope,goal:GoalDefinition,
    branchId:String,modelId:String,thinking:Boolean,expectedWorkspaceBinding:String?,callerJob:Job,parentGoalStartRunId:String?=null,
    ownerInstruction:String=goal.outcome,capturedAllowTools:Boolean=true,capturedMode:String=ConversationSubmissionPolicy.newRequestMode().wireName,
    capturedPcDataBinding:String?=capturePcDataBindingForNewRun(access.threadId),capturedPcRevision:Long?=null,allowCloudText:Boolean=false,
    capturedAllowExternalAgent:Boolean=false,capturedExternalAgentRevision:Long?=null,
    capturedKakaoReadBinding:String?=null,capturedPcImageRecoveryAllowed:Boolean=false,
    nextSegment:GoalSegmentDecision.Continue?=null,capturedPcTextDefault:String?=null,
    ownerReplyTo:String?=null):GoalLifecycleReceipt {
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
            // Automatic children already inherit the parent's checked ledger. Validate all original
            // rows one by one; retaining every prior encrypted envelope here grows quadratically.
            val continuationParent=nextSegment?.let {
                val id=checkNotNull(it.binding.parentRunId)
                check(goal.linkedRunIds.lastOrNull()==id)
                requireGoalRunBinding(goal,checkNotNull(app.repository.chat.run(id)))
            }
            val sources=mutableListOf<GoalRunEvidenceSource>()
            for(id in goal.linkedRunIds) {
                val run=checkNotNull(app.repository.chat.run(id)){"목표에 연결된 실행이 삭제되었습니다."}
                val historical=requireGoalRunBinding(goal,run)
                continuationParent?.let {requireSameGoalAuthority(historical,it)}
                check(!goalSourceNativeEffectsUnresolved(run)){"이전 목표 실행의 공통 기록이 없거나 효과가 불확실해 새 실행으로 우회하지 않았습니다."}
                check(run.threadRevision==access.threadRevision&&run.projectRevision==(access.projectId?.let {app.repository.chat.project(it)?.revision}?:0L)) {
                    "원래 목표 실행 이후 대화·프로젝트 권한 버전이 변경되었습니다. 이전 승인을 확대하지 않았습니다."
                }
                check(run.status !in setOf("queued","running","waiting_approval")){"같은 목표의 작업이 아직 대기·진행 중입니다. 먼저 중지하거나 완료해주세요."}
                if(continuationParent==null||id==continuationParent.runId)
                    sources+=GoalRunEvidenceSource(run.id,run.threadId,run.projectId,run.status,run.contextJson)
            }
            val queuedIds=app.repository.chat.queuedRuns().map {it.id}.toSet()
            check(goal.linkedRunIds.none {it in queuedIds}){"같은 목표의 이어쓰기 요청이 아직 대기 중입니다."}
            check(capturedAllowTools&&capturedMode in setOf("act","debug")){"읽기 전용·도구 미허용 요청에서 새 목표 실행 권한을 만들지 않았습니다."}
            val now=System.currentTimeMillis()
            val answered=ownerReplyTo?.let {id->
                check(nextSegment==null&&goal.linkedRunIds.lastOrNull()==id)
                checkNotNull(app.repository.chat.run(id)).also {
                    check(it.status=="paused"&&it.errorCode==GOAL_OWNER_INPUT&&it.branchId==branchId)
                }
            }
            val idle=answered?.let {check(it.updatedAt in 0..now); now-it.updatedAt}?:0
            val entryBudget=if(nextSegment==null)goalEntryBudget(goal,sources,now,idle)else null
            val parent=nextSegment?.let {next->
                check(parentGoalStartRunId==null)
                val row=checkNotNull(app.repository.chat.run(checkNotNull(next.binding.parentRunId)))
                val decision=goalSegmentDecision(row,goal,access.scopeKey,System.currentTimeMillis(),unknownEffects=false,useNativeBudget=true)
                check(decision is GoalSegmentDecision.Continue&&decision.binding==next.binding&&
                    decision.ledger.map {it.json()}==next.ledger.map {it.json()}){"목표의 최종 체크포인트와 누적 실행 기록이 변경되었습니다."}
                row
            }
            val ownerReadback=answered?.let {
                val original=QueuedChatRequest.fromJson(it.contextJson)
                GoalReadbackSeed.from(original,assessGoalReadback(original,access.scopeKey))
            }
            val history=nextSegment?.ledger?:collectGoalLedger(goal,sources,binding,access.scopeKey,answered?.id)
            val archive=GoalObservationIndex.decode("")
            val priorLedger=if(nextSegment!=null)history else compactGoalLedger(history,archive)
                ?:throw GoalAutopilotPaused(GoalAutopilotPauseReason.TOOL_BUDGET)
            if(entryBudget!=null&&history.size-entryBudget.phaseStartCalls>=GoalSegmentBinding.PHASE_TOOL_CALLS)throw GoalAutopilotPaused(GoalAutopilotPauseReason.TOOL_BUDGET)
            val active=withContext(NonCancellable) {
                goals.saveGuarded(access,goal.copy(status=GoalStatus.ACTIVE)).also {writtenGoal=it}
            }
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            val runId=UUID.randomUUID().toString().also {reserved=it}
            val segment=nextSegment?.binding?:checkNotNull(entryBudget).let {budget->GoalSegmentBinding(goal.id,budget.originalGoalVersion,goalWorkDigest(goal),access.scopeKey,
                access.threadRevision,access.useOwnerMemory,access.memoryEnabled,runId,null,null,1,budget.startedAtMs,history.size,budget.noProgressSegments,budget.totalSegments,budget.phaseStartSegments,budget.phaseStartCalls,
                archivedCalls=history.size-priorLedger.size,archivedObservations=if(history.size!=priorLedger.size)archive.encode() else "")}
            check(segment.scopeKey==access.scopeKey&&segment.scopeRevision==access.threadRevision&&segment.useOwnerMemory==access.useOwnerMemory&&segment.memoryEnabled==access.memoryEnabled){"목표의 캡처된 자료 접근 범위가 변경되었습니다."}
            val pendingGoal=active.copy(linkedRunIds=(active.linkedRunIds+runId).distinct())
            val pcBinding=capturedPcDataBinding
            val browserRoot=sources.lastOrNull()?.let {QueuedChatRequest.fromJson(it.contextJson)}?.takeIf {
                it.goal?.let { capturedGoal->sameGoalWork(capturedGoal,goal) }==true
            }?.let {it.browserWorkRootRunId?:it.goalSegment?.rootRunId}
            val readback=nextSegment?.readback?:ownerReadback
            var captured:QueuedChatRequest?=null
            app.repository.chat.enqueueUserRun(access.threadId,branchId,ownerInstruction,modelId,thinking,runId=runId){user,run->
                QueuedChatRequest(run.id,access.threadId,branchId,access.projectId,ownerInstruction,modelId,thinking,emptyList(),user.id,
                    allowTools=capturedAllowTools,mode=capturedMode,previousSteps=readback?.steps.orEmpty(),
                    readbackObligations=readback?.forRun(runId),goalReadbackSeedCount=readback?.steps?.size?:0,
                    workspaceBinding=binding,goal=pendingGoal,parentGoalStartRunId=parentGoalStartRunId,pcDataBinding=pcBinding,capturedPcRevision=capturedPcRevision,allowCloudText=allowCloudText,
                    allowExternalAgent=capturedAllowExternalAgent,externalAgentRevision=capturedExternalAgentRevision,
                    kakaoReadBinding=capturedKakaoReadBinding,pcImageRecoveryAllowed=capturedPcImageRecoveryAllowed,
                    goalSegment=segment,goalLedger=priorLedger,capturedPcTextDefault=capturedPcTextDefault,
                    browserWorkRootRunId=browserRoot?.takeUnless {it==segment.rootRunId},
                    goalOwnerReplyParentId=answered?.id,goalOwnerReplyParentDigest=answered?.let {goalDigest(it.contextJson)}).also {request->
                        parent?.let {requireSameGoalAuthority(QueuedChatRequest.fromJson(it.contextJson),request)}
                        // Historical data cannot cross a changed cloud consent/model/branch boundary.
                        sources.forEach {
                            requireSameGoalAuthority(QueuedChatRequest.fromJson(it.contextJson),request)
                        }
                        captured=request
                    }.toJson().also {ownedContexts+=it}
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
                requireGoalRunBinding(linked,saved).toJson()==context&&goals.loadGuarded(access)==linked){"목표와 실제 대기 작업의 저장 결과를 확인하지 못했습니다."}
            check(app.repository.chat.accessScope(access.threadId)==access)
            currentCoroutineContext().ensureActive();check(callerJob.isActive)
            if(!com.user.localagent.voice.AgentRunService.track(this.context,runId,access.threadId,ownerInstruction)||
                !com.user.localagent.voice.RunKeepAlive.await(runId).active)throw GoalAutopilotPaused(GoalAutopilotPauseReason.STOPPED)
            check(app.repository.chat.accessScope(access.threadId)==access&&goals.loadGuarded(access)==linked)
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
                            writtenGoal=restored
                            if(selectedThreadId.value==access.threadId&&selectedProjectId.value==access.projectId.orEmpty())goalState.value=restored
                        }
                    }
                }
            } catch(cleanup:Throwable) {error.addSuppressed(cleanup)}
        }
        if(error is GoalAutopilotPaused)withContext(NonCancellable) {
            // Re-entry cannot renew a spent budget. This is an intentional PAUSED CAS, not rollback.
            val current=goals.loadGuarded(access)
            if(app.repository.chat.accessScope(access.threadId)==access&&current!=null&&(current==goal||current==writtenGoal)) {
                val paused=goals.saveGuarded(access,current.copy(status=GoalStatus.PAUSED))
                check(goals.loadGuarded(access)==paused)
                if(selectedThreadId.value==access.threadId&&selectedProjectId.value==access.projectId.orEmpty())goalState.value=paused
            }
        }
        throw error
    }
}

/** Root calls after parent cleanup, under goalMutation; queue CAS/readback remains the same path. */
internal suspend fun ChatRunCoordinator.queueNextGoalSegment(access:ChatAccessScope,goal:GoalDefinition,
    parent:ChatRunRecord,next:GoalSegmentDecision.Continue,callerJob:Job):GoalLifecycleReceipt {
    check(app.repository.chat.run(parent.id)==parent){"목표의 부모 실행이 변경되었습니다."}
    val request=requireGoalRunBinding(goal,parent)
    check(next.binding.parentRunId==parent.id&&next.binding.parentCheckpointDigest==goalDigest(parent.contextJson))
    return queueBoundedGoalRun(access,goal,request.branchId,request.modelId,request.thinking,request.workspaceBinding,callerJob,
        ownerInstruction=request.text,capturedAllowTools=request.allowTools,capturedMode=request.mode,capturedPcDataBinding=request.pcDataBinding,
        capturedPcRevision=request.capturedPcRevision,allowCloudText=request.allowCloudText,
        capturedAllowExternalAgent=request.allowExternalAgent,capturedExternalAgentRevision=request.externalAgentRevision,
        capturedKakaoReadBinding=request.kakaoReadBinding,capturedPcImageRecoveryAllowed=request.pcImageRecoveryAllowed,nextSegment=next,
        capturedPcTextDefault=request.capturedPcTextDefault)
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
        val activeHost=executionForThread(access.threadId)
        val active=activeHost?.capturedActiveRequest
        if(active!=null&&active.runId in goal.linkedRunIds) {
            val host=checkNotNull(activeHost)
            check(active.threadId==access.threadId&&active.projectId==access.projectId&&active.goal?.id==goal.id){"다른 목표의 진행 작업을 중지하지 않았습니다."}
            if(active.runId==callerRunId) {
                host.pendingGoalStopRunId=active.runId;deferCaller=true
            } else {
                runningJob=checkNotNull(host.requestJob){"목표 실행의 현재 작업을 확인하지 못했습니다."}
                runningId=active.runId
                host.stop()
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
