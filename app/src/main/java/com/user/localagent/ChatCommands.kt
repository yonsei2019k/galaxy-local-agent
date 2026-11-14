package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.ui.UiRunEvent
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

fun ChatRunCoordinator.changeMode(value:String){
    val mode=AgentWorkMode.parse(value)
    runMode.value=mode.wireName
    context.getSharedPreferences("chat-preferences",0).edit().putString("mode-${selectedThreadId.value}",mode.wireName).apply()
    status.value="${mode.label} 모드 · ${mode.description}"
}

/** Commands do not need a model and never silently shorten the submitted text. */
internal fun ChatRunCoordinator.handleChatCommand(input:String):String? {
    val trimmed=input.trimStart()
    val command=trimmed.substringBefore(' ').substringBefore('\n')
    when(command){
        "/new"->{newThread();return null}
        "/status"->{scope.launch {
            val id=selectedThreadId.value;val thread=app.repository.chat.thread(id)?:return@launch
            val summary=(executionForThread(id)?.executionEngine?:executionEngine).lastGeneration.value
            app.repository.chat.addMessage(id,thread.activeBranchId,"assistant",buildString {
                append("대기 요청: ${queued.value.count {it.threadId==id}}개\n모델 선택: ${preferredModelId.value}")
                summary?.let {append("\n최근 생성: ${it.reason} · ${it.evidence}\n출력 토큰: ${it.decodeTokens?:"미제공"} / ${it.configuredMaxOutputTokens}")}
            })
        };return null}
        "/compact"->{scope.launch {
            val id=selectedThreadId.value;val thread=app.repository.chat.thread(id)?:return@launch
            val summary=compactBranch(id,thread.activeBranchId,explicit=true)
            status.value=if(summary==null)"요약할 대화가 아직 없습니다."else"기존 원문을 유지하고 요약 문맥을 저장했습니다."
        };return null}
        "/fork"->{visibleLines.value.lastOrNull()?.let {forkMessage(it.id)};return null}
    }
    return input
}

internal suspend fun ChatRunCoordinator.compactBranch(threadId:String,branchId:String,explicit:Boolean=false):String? {
    val chat=app.repository.chat
    val prior=chat.branchSummary(threadId,branchId)
    val messages=chat.messages(threadId,branchId).first()
    if(!explicit&&messages.size<=12)return prior?.text
    val selected=(if(explicit)messages else messages.dropLast(6)).takeLast(40)
    if(selected.isEmpty())return prior?.text
    if(!explicit&&prior?.sourceMessageIds?.lastOrNull()==selected.last().id)return prior.text
    val summary=extractiveChatSummary(selected.map {it.role to it.text})
    chat.saveBranchSummary(threadId,branchId,summary,selected.map {it.id})
    return summary
}

/** Explicitly labelled extractive context: originals remain available for retrieval/export. */
internal fun extractiveChatSummary(messages:List<Pair<String,String>>,limit:Int=2200):String {
    require(limit>=256)
    val parts=messages.map {(role,text)->
        val headings=text.lineSequence().filter {it.trimStart().startsWith('#')}.take(4).joinToString(" / ")
        val excerpt=if(role=="user")text.safeHead(240)else (headings.ifBlank {text.safeHead(120)}+" … "+text.safeTail(120))
        "${if(role=="user")"사용자"else"응답"}: $excerpt"
    }
    val chosen=mutableListOf<String>();var used=80
    for(part in parts.asReversed()){if(used+part.length+1>limit)break;chosen+=part;used+=part.length+1}
    return "발췌 요약 · 원문 보존 · 새로운 실행 권한이 아님\n"+chosen.asReversed().joinToString("\n")
}

fun ChatRunCoordinator.editQueued(id:String,text:String){scope.launch {
    try{
        queueMutation.withLock {
        val old=queued.value.firstOrNull {it.runId==id}?:error("이미 시작되거나 삭제된 요청입니다.")
        if(old.goal!=null){
            status.value="목표 연결 요청은 대기열에서 편집할 수 없습니다. 취소 후 목표 관리에서 목표와 완료 기준을 수정하고 다시 추진해주세요."
            return@launch
        }
        require(text.isNotBlank()&&text.length<=12000)
        val updated=old.copy(text=text)
        app.repository.chat.editQueuedRun(id,text,updated.toJson())
        queued.value=queued.value.map {if(it.runId==id)updated else it}
        status.value="대기 요청을 수정하고 저장했습니다."
        }
    }catch(cancel:CancellationException){throw cancel}
    catch(error:Exception){
        val threadId=runCatching{app.repository.chat.run(id)?.threadId}.getOrNull()
        val retained=if(threadId!=null&&text.isNotBlank())runCatching{preserveUnappliedInstruction(app.repository,threadId,text)}else null
        status.value=if(retained?.getOrNull()==true)"대기 요청 수정은 적용하지 않았습니다. 제안 내용을 원래 대화 기록에 보존했습니다."
            else "대기 요청을 수정하지 못했습니다. 제안 내용의 보존도 확인하지 못했습니다."
    }
}}

fun ChatRunCoordinator.reorderQueued(id:String,delta:Int){scope.launch {
    try{
        queueMutation.withLock {
        require(delta in setOf(-1,1))
        val list=queued.value.toMutableList();val index=list.indexOfFirst {it.runId==id}
        require(index>=0);val target=index+delta;if(target !in list.indices)return@launch
        java.util.Collections.swap(list,index,target)
        val saved=app.repository.chat.reorderQueuedRuns(list.map {it.runId})
        val byId=saved.associateBy {it.id}
        queued.value=list.map {request->byId[request.runId]?.let {QueuedChatRequest.fromJson(it.contextJson)}?:request}
        status.value="대기 순서를 저장했습니다."
        }
    }catch(error:Exception){status.value="대기 상태가 바뀌어 순서를 변경하지 않았습니다. 다시 확인해주세요."}
}}

