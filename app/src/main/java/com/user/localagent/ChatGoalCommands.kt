package com.user.localagent

import com.user.localagent.core.ConversationSubmissionPolicy
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus
import com.user.localagent.data.ChatAccessScope
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** A captured UI edit still uses scope/revision CAS and quiesces only this goal's work. */
fun ChatRunCoordinator.saveGoal(goal:GoalDefinition,onSuccess:()->Unit={}) {
    val selected=selectedThreadId.value
    if(goal.threadId!=selected||goal.projectId.orEmpty()!=selectedProjectId.value){status.value="목표 편집을 연 대화·프로젝트가 바뀌었습니다. 원래 대화에서 다시 저장해주세요.";return}
    scope.launch {
        try {
            val access=app.repository.chat.accessScope(selected)
            check(access.projectId==goal.projectId)
            val saved=saveUiGoalGuarded(access,goal)
            if(selectedThreadId.value==selected&&selectedProjectId.value==saved.projectId.orEmpty())goalState.value=saved
            status.value="목표와 완료 기준을 저장하고 다시 확인했습니다."
            onSuccess()
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){status.value="목표를 저장하지 못했습니다. 편집 내용은 유지됩니다 · ${error.message?:error.javaClass.simpleName}"}
    }
}

/** Keeps the original positional/trailing-lambda API; newer callers should pass their snapshot. */
fun ChatRunCoordinator.deleteGoal(threadId:String,onSuccess:()->Unit={}) = deleteGoal(threadId,null,onSuccess)

fun ChatRunCoordinator.deleteGoal(threadId:String,expected:GoalDefinition?,onSuccess:()->Unit={}) {
    if(selectedThreadId.value!=threadId){status.value="목표 삭제를 연 대화가 바뀌었습니다. 원래 대화에서 다시 확인해주세요.";return}
    val project=selectedProjectId.value
    // The old entry point captures the displayed snapshot, never a goal loaded later as a target.
    val captured=expected?:goalState.value
    if(captured==null||captured.threadId!=threadId||captured.projectId.orEmpty()!=project){status.value="해제할 목표의 실제 버전을 확인하지 못했습니다. 현재 목표를 다시 읽어주세요.";return}
    scope.launch {
        try {
            val access=app.repository.chat.accessScope(threadId)
            check(access.projectId==captured.projectId&&access.projectId.orEmpty()==project)
            quiesceGoalRuns(access,captured,null)
            goalMutation.withLock {
                check(goals.loadGuarded(access)==captured){"해제할 목표가 변경되었습니다. 최신 목표를 다시 읽어주세요."}
                check(goals.deleteGuarded(access,captured.id,captured.updatedAt))
                check(goals.loadGuarded(access)==null){"목표 해제 결과를 확인하지 못했습니다."}
                if(selectedThreadId.value==threadId&&selectedProjectId.value==project)goalState.value=null
            }
            status.value="목표와 연결된 작업을 중지하고 저장된 목표 해제를 확인했습니다. 이미 수행한 작업 결과는 유지됩니다."
            onSuccess()
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){status.value="목표를 해제하지 못했습니다 · ${error.message?:error.javaClass.simpleName}"}
    }
}

/** An explicit UI button captures a new grant; subsequent host segments preserve it unchanged. */
fun ChatRunCoordinator.startGoal(goal:GoalDefinition,onSuccess:()->Unit={}) {
    val threadId=selectedThreadId.value
    if(goal.threadId!=threadId||goal.projectId.orEmpty()!=selectedProjectId.value){status.value="목표 추진을 연 대화·프로젝트가 바뀌었습니다. 원래 대화에서 다시 시작해주세요.";return}
    val selectedModel=preferredModelId.value;val thinking=modelOptions.value.thinking
    val pcSettings=PcComputeSettings.get(context)
    val pcAuthority=pcSettings.captureTextAuthority()
    val externalAuthority=ExternalAgentSettings.get(context).capture()
    val model=com.user.localagent.core.ConversationPrivacy.modelForNewRequest(selectedModel,ExternalAgentSettings.MODEL_ID,goal.toJson().toString(),false)
    scope.launch {
        try {
            val access=app.repository.chat.accessScope(threadId)
            check(access.projectId==goal.projectId)
            val before=checkNotNull(app.repository.chat.thread(threadId))
            val branchId=before.activeBranchId
            val binding=app.workspaceFor(access.projectId).bindingFingerprint()
            val prepared=saveUiGoalGuarded(access,goal)
            goalMutation.withLock {
                queueBoundedGoalRun(access,prepared,branchId,model,thinking,binding,checkNotNull(currentCoroutineContext()[Job]),
                    capturedPcRevision=pcAuthority.revision,allowCloudText=false,
                    capturedAllowExternalAgent=model==ExternalAgentSettings.MODEL_ID&&externalAuthority.enabled,
                    capturedExternalAgentRevision=if(model==ExternalAgentSettings.MODEL_ID)externalAuthority.revision else null)
                if(selectedThreadId.value==threadId&&selectedProjectId.value==goal.projectId.orEmpty()){
                    runMode.value=ConversationSubmissionPolicy.newRequestMode().wireName
                }
            }
            status.value="목표 실행을 저장했습니다. 확인된 결과를 보존하며 승인된 범위와 누적 예산 안에서 이어갑니다."
            drain();onSuccess()
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){status.value="목표 실행을 시작하지 못했습니다. 목표와 편집 내용은 유지됩니다 · ${error.message?:error.javaClass.simpleName}"}
    }
}

/** Host UI may confirm criteria, but a changed/paused/completed goal cannot leave old work active. */
private suspend fun ChatRunCoordinator.saveUiGoalGuarded(access:ChatAccessScope,edited:GoalDefinition):GoalDefinition {
    edited.validate();check(edited.threadId==access.threadId&&edited.projectId==access.projectId)
    val previous=goalMutation.withLock {
        goals.loadGuarded(access).also {old->
            if(old==null)check(edited.updatedAt==0L){"목표가 삭제되었거나 변경되었습니다."}
            else check(old.id==edited.id&&old.updatedAt==edited.updatedAt){"목표 버전이 변경되었습니다. 최신 목표를 다시 읽어주세요."}
        }
    }
    if(previous!=null&&previous.linkedRunIds.isNotEmpty()&&
        (!sameGoalWork(previous,edited)||edited.status in setOf(GoalStatus.PAUSED,GoalStatus.COMPLETE))) {
        quiesceGoalRuns(access,previous,null)
    }
    return goalMutation.withLock {
        currentCoroutineContext().ensureActive()
        check(goals.loadGuarded(access)==previous){"목표가 편집 중 변경되었습니다. 최신 목표를 다시 읽어주세요."}
        if(previous==edited)edited else goals.saveGuarded(access,edited).also {check(goals.loadGuarded(access)==it)}
    }
}
