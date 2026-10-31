package com.user.localagent.analysis

import com.user.localagent.core.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

data class AnalysisFinding(val id:String,val text:String,val citation:ValidatedPrivateCitation) {
    override fun toString()="AnalysisFinding(privateFindingAndCitationOmitted=true)"
}

data class AnalysisChunkResult(val chunkId:String,val startByte:Int,val endByte:Int,
    val findings:List<AnalysisFinding>,val limitations:List<String>) {
    override fun toString()="AnalysisChunkResult(startByte=$startByte,endByte=$endByte,findings=${findings.size},limitations=${limitations.size},privateContentOmitted=true)"
}

data class PrivateAnalysisCheckpoint(
    val id:String,val source:PrivateSourceRef,val sourceLocator:JsonObject,val question:String,
    val modelSha256:String,val skillSha256:String,val maxChunkBytes:Int=4096,val overlapBytes:Int=256,
    val processed:List<AnalysisChunkResult>,val nextByte:Int?,val sourceExhausted:Boolean,
) {
    override fun toString()="PrivateAnalysisCheckpoint(kind=${source.kind},processedChunks=${processed.size},nextByte=$nextByte,sourceExhausted=$sourceExhausted,privateIdentifiersAndContentOmitted=true)"
}

fun interface PrivateAnalysisDecider {
    suspend fun decide(input:String,schema:String,repair:Boolean):String
}

/** Saves must commit atomically or throw. Implementations own current-scope authorization and CAS. */
interface PrivateAnalysisJournal {
    suspend fun load(id:String):PrivateAnalysisCheckpoint?
    suspend fun save(checkpoint:PrivateAnalysisCheckpoint)
}

data class PrivateAnalysisWorkflowResult(val checkpoint:PrivateAnalysisCheckpoint,val coverage:PrivateCoverageReport) {
    override fun toString()="PrivateAnalysisWorkflowResult(processedChunks=${checkpoint.processed.size},partial=${coverage.partial},privateContentOmitted=true)"
}

