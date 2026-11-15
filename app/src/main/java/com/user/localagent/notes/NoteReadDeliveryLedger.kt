package com.user.localagent.notes

import com.user.localagent.core.*
import com.user.localagent.inference.GenerationStopReason
import com.user.localagent.inference.GenerationSummary

object NoteReadDeliveryCompletion {
    fun canAcknowledge(paused:Boolean,opaque:Boolean,hasText:Boolean,summary:GenerationSummary?):Boolean=
        !paused&&!opaque&&hasText&&summary?.reason==GenerationStopReason.EOG&&summary.errorCode==null&&!summary.mayBeTruncated
}

/** Ephemeral request-host object. No serializer, model field, permission or durable read token. */
class NoteReadDeliveryBatch internal constructor(internal val records:Map<String,String>,val invalidated:Boolean) {
    val hasPages:Boolean get()=records.isNotEmpty()
    override fun toString()="NoteReadDeliveryBatch(private host input observation)"
}

/** An issued page, accepted intact in an input, then acknowledged only after that generation returns. */
class NoteReadDeliveryLedger {
    private data class Issued(val id:String,val key:String)
    private val issued=linkedMapOf<String,Issued>()
    private val acknowledged=linkedMapOf<String,String>()
    private var pending:NoteReadDeliveryBatch?=null
    private var invalidated=false
    @Synchronized fun issued(call:ToolCall,page:NoteReadPage) {
        require(call.name=="read_note"&&call.arguments["id"]?.toString()==kotlinx.serialization.json.JsonPrimitive(page.id).toString())
        issued[page.readToken]=Issued(page.id,NoteReadbackPages.deliveryKey(call,page.json().toString()))
        while(issued.size>NoteReadPatchSession.MAX_PENDING)issued.remove(issued.keys.first())
    }
    @Synchronized fun accept(tokens:Set<String>):NoteReadDeliveryBatch {
        val records=tokens.mapNotNull{issued[it]}.associate{it.key to it.id}
        return NoteReadDeliveryBatch(records.toMap(),invalidated).also{pending=it;invalidated=false}
    }
    @Synchronized fun acknowledge(batch:NoteReadDeliveryBatch) {
        check(batch===pending){"NOTE_DELIVERY_BATCH_NOT_CURRENT"}
        check((acknowledged.keys+batch.records.keys).size<=WorkSegments.STEP_BUDGET){"NOTE_DELIVERY_BUDGET"}
        acknowledged.putAll(batch.records);pending=null
    }
    @Synchronized fun invalidate(id:String) {
        issued.entries.removeAll{it.value.id==id};acknowledged.entries.removeAll{it.value==id}
        pending=null;invalidated=true
    }
    @Synchronized fun keys():Set<String> = acknowledged.keys.toSet()
    @Synchronized fun clear(){issued.clear();acknowledged.clear();pending=null;invalidated=false}
}
