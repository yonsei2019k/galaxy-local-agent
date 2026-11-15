package com.user.localagent.notes

import com.user.localagent.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** One instance per captured request. Source/preview text is untrusted data, never instruction authority. */
class NoteReadPatchHost(private val binding:NoteReadBinding,private val store:NotePatchStore,private val verifyLive:suspend()->Unit) {
    private val session=NoteReadPatchSession(binding)
    private val delivery=NoteReadDeliveryLedger()
    private val reviews=mutableMapOf<String,String>()
    private val approvals=mutableMapOf<String,String>()
    fun accept(evidence:List<JsonObject>,actualModelQuery:String):NoteReadDeliveryBatch {
        session.acceptDelivered(evidence,actualModelQuery);return delivery.accept(session.acceptedPageTokens())
    }
    fun acknowledge(batch:NoteReadDeliveryBatch){delivery.acknowledge(batch)}
    fun deliveredKeys():Set<String> = delivery.keys()
    fun clear(){session.clear();delivery.clear();reviews.clear();approvals.clear()}
    private fun identity(call:ToolCall):NotePatchIdentity {
        NoteReadPatchToolCatalog.validate(call);check(call.name=="patch_note")
        val canonical=buildJsonObject {
            put("runId",binding.runId);put("threadId",binding.threadId);put("branchId",binding.branchId);put("scopeKey",binding.scopeKey)
            put("tool",call.name);put("arguments",JsonObject(call.arguments.toSortedMap()))
        }
        val digest=noteSha(canonical.toString())
        return NotePatchIdentity("note-patch-"+digest,digest,call.arguments.getValue("id").jsonPrimitive.content,call.arguments.getValue("scope").jsonPrimitive.content)
    }
    private fun preview(source:NoteSource,plan:NotePatchPlan)=buildJsonObject {
        put("tool","patch_note");put("title",source.title);put("collection",source.collection);put("diff",plan.preview())
        put("sourceTextIsUntrusted",true);put("grantsPermission",false)
    }.toString()
    suspend fun review(call:ToolCall):String? {
        if(call.name!="patch_note")return null
        verifyLive();val identity=identity(call)
        // A receipt retry only observes an existing effect; it cannot start a new edit.
        store.reconcile(identity)?.let{return it.json().toString()}
        val source=store.read(identity.id)
        val request=NoteReadPatchToolCatalog.patchRequest(call)
        val plan=try{session.previewPatch(source,request)}catch(error:NotePatchRejected){throw NotePatchPreviewRejected(error)}
        val text=preview(source,plan);reviews[identity.operationId]=text;approvals.remove(identity.operationId)
        return text
    }
    /** Called only after the existing PendingAction UI accepted THIS review and live run/branch was rechecked. */
    fun ownerConfirmed(call:ToolCall) {
        if(call.name!="patch_note")return
        val key=identity(call).operationId
        reviews.remove(key)?.let{approvals[key]=it}
    }
    suspend fun execute(call:ToolCall):ToolResult {
        NoteReadPatchToolCatalog.validate(call);verifyLive();currentCoroutineContext().ensureActive()
        if(call.name=="read_note") {
            val request=NoteReadPatchToolCatalog.readRequest(call)
            try{
                val page=session.read(store.read(request.id),request)
                verifyLive();currentCoroutineContext().ensureActive();delivery.issued(call,page)
                return ToolResult(call.id,true,page.json().toString(),VerificationState.VERIFIED,"메모 실제 범위 조회 · 전체 여부는 completeBody로 표시")
            }catch(error:Throwable){session.clear();delivery.invalidate(request.id);throw error}
        }
        val identity=identity(call)
        store.reconcile(identity)?.let{return result(call,it)}
        val approved=approvals.remove(identity.operationId)?:error("NOTE_OWNER_APPROVAL_REQUIRED")
        val source=store.read(identity.id);val plan=session.previewPatch(source,NoteReadPatchToolCatalog.patchRequest(call))
        check(preview(source,plan)==approved){"NOTE_APPROVED_DIFF_CHANGED"}
        val ownerJob=currentCoroutineContext()[Job]
        currentCoroutineContext().ensureActive();verifyLive()
        // Original Job is checked again INSIDE the transaction, immediately before writing.
        val committed=withContext(NonCancellable){store.commit(identity,source,plan){ownerJob?.ensureActive();verifyLive()}}
        return result(call,committed)
    }
    /** Cancellation may hide a return value after commit. Only this actual durable receipt can restore it. No mutation. */
    suspend fun recover(call:ToolCall):ToolResult?=if(call.name!="patch_note")null else store.reconcile(identity(call))?.let{result(call,it)}
    private fun result(call:ToolCall,receipt:NotePatchReceipt)=ToolResult(call.id,true,receipt.json().toString(),VerificationState.VERIFIED,
        if(receipt.changed)"메모 부분 수정·전체 저장 재조회·원자 영수증 확인"else"같은 내용 · 본문/버전 변경 없이 재조회 확인")
}

object NoteReadModelDelivery {
    fun modelResult(raw:String):String {
        require(raw.length<=NoteReadPatchSession.MAX_PAGE_JSON)
        val page=Json.parseToJsonElement(raw).jsonObject
        // Host-issued exact equality is independently checked by NoteReadPatchSession.acceptDelivered.
        require(page["status"]?.jsonPrimitive?.content=="ok")
        return buildJsonObject {
            put("data",page);put("truncated",false);put("sourceScopePartial",!page.getValue("completeBody").jsonPrimitive.boolean)
            put("dataOnly",true);put("notePageIntact",true)
        }.toString()
    }
}
