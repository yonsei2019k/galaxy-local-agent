package com.user.localagent.core

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Whole-body AFTER-read evidence only. This does not grant edit authority or restore model read tokens. */
class NoteReadbackPages {
    data class Observation(val target:RequiredReadbackPolicy.Resource,val snapshot:RequiredReadbackPolicy.Snapshot?)
    private data class Pages(val collection:String,val revision:String,val sha:String,val chars:CharArray,val seen:BooleanArray)
    private val pages=linkedMapOf<String,Pages>()
    fun invalidate(id:String){pages.remove(id)}
    fun observe(call:ToolCall,source:JsonObject?):Observation {
        val id=call.arguments.getValue("id").jsonPrimitive.content
        val target=RequiredReadbackPolicy.Resource("note_body_v1",id)
        val snapshot=runCatching {
            NoteReadPatchToolCatalog.validate(call);require(call.name=="read_note")
            val j=checkNotNull(source)
            fun s(k:String)=j.getValue(k).jsonPrimitive.also{require(it.isString)}.content
            fun i(k:String)=j.getValue(k).jsonPrimitive.also{require(!it.isString)}.int
            fun b(k:String)=j.getValue(k).jsonPrimitive.also{require(!it.isString)}.boolean
            val request=NoteReadPatchToolCatalog.readRequest(call)
            val revision=s("expectedUpdatedAt");val sha=s("bodySha256");val text=s("text");val offset=i("offset");val total=i("totalLength")
            require(s("status")=="ok"&&s("id")==id&&s("scope")==request.scope&&revision==request.expectedUpdatedAt.toString()&&
                s("offsetUnit")=="utf16_code_units"&&sha.matches(Regex("[a-f0-9]{64}"))&&
                offset==request.offset&&total in 0..NoteReadPatchSession.MAX_LEGACY_READ&&text.length<=request.limit&&
                i("returnedLength")==text.length&&offset>=0&&offset+text.length<=total&&
                b("hasMore")== (offset+text.length<total)&&b("completeBody")== (offset==0&&text.length==total)&&
                b("partial")== !b("completeBody")&&b("untrustedData")&&!b("grantsPermission"))
            require(if(b("hasMore"))j["nextOffset"]?.jsonPrimitive?.int==offset+text.length else j["nextOffset"]==JsonNull)
            require(text.isNotEmpty()||offset==total)
            val previous=pages[id]
            val state=previous?.takeIf{it.collection==request.scope&&it.revision==revision&&it.sha==sha&&it.chars.size==total}
                ?:Pages(request.scope,revision,sha,CharArray(total),BooleanArray(total)).also{
                    pages[id]=it;while(pages.size>4)pages.remove(pages.keys.first())
                }
            text.forEachIndexed{index,char->val at=offset+index;require(!state.seen[at]||state.chars[at]==char);state.chars[at]=char;state.seen[at]=true}
            if(!state.seen.all{it})return@runCatching null
            val whole=String(state.chars);require(hash(whole)==sha)
            snapshot(id,request.scope,revision,sha)
        }.getOrElse {pages.remove(id);null}
        return Observation(target,snapshot)
    }
    companion object {
        /** Scheduling hint only: raw provider coverage permits a review, never completion/permission. */
        fun canReviewDelivery(pending:List<RequiredReadbackPolicy.Pending>,outcomes:List<ObservedToolOutcome>):Boolean=runCatching {
            if(pending.isEmpty()||pending.any{it.readTool!="read_note"}||outcomes.size>WorkSegments.STEP_BUDGET)return@runCatching false
            val pages=NoteReadbackPages();val latest=mutableMapOf<RequiredReadbackPolicy.Resource,RequiredReadbackPolicy.Snapshot?>()
            for(observed in outcomes){
                val call=observed.call;val result=observed.result
                if(call.name !in setOf("read_note","patch_note"))continue
                val id=(call.arguments["id"] as? JsonPrimitive)?.takeIf{it.isString}?.content?:return@runCatching false
                val target=RequiredReadbackPolicy.Resource("note_body_v1",id)
                if(call.name=="patch_note"||!result.success||result.verification!=VerificationState.VERIFIED||result.effectNotApplied||result.id!=call.id){
                    pages.invalidate(id);latest[target]=null;continue
                }
                val json=if(result.content.length<=NoteReadPatchSession.MAX_PAGE_JSON)Json.parseToJsonElement(result.content).jsonObject else null
                val page=pages.observe(call,json);latest[page.target]=page.snapshot
            }
            pending.all{latest[it.expected.resource]==it.expected}
        }.getOrDefault(false)
        /** Identity of one actual host call/result, not a model-issued proof or permission. */
        fun deliveryKey(call:ToolCall,raw:String):String=hash(buildJsonObject{
            put("callId",call.id);put("tool",call.name);put("arguments",JsonObject(call.arguments.toSortedMap()));put("resultSha256",hash(raw))
        }.toString())
        fun snapshot(id:String,collection:String,revision:String,bodySha256:String):RequiredReadbackPolicy.Snapshot {
            require(id.isNotBlank()&&collection.isNotBlank()&&collection !in InternalNoteCollections.protected&&
                revision.toLongOrNull()?.let{it>=0}==true&&bodySha256.matches(Regex("[a-f0-9]{64}")))
            return RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource("note_body_v1",id),revision,hash(buildJsonObject{
                put("id",id);put("collection",collection);put("bodySha256",bodySha256)
            }.toString()))
        }
        private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
