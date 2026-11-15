package com.user.localagent.notes

import com.user.localagent.core.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal fun noteSha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
internal const val NOTE_PATCH_RECEIPT="note_patch_receipt_v1"

/** Implemented by one scoped Room transaction. No Android types or model supplied authority. */
interface NotePatchTransaction {
    suspend fun note(id:String):NoteSource?
    suspend fun putPreservingMetadata(before:NoteSource,body:String):NoteSource
    suspend fun receipt(operationId:String):String?
    suspend fun putReceipt(operationId:String,json:String)
}
interface NotePatchTransactions {
    suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T
}
data class NotePatchIdentity(val operationId:String,val argumentsSha256:String,val id:String,val collection:String)
data class NotePatchReceipt(val identity:NotePatchIdentity,val title:String,val beforeUpdatedAt:Long,val updatedAt:Long,
    val beforeSha256:String,val afterSha256:String,val changed:Boolean) {
    fun json():JsonObject=buildJsonObject {
        put("status","ok");put("schema",1);put("operationId",identity.operationId);put("argumentsSha256",identity.argumentsSha256)
        put("id",identity.id);put("scope",identity.collection);put("title",title)
        put("beforeUpdatedAt",beforeUpdatedAt.toString());put("updatedAt",updatedAt.toString())
        put("beforeSha256",beforeSha256);put("afterSha256",afterSha256);put("changed",changed)
        put("storedAndReadBack",true);put("readbackComplete",true);put("unreadTextPreserved",true)
        put("untrustedData",true);put("grantsPermission",false)
    }
    override fun toString()="NotePatchReceipt(private durable host observation)"
    companion object {
        fun parse(raw:String):NotePatchReceipt {
            require(raw.length<=4000)
            val j=Json.parseToJsonElement(raw).jsonObject
            fun s(k:String)=(j.getValue(k) as JsonPrimitive).also{require(it.isString)}.content
            fun b(k:String)=(j.getValue(k) as JsonPrimitive).boolean
            require(j["schema"]?.jsonPrimitive?.int==1&&s("status")=="ok"&&b("storedAndReadBack")&&b("readbackComplete")&&b("unreadTextPreserved")&&b("untrustedData")&&!b("grantsPermission"))
            val receipt=NotePatchReceipt(NotePatchIdentity(s("operationId"),s("argumentsSha256"),s("id"),s("scope")),s("title"),
                s("beforeUpdatedAt").toLong(),s("updatedAt").toLong(),s("beforeSha256"),s("afterSha256"),b("changed"))
            require(receipt.json()==j) // Exact host schema, no ignored extra authority or malformed flags.
            require(listOf(receipt.identity.argumentsSha256,receipt.beforeSha256,receipt.afterSha256).all{it.matches(Regex("[a-f0-9]{64}"))})
            require(receipt.beforeUpdatedAt>=0&&if(receipt.changed)receipt.updatedAt>receipt.beforeUpdatedAt else receipt.updatedAt==receipt.beforeUpdatedAt)
            return receipt
        }
    }
}

/** The readback and durable receipt are in the SAME transaction as the update; errors roll back all three. */
class NotePatchStore(private val binding:NoteReadBinding,private val transactions:NotePatchTransactions) {
    suspend fun read(id:String):NoteSource=transactions.transaction(false){checkNotNull(note(id)).also{target(it)}}
    suspend fun reconcile(identity:NotePatchIdentity):NotePatchReceipt?=transactions.transaction(false){reconcileInside(identity)}
    suspend fun commit(identity:NotePatchIdentity,source:NoteSource,plan:NotePatchPlan,beforeEffect:suspend()->Unit):NotePatchReceipt=
        transactions.transaction(true) {
            reconcileInside(identity)?.let{return@transaction it}
            val current=checkNotNull(note(identity.id));target(current)
            check(current==source&&current.id==plan.id&&current.collection==plan.scope&&current.updatedAt==plan.expectedUpdatedAt&&
                noteSha(current.body)==plan.beforeSha256){"NOTE_CAS_CONFLICT"}
            check(identity.id==current.id&&identity.collection==current.collection)
            check(current.body.length<=NoteReadPatchSession.MAX_BODY&&plan.afterBody.isNotBlank()&&plan.afterBody.length<=NoteReadPatchSession.MAX_BODY)
            check(plan.oldText.isNotEmpty()&&current.body.indexOf(plan.oldText)==plan.startOffset&&
                current.body.indexOf(plan.oldText,plan.startOffset+1)==-1){"NOTE_LITERAL_CONFLICT"}
            check(plan.afterBody==current.body.substring(0,plan.startOffset)+plan.newText+current.body.substring(plan.startOffset+plan.oldText.length)&&
                noteSha(plan.afterBody)==plan.afterSha256&&plan.changed==(current.body!=plan.afterBody))
            beforeEffect() // Last cancellable/live-owner fence, immediately before the bounded database effect.
            val written=if(plan.changed)putPreservingMetadata(current,plan.afterBody)else current
            val actual=checkNotNull(note(current.id));target(actual)
            check(actual==written&&actual.id==current.id&&actual.title==current.title&&actual.collection==current.collection&&
                actual.scopeKey==current.scopeKey&&actual.body==plan.afterBody&&noteSha(actual.body)==plan.afterSha256&&
                if(plan.changed)actual.updatedAt>current.updatedAt else actual.updatedAt==current.updatedAt){"NOTE_READBACK_MISMATCH"}
            val receipt=NotePatchReceipt(identity,actual.title,current.updatedAt,actual.updatedAt,plan.beforeSha256,plan.afterSha256,plan.changed)
            putReceipt(identity.operationId,receipt.json().toString())
            check(receipt(identity.operationId)==receipt.json().toString()){"NOTE_RECEIPT_READBACK_MISMATCH"}
            receipt
        }
    private suspend fun NotePatchTransaction.reconcileInside(identity:NotePatchIdentity):NotePatchReceipt? {
        val raw=receipt(identity.operationId)?:return null
        val previous=NotePatchReceipt.parse(raw);check(previous.identity==identity){"NOTE_OPERATION_COLLISION"}
        val current=checkNotNull(note(identity.id));target(current)
        check(current.collection==identity.collection&&current.title==previous.title&&current.updatedAt==previous.updatedAt&&
            noteSha(current.body)==previous.afterSha256){"NOTE_RECEIPT_TARGET_CHANGED"}
        return previous
    }
    private fun target(note:NoteSource) {
        check(note.scopeKey==binding.scopeKey&&note.collection !in InternalNoteCollections.protected){"NOTE_TARGET_FORBIDDEN"}
    }
}
