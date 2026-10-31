package com.user.localagent.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class PrivateEvidenceKind { SMS, AUDIO_TRANSCRIPT, IMPORTED_CONVERSATION, DOCUMENT }
enum class PrivateTextCompleteness { COMPLETE_SELECTED_TEXT, PARTIAL_SELECTED_TEXT, UNKNOWN }

/** This hash and every byte offset refer to exact decoded text encoded as UTF-8, not original file bytes. */
data class PrivateSourceRef(val scopeId:String,val sourceId:String,val kind:PrivateEvidenceKind,val contentSha256:String) {
    init {
        require(scopeId.isNotBlank()&&scopeId.length<=256&&scopeId.none {it.code<32})
        require(sourceId.isNotBlank()&&sourceId.length<=256&&sourceId.none {it.code<32})
        require(contentSha256.matches(Regex("[a-f0-9]{64}")))
    }
    override fun toString()="PrivateSourceRef(kind=$kind,privateIdentifiersOmitted=true)"
}

/** Bounded source bytes remain in caller-owned RAM; this utility performs no reading or persistence. */
class PrivateAnalysisSource private constructor(
    val ref:PrivateSourceRef,private val bytes:ByteArray,val completeness:PrivateTextCompleteness,
) {
    val utf8ByteLength:Int get()=bytes.size
    internal fun isBoundary(offset:Int)=offset in 0..bytes.size&&(offset==bytes.size||bytes[offset].toInt() and 0xc0!=0x80)
    internal fun slice(start:Int,end:Int):String {
        require(start in 0..end&&end<=bytes.size&&isBoundary(start)&&isBoundary(end))
        return String(bytes,start,end-start,Charsets.UTF_8)
    }
    override fun toString()="PrivateAnalysisSource(kind=${ref.kind},utf8ByteLength=$utf8ByteLength,privateTextOmitted=true)"
    companion object {
        const val MAX_SOURCE_BYTES=20*1024*1024
        fun create(scopeId:String,sourceId:String,kind:PrivateEvidenceKind,text:String,
            completeness:PrivateTextCompleteness=PrivateTextCompleteness.UNKNOWN):PrivateAnalysisSource {
            require(text.length<=MAX_SOURCE_BYTES){"분석 원문이 지원 범위를 넘었습니다."}
            val bytes=PrivateAnalysisEvidence.strictUtf8(text)
            require(bytes.size<=MAX_SOURCE_BYTES){"분석 원문은20MiB 이하여야 합니다."}
            val ref=PrivateSourceRef(scopeId,sourceId,kind,PrivateAnalysisEvidence.sha256(bytes))
            return PrivateAnalysisSource(ref,bytes,completeness)
        }
    }
}

data class PrivateEvidenceChunk(val id:String,val source:PrivateSourceRef,val startByte:Int,val endByte:Int,val text:String) {
    override fun toString()="PrivateEvidenceChunk(startByte=$startByte,endByte=$endByte,privateTextOmitted=true)"
}
data class PrivateChunkCursor(val source:PrivateSourceRef,val maxChunkBytes:Int,val overlapBytes:Int,val nextByte:Int) {
    override fun toString()="PrivateChunkCursor(nextByte=$nextByte,privateIdentifiersOmitted=true)"
}
data class PrivateChunkPage(val chunks:List<PrivateEvidenceChunk>,val next:PrivateChunkCursor?,val sourceExhausted:Boolean)

data class PrivateCitationProposal(val chunkId:String,val quote:String,val startByte:Int?=null,val endByte:Int?=null) {
    override fun toString()="PrivateCitationProposal(hasExplicitSpan=${startByte!=null&&endByte!=null},privateQuoteOmitted=true)"
}
data class ValidatedPrivateCitation(val source:PrivateSourceRef,val chunkId:String,val startByte:Int,val endByte:Int,
    val quote:String,val quoteSha256:String) {
    val validationMeaning:String get()="exact_quote_span_match_only"
    val semanticAccuracyVerified:Boolean get()=false
    override fun toString()="ValidatedPrivateCitation(startByte=$startByte,endByte=$endByte,privateQuoteOmitted=true)"
}
enum class PrivateCitationStatus { ACCEPTED, UNKNOWN_CHUNK, STALE_OR_WRONG_SOURCE, INVALID_CHUNK, EMPTY_QUOTE, QUOTE_TOO_LONG, MALFORMED_QUOTE, INVALID_SPAN, QUOTE_MISMATCH, AMBIGUOUS_QUOTE }
data class PrivateCitationValidation(val status:PrivateCitationStatus,val citation:ValidatedPrivateCitation?=null) {
    val accepted:Boolean get()=status==PrivateCitationStatus.ACCEPTED
}

