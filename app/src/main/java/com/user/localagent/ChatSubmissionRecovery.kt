package com.user.localagent

import com.user.localagent.data.ChatRunRecord
import com.user.localagent.data.ChatStore
import com.user.localagent.data.LocalRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class SubmissionRecoveryKind { CANCELLED, ABSENT, QUEUED, OTHER_STATE, UNKNOWN }
internal data class SubmissionRecovery(val kind:SubmissionRecoveryKind,val run:ChatRunRecord?=null)

internal suspend fun submissionSourceStillLive(repository:LocalRepository,run:ChatRunRecord?):Boolean {
    if(run==null)return false
    val thread=repository.chat.thread(run.threadId)?:return false
    if(thread.revision!=run.threadRevision||thread.projectId!=run.projectId||thread.activeBranchId!=run.branchId)return false
    if((run.projectId?.let{repository.chat.project(it)?.revision}?:0L)!=run.projectRevision)return false
    val user=run.userMessageId?.let{repository.getMessage(it)}?:return false
    return user.role=="user"&&user.threadId==run.threadId
}

/** Host holds queueMutation. A failed cancellation is never reported as a cancelled request. */
internal suspend fun recoverCommittedSubmission(
    chat:ChatStore,runId:String,
    cancelAttempt:suspend (String)->Unit={chat.cancelQueuedRun(it);Unit},
):SubmissionRecovery {
    try {cancelAttempt(runId)}catch(_:Exception){/* Verify actual durable state below. */}
    return try {
        val actual=chat.run(runId)
        when {
            actual==null->SubmissionRecovery(SubmissionRecoveryKind.ABSENT)
            actual.status=="cancelled"&&actual.startedAt==null->SubmissionRecovery(SubmissionRecoveryKind.CANCELLED,actual)
            actual.status=="queued"&&actual.startedAt==null->SubmissionRecovery(SubmissionRecoveryKind.QUEUED,actual)
            else->SubmissionRecovery(SubmissionRecoveryKind.OTHER_STATE,actual)
        }
    }catch(_:Exception){SubmissionRecovery(SubmissionRecoveryKind.UNKNOWN)}
}

/** Preserve an uncommitted request without racing a newer composer/model selection. */
internal suspend fun preserveUnsubmittedRequest(repository:LocalRepository,threadId:String,text:String):Boolean {
    val thread=repository.chat.thread(threadId)?:return false
    val quoted=buildJsonObject {put("quoted_owner_request",text);put("submitted",false);put("applied",false)}
    val body="[전송하지 못한 요청]\n\n$quoted"
    val saved=repository.chat.addMessage(thread.id,thread.activeBranchId,"assistant",body)
    return repository.chat.fullMessageText(saved.id)==body
}
