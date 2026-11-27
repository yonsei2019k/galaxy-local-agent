package com.user.localagent

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.WorkSegments
import com.user.localagent.externalagent.ExternalAgentContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal const val GOAL_JOURNAL_REFUSED="공통 실행 기록이 재실행·만료·미확인 상태를 감지해 도구를 시작하지 않았습니다."

/** Host dispatch journal surface. Production is RustExecutionJournal; a double exists only in test sources. */
internal interface GoalExecutionJournal:AutoCloseable {
    fun begin(binding:String,call:ToolCall)
    fun outcome(call:ToolCall,result:ToolResult)
    fun checkpoint():String
    fun sealCheckpoint():String
}

internal fun goalJournalScopeId(request:QueuedChatRequest,scopeKey:String):String=ExternalAgentContract.sha(listOf(request.runId,request.threadId,
    request.branchId,request.projectId.orEmpty(),scopeKey,request.workspaceBinding.orEmpty()).joinToString("\n"))

/** The exact binding goalNativeEffectsUnresolved expects. A local-only run carries an explicit null revision. */
internal fun goalJournalBinding(request:QueuedChatRequest,scopeKey:String):JsonObject=buildJsonObject {
    put("runId",request.runId);put("threadId",request.threadId);put("branchId",request.branchId);put("projectId",request.projectId)
    put("scopeId",goalJournalScopeId(request,scopeKey));put("authorizationRevision",request.externalAgentRevision)
}

/** Local goal segments: no cloud prerequisite, at most one segment's steps, the goal's fixed 30-minute ceiling. */
internal fun localGoalJournalProfile(request:QueuedChatRequest):String=buildJsonObject {
    put("profileId","local-goal");put("provider",if(request.modelId.startsWith("paired-pc-"))"local_pc" else "local_phone")
    put("modelId","host-selected");put("reasoning",JsonNull)
    put("maxToolCalls",minOf(WorkSegments.STEP_BUDGET,request.goalSegment?.let {goalToolRoom(it,0)}?:WorkSegments.STEP_BUDGET).coerceAtLeast(1))
    put("deadlineMs",GoalSegmentBinding.MAX_DURATION_MS);put("cloudAuthorized",false)
}.toString()

/**
 * Write-ahead effect record shared by the cloud and local goal paths. A begin is durable before the effect starts;
 * a process death after it restores an uncertain in-flight call. Never a permission or completion receipt.
 */
internal class GoalEffectRecorder(private val journal:GoalExecutionJournal,private val binding:suspend()->String,
    private val persist:suspend(String)->Unit):AutoCloseable {
    private var inFlight:ToolCall?=null
    /** False means the effect must not start; the caller records a no-effect failure. */
    suspend fun begin(call:ToolCall):Boolean {
        if(inFlight!=null)return false
        val bound=try{binding()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){return false}
        if(runCatching{journal.begin(bound,call)}.isFailure)return false
        inFlight=call
        // SQLCipher commit is the write-ahead boundary. A process death after this point
        // restores an uncertain in-flight call and cannot silently replay a write.
        persist(journal.checkpoint())
        return true
    }
    /** Actual host executor result only; a missing finish leaves the call uncertain. */
    suspend fun finish(result:ToolResult) {
        val call=inFlight?:return
        if(result.id!=call.id)return // An inner tool result cannot resolve the enclosing call.
        withContext(NonCancellable){journal.outcome(call,result);inFlight=null;persist(journal.checkpoint())}
    }
    suspend fun run(call:ToolCall,refused:()->ToolResult,execute:suspend()->ToolResult):ToolResult {
        if(!begin(call))return refused()
        val result=execute()
        finish(result)
        return result
    }
    fun checkpoint():String=journal.checkpoint()
    fun sealCheckpoint():String=journal.sealCheckpoint()
    override fun close()=journal.close()
}