/** Stable chunks and exact provenance checks. No relevance ranking, fact extraction or intent interpretation. */
object PrivateAnalysisEvidence {
    fun page(source:PrivateAnalysisSource,maxChunkBytes:Int=4096,overlapBytes:Int=256,limit:Int=16,cursor:PrivateChunkCursor?=null):PrivateChunkPage {
        require(maxChunkBytes in 128..16000&&overlapBytes in 0..maxChunkBytes/2&&limit in 1..64){"분석 조각·겹침·페이지 개수의 제한을 확인해주세요."}
        if(cursor!=null)require(cursor.source==source.ref&&cursor.maxChunkBytes==maxChunkBytes&&cursor.overlapBytes==overlapBytes&&
            cursor.nextByte in 1 until source.utf8ByteLength&&source.isBoundary(cursor.nextByte)){"이 원문·설정과 일치하는 다음 조각 위치가 필요합니다."}
        val result=mutableListOf<PrivateEvidenceChunk>()
        var start=cursor?.nextByte?:0
        while(start<source.utf8ByteLength&&result.size<limit){
            var end=minOf(source.utf8ByteLength,start+maxChunkBytes)
            while(!source.isBoundary(end))end--
            check(end>start)
            result+=PrivateEvidenceChunk(chunkId(source.ref,start,end),source.ref,start,end,source.slice(start,end))
            if(end==source.utf8ByteLength)return PrivateChunkPage(result,null,true)
            var next=end-overlapBytes
            while(!source.isBoundary(next))next++
            check(next>start&&next<=end)
            start=next
        }
        return if(start>=source.utf8ByteLength)PrivateChunkPage(result,null,true)
            else PrivateChunkPage(result,PrivateChunkCursor(source.ref,maxChunkBytes,overlapBytes,start),false)
    }

    /** observedChunks must be the host's chunks actually offered in this request, never a model-created list. */
    fun validateCitation(source:PrivateAnalysisSource,observedChunks:Collection<PrivateEvidenceChunk>,proposal:PrivateCitationProposal):PrivateCitationValidation {
        val chunk=observedChunks.firstOrNull {it.id==proposal.chunkId}?:return PrivateCitationValidation(PrivateCitationStatus.UNKNOWN_CHUNK)
        if(chunk.source!=source.ref)return PrivateCitationValidation(PrivateCitationStatus.STALE_OR_WRONG_SOURCE)
        if(!validChunk(source,chunk))return PrivateCitationValidation(PrivateCitationStatus.INVALID_CHUNK)
        if(proposal.quote.isEmpty())return PrivateCitationValidation(PrivateCitationStatus.EMPTY_QUOTE)
        if(proposal.quote.length>8192)return PrivateCitationValidation(PrivateCitationStatus.QUOTE_TOO_LONG)
        val quoteBytes=try {strictUtf8(proposal.quote)}catch(_:IllegalArgumentException){return PrivateCitationValidation(PrivateCitationStatus.MALFORMED_QUOTE)}
        if(quoteBytes.size>8192)return PrivateCitationValidation(PrivateCitationStatus.QUOTE_TOO_LONG)
        val explicit=proposal.startByte!=null||proposal.endByte!=null
        val start:Int
        val end:Int
        if(explicit){
            start=proposal.startByte?:return PrivateCitationValidation(PrivateCitationStatus.INVALID_SPAN)
            end=proposal.endByte?:return PrivateCitationValidation(PrivateCitationStatus.INVALID_SPAN)
            if(start<chunk.startByte||end>chunk.endByte||end<=start||end.toLong()-start!=quoteBytes.size.toLong()||!source.isBoundary(start)||!source.isBoundary(end))return PrivateCitationValidation(PrivateCitationStatus.INVALID_SPAN)
        }else{
            val at=chunk.text.indexOf(proposal.quote)
            if(at<0)return PrivateCitationValidation(PrivateCitationStatus.QUOTE_MISMATCH)
            if(chunk.text.indexOf(proposal.quote,at+1)>=0)return PrivateCitationValidation(PrivateCitationStatus.AMBIGUOUS_QUOTE)
            start=chunk.startByte+strictUtf8(chunk.text.substring(0,at)).size
            end=start+quoteBytes.size
        }
        if(source.slice(start,end)!=proposal.quote)return PrivateCitationValidation(PrivateCitationStatus.QUOTE_MISMATCH)
        return PrivateCitationValidation(PrivateCitationStatus.ACCEPTED,ValidatedPrivateCitation(source.ref,chunk.id,start,end,proposal.quote,sha256(quoteBytes)))
    }

