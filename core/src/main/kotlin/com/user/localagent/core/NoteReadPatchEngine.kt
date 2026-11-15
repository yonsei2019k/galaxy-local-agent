package com.user.localagent.core

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

class NotePatchRejected(val code:String):IllegalArgumentException(code)
private fun requireNote(ok:Boolean,code:String){if(!ok)throw NotePatchRejected(code)}

data class NoteReadBinding(val runId:String,val threadId:String,val branchId:String,val scopeKey:String)
/** Host repository state only. Never constructed from model JSON or a search excerpt. */
data class NoteSource(val id:String,val scopeKey:String,val collection:String,val title:String,val body:String,val updatedAt:Long){
    override fun toString()="NoteSource(private host state)"
}
data class NoteReadRequest(val id:String,val scope:String,val expectedUpdatedAt:Long,val offset:Int=0,val limit:Int=900)
data class NotePatchRequest(val id:String,val scope:String,val expectedUpdatedAt:Long,val oldText:String,val newText:String){
    override fun toString()="NotePatchRequest(private literal edit)"
}
class NoteReadPage internal constructor(val id:String,val scope:String,val expectedUpdatedAt:Long,
    val offset:Int,val text:String,val totalLength:Int,val bodySha256:String,val readToken:String,val patchable:Boolean) {
    val endOffset:Int get()=offset+text.length
    val hasMore:Boolean get()=endOffset<totalLength
    val completeBody:Boolean get()=offset==0&&!hasMore
    fun json():JsonObject=buildJsonObject {
        put("status","ok");put("id",id);put("scope",scope);put("expectedUpdatedAt",expectedUpdatedAt.toString())
        put("offset",offset);put("text",text);put("returnedLength",text.length);put("totalLength",totalLength)
        put("nextOffset",if(hasMore)JsonPrimitive(endOffset)else JsonNull)
        put("hasMore",hasMore);put("partial",!completeBody);put("completeBody",completeBody)
        put("offsetUnit","utf16_code_units");put("bodySha256",bodySha256);put("readToken",readToken)
        put("patchable",patchable);put("untrustedData",true);put("grantsPermission",false)
    }
    /** The page is intact; sourceScopePartial still honestly describes unread note text. */
    fun modelResult():JsonObject=buildJsonObject {
        put("data",json());put("truncated",false);put("sourceScopePartial",!completeBody)
        put("dataOnly",true);put("notePageIntact",true)
    }
    override fun toString()="NoteReadPage(private source fragment)"
}
class NotePatchPlan internal constructor(val id:String,val scope:String,val expectedUpdatedAt:Long,
    val startOffset:Int,val oldText:String,val newText:String,val beforeSha256:String,val afterSha256:String,
    val afterBody:String,val changed:Boolean) {
    /** Host approval binds this exact diff and current target/version; never expose afterBody as an excerpt replacement. */
    fun preview():JsonObject=buildJsonObject {
        put("id",id);put("scope",scope);put("expectedUpdatedAt",expectedUpdatedAt.toString());put("startOffset",startOffset)
        put("oldText",oldText);put("newText",newText);put("beforeSha256",beforeSha256);put("afterSha256",afterSha256)
        put("changed",changed);put("unreadTextPreserved",true);put("requiresApproval",changed)
    }
    override fun toString()="NotePatchPlan(private host diff; no effect applied)"
}

/** Pure, request-local read-delivery authority + literal patch engine. No storage, inference or network. */
class NoteReadPatchSession(private val binding:NoteReadBinding) {
    companion object {
        const val MAX_BODY=12_000
        const val MAX_LEGACY_READ=64_000
        const val MAX_PAGE=900
        const val MAX_PAGE_JSON=3_000
        const val MAX_PENDING=16
    }
    private val issued=linkedMapOf<String,NoteReadPage>()
    private var delivered:Set<String> = emptySet()

    @Synchronized fun read(note:NoteSource,request:NoteReadRequest):NoteReadPage {
        target(note,request.id,request.scope,request.expectedUpdatedAt)
        requireNote(note.body.length<=MAX_LEGACY_READ,"LEGACY_READ_LIMIT")
        utf8(note.body)
        requireNote(request.limit in 2..MAX_PAGE,"READ_LIMIT_INVALID")
        requireNote(request.offset in 0..note.body.length&&boundary(note.body,request.offset),"READ_OFFSET_INVALID")
        var end=minOf(note.body.length,request.offset+request.limit)
        if(!boundary(note.body,end))end--
        val hash=sha(note.body);val token=UUID.randomUUID().toString()
        fun page()=NoteReadPage(note.id,note.collection,note.updatedAt,request.offset,note.body.substring(request.offset,end),
            note.body.length,hash,token,note.body.length<=MAX_BODY)
        var result=page()
        while(result.json().toString().length>MAX_PAGE_JSON&&end>request.offset){
            end--;if(!boundary(note.body,end))end--
            result=page()
        }
        requireNote(end>request.offset||request.offset==note.body.length,"READ_PAGE_BUDGET_EXHAUSTED")
        requireNote(result.json().toString().length<=MAX_PAGE_JSON,"READ_PAGE_BUDGET_EXHAUSTED")
        issued[token]=result
        while(issued.size>MAX_PENDING){val expired=issued.keys.first();issued.remove(expired);delivered=delivered-expired}
        return result
    }