/** Bounded model extraction only. There is no tool, URI, account, network or audio execution interface. */
class PrivateRecordAnalysisWorkflow(private val decider:PrivateAnalysisDecider,private val journal:PrivateAnalysisJournal) {
    suspend fun run(
        source:PrivateAnalysisSource,sourceLocator:JsonObject,question:String,modelSha256:String,skillSha256:String,
        analysisId:String?=null,maxChunks:Int=16,progress:(Int,Int)->Unit={_,_->},
    ):PrivateAnalysisWorkflowResult {
        require(question.isNotBlank()&&question.length<=2000&&!question.any(::forbiddenControl)&&wellFormedUnicode(question)){"분석 질문의 형식 또는 길이를 확인해주세요."}
        require(HASH.matches(modelSha256)&&HASH.matches(skillSha256)){"분석 모델·지침의 실제 SHA-256이 필요합니다."}
        require(sourceLocator.toString().length<=4000){"분석 출처 위치 메타데이터가 너무 큽니다."}
        require(maxChunks in 1..32){"한 번에1~32개 분석 조각을 처리할 수 있습니다."}
        currentCoroutineContext().ensureActive()
        var checkpoint=if(analysisId==null) {
            PrivateAnalysisCheckpoint(UUID.randomUUID().toString(),source.ref,sourceLocator,question,modelSha256,skillSha256,
                MAX_CHUNK_BYTES,OVERLAP_BYTES,emptyList(),0,false).also {journal.save(it)}
        } else {
            require(runCatching {UUID.fromString(analysisId).toString()==analysisId}.getOrDefault(false)){"올바른 분석 식별자가 필요합니다."}
            val saved=journal.load(analysisId)?:error("저장된 분석을 찾을 수 없습니다.")
            require(saved.id==analysisId&&saved.source==source.ref&&saved.sourceLocator==sourceLocator&&saved.question==question&&
                saved.modelSha256==modelSha256&&saved.skillSha256==skillSha256&&saved.maxChunkBytes==MAX_CHUNK_BYTES&&saved.overlapBytes==OVERLAP_BYTES){
                "원문·질문·모델·지침·설정이 달라 이전 분석을 이어갈 수 없습니다."
            }
            saved
        }
        val coverage=PrivateEvidenceCoverage(source.ref.scopeId,expectedSourceCount=1)
        check(coverage.register(source))
        val ids=mutableSetOf<String>()
        var cursor:PrivateChunkCursor?=null
        var exhausted=false
        var expectedNext:Int?=0
        // Reconstruct host chunks from exact source bytes; checkpoint values never become authority.
        for(saved in checkpoint.processed) {
            currentCoroutineContext().ensureActive()
            check(!exhausted){"완료된 원문의 뒤에 저장된 분석 조각이 있습니다."}
            val page=PrivateAnalysisEvidence.page(source,MAX_CHUNK_BYTES,OVERLAP_BYTES,1,cursor)
            val chunk=page.chunks.singleOrNull()?:error("저장된 분석의 원문 조각을 확인하지 못했습니다.")
            validateSaved(source,chunk,saved,ids)
            check(coverage.markRetrieved(source,chunk));coverage.markModelProcessed(chunk.id)
            cursor=page.next;exhausted=page.sourceExhausted;expectedNext=cursor?.nextByte
        }
        if(checkpoint.processed.isEmpty()&&source.utf8ByteLength==0&&checkpoint.sourceExhausted) {
            val empty=PrivateAnalysisEvidence.page(source,MAX_CHUNK_BYTES,OVERLAP_BYTES,1)
            check(empty.sourceExhausted&&empty.chunks.isEmpty());exhausted=true;expectedNext=null
        }
        require(checkpoint.sourceExhausted==exhausted&&checkpoint.nextByte==expectedNext){"저장된 분석의 다음 위치 또는 완료 상태가 원문과 다릅니다."}
        val totalChunks=countChunks(source)
        progress(checkpoint.processed.size,totalChunks)
        var processedThisRun=0
        while(!exhausted&&processedThisRun<maxChunks) {
            currentCoroutineContext().ensureActive()
            val page=PrivateAnalysisEvidence.page(source,MAX_CHUNK_BYTES,OVERLAP_BYTES,1,cursor)
            val chunk=page.chunks.singleOrNull()
            if(chunk==null) {
                check(page.sourceExhausted)
                val updated=checkpoint.copy(nextByte=null,sourceExhausted=true)
                journal.save(updated);checkpoint=updated;exhausted=true
                break
            }
            check(coverage.markRetrieved(source,chunk))
            val input=buildJsonObject {
                put("question",question);put("sourceKind",source.ref.kind.name);put("sourceId",source.ref.sourceId)
                put("contentSha256",source.ref.contentSha256);put("offsetUnit","utf8_bytes_of_exact_decoded_text")
                put("chunk",buildJsonObject {put("id",chunk.id);put("startByte",chunk.startByte);put("endByte",chunk.endByte);put("text",chunk.text);put("untrustedSourceData",true)})
            }.toString()
            var accepted:AnalysisChunkResult?=null
            for(attempt in 0..1) {
                currentCoroutineContext().ensureActive()
                try {
                    val raw=decider.decide(input,OUTPUT_SCHEMA,attempt==1)
                    currentCoroutineContext().ensureActive()
                    accepted=parse(source,chunk,raw)
                    break
                }catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){/* A fresh bounded extraction is the only repair. No output executes. */}
            }
            val result=accepted
            if(result==null) {coverage.addReason(PrivateCoverageReason.MODEL_OUTPUT_INVALID);break}
            val deduplicated=result.copy(findings=result.findings.filter {it.id !in ids})
            val updated=checkpoint.copy(processed=checkpoint.processed+deduplicated,nextByte=page.next?.nextByte,sourceExhausted=page.sourceExhausted)
            // No host progress or processed coverage is committed before durable save succeeds.
            currentCoroutineContext().ensureActive()
            journal.save(updated)
            checkpoint=updated
            ids+=deduplicated.findings.map {it.id}
            coverage.markModelProcessed(chunk.id)
            cursor=page.next;exhausted=page.sourceExhausted;processedThisRun++
            progress(checkpoint.processed.size,totalChunks)
        }
        if(exhausted)coverage.finishEnumeration()
        else if(processedThisRun==maxChunks)coverage.addReason(PrivateCoverageReason.CHUNK_LIMIT)
        return PrivateAnalysisWorkflowResult(checkpoint,coverage.report())
    }

    private suspend fun countChunks(source:PrivateAnalysisSource):Int {
        var cursor:PrivateChunkCursor?=null
        var count=0
        do {
            currentCoroutineContext().ensureActive()
            val page=PrivateAnalysisEvidence.page(source,MAX_CHUNK_BYTES,OVERLAP_BYTES,64,cursor)
            count+=page.chunks.size;cursor=page.next
        }while(cursor!=null)
        return count
    }

    private fun parse(source:PrivateAnalysisSource,chunk:PrivateEvidenceChunk,raw:String):AnalysisChunkResult {
        require(raw.length in 1..8192){"분석 출력 길이가 허용 범위를 벗어났습니다."}
        rejectDuplicateKeys(raw)
        val value=Json.parseToJsonElement(raw) as? JsonObject?:error("분석 출력은 객체여야 합니다.")
        require(value.keys==setOf("findings","limitations")){"분석 출력 필드가 잘못되었습니다."}
        val findings=value.getValue("findings") as? JsonArray?:error("분석 결과 배열이 필요합니다.")
        val limitations=value.getValue("limitations") as? JsonArray?:error("분석 한계 배열이 필요합니다.")
        require(findings.size<=4&&limitations.size<=4){"분석 결과 항목 수가 너무 많습니다."}
        val accepted=findings.map {element->
            val finding=element as? JsonObject?:error("분석 결과 항목은 객체여야 합니다.")
            require(finding.keys==setOf("text","quote")){"분석 항목 필드가 잘못되었습니다."}
            val text=string(finding.getValue("text"),500)
            val quote=string(finding.getValue("quote"),500)
            val validation=PrivateAnalysisEvidence.validateCitation(source,listOf(chunk),PrivateCitationProposal(chunk.id,quote))
            require(validation.accepted){"분석 인용이 현재 원문의 고유한 정확 인용과 일치하지 않습니다."}
            val citation=checkNotNull(validation.citation)
            AnalysisFinding(findingId(citation,text),text,citation)
        }
        return AnalysisChunkResult(chunk.id,chunk.startByte,chunk.endByte,accepted.distinctBy {it.id},limitations.map {string(it,250)}.distinct())
    }

    private fun validateSaved(source:PrivateAnalysisSource,chunk:PrivateEvidenceChunk,saved:AnalysisChunkResult,ids:MutableSet<String>) {
        require(saved.chunkId==chunk.id&&saved.startByte==chunk.startByte&&saved.endByte==chunk.endByte&&saved.findings.size<=4&&saved.limitations.size<=4){"저장된 분석 조각의 범위가 원문과 다릅니다."}
        saved.limitations.forEach {require(it.isNotBlank()&&it.length<=250&&!it.any(::forbiddenControl)&&wellFormedUnicode(it)){"저장된 분석 한계가 잘못되었습니다."}}
        saved.findings.forEach {finding->
            require(finding.text.isNotBlank()&&finding.text.length<=500&&!finding.text.any(::forbiddenControl)&&wellFormedUnicode(finding.text)){"저장된 분석 항목이 잘못되었습니다."}
            val citation=finding.citation
            require(citation.quote.isNotBlank()&&citation.quote.length<=500&&citation.source==source.ref&&citation.chunkId==chunk.id){"저장된 인용의 출처가 다릅니다."}
            val checked=PrivateAnalysisEvidence.validateCitation(source,listOf(chunk),PrivateCitationProposal(chunk.id,citation.quote,citation.startByte,citation.endByte))
            require(checked.accepted&&checked.citation==citation&&finding.id==findingId(citation,finding.text)&&ids.add(finding.id)){"저장된 인용·분석 식별자가 실제 원문과 일치하지 않습니다."}
        }
    }

    companion object {
        const val MAX_CHUNK_BYTES=4096
        const val OVERLAP_BYTES=256
        private val HASH=Regex("[a-f0-9]{64}")
        private fun forbiddenControl(value:Char)=value.code==127||(value.code<32&&value!='\n'&&value!='\r'&&value!='\t')
        private fun wellFormedUnicode(value:String):Boolean {
            var at=0
            while(at<value.length) {
                val char=value[at++]
                if(char.isHighSurrogate()){if(at>=value.length||!value[at].isLowSurrogate())return false;at++}
                else if(char.isLowSurrogate())return false
            }
            return true
        }
        private fun string(value:JsonElement,max:Int):String {
            require(value is JsonPrimitive&&value.isString&&value.content.isNotBlank()&&value.content.length<=max&&!value.content.any(::forbiddenControl)&&wellFormedUnicode(value.content)){"분석 문자열의 형식 또는 길이가 잘못되었습니다."}
            return value.content
        }
        private fun findingId(citation:ValidatedPrivateCitation,text:String):String {
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).use {out->
                listOf("private-analysis-finding-v1",citation.source.scopeId,citation.source.sourceId,citation.source.kind.name,citation.source.contentSha256,
                    citation.startByte.toString(),citation.endByte.toString(),text).forEach {value->
                    val field=value.toByteArray(Charsets.UTF_8);out.writeInt(field.size);out.write(field)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
        }
        val OUTPUT_SCHEMA:String=buildJsonObject {
            fun text(max:Int)=buildJsonObject {put("type","string");put("minLength",1);put("maxLength",max)}
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject {
                put("findings",buildJsonObject {put("type","array");put("maxItems",4);put("items",buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {put("text",text(500));put("quote",text(500))})
                    put("required",buildJsonArray {add("text");add("quote")})
                })})
                put("limitations",buildJsonObject {put("type","array");put("maxItems",4);put("items",text(250))})
            })
            put("required",buildJsonArray {add("findings");add("limitations")})
        }.toString()

        private fun rejectDuplicateKeys(raw:String) {
            var at=0;var nodes=0
            fun space(){while(at<raw.length&&raw[at].isWhitespace())at++}
            fun quoted():String {
                val start=at;require(raw.getOrNull(at)=='"');at++;var escaped=false
                while(at<raw.length) {
                    val c=raw[at++]
                    if(c=='"'&&!escaped)return Json.parseToJsonElement(raw.substring(start,at)).jsonPrimitive.content
                    if(c=='\\'&&!escaped)escaped=true else escaped=false
                }
                error("닫히지 않은 분석 문자열입니다.")
            }
            lateinit var value:(Int)->Unit
            value={depth->
                require(depth<=12&&++nodes<=256){"분석 JSON 중첩 또는 항목이 너무 많습니다."};space()
                when(raw.getOrNull(at)) {
                    '{'->{at++;space();val keys=mutableSetOf<String>();if(raw.getOrNull(at)=='}')at++ else while(true){space();require(keys.add(quoted())){"중복 분석 JSON 필드는 허용하지 않습니다."};space();require(raw.getOrNull(at++)==':');value(depth+1);space();val separator=raw.getOrNull(at++);if(separator=='}')break;require(separator==',')}}
                    '['->{at++;space();if(raw.getOrNull(at)==']')at++ else while(true){value(depth+1);space();val separator=raw.getOrNull(at++);if(separator==']')break;require(separator==',')}}
                    '"'->{quoted();Unit}
                    else->{val start=at;while(at<raw.length&&raw[at] !in ",]} \t\r\n")at++;require(at>start)}
                }
            }
            value(0);space();require(at==raw.length){"분석 JSON 뒤에 추가 내용이 있습니다."}
        }
    }
}