    internal fun validChunk(source:PrivateAnalysisSource,chunk:PrivateEvidenceChunk):Boolean = runCatching {
        chunk.source==source.ref&&chunk.startByte>=0&&chunk.endByte>chunk.startByte&&chunk.endByte<=source.utf8ByteLength&&
            chunk.endByte.toLong()-chunk.startByte<=16000&&source.isBoundary(chunk.startByte)&&source.isBoundary(chunk.endByte)&&
            chunk.id==chunkId(source.ref,chunk.startByte,chunk.endByte)&&chunk.text==source.slice(chunk.startByte,chunk.endByte)
    }.getOrDefault(false)

    private fun chunkId(source:PrivateSourceRef,start:Int,end:Int):String {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out->
            listOf("private-evidence-chunk-v1",source.scopeId,source.sourceId,source.kind.name,source.contentSha256,start.toString(),end.toString()).forEach {field->
                val encoded=strictUtf8(field);out.writeInt(encoded.size);out.write(encoded)
            }
        }
        return sha256(bytes.toByteArray())
    }
    internal fun strictUtf8(text:String):ByteArray {
        var index=0
        while(index<text.length){
            val c=text[index++]
            if(c.isHighSurrogate()){require(index<text.length&&text[index].isLowSurrogate()){ "원문에 올바르지 않은 유니코드가 있습니다." };index++}
            else require(!c.isLowSurrogate()){ "원문에 올바르지 않은 유니코드가 있습니다." }
        }
        return text.toByteArray(Charsets.UTF_8)
    }
    internal fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
}

enum class PrivateCoverageReason {
    SOURCE_ENUMERATION_INCOMPLETE, SOURCE_COUNT_MISMATCH, SOURCE_LIMIT, CHUNK_LIMIT, SOURCE_CHANGED,
    SOURCE_TEXT_PARTIAL, SOURCE_TEXT_COMPLETENESS_UNKNOWN, UNRETRIEVED_TEXT, UNPROCESSED_TEXT,
    CANCELLED, TIME_LIMIT, TOKEN_LIMIT, PROVIDER_ERROR, PERMISSION_REVOKED, ASR_OMISSION, MODEL_OUTPUT_INVALID,
}
data class PrivateCoverageReport(val expectedSourceCount:Long?,val registeredSourceCount:Int,val enumerationComplete:Boolean,
    val knownTextBytes:Long,val retrievedBytes:Long,val modelProcessedBytes:Long,val fullyRetrievedSources:Int,val fullyProcessedSources:Int,
    val reasons:Set<PrivateCoverageReason>,val completeWithinDeclaredScope:Boolean) {
    val partial:Boolean get()=!completeWithinDeclaredScope
    val semanticAccuracyVerified:Boolean get()=false
    val wholePhoneHistoryVerified:Boolean get()=false
}

