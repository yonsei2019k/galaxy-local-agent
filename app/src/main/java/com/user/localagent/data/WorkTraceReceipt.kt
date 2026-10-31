package com.user.localagent.data

import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class WorkOutcomeReceipt(val audit:ToolRunRecord,val receiptId:String)
internal data class WorkTraceReceipt(val binding:WorkTraceBinding,val auditId:String,val startEventId:String,
    val toolName:String,val invocationId:String,val argsHash:String,val scopeHash:String,val state:VerificationState,val fileDigest:String?)

internal object WorkTraceReceiptCodec {
    const val ROW_NAME="work_receipt_v1"
    fun auditId(startId:String)="outcome-"+digest(startId)
    fun receiptId(auditId:String)="receipt-"+digest(auditId)
    fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    fun fileDigest(file:WorkFileChange)=digest(buildJsonObject{
        put("path",file.relativePath);put("operation",file.operation.name);put("before",file.beforeSha256);put("after",file.afterSha256);put("bytes",file.bytes);put("workspace",file.workspaceBinding)
    }.toString())
    fun encode(receipt:WorkTraceReceipt):String {
        val b=receipt.binding
        return buildJsonObject{
            put("v",1);put("branch",b.branchId);put("project",b.projectId);put("threadRevision",b.threadRevision);put("projectRevision",b.projectRevision)
            put("audit",receipt.auditId);put("start",receipt.startEventId);put("tool",receipt.toolName);put("call",receipt.invocationId);put("argsHash",receipt.argsHash);put("scopeHash",receipt.scopeHash);put("state",receipt.state.name);put("fileDigest",receipt.fileDigest)
        }.toString().also{require(it.length<=4096)}
    }
    fun decode(row:ToolRunRecord):WorkTraceReceipt {
        require(row.name==ROW_NAME&&!row.success&&row.summary.length in 1..4096)
        WorkTraceCodec.rejectDuplicateKeys(row.summary)
        val o=Json.parseToJsonElement(row.summary).jsonObject
        require(o.keys==setOf("v","branch","project","threadRevision","projectRevision","audit","start","tool","call","argsHash","scopeHash","state","fileDigest"))
        fun number(key:String):Long {val p=o.getValue(key).jsonPrimitive;require(!p.isString);return p.longOrNull?:error("영수증 숫자 형식이 올바르지 않습니다.")}
        fun text(key:String):String {val p=o.getValue(key).jsonPrimitive;require(p.isString);return p.content}
        fun nullable(key:String)=o.getValue(key).takeUnless{it is JsonNull}?.jsonPrimitive?.also{require(it.isString)}?.content
        require(number("v")==1L)
        val result=WorkTraceReceipt(WorkTraceBinding(requireNotNull(row.runId),requireNotNull(row.threadId),text("branch"),nullable("project"),number("threadRevision"),number("projectRevision")),text("audit"),text("start"),text("tool"),text("call"),text("argsHash"),text("scopeHash"),VerificationState.valueOf(text("state")),nullable("fileDigest"))
        require(receiptId(result.auditId)==row.id)
        require(auditId(result.startEventId)==result.auditId)
        require(listOf(result.argsHash,result.scopeHash).all{it.matches(Regex("[a-f0-9]{64}"))})
        result.fileDigest?.let{require(it.matches(Regex("[a-f0-9]{64}")))}
        return result
    }
}