/** A typed instruction is pinned to the run shown when the dialog opened. */
fun ChatRunCoordinator.steer(text:String,expectedRunId:String?=activeRunId,expectedThreadId:String?=activeThreadId){
    require(text.isNotBlank()&&text.length<=4000)
    val runId=expectedRunId?:return
    val threadId=expectedThreadId?:return
    val worker=executionForRun(runId)
    if(worker!=null&&worker!==this){worker.steer(text,runId,threadId);return}
    scope.launch {
        try{
            if(activeRunId!=runId||activeThreadId!=threadId){
                val retained=preserveUnappliedInstruction(app.repository,threadId,text)
                status.value=if(retained)"원래 작업이 끝나 적용하지 않은 추가 지시를 원래 대화 기록에 보존했습니다."
                    else "원래 대화가 삭제되어 추가 지시를 적용하거나 다시 만들지 않았습니다."
                return@launch
            }
            val original=app.repository.chat.run(runId)?:error("원래 작업을 찾을 수 없습니다.")
            val request=QueuedChatRequest.fromJson(original.contextJson)
            val running=requestJob
            pendingSteerRunId=runId
            approval.value?.takeIf {it.runId==runId}?.decision?.complete(false)
            if(!toolInFlight){executionEngine.cancel();running?.cancel(CancellationException("사용자 추가 지시"))}
            status.value="현재 실행 결과를 보존한 뒤 추가 지시를 적용합니다."
            running?.join()
            val thread=app.repository.chat.thread(threadId)?:error("대화가 삭제되었습니다.")
            check(thread.activeBranchId==request.branchId&&thread.projectId==request.projectId){"대화 버전이나 프로젝트가 변경되었습니다."}
            val steps=completedSteps[runId].orEmpty()
            val updated="${request.text}\n\n사용자의 최신 추가 지시:\n$text"
            require(updated.length<=12000){"원래 요청과 추가 지시가 너무 깁니다."}
            if(request.goal!=null)goalMutation.withLock {
                val access=app.repository.chat.accessScope(threadId)
                val actual=checkNotNull(goals.loadGuarded(access)){"추가 지시의 목표가 해제되었습니다."}
                check(actual.status==GoalStatus.ACTIVE&&sameGoalWork(request.goal,actual)){"목표가 중지·변경되어 이전 작업의 추가 지시를 적용하지 않았습니다."}
                queueBoundedGoalRun(access,actual,request.branchId,request.modelId,request.thinking,request.workspaceBinding,
                    checkNotNull(currentCoroutineContext()[Job]),ownerInstruction=updated,capturedAllowTools=request.allowTools,capturedMode=request.mode,
                    capturedPcDataBinding=request.pcDataBinding,capturedPcRevision=request.capturedPcRevision,allowCloudText=request.allowCloudText)
            }else queueMutation.withLock {
            var submitted:QueuedChatRequest?=null
            app.repository.chat.enqueueUserRun(threadId,thread.activeBranchId,updated,request.modelId,request.thinking){user,newRun->
                QueuedChatRequest(newRun.id,threadId,thread.activeBranchId,thread.projectId,updated,request.modelId,request.thinking,emptyList(),user.id,
                    allowTools=request.allowTools,mode=request.mode,previousSteps=steps,workspaceBinding=request.workspaceBinding,goal=request.goal,
                    pcDataBinding=request.pcDataBinding,kakaoReadBinding=request.kakaoReadBinding,pcImageRecoveryAllowed=request.pcImageRecoveryAllowed,capturedPcRevision=request.capturedPcRevision,allowCloudText=request.allowCloudText).also {submitted=it}.toJson()
            }
            queued.value=listOf(checkNotNull(submitted))+queued.value
            app.repository.chat.reorderQueuedRuns(queued.value.map {it.runId})
            }
            runTimeline.value=runTimeline.value+UiRunEvent("steer-$runId","추가 지시 적용","기존 실행 결과 ${steps.size}개를 재실행 방지 근거로 유지","plan",threadId)
            drain()
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){
            val retained=runCatching{preserveUnappliedInstruction(app.repository,threadId,text)}
            status.value=when(retained.getOrNull()){
                true->"추가 지시를 적용하지 못해 원래 대화 기록에 보존했습니다. 기존 초안과 모델 설정은 유지됩니다."
                false->"원래 대화가 삭제되어 추가 지시를 적용하거나 다시 만들지 않았습니다."
                null->"추가 지시를 적용하지 못했습니다. 대화 기록 저장도 확인하지 못했습니다."
            }
        }finally{if(pendingSteerRunId==runId)pendingSteerRunId=null}
    }
}

/** Stores an unapplied proposal as quoted data; never writes drafts or creates a missing room. */
internal suspend fun preserveUnappliedInstruction(repository:LocalRepository,threadId:String,text:String):Boolean {
    val thread=repository.chat.thread(threadId)?:return false
    val body="[아직 적용하지 않은 추가 지시]\n이 내용은 실행에 적용되지 않은 제안이며, 작업 완료나 새 실행 권한을 뜻하지 않습니다.\n\n"+
        buildJsonObject{put("quoted_owner_instruction",text);put("applied",false)}.toString()
    val stored=repository.chat.addMessage(thread.id,thread.activeBranchId,"assistant",body)
    check(stored.threadId==thread.id&&stored.branchId==thread.activeBranchId&&repository.chat.fullMessageText(stored.id)==body){"추가 지시 보존을 확인하지 못했습니다."}
    return true
}
