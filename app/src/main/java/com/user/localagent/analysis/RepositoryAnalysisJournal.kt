package com.user.localagent.analysis

import com.user.localagent.core.*
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Host-only atomic commit seam. The production callback uses the repository's scoped transaction. */
fun interface AnalysisCheckpointCommit {
    suspend fun commit(rootId:String,expectedRootSha256:String?,rootBody:String,stepId:String?,stepBody:String?)
}

/** Encrypted Room notes with append-only results and a compare-and-set root. Source text is not copied. */
class RepositoryAnalysisJournal internal constructor(
    private val read:suspend(String)->NoteRecord?,private val scopeId:String,private val commit:AnalysisCheckpointCommit,
):PrivateAnalysisJournal {
    constructor(repository:LocalRepository,scopeId:String,commit:AnalysisCheckpointCommit):this(repository::getNote,scopeId,commit)
    private var cached:Cached?=null
    private val knownHeads=mutableMapOf<String,String>()
    private data class Cached(val checkpoint:PrivateAnalysisCheckpoint,val rootBody:String,val lastId:String?,val lastSha:String?)

    override suspend fun load(id:String):PrivateAnalysisCheckpoint? {
        require(UUID_PATTERN.matches(id))
        val root=read(id)?:return null
        require(root.collection==COLLECTION)
        val metadata=parse(root.body)
        require(metadata.string("kind")==ROOT_KIND&&metadata.string("id")==id&&metadata.string("scopeId")==scopeId)
        val source=source(metadata.getValue("source").jsonObject)
        require(source.scopeId==scopeId)
        val expected=metadata.integer("stepCount");require(expected in 0..10_000)
        var next=metadata.optional("lastId");var hash=metadata.optional("lastSha256")
        require((next==null)==(hash==null))
        val reversed=mutableListOf<AnalysisChunkResult>();val visited=mutableSetOf<String>()
        while(next!=null) {
            currentCoroutineContext().ensureActive()
            require(reversed.size<expected&&visited.add(next)&&UUID_PATTERN.matches(next))
            val note=read(next)?:error("분석 진행 기록의 일부가 삭제되었거나 현재 범위에서 해제되었습니다.")
            require(note.collection==COLLECTION&&sha(note.body)==hash)
            val entry=parse(note.body)
            require(entry.string("kind")==STEP_KIND&&entry.string("analysisId")==id&&entry.string("scopeId")==scopeId)
            require(entry.integer("index")==expected-reversed.size-1)
            reversed+=step(entry.getValue("result").jsonObject,source)
            next=entry.optional("previousId");hash=entry.optional("previousSha256")
            require((next==null)==(hash==null))
        }
        require(reversed.size==expected)
        val result=PrivateAnalysisCheckpoint(
            id=id,source=source,sourceLocator=metadata.getValue("sourceLocator").jsonObject,
            question=metadata.string("question"),modelSha256=metadata.string("modelSha256"),skillSha256=metadata.string("skillSha256"),
            maxChunkBytes=metadata.integer("maxChunkBytes"),overlapBytes=metadata.integer("overlapBytes"),
            processed=reversed.asReversed(),nextByte=metadata["nextByte"]?.jsonPrimitive?.intOrNull,
            sourceExhausted=metadata.getValue("sourceExhausted").jsonPrimitive.boolean,
        )
        require(result.question.length in 1..2000&&SHA.matches(result.modelSha256)&&SHA.matches(result.skillSha256))
        require(result.maxChunkBytes==4096&&result.overlapBytes==256)
        cached=Cached(result,root.body,metadata.optional("lastId"),metadata.optional("lastSha256"))
        knownHeads[id]=sha(root.body)
        return result
    }

    override suspend fun save(checkpoint:PrivateAnalysisCheckpoint) {
        require(UUID_PATTERN.matches(checkpoint.id)&&checkpoint.source.scopeId==scopeId)
        val current=read(checkpoint.id)
        val known=knownHeads[checkpoint.id]
        if(known!=null)require(current!=null&&sha(current.body)==known){"저장된 분석이 삭제되거나 다른 작업에서 변경되었습니다. 최신 상태를 다시 확인해주세요."}
        if(current!=null&&cached?.checkpoint?.id!=checkpoint.id)load(checkpoint.id)
        require(knownHeads.size<1024||checkpoint.id in knownHeads){"분석 실행 범위를 새로 열어주세요."}
        val previous=if(current!=null)checkNotNull(cached)else null
        require(current==null||current.collection==COLLECTION)
        if(previous!=null) {
            val old=previous.checkpoint
            require(old.source==checkpoint.source&&old.sourceLocator==checkpoint.sourceLocator&&old.question==checkpoint.question&&
                old.modelSha256==checkpoint.modelSha256&&old.skillSha256==checkpoint.skillSha256&&old.maxChunkBytes==checkpoint.maxChunkBytes&&old.overlapBytes==checkpoint.overlapBytes)
            require(checkpoint.processed.size in old.processed.size..old.processed.size+1&&checkpoint.processed.take(old.processed.size)==old.processed)
        } else require(checkpoint.processed.isEmpty())
        val appended=checkpoint.processed.lastOrNull()?.takeIf {checkpoint.processed.size>(previous?.checkpoint?.processed?.size?:0)}
        val stepId=appended?.let {UUID.randomUUID().toString()}
        val stepBody=appended?.let {result->buildJsonObject {
            put("kind",STEP_KIND);put("analysisId",checkpoint.id);put("scopeId",scopeId);put("index",checkpoint.processed.lastIndex)
            put("previousId",previous?.lastId);put("previousSha256",previous?.lastSha)
            put("result",stepJson(result))
        }.toString()}
        require(stepBody==null||stepBody.length<=12_000)
        val lastId=stepId?:previous?.lastId;val lastSha=stepBody?.let(::sha)?:previous?.lastSha
        val rootBody=buildJsonObject {
            put("kind",ROOT_KIND);put("id",checkpoint.id);put("scopeId",scopeId);put("source",sourceJson(checkpoint.source))
            put("sourceLocator",checkpoint.sourceLocator);put("question",checkpoint.question)
            put("modelSha256",checkpoint.modelSha256);put("skillSha256",checkpoint.skillSha256)
            put("maxChunkBytes",checkpoint.maxChunkBytes);put("overlapBytes",checkpoint.overlapBytes)
            put("stepCount",checkpoint.processed.size);put("lastId",lastId);put("lastSha256",lastSha)
            if(checkpoint.nextByte!=null)put("nextByte",checkpoint.nextByte)else put("nextByte",JsonNull)
            put("sourceExhausted",checkpoint.sourceExhausted)
        }.toString()
        require(rootBody.length<=12_000)
        currentCoroutineContext().ensureActive()
        commit.commit(checkpoint.id,current?.body?.let(::sha),rootBody,stepId,stepBody)
        val confirmed=read(checkpoint.id)
        check(confirmed?.collection==COLLECTION&&confirmed.body==rootBody){"분석 진행 상태를 저장한 뒤 확인하지 못했습니다."}
        if(stepId!=null)check(read(stepId)?.let {it.collection==COLLECTION&&it.body==stepBody}==true)
        cached=Cached(checkpoint,rootBody,lastId,lastSha)
        knownHeads[checkpoint.id]=sha(rootBody)
    }

    companion object {
        const val COLLECTION="private-analysis"
        const val ROOT_KIND="private_analysis_checkpoint_v1"
        const val STEP_KIND="private_analysis_step_v1"
        private val UUID_PATTERN=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val SHA=Regex("[a-f0-9]{64}")
        private fun parse(raw:String)=Json.parseToJsonElement(raw).jsonObject
        private fun JsonObject.string(key:String)=getValue(key).jsonPrimitive.content.also {require(getValue(key).jsonPrimitive.isString)}
        private fun JsonObject.optional(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull?.takeIf {it!="null"}
        private fun JsonObject.integer(key:String)=getValue(key).jsonPrimitive.int
        private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
        private fun sourceJson(ref:PrivateSourceRef)=buildJsonObject {put("scopeId",ref.scopeId);put("sourceId",ref.sourceId);put("kind",ref.kind.name);put("contentSha256",ref.contentSha256)}
        private fun source(value:JsonObject)=PrivateSourceRef(value.string("scopeId"),value.string("sourceId"),PrivateEvidenceKind.valueOf(value.string("kind")),value.string("contentSha256"))
        private fun stepJson(step:AnalysisChunkResult)=buildJsonObject {
            put("chunkId",step.chunkId);put("startByte",step.startByte);put("endByte",step.endByte)
            put("findings",buildJsonArray {step.findings.forEach {finding->add(buildJsonObject {
                put("id",finding.id);put("text",finding.text)
                val cite=finding.citation
                put("chunkId",cite.chunkId);put("startByte",cite.startByte);put("endByte",cite.endByte);put("quote",cite.quote);put("quoteSha256",cite.quoteSha256)
            })}})
            put("limitations",JsonArray(step.limitations.map(::JsonPrimitive)))
        }
        private fun step(value:JsonObject,source:PrivateSourceRef):AnalysisChunkResult {
            val findings=value.getValue("findings").jsonArray
            require(findings.size<=4)
            return AnalysisChunkResult(value.string("chunkId"),value.integer("startByte"),value.integer("endByte"),findings.map {entry->
                val item=entry.jsonObject;val quote=item.string("quote");val text=item.string("text")
                require(quote.length in 1..500&&text.length in 1..500&&SHA.matches(item.string("id"))&&sha(quote)==item.string("quoteSha256"))
                AnalysisFinding(item.string("id"),text,ValidatedPrivateCitation(source,item.string("chunkId"),item.integer("startByte"),item.integer("endByte"),quote,item.string("quoteSha256")))
            },value.getValue("limitations").jsonArray.map {it.jsonPrimitive.content}.also {require(it.size<=4&&it.all {text->text.length<=250})})
        }
    }
}
