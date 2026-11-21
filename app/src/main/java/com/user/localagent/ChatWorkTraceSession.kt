package com.user.localagent

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.data.*
import com.user.localagent.inference.GenerationSummary
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.UUID

/** Host observations only. Logging never authorizes or repeats a user action. */
internal class ChatWorkTraceSession private constructor(
    private val store:WorkTraceStore,private val binding:WorkTraceBinding,
    private val request:QueuedChatRequest,private val unavailable:()->Unit,
) {
    private val execution=UUID.randomUUID().toString()
    private var ordinal=0
    private val fingerprinter=WorkTraceFingerprinter()
    private val progressLock=Any()
    private val progress=Channel<String>(64)
    private var progressJob:Job?=null
    private var progressClosed=false

    /** Live status ticks (seconds, token counters) belong in the status line, not the reasoning record. */
    @Suppress("UNUSED_PARAMETER")
    fun progress(summary:String,scope:CoroutineScope){}

    /** Only explicit owner-facing plans are published; raw inference is not a public explanation. */
    suspend fun publicPlan(goal:String,constraints:List<String>){
        publicWorkPlanParts(goal,constraints).forEach{summary->
            stage(WorkTraceCode.PLAN_READY,summary,WorkSummarySource.MODEL_PLAN)
        }
    }
    suspend fun publicDecision(explanation:String?){
        val text=explanation?.let(WorkTraceCodec::redactPublicExplanation)?.takeIf{it.isNotBlank()}?:return
        stage(WorkTraceCode.DECISION_EXPLAINED,text,WorkSummarySource.MODEL_PLAN)
    }
    suspend fun closeProgress(){
        val job=synchronized(progressLock){progressClosed=true;progress.close();progressJob}
        job?.join()
    }

    class Attempt internal constructor(val actual:ToolCall,val host:ToolCall) {
        internal var hashes:WorkTraceHashes?=null
        internal var started:WorkTraceRecord?=null
    }
    fun attempt(call:ToolCall)=Attempt(call,call.copy(id=UUID.randomUUID().toString()))

    suspend fun stage(code:WorkTraceCode,summary:String?=null,source:WorkSummarySource=WorkSummarySource.HOST,count:Int?=null){
        val kind=when(code){
            WorkTraceCode.RUN_STARTED,WorkTraceCode.RUN_FINISHED,WorkTraceCode.RUN_INTERRUPTED->WorkTraceKind.RUN
            WorkTraceCode.SEMANTIC_STARTED,WorkTraceCode.DECISION_EXPLAINED->WorkTraceKind.RATIONALE
            WorkTraceCode.PLAN_READY->WorkTraceKind.PLAN
            WorkTraceCode.CONTEXT_READY->WorkTraceKind.CONTEXT
            WorkTraceCode.INFERENCE_STARTED,WorkTraceCode.INFERENCE_FINISHED->WorkTraceKind.INFERENCE
            WorkTraceCode.ANSWER_STARTED,WorkTraceCode.ANSWER_FINISHED,WorkTraceCode.ANSWER_PAUSED->WorkTraceKind.ANSWER
            else->return
        }
        append(WorkTraceDraft(store.executionKey("${code.name.lowercase()}.${++ordinal}",execution),kind,code,
            count=count,publicSummary=summary?.let(WorkTraceCodec::maskPreview),summarySource=source))
    }

    suspend fun beginGeneration(modelId:String,options:InferenceOptions,visibleAnswer:Boolean){
        stage(WorkTraceCode.INFERENCE_STARTED,"모델 $modelId · 문맥 ${options.contextSize} · 깊게 생각 ${if(options.thinking)"요청"else"끔"}")
        if(visibleAnswer)stage(WorkTraceCode.ANSWER_STARTED)
    }

    suspend fun finishGeneration(summary:GenerationSummary?,paused:Boolean,visibleAnswer:Boolean){
        val detail=summary?.let {"종료 ${it.reason.name} · 근거 ${it.evidence.name}"+
            (it.decodeTokens?.let{tokens->" · 마지막 구간 $tokens 토큰"}?:" · 토큰 수 미제공")}
            ?:"생성 종료 근거를 제공받지 못했습니다."
        stage(WorkTraceCode.INFERENCE_FINISHED,detail)
        if(paused)stage(WorkTraceCode.ANSWER_PAUSED)
        else if(visibleAnswer)stage(WorkTraceCode.ANSWER_FINISHED)
    }

    suspend fun approval(attempt:Attempt,code:WorkTraceCode){
        if(code !in setOf(WorkTraceCode.APPROVAL_WAITING,WorkTraceCode.APPROVAL_ACCEPTED,WorkTraceCode.APPROVAL_DECLINED))return
        val hashes=hashes(attempt)?:return
        append(WorkTraceDraft("tool.${attempt.host.id}.${code.name.lowercase()}",WorkTraceKind.APPROVAL,code,
            toolName=attempt.host.name,invocationId=attempt.host.id,argsHash=hashes.argsHash,scopeHash=hashes.scopeHash))
    }

    suspend fun start(attempt:Attempt){
        val hashes=hashes(attempt)?:return
        attempt.started=append(WorkTraceDraft("tool.${attempt.host.id}.start",WorkTraceKind.TOOL,WorkTraceCode.TOOL_STARTED,
            toolName=attempt.host.name,invocationId=attempt.host.id,argsHash=hashes.argsHash,scopeHash=hashes.scopeHash))
    }

    /** True means the ordinary action audit itself was durably written, even if a UI trace failed. */
    suspend fun outcome(attempt:Attempt,result:ToolResult):Boolean {
        val started=attempt.started?:return false
        val hashes=attempt.hashes?:return false
        if(result.id!=attempt.actual.id){unavailable();return false}
        val file=try {verifiedFileChange(attempt.actual,result)}catch(_:Exception){unavailable();return false}
        var receipt:WorkOutcomeReceipt?=null
        // Retry only the idempotent journal operation. The tool is never executed here.
        repeat(2){
            if(receipt==null)try {receipt=store.recordOutcome(binding,started.id,result.success,result.verification,result.verificationSummary,file)}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){unavailable()}
        }
        val recorded=receipt?:return false
        val common=WorkTraceDraft("tool.${attempt.host.id}.result",WorkTraceKind.TOOL,WorkTraceCode.TOOL_RESULT,
            toolName=attempt.host.name,invocationId=attempt.host.id,evidence=result.verification,auditId=recorded.audit.id,
            argsHash=hashes.argsHash,scopeHash=hashes.scopeHash)
        append(common)
        append(common.copy(operationKey="tool.${attempt.host.id}.verification",kind=WorkTraceKind.VERIFICATION,code=WorkTraceCode.VERIFICATION_RECORDED))
        if(file!=null)append(common.copy(operationKey="tool.${attempt.host.id}.file",kind=WorkTraceKind.FILE,code=WorkTraceCode.FILE_CHANGE,fileChange=file))
        return true
    }

    suspend fun finishFromActualState(status:String){
        when(status){
            "completed"->stage(WorkTraceCode.RUN_FINISHED)
            "cancelled","failed","interrupted"->stage(WorkTraceCode.RUN_INTERRUPTED)
        }
    }

    private suspend fun hashes(attempt:Attempt):WorkTraceHashes? {
        attempt.hashes?.let{return it}
        return try {withContext(Dispatchers.IO){fingerprinter.of(binding,attempt.host,request.workspaceBinding)}.also{attempt.hashes=it}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){unavailable();null}
    }

    private suspend fun append(event:WorkTraceDraft):WorkTraceRecord?=try{store.append(binding,event)}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){unavailable();null}

    private fun verifiedFileChange(call:ToolCall,result:ToolResult):WorkFileChange? {
        if(call.name!="write_project_file"||!result.success||result.verification!=VerificationState.VERIFIED)return null
        val value=Json.parseToJsonElement(result.content).jsonObject
        val path=call.arguments.getValue("path").jsonPrimitive.content
        val content=call.arguments.getValue("content").jsonPrimitive.content
        val before=call.arguments["expectedSha256"]?.jsonPrimitive?.contentOrNull
        val actualSha=value.getValue("sha256").jsonPrimitive.content
        check(value.getValue("path").jsonPrimitive.content==path&&actualSha==ProjectWorkspace.sha256(content))
        check(value.getValue("verified").jsonPrimitive.boolean&&!value.getValue("executed").jsonPrimitive.boolean)
        val bytes=value.getValue("bytes").jsonPrimitive.long
        check(bytes==content.toByteArray(Charsets.UTF_8).size.toLong())
        return WorkFileChange(path,if(before==null)WorkFileOperation.CREATED else WorkFileOperation.MODIFIED,before,actualSha,bytes,
            requireNotNull(request.workspaceBinding),diffRef=null)
    }

    companion object {
        suspend fun open(repository:LocalRepository,request:QueuedChatRequest,unavailable:()->Unit):ChatWorkTraceSession?=try {
            ChatWorkTraceSession(repository.workTrace,repository.workTrace.open(request.runId,request.threadId),request,unavailable)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){unavailable();null}
    }
}
