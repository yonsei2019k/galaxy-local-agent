package com.user.localagent.analysis

import com.user.localagent.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Request-owned host plumbing; no natural-language intent matching or authority from source text. */
object PrivateAnalysisHost {
    val toolNames:Set<String> = PrivateAnalysisToolCatalog.names + RecordingToolCatalog.names
    val cancellableNames:Set<String> = toolNames+com.user.localagent.core.GoalToolCatalog.GET

    fun afterAudit(result:ToolResult,auditOk:Boolean):ToolResult = if(auditOk)result else result.copy(
        verificationSummary=result.verificationSummary.take(3800)+" · 감사 기록 저장 미확인 · 재실행 전에 기존 결과를 확인해주세요.")

    /** Project policy can change without a thread revision; fence long running reads as well. */
    suspend fun <T> withLiveAccess(verify:suspend()->Unit,block:suspend()->T,pollMillis:Long=200):T=coroutineScope {
        require(pollMillis in 1..1000);verify()
        val operation=checkNotNull(currentCoroutineContext()[Job])
        val watch=launch(Dispatchers.IO) {
            while(isActive) {
                delay(pollMillis)
                try {verify()}
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){operation.cancel(CancellationException("원래 자료의 대화·프로젝트 접근 범위가 변경되었습니다."));return@launch}
            }
        }
        try {block().also {verify()}}finally{withContext(NonCancellable){watch.cancelAndJoin()}}
    }

    /** Replace the same analysis' older batch receipt so the model sees its actual resume version. */
    fun addEvidence(evidence:MutableList<JsonObject>,call:ToolCall,result:ToolResult) {
        require(call.name in toolNames)
        val id=PrivateAnalysisModelContext.analysisId(result)
        if(id!=null)evidence.removeAll { entry ->
            val prior=entry["result"] as? JsonObject
            entry["tool"]?.jsonPrimitive?.content in PrivateAnalysisToolCatalog.names &&
                prior?.get("analysisId")?.jsonPrimitive?.contentOrNull==id
        }
        val preview=Json.parseToJsonElement(PrivateAnalysisModelContext.build(result)).jsonObject
        evidence+=buildJsonObject {
            put("tool",call.name);put("success",result.success);put("verification",result.verification.name)
            put("verificationSummary",result.verificationSummary);put("result",preview)
        }
    }

    /** Validate the host-created private file again before exposing a clickable answer token. */
    fun verifiedArtifactToken(result:ToolResult,exports:File):String? {
        val token=PrivateAnalysisModelContext.trustedArtifactToken(result)?:return null
        val payload=Json.parseToJsonElement(result.content).jsonObject
        val artifact=payload["artifact"] as? JsonObject?:return null
        val name=token.removePrefix("[[file:").removeSuffix("]]")
        val directory=exports.canonicalFile
        val file=File(directory,name)
        if(!file.isFile||file.canonicalFile.parentFile!=directory||file.length() !in 1..20L*1024*1024)return null
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        val actual=digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
        if(artifact["sha256"]?.jsonPrimitive?.content!=actual)return null
        val declared=artifact["bytes"]?.jsonPrimitive?.longOrNull
        if(declared==null||declared!=file.length())return null
        return token
    }

    /** Long reads execute on the original Job. Only the durable receipt is shielded from cancel. */
    suspend fun executeCancellable(call:ToolCall,execute:suspend()->ToolResult,commit:suspend(ToolResult)->Unit,
        timeoutMillis:Long=ToolRegistry.definitions.getValue(call.name).timeoutMillis):ToolResult {
        require(call.name in cancellableNames)
        require(timeoutMillis>0)
        var cancellation:CancellationException?=null
        val result=try {
            withTimeout(timeoutMillis){execute()}
        } catch(timeout:TimeoutCancellationException) {
            if(!currentCoroutineContext().isActive)cancellation=timeout
            ToolResult(call.id,false,"제한 시간 안에 처리를 마치지 못했습니다. 저장된 분석 진행 상태와 자료 목록을 확인해주세요.",VerificationState.UNVERIFIED,"분석·전사 제한 시간 도달 · 전체 완료 미확인")
        } catch(cancel:CancellationException) {
            cancellation=cancel
            ToolResult(call.id,false,"작업을 중단했습니다. 저장된 분석 진행 상태를 확인한 뒤 이어갈 수 있습니다.",VerificationState.UNVERIFIED,"분석·전사 중단 · 전체 완료 미확인")
        }
        withContext(NonCancellable){commit(result)}
        cancellation?.let {throw it}
        currentCoroutineContext().ensureActive()
        return result
    }
}