/** Per-request metadata only. Processing means a host-validated model response, never proven comprehension. */
class PrivateEvidenceCoverage(private val scopeId:String,private val expectedSourceCount:Long?=null,
    private val maxSources:Int=100_000,private val maxObservedChunks:Int=100_000) {
    private data class Identity(val sourceId:String,val kind:PrivateEvidenceKind)
    private data class Span(val start:Int,val end:Int)
    private class SourceState(val ref:PrivateSourceRef,val bytes:Int,val completeness:PrivateTextCompleteness) {
        val retrieved=mutableListOf<Span>();val processed=mutableListOf<Span>()
    }
    private data class Observed(val identity:Identity,val span:Span)
    private val sources=linkedMapOf<Identity,SourceState>()
    private val observed=mutableMapOf<String,Observed>()
    private val processedIds=mutableSetOf<String>()
    private val problems=linkedSetOf<PrivateCoverageReason>()
    private var enumerationComplete=false
    init {require(scopeId.isNotBlank()&&scopeId.length<=256&&scopeId.none {it.code<32});require(expectedSourceCount==null||expectedSourceCount>=0);require(maxSources in 1..100_000&&maxObservedChunks in 1..100_000)}

    fun register(source:PrivateAnalysisSource):Boolean {
        require(source.ref.scopeId==scopeId){"다른 요청 범위의 원문을 합칠 수 없습니다."}
        val key=Identity(source.ref.sourceId,source.ref.kind)
        sources[key]?.let {state->
            if(state.ref!=source.ref||state.bytes!=source.utf8ByteLength||state.completeness!=source.completeness){problems+=PrivateCoverageReason.SOURCE_CHANGED;return false}
            return true
        }
        check(!enumerationComplete){"범위 목록을 확정한 뒤 새 원문을 추가할 수 없습니다."}
        if(sources.size==maxSources){problems+=PrivateCoverageReason.SOURCE_LIMIT;return false}
        sources[key]=SourceState(source.ref,source.utf8ByteLength,source.completeness)
        return true
    }
    fun markRetrieved(source:PrivateAnalysisSource,chunk:PrivateEvidenceChunk):Boolean {
        require(source.ref.scopeId==scopeId&&chunk.source.scopeId==scopeId){"다른 요청 범위의 조각을 사용할 수 없습니다."}
        val key=Identity(source.ref.sourceId,source.ref.kind)
        val state=sources[key]?:error("원문을 먼저 등록해야 합니다.")
        if(state.ref!=source.ref){problems+=PrivateCoverageReason.SOURCE_CHANGED;return false}
        require(PrivateAnalysisEvidence.validChunk(source,chunk)){"원문과 조각의 해시·범위·본문이 일치하지 않습니다."}
        if(chunk.id in observed)return true
        if(observed.size==maxObservedChunks){problems+=PrivateCoverageReason.CHUNK_LIMIT;return false}
        val span=Span(chunk.startByte,chunk.endByte)
        observed[chunk.id]=Observed(key,span);merge(state.retrieved,span)
        return true
    }
    /** Call only after the host accepts a bounded response for this exact offered chunk. */
    fun markModelProcessed(chunkId:String) {
        val chunk=observed[chunkId]?:error("실제로 가져온 조각만 처리 완료로 기록할 수 있습니다.")
        if(processedIds.add(chunkId))merge(sources.getValue(chunk.identity).processed,chunk.span)
    }
    /** Only a real scoped provider/catalog EOF may finalize enumeration; a full page is not EOF. */
    fun finishEnumeration(){enumerationComplete=true}
    fun addReason(reason:PrivateCoverageReason){problems+=reason}
    fun report():PrivateCoverageReport {
        val reasons=problems.toMutableSet()
        if(!enumerationComplete)reasons+=PrivateCoverageReason.SOURCE_ENUMERATION_INCOMPLETE
        if(expectedSourceCount!=null&&expectedSourceCount!=sources.size.toLong())reasons+=PrivateCoverageReason.SOURCE_COUNT_MISMATCH
        if(sources.values.any {it.completeness==PrivateTextCompleteness.PARTIAL_SELECTED_TEXT})reasons+=PrivateCoverageReason.SOURCE_TEXT_PARTIAL
        if(sources.values.any {it.completeness==PrivateTextCompleteness.UNKNOWN})reasons+=PrivateCoverageReason.SOURCE_TEXT_COMPLETENESS_UNKNOWN
        val known=sources.values.sumOf {it.bytes.toLong()}
        val retrieved=sources.values.sumOf {covered(it.retrieved)}
        val processed=sources.values.sumOf {covered(it.processed)}
        if(retrieved<known)reasons+=PrivateCoverageReason.UNRETRIEVED_TEXT
        if(processed<known)reasons+=PrivateCoverageReason.UNPROCESSED_TEXT
        return PrivateCoverageReport(expectedSourceCount,sources.size,enumerationComplete,known,retrieved,processed,
            sources.values.count {covered(it.retrieved)==it.bytes.toLong()},sources.values.count {covered(it.processed)==it.bytes.toLong()},reasons.toSet(),reasons.isEmpty())
    }
    private fun covered(spans:List<Span>)=spans.sumOf {it.end.toLong()-it.start}
    private fun merge(target:MutableList<Span>,added:Span) {
        val sorted=(target+added).sortedBy {it.start};target.clear()
        sorted.forEach {span->
            val previous=target.lastOrNull()
            if(previous!=null&&span.start<=previous.end)target[target.lastIndex]=Span(previous.start,maxOf(previous.end,span.end))else target+=span
        }
    }
}