    /** Only trusted host evidence actually present intact in THIS generation's model input. Replace, never accumulate. */
    @Synchronized fun acceptDelivered(evidence:List<JsonObject>,actualModelQuery:String) {
        delivered=emptySet()
        if(evidence.size>32||actualModelQuery.length>1_000_000)return
        val serialized=evidence.map{it.toString()}
        if(serialized.sumOf{it.length.toLong()}>256_000)return
        val whole=actualModelQuery.contains(JsonArray(evidence).toString())
        delivered=evidence.indices.mapNotNull {index->
            val item=evidence[index]
            if(!whole&&!actualModelQuery.contains(serialized[index]))return@mapNotNull null
            if(item.text("tool")!="read_note"||item.flag("success")!=true||item.text("verification")!="VERIFIED")return@mapNotNull null
            val wrapped=item["result"] as? JsonObject?:return@mapNotNull null
            val data=wrapped["data"] as? JsonObject?:return@mapNotNull null
            val token=data.text("readToken")?:return@mapNotNull null
            val pending=issued[token]?:return@mapNotNull null
            if(wrapped!=pending.modelResult())return@mapNotNull null
            token
        }.toSet()
    }

    @Synchronized fun previewPatch(note:NoteSource,request:NotePatchRequest):NotePatchPlan {
        target(note,request.id,request.scope,request.expectedUpdatedAt)
        requireNote(note.body.length<=MAX_BODY,"LEGACY_NOTE_READ_ONLY")
        requireNote(request.oldText.isNotEmpty()&&request.oldText.length<=MAX_PAGE,"OLD_TEXT_INVALID")
        requireNote(request.newText.length<=2_000,"NEW_TEXT_TOO_LONG")
        utf8(note.body);utf8(request.oldText);utf8(request.newText)
        val start=note.body.indexOf(request.oldText)
        requireNote(start>=0,"OLD_TEXT_NOT_FOUND")
        // Detect overlapping second occurrences too. No regex, normalization, trimming or case folding.
        requireNote(note.body.indexOf(request.oldText,start+1)<0,"OLD_TEXT_NOT_UNIQUE")
        val before=sha(note.body)
        requireNote(delivered.any {token->issued[token]?.let{read->
            read.id==note.id&&read.scope==note.collection&&read.expectedUpdatedAt==note.updatedAt&&read.bodySha256==before&&
                start>=read.offset&&start+request.oldText.length<=read.endOffset&&
                read.text==note.body.substring(read.offset,read.endOffset)
        }==true},"ORIGINAL_FRAGMENT_NOT_DELIVERED")
        val after=note.body.substring(0,start)+request.newText+note.body.substring(start+request.oldText.length)
        requireNote(after.length<=MAX_BODY&&after.isNotBlank(),"RESULT_BODY_INVALID")
        return NotePatchPlan(note.id,note.collection,note.updatedAt,start,request.oldText,request.newText,before,sha(after),after,after!=note.body)
    }

    @Synchronized fun acceptedPageTokens():Set<String> = delivered.toSet()
    @Synchronized fun clear(){issued.clear();delivered=emptySet()}
    private fun target(note:NoteSource,id:String,scope:String,version:Long) {
        requireNote(binding.runId.isNotBlank()&&binding.threadId.isNotBlank()&&binding.branchId.isNotBlank()&&binding.scopeKey.isNotBlank(),"REQUEST_BINDING_INVALID")
        requireNote(note.id==id&&note.scopeKey==binding.scopeKey,"NOTE_SCOPE_MISMATCH")
        requireNote(scope.isNotBlank()&&scope.length<=80&&scope==note.collection,"COLLECTION_MISMATCH")
        requireNote(scope !in InternalNoteCollections.protected,"PROTECTED_COLLECTION")
        requireNote(version>=0&&version==note.updatedAt,"NOTE_VERSION_CHANGED")
    }
    private fun utf8(value:String):ByteArray {
        requireNote('\u0000' !in value,"NUL_TEXT_UNSUPPORTED")
        val bytes=try{Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))}
            catch(_:Exception){throw NotePatchRejected("MALFORMED_UNICODE")}
        return ByteArray(bytes.remaining()).also{bytes.get(it)}
    }
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(utf8(value)).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun boundary(value:String,index:Int)=index==0||index==value.length||
        !(value[index-1].isHighSurrogate()&&value[index].isLowSurrogate())
    private fun JsonObject.text(key:String)=(this[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
    private fun JsonObject.flag(key:String)=(this[key] as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull
}
